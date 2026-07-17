import Fastify, { type FastifyInstance } from "fastify";
import type { Readable } from "node:stream";

import { isMainModule } from "./main-module.js";
import { asRuntimeStartupError, RuntimeStartupError } from "./startup-error.js";
import { loadRuntimeConfig, type LoadRuntimeConfigOptions } from "../config/runtime-config.js";
import { checkLogDirectory } from "../health/filesystem.js";
import { checkModelProvider, type ModelProviderHealthCheck } from "../health/model-provider.js";
import { registerHealthRoute, RuntimeHealthState } from "../health/runtime-health.js";
import {
  acquireRuntimeDatabaseLock,
  RuntimeDatabaseLockBusyError,
  type RuntimeDatabaseLock,
} from "../health/runtime-lock.js";
import { checkRuntimeSqlite, type RuntimeSqliteHandle } from "../health/sqlite.js";
import { loadMarkdownKnowledge } from "../knowledge/markdown-loader.js";
import { RuntimeLogger } from "../observability/runtime-logger.js";
import { SchemaRegistry } from "../protocol/schema-registry.js";
import { UnsupportedModelProvider, type ModelProvider } from "../providers/model-provider.js";
import { createProductionModelProvider } from "../providers/provider-factory.js";
import { AgentRequestService } from "../requests/agent-request-service.js";
import {
  DisabledConversationRepository,
  SqliteConversationRepository,
} from "../storage/conversation-repository.js";
import { migrateRuntimeStorage } from "../storage/migrations.js";
import { SqliteProjectRepository } from "../storage/project-repository.js";
import { LocalToolExecutor } from "../tools/local-tool-executor.js";
import { ToolRegistry } from "../tools/tool-registry.js";
import { registerPaperHandshakeRoute } from "../transport/paper-handshake.js";
import {
  SqliteUsageAccounting,
  usdToMicroUsd,
  type UsageAccounting,
} from "../usage/usage-accounting.js";
import { runtimeIdentity, type RuntimeIdentity } from "../version.js";

export interface BootstrapOptions extends LoadRuntimeConfigOptions {
  readonly logger?: RuntimeLogger;
  readonly modelProviderHealthCheck?: ModelProviderHealthCheck;
  readonly modelProvider?: ModelProvider;
  readonly protocolRoot?: string;
  readonly now?: () => Date;
  readonly signal?: AbortSignal;
}

export interface RuntimeListenAddress {
  readonly host: "127.0.0.1";
  readonly port: number;
}

export interface BootstrapResult {
  readonly app: FastifyInstance;
  readonly identity: RuntimeIdentity;
  readonly health: RuntimeHealthState;
  readonly costs: UsageAccounting;
  readonly listenAddress: RuntimeListenAddress;
}

export interface StartRuntimeResult extends BootstrapResult {
  close(): Promise<void>;
}

export function createRuntimeApp(health?: RuntimeHealthState): FastifyInstance {
  const app = Fastify({ logger: false });
  if (health !== undefined) {
    registerHealthRoute(app, health);
  }
  return app;
}

function requireStartupActive(signal?: AbortSignal): void {
  if (signal?.aborted === true) {
    throw new RuntimeStartupError({
      code: "STARTUP_INTERNAL_ERROR",
      stage: "startup",
      safeMessage: "Runtime startup was cancelled.",
    });
  }
}

async function checkProtocolSchema(protocolRoot?: string): Promise<SchemaRegistry> {
  try {
    const registry = await SchemaRegistry.load(protocolRoot);
    const requiredSchemas = [
      "agent-cancel.schema.json",
      "agent-complete.schema.json",
      "agent-error.schema.json",
      "agent-request.schema.json",
      "capability.schema.json",
      "client-payload.schema.json",
      "envelope.schema.json",
      "management-costs-request.schema.json",
      "management-costs-response.schema.json",
      "session-resume.schema.json",
      "session-resumed.schema.json",
      "tool-call.schema.json",
      "tool-result.schema.json",
      "tools/player-context-read-arguments.schema.json",
      "tools/player-context-read-result.schema.json",
      "tools/player-held-item-read-arguments.schema.json",
      "tools/player-held-item-read-result.schema.json",
      "tools/server-info-read-arguments.schema.json",
      "tools/server-info-read-result.schema.json",
      "tools/server-plugins-list-arguments.schema.json",
      "tools/server-plugins-list-result.schema.json",
      "tools/server-recipe-lookup-arguments.schema.json",
      "tools/server-recipe-lookup-result.schema.json",
      "tools/server-recipe-uses-arguments.schema.json",
      "tools/server-recipe-uses-result.schema.json",
      "tools/landmark-search-arguments.schema.json",
      "tools/landmark-search-result.schema.json",
      "tools/build-preview-create-arguments.schema.json",
      "tools/build-preview-create-result.schema.json",
      "tools/server-docs-search-arguments.schema.json",
      "tools/server-docs-search-result.schema.json",
      "tools/project-common.schema.json",
      "tools/project-list-arguments.schema.json",
      "tools/project-list-result.schema.json",
      "tools/project-read-arguments.schema.json",
      "tools/project-read-result.schema.json",
      "tools/project-create-arguments.schema.json",
      "tools/project-create-result.schema.json",
      "tools/project-update-arguments.schema.json",
      "tools/project-update-result.schema.json",
    ];
    if (requiredSchemas.some((schema) => !registry.schemaReferences.includes(schema))) {
      throw new Error("A required protocol schema alias is unavailable");
    }
    return registry;
  } catch (error) {
    throw new RuntimeStartupError({
      code: "PROTOCOL_SCHEMA_UNAVAILABLE",
      stage: "protocol",
      safeMessage: "Capability protocol schema could not be loaded.",
      cause: error,
    });
  }
}

export async function bootstrap(options: BootstrapOptions = {}): Promise<BootstrapResult> {
  const logger = options.logger ?? new RuntimeLogger();
  let sqlite: RuntimeSqliteHandle | undefined;
  let runtimeDatabaseLock: RuntimeDatabaseLock | undefined;
  let app: FastifyInstance | undefined;

  try {
    requireStartupActive(options.signal);
    const loaded = await loadRuntimeConfig(options);
    requireStartupActive(options.signal);
    for (const warning of loaded.warnings) {
      logger.configWarning(warning);
    }

    await checkLogDirectory(loaded.paths.rootDirectory, loaded.paths.logDirectory);
    requireStartupActive(options.signal);
    const schemaRegistry = await checkProtocolSchema(options.protocolRoot);
    requireStartupActive(options.signal);
    const tools = new ToolRegistry(schemaRegistry);
    sqlite = await checkRuntimeSqlite(loaded.paths.rootDirectory, loaded.paths.sqlite);
    requireStartupActive(options.signal);
    const storageNow = options.now?.() ?? new Date();
    let conversations: DisabledConversationRepository | SqliteConversationRepository;
    let projects: SqliteProjectRepository;
    let costs: SqliteUsageAccounting;
    try {
      migrateRuntimeStorage(sqlite.database, storageNow.toISOString());
      runtimeDatabaseLock = acquireRuntimeDatabaseLock(sqlite.database, storageNow.toISOString());
      conversations = loaded.config.privacy.storeConversations
        ? new SqliteConversationRepository(sqlite.database)
        : new DisabledConversationRepository();
      projects = new SqliteProjectRepository(sqlite.database);
      costs = new SqliteUsageAccounting(sqlite.database, {
        serverId: loaded.config.server.id,
        provider: loaded.config.model.provider,
        model: loaded.config.model.model,
        pricing: {
          inputMicroUsdPerMillionTokens: loaded.config.model.inputMicroUsdPerMillionTokens,
          outputMicroUsdPerMillionTokens: loaded.config.model.outputMicroUsdPerMillionTokens,
        },
        limits: {
          dailyRequestsPerPlayer: loaded.config.limits.dailyRequestsPerPlayer,
          monthlyBudgetMicroUsd: usdToMicroUsd(loaded.config.limits.monthlyBudgetUsd),
          providerRoundReservationMicroUsd: loaded.config.limits.providerRoundReservationMicroUsd,
        },
      });
      costs.recoverAbandonedRequests(storageNow.getTime());
      costs.pruneHistoricalDetails(storageNow.getTime());
      if (loaded.config.privacy.storeConversations && loaded.config.privacy.retentionDays > 0) {
        const cutoff = new Date(
          storageNow.getTime() - loaded.config.privacy.retentionDays * 24 * 60 * 60 * 1000,
        );
        conversations.purgeExpired(cutoff.toISOString());
      }
    } catch (error) {
      if (error instanceof RuntimeDatabaseLockBusyError) {
        throw new RuntimeStartupError({
          code: "SQLITE_BUSY",
          stage: "sqlite",
          field: "/storage/sqlitePath",
          safeMessage: "Runtime SQLite database is owned by another Runtime process.",
          cause: error,
        });
      }
      throw new RuntimeStartupError({
        code: "SQLITE_WRITE_FAILED",
        stage: "sqlite",
        field: "/storage/sqlitePath",
        safeMessage: "Runtime SQLite schema could not be prepared.",
        cause: error,
      });
    }

    requireStartupActive(options.signal);
    const knowledge = await loadMarkdownKnowledge(loaded.paths.knowledgeRoots);
    requireStartupActive(options.signal);
    const localTools = new LocalToolExecutor(knowledge, projects);

    const provider =
      options.modelProvider ??
      (options.modelProviderHealthCheck === undefined
        ? createProductionModelProvider(loaded.config.model)
        : new UnsupportedModelProvider());
    const providerHealthCheck = options.modelProviderHealthCheck ?? provider;
    await checkModelProvider(
      loaded.config,
      providerHealthCheck,
      loaded.config.model.timeoutSeconds * 1000,
      options.signal,
    );
    requireStartupActive(options.signal);

    const health = new RuntimeHealthState(options.now?.().toISOString());
    const agentRequests = new AgentRequestService({
      provider,
      config: loaded.config,
      conversations,
      tools,
      localTools,
      usage: costs,
      ...(options.now === undefined ? {} : { now: () => options.now?.().getTime() ?? Date.now() }),
    });
    app = createRuntimeApp();
    await registerPaperHandshakeRoute(app, {
      serverId: loaded.config.server.id,
      serverToken: loaded.config.transport.serverToken,
      schemaRegistry,
      health,
      agentRequests,
      usage: costs,
      ...(options.now === undefined ? {} : { now: options.now }),
    });
    registerHealthRoute(app, health);
    app.addHook("preClose", async () => {
      await agentRequests.close();
    });
    app.addHook("onClose", async () => {
      health.markStopped();
      try {
        runtimeDatabaseLock?.release();
      } finally {
        sqlite?.close();
      }
    });
    await app.ready();
    requireStartupActive(options.signal);

    return {
      app,
      identity: runtimeIdentity,
      health,
      costs,
      listenAddress: {
        host: loaded.config.transport.host,
        port: loaded.config.transport.port,
      },
    };
  } catch (error) {
    await app?.close().catch(() => undefined);
    try {
      runtimeDatabaseLock?.release();
    } catch {
      // The original startup error remains authoritative during cleanup.
    }
    try {
      sqlite?.close();
    } catch {
      // Preserve the original stable startup error when cleanup also fails.
    }
    throw asRuntimeStartupError(error);
  }
}

export async function startRuntime(options: BootstrapOptions = {}): Promise<StartRuntimeResult> {
  const logger = options.logger ?? new RuntimeLogger();
  const result = await bootstrap({ ...options, logger });
  let listenerBound = false;

  try {
    requireStartupActive(options.signal);
    await result.app.listen(result.listenAddress);
    listenerBound = true;
    requireStartupActive(options.signal);
    logger.ready(result.listenAddress.port);
    result.health.markReady();
  } catch (error) {
    await result.app.close().catch(() => undefined);
    throw new RuntimeStartupError({
      code: listenerBound ? "STARTUP_INTERNAL_ERROR" : "LISTEN_FAILED",
      stage: listenerBound ? "startup" : "listen",
      safeMessage: listenerBound
        ? "Runtime failed while completing startup."
        : "Runtime could not bind its local listening port.",
      cause: error,
    });
  }

  return {
    ...result,
    close: async () => {
      await result.app.close();
    },
  };
}

export interface RuntimeCliOptions {
  readonly configPath?: string;
  readonly managed: boolean;
}

export function parseRuntimeCli(arguments_: readonly string[]): RuntimeCliOptions {
  if (arguments_.length === 0) {
    return { managed: false };
  }
  if (arguments_.length === 2 && arguments_[0] === "--config" && arguments_[1] !== undefined) {
    return { configPath: arguments_[1], managed: false };
  }
  if (
    arguments_.length === 3 &&
    arguments_[0] === "--config" &&
    arguments_[1] !== undefined &&
    arguments_[2] === "--managed"
  ) {
    return { configPath: arguments_[1], managed: true };
  }

  throw new RuntimeStartupError({
    code: "CONFIG_PATH_INVALID",
    stage: "config",
    safeMessage: "Usage: agma-runtime [--config <path> [--managed]]",
  });
}

export interface ManagedParentObservation {
  readonly signal: AbortSignal;
  dispose(): void;
}

export function observeManagedParent(input: Readable): ManagedParentObservation {
  const controller = new AbortController();
  let disposed = false;
  const abort = (): void => {
    controller.abort();
  };

  input.once("end", abort);
  input.once("error", abort);
  if (input.readableEnded || input.destroyed) {
    abort();
  } else {
    input.resume();
  }

  return {
    signal: controller.signal,
    dispose: () => {
      if (disposed) {
        return;
      }
      disposed = true;
      input.off("end", abort);
      input.off("error", abort);
      input.pause();
    },
  };
}

async function runMain(): Promise<void> {
  const logger = new RuntimeLogger();
  let cli: RuntimeCliOptions;
  let runtime: StartRuntimeResult;
  let managedParent: ManagedParentObservation | undefined;
  try {
    cli = parseRuntimeCli(process.argv.slice(2));
    managedParent = cli.managed ? observeManagedParent(process.stdin) : undefined;
    runtime = await startRuntime({
      logger,
      ...(managedParent === undefined ? {} : { signal: managedParent.signal }),
      ...(cli.configPath === undefined ? {} : { configPath: cli.configPath }),
    });
    if (managedParent?.signal.aborted === true) {
      await runtime.close();
      managedParent.dispose();
      return;
    }
  } catch (error) {
    managedParent?.dispose();
    if (managedParent?.signal.aborted === true) {
      return;
    }
    logger.startupFailure(asRuntimeStartupError(error));
    process.exitCode = 1;
    return;
  }

  let stopping = false;
  const stop = async (): Promise<void> => {
    if (stopping) {
      return;
    }
    stopping = true;
    process.off("SIGINT", stop);
    process.off("SIGTERM", stop);
    managedParent?.signal.removeEventListener("abort", stopOnParentClose);
    managedParent?.dispose();
    await runtime.close();
    logger.stopped();
  };
  const stopOnParentClose = (): void => {
    void stop();
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  if (managedParent !== undefined) {
    managedParent.signal.addEventListener("abort", stopOnParentClose, { once: true });
    if (managedParent.signal.aborted) {
      await stop();
    }
  }
}

if (isMainModule(process.argv[1], import.meta.url)) {
  await runMain();
}
