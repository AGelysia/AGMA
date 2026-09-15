import Fastify, { type FastifyInstance } from "fastify";
import { fileURLToPath } from "node:url";

import standaloneSchemaAllowlist from "../../../../standalone-client/contracts/runtime-schema-allowlist.json" with { type: "json" };
import standaloneRelease from "../../../../standalone-client/version.json" with { type: "json" };

import { isMainModule } from "../../bootstrap/main-module.js";
import { asRuntimeStartupError, RuntimeStartupError } from "../../bootstrap/startup-error.js";
import {
  observeManagedParent,
  parseManagedRuntimeCli,
  runManagedRuntimeMain,
  type ManagedParentObservation,
} from "../../bootstrap/runtime-lifecycle.js";
import {
  loadStandaloneClientConfig,
  type LoadStandaloneConfigOptions,
} from "../../config/standalone-client-config.js";
import { createConfiguredEvidencePipeline } from "../../evidence/pipeline-factory.js";
import type { SafeHttpFetcher } from "../../evidence/safe-http-fetcher.js";
import type { SearchProvider } from "../../evidence/search-provider.js";
import { SqliteWebSearchBudget } from "../../evidence/search-budget.js";
import { checkLogDirectory } from "../../health/filesystem.js";
import { checkModelProvider, type ModelProviderHealthCheck } from "../../health/model-provider.js";
import { registerHealthRoute, RuntimeHealthState } from "../../health/runtime-health.js";
import {
  acquireRuntimeDatabaseLock,
  RuntimeDatabaseLockBusyError,
  type RuntimeDatabaseLock,
} from "../../health/runtime-lock.js";
import { checkRuntimeSqlite, type RuntimeSqliteHandle } from "../../health/sqlite.js";
import { RuntimeLogger } from "../../observability/runtime-logger.js";
import { SchemaRegistry } from "../../protocol/schema-registry.js";
import { UnsupportedModelProvider, type ModelProvider } from "../../providers/model-provider.js";
import { createProductionModelProvider } from "../../providers/provider-factory.js";
import { ClientAgentRequestService } from "../../requests/client-agent-request-service.js";
import {
  DisabledConversationRepository,
  SqliteConversationRepository,
} from "../../storage/conversation-repository.js";
import { migrateRuntimeStorage } from "../../storage/migrations.js";
import { SqliteProjectRepository } from "../../storage/project-repository.js";
import { ClientToolRegistry } from "../../tools/client-tool-registry.js";
import { ProjectToolExecutor } from "../../tools/project-tool-executor.js";
import { StandaloneKnowledgeHotIndex } from "../knowledge/knowledge-loader.js";
import { registerConnectorHandshakeRoute } from "../../transport/connector-handshake.js";
import { SqliteUsageAccounting, type UsageAccounting } from "../../usage/usage-accounting.js";
import { runtimeIdentity, type RuntimeIdentity } from "../../version.js";

const standaloneRuntimeIdentity: RuntimeIdentity = Object.freeze({
  ...runtimeIdentity,
  version: standaloneRelease.version,
});

export interface StandaloneBootstrapOptions extends LoadStandaloneConfigOptions {
  readonly logger?: RuntimeLogger;
  readonly modelProviderHealthCheck?: ModelProviderHealthCheck;
  readonly modelProvider?: ModelProvider;
  readonly standaloneProtocolRoot?: string;
  readonly connectorToolTimeoutMilliseconds?: number;
  readonly searchProvider?: SearchProvider;
  readonly safeHttpFetcher?: SafeHttpFetcher;
  readonly now?: () => Date;
  readonly signal?: AbortSignal;
}

export interface StandaloneRuntimeResult {
  readonly app: FastifyInstance;
  readonly identity: RuntimeIdentity;
  readonly health: RuntimeHealthState;
  readonly costs: UsageAccounting;
  readonly profile: "client";
  readonly listenAddress: { readonly host: "127.0.0.1"; readonly port: number };
}

export interface StartedStandaloneRuntime extends StandaloneRuntimeResult {
  close(): Promise<void>;
}

function defaultStandaloneProtocolRoot(): string {
  return fileURLToPath(new URL("../../../../standalone-client/contracts/", import.meta.url));
}

async function loadStandaloneSchemas(root?: string): Promise<SchemaRegistry> {
  try {
    const schemas = await SchemaRegistry.load(root ?? defaultStandaloneProtocolRoot());
    const required = standaloneSchemaAllowlist.schemas;
    if (required.some((reference) => !schemas.schemaReferences.includes(reference))) {
      throw new Error("A required standalone schema is unavailable.");
    }
    return schemas;
  } catch (error) {
    throw new RuntimeStartupError({
      code: "PROTOCOL_SCHEMA_UNAVAILABLE",
      stage: "protocol",
      safeMessage: "Standalone connector schemas could not be loaded.",
      cause: error,
    });
  }
}

function requireActive(signal?: AbortSignal): void {
  if (signal?.aborted === true) {
    throw new RuntimeStartupError({
      code: "STARTUP_INTERNAL_ERROR",
      stage: "startup",
      safeMessage: "Standalone Runtime startup was cancelled.",
    });
  }
}

export async function bootstrapStandaloneClient(
  options: StandaloneBootstrapOptions = {},
): Promise<StandaloneRuntimeResult> {
  const logger = options.logger ?? new RuntimeLogger();
  let sqlite: RuntimeSqliteHandle | undefined;
  let lock: RuntimeDatabaseLock | undefined;
  let app: FastifyInstance | undefined;
  try {
    requireActive(options.signal);
    const loaded = await loadStandaloneClientConfig(options);
    const config = loaded.resolved.service;
    await checkLogDirectory(loaded.paths.rootDirectory, loaded.paths.logDirectory);
    logger.setLevel(loaded.config.logging.level);
    logger.useLogDirectory(loaded.paths.logDirectory);
    for (const warning of loaded.warnings) logger.configWarning(warning);
    const schemas = await loadStandaloneSchemas(options.standaloneProtocolRoot);
    requireActive(options.signal);
    const tools = new ClientToolRegistry(schemas, loaded.resolved.allowedClientTools);

    sqlite = await checkRuntimeSqlite(loaded.paths.rootDirectory, loaded.paths.sqlite);
    const storageNow = options.now?.() ?? new Date();
    let conversations: DisabledConversationRepository | SqliteConversationRepository;
    let costs: SqliteUsageAccounting;
    try {
      migrateRuntimeStorage(sqlite.database, storageNow.toISOString());
      lock = acquireRuntimeDatabaseLock(sqlite.database, storageNow.toISOString());
      conversations = config.privacy.storeConversations
        ? new SqliteConversationRepository(sqlite.database)
        : new DisabledConversationRepository();
      costs = new SqliteUsageAccounting(sqlite.database, {
        serverId: config.scopeId,
        provider: config.model.provider,
        model: config.model.model,
        pricing: {
          inputMicroUsdPerMillionTokens: config.model.inputMicroUsdPerMillionTokens,
          outputMicroUsdPerMillionTokens: config.model.outputMicroUsdPerMillionTokens,
        },
        limits: {
          dailyRequestsPerPlayer: config.limits.dailyRequestsPerPlayer,
          monthlyBudgetMicroUsd: config.limits.monthlyBudgetMicroUsd,
          providerRoundReservationMicroUsd: config.limits.providerRoundReservationMicroUsd,
        },
      });
      costs.recoverAbandonedRequests(storageNow.getTime());
      costs.pruneHistoricalDetails(storageNow.getTime());
      if (config.privacy.storeConversations && config.privacy.retentionDays > 0) {
        const cutoff = new Date(
          storageNow.getTime() - config.privacy.retentionDays * 24 * 60 * 60 * 1000,
        );
        conversations.purgeExpired(cutoff.toISOString());
      }
    } catch (error) {
      if (error instanceof RuntimeDatabaseLockBusyError) {
        throw new RuntimeStartupError({
          code: "SQLITE_BUSY",
          stage: "sqlite",
          field: "/storage/sqlitePath",
          safeMessage: "The client Runtime database is owned by another process.",
          cause: error,
        });
      }
      throw new RuntimeStartupError({
        code: "SQLITE_WRITE_FAILED",
        stage: "sqlite",
        field: "/storage/sqlitePath",
        safeMessage: "The client Runtime database could not be prepared.",
        cause: error,
      });
    }

    const provider =
      options.modelProvider ??
      (options.modelProviderHealthCheck === undefined
        ? createProductionModelProvider(config.model)
        : new UnsupportedModelProvider());
    await checkModelProvider(
      config,
      options.modelProviderHealthCheck ?? provider,
      config.model.timeoutSeconds * 1000,
      options.signal,
    );
    requireActive(options.signal);
    const health = new RuntimeHealthState(options.now?.().toISOString(), "client-1.0");
    const searchBudget =
      loaded.resolved.webEvidence === undefined
        ? undefined
        : new SqliteWebSearchBudget(sqlite.database, {
            scopeId: loaded.resolved.scopeId,
            requestCostMicroUsd: loaded.resolved.webEvidence.requestCostMicroUsd,
            monthlyBudgetMicroUsd: loaded.resolved.webEvidence.monthlyBudgetMicroUsd,
            ...(options.now === undefined ? {} : { now: options.now }),
          });
    const webEvidence = createConfiguredEvidencePipeline(loaded.resolved, {
      ...(options.searchProvider === undefined ? {} : { searchProvider: options.searchProvider }),
      ...(options.safeHttpFetcher === undefined ? {} : { fetcher: options.safeHttpFetcher }),
      ...(options.now === undefined ? {} : { now: options.now }),
      ...(searchBudget === undefined ? {} : { budget: searchBudget }),
    });
    const knowledge = await StandaloneKnowledgeHotIndex.load(loaded.paths.knowledgeRoots);
    requireActive(options.signal);
    const requests = new ClientAgentRequestService({
      provider,
      config,
      tools,
      conversations,
      usage: costs,
      logger,
      localTools: new ProjectToolExecutor(new SqliteProjectRepository(sqlite.database), knowledge),
      ...(webEvidence === undefined ? {} : { webEvidence }),
      ...(options.now === undefined ? {} : { now: () => options.now?.().getTime() ?? Date.now() }),
    });

    app = Fastify({ logger: false });
    await registerConnectorHandshakeRoute(app, {
      scopeId: loaded.resolved.scopeId,
      subjectId: loaded.resolved.subjectId,
      connectorToken: loaded.resolved.connectorToken,
      componentVersion: standaloneRuntimeIdentity.version,
      schemaRegistry: schemas,
      health,
      agentRequests: requests,
      tools,
      ...(options.connectorToolTimeoutMilliseconds === undefined
        ? {}
        : { toolTimeoutMilliseconds: options.connectorToolTimeoutMilliseconds }),
      ...(options.now === undefined ? {} : { now: options.now }),
    });
    registerHealthRoute(app, health);
    app.addHook("preClose", async () => requests.close());
    app.addHook("onClose", async () => {
      health.markStopped();
      try {
        lock?.release();
      } finally {
        sqlite?.close();
      }
    });
    await app.ready();
    requireActive(options.signal);
    return {
      app,
      identity: standaloneRuntimeIdentity,
      health,
      costs,
      profile: "client",
      listenAddress: { host: config.transport.host, port: config.transport.port },
    };
  } catch (error) {
    await app?.close().catch(() => undefined);
    try {
      lock?.release();
    } catch {
      // Preserve the original startup error.
    }
    try {
      sqlite?.close();
    } catch {
      // Preserve the original startup error.
    }
    throw asRuntimeStartupError(error);
  }
}

export async function startStandaloneClient(
  options: StandaloneBootstrapOptions = {},
): Promise<StartedStandaloneRuntime> {
  const logger = options.logger ?? new RuntimeLogger();
  const runtime = await bootstrapStandaloneClient({ ...options, logger });
  try {
    await runtime.app.listen(runtime.listenAddress);
    logger.ready(runtime.listenAddress.port);
    runtime.health.markReady();
  } catch (error) {
    await runtime.app.close().catch(() => undefined);
    throw new RuntimeStartupError({
      code: "LISTEN_FAILED",
      stage: "listen",
      safeMessage: "The standalone Runtime could not bind its loopback port.",
      cause: error,
    });
  }
  return { ...runtime, close: async () => runtime.app.close() };
}

export type StandaloneManagedParent = ManagedParentObservation;

export const observeStandaloneManagedParent = observeManagedParent;

export function parseStandaloneCli(arguments_: readonly string[]): {
  readonly configPath: string;
  readonly managed: boolean;
} {
  const usage = "Usage: standalone Runtime --config <path> [--managed]";
  const { configPath, managed } = parseManagedRuntimeCli(arguments_, {
    configPathRequired: true,
    usage,
  });
  if (configPath === undefined) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_INVALID",
      stage: "config",
      safeMessage: usage,
    });
  }
  return { configPath, managed };
}

async function runMain(): Promise<void> {
  await runManagedRuntimeMain<
    { readonly configPath: string; readonly managed: boolean },
    StartedStandaloneRuntime
  >({
    parse: parseStandaloneCli,
    start: (cli, logger, signal) =>
      startStandaloneClient({
        configPath: cli.configPath,
        logger,
        ...(signal === undefined ? {} : { signal }),
      }),
  });
}

if (isMainModule(process.argv[1], import.meta.url)) await runMain();
