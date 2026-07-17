import { constants } from "node:fs";
import { open, realpath } from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { TextDecoder } from "node:util";

import { parseDocument } from "yaml";
import { z } from "zod";

import { RuntimeStartupError } from "../bootstrap/startup-error.js";
import { modelProviderIds, type ModelProviderId } from "../providers/model-provider.js";
import type { ClientToolId } from "../tools/tool-types.js";

const MAXIMUM_CONFIG_BYTES = 64 * 1024;
const PLACEHOLDER_SECRET = /^(?:change-?me|replace-with-|your[-_])/iu;
const legacyIsolationField = `separateFrom${String.fromCharCode(80, 97, 112, 101, 114)}`;

const relativePath = z
  .string()
  .min(1)
  .max(512)
  .refine(
    (value) =>
      !value.includes("\0") &&
      !value.includes("\\") &&
      !isAbsolute(value) &&
      !/^(?:[A-Za-z]:[\\/]|\/\/)/u.test(value) &&
      !value.split("/").includes(".."),
    "must be a contained relative path",
  );

const secretReference = z
  .object({
    source: z.enum(["environment", "credential_store", "private_file"]),
    reference: z
      .string()
      .min(1)
      .max(256)
      .regex(/^[A-Za-z0-9][A-Za-z0-9._/-]*$/u)
      .refine((value) => !value.split("/").includes(".."), "must not traverse parents"),
  })
  .strict();

const baseUrl = z
  .string()
  .min(1)
  .max(2048)
  .transform((value, context) => {
    let url: URL;
    try {
      url = new URL(value);
    } catch {
      context.addIssue({ code: "custom", message: "must be an absolute URL" });
      return z.NEVER;
    }
    const loopback =
      url.protocol === "http:" &&
      url.hostname === "127.0.0.1" &&
      /^http:\/\/127\.0\.0\.1(?::[0-9]+)?(?:\/|$)/u.test(value);
    if (
      (url.protocol !== "https:" && !loopback) ||
      url.username.length > 0 ||
      url.password.length > 0 ||
      url.search.length > 0 ||
      url.hash.length > 0
    ) {
      context.addIssue({ code: "custom", message: "must use a credential-free HTTPS URL" });
      return z.NEVER;
    }
    return value.replace(/\/+$/u, "");
  });

const schema = z
  .object({
    configVersion: z.literal(3),
    profile: z.literal("client"),
    identity: z.object({ installationId: z.uuid(), scope: z.literal("installation") }).strict(),
    transport: z
      .object({
        host: z.literal("127.0.0.1"),
        port: z.number().int().min(1024).max(65_535),
        connectorToken: secretReference,
        authenticationDomain: z.literal("agma-connector-handshake-v1"),
      })
      .strict(),
    model: z
      .object({
        provider: z.enum(modelProviderIds),
        baseUrl: baseUrl.nullable(),
        apiKey: secretReference,
        model: z
          .string()
          .min(1)
          .max(128)
          .regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/u),
        timeoutSeconds: z.number().int().min(1).max(120),
        inputMicroUsdPerMillionTokens: z.number().int().min(0).max(1_000_000_000_000),
        outputMicroUsdPerMillionTokens: z.number().int().min(0).max(1_000_000_000_000),
      })
      .strict()
      .superRefine((model, context) => {
        if (model.provider === "openai-compatible" && model.baseUrl === null) {
          context.addIssue({ code: "custom", path: ["baseUrl"], message: "is required" });
        }
      }),
    storage: z.object({ sqlitePath: relativePath }).strict(),
    logging: z
      .object({ directory: relativePath, level: z.enum(["debug", "info", "warn", "error"]) })
      .strict(),
    limits: z
      .object({
        maxConcurrentRequests: z.number().int().min(1).max(8),
        maxQueuedRequests: z.number().int().min(0).max(128),
        maxToolRounds: z.number().int().min(1).max(8),
        maxContextMessages: z.number().int().min(1).max(100),
        maxContextCharacters: z.number().int().min(4096).max(65_536),
        requestCooldownSeconds: z.number().int().min(0).max(3600),
        dailyRequests: z.number().int().min(1).max(1_000_000),
        monthlyBudgetMicroUsd: z.number().int().min(0).max(1_000_000_000_000),
        providerRoundReservationMicroUsd: z.number().int().min(1).max(1_000_000_000_000),
      })
      .strict(),
    privacy: z
      .object({
        storeConversations: z.boolean(),
        retentionDays: z.number().int().min(0).max(3650),
        logMessageContent: z.literal(false),
        logToolCalls: z.literal(false),
      })
      .strict(),
    toolPolicy: z
      .object({
        allowed: z
          .array(
            z.enum([
              "game.resource.search",
              "game.process.lookup",
              "game.process.uses",
              "game.process.plan",
              "game.inventory.snapshot",
            ]),
          )
          .max(5),
        denied: z
          .array(
            z
              .string()
              .min(1)
              .max(128)
              .regex(/^[a-z][a-z0-9_.-]*$/u),
          )
          .min(4)
          .max(16),
        inventoryDefaultEnabled: z.literal(false),
      })
      .strict(),
    networkPolicy: z
      .object({
        webSearchDefaultEnabled: z.literal(false),
        remoteCustomUrlRequiresHttps: z.literal(true),
      })
      .strict(),
    webEvidence: z
      .object({
        provider: z.literal("brave"),
        apiKey: secretReference,
        requestCostMicroUsd: z.number().int().min(1).max(1_000_000_000),
        monthlyBudgetMicroUsd: z.number().int().min(0).max(1_000_000_000_000),
        defaultAuthorization: z.literal("off"),
        persistentAuthorizationEnabled: z.boolean(),
        country: z
          .string()
          .length(2)
          .regex(/^[A-Z]{2}$/u),
        searchLanguage: z
          .string()
          .min(2)
          .max(8)
          .regex(/^[a-z]{2,3}(?:-[a-z]{2})?$/u),
      })
      .strict()
      .optional(),
    storagePolicy: z.record(z.string(), z.unknown()).superRefine((policy, context) => {
      const keys = Object.keys(policy);
      if (
        keys.length !== 2 ||
        policy["scope"] !== "installation" ||
        policy[legacyIsolationField] !== true
      ) {
        context.addIssue({ code: "custom", message: "must declare isolated installation storage" });
      }
    }),
  })
  .strict()
  .superRefine((config, context) => {
    if (!config.privacy.storeConversations && config.privacy.retentionDays !== 0) {
      context.addIssue({
        code: "custom",
        path: ["privacy", "retentionDays"],
        message: "must be zero when conversation storage is disabled",
      });
    }
    if (new Set(config.toolPolicy.allowed).size !== config.toolPolicy.allowed.length) {
      context.addIssue({
        code: "custom",
        path: ["toolPolicy", "allowed"],
        message: "must be unique",
      });
    }
    if (new Set(config.toolPolicy.denied).size !== config.toolPolicy.denied.length) {
      context.addIssue({
        code: "custom",
        path: ["toolPolicy", "denied"],
        message: "must be unique",
      });
    }
    if (
      config.transport.connectorToken.source === config.model.apiKey.source &&
      config.transport.connectorToken.reference === config.model.apiKey.reference
    ) {
      context.addIssue({
        code: "custom",
        path: ["transport", "connectorToken"],
        message: "must use a distinct secret reference",
      });
    }
    if (
      config.webEvidence !== undefined &&
      config.webEvidence.monthlyBudgetMicroUsd < config.webEvidence.requestCostMicroUsd
    ) {
      context.addIssue({
        code: "custom",
        path: ["webEvidence", "monthlyBudgetMicroUsd"],
        message: "must fund one request",
      });
    }
  });

export type StandaloneClientConfig = z.infer<typeof schema>;

export interface StandaloneModelConfig {
  readonly provider: ModelProviderId;
  readonly baseUrl?: string;
  readonly apiKey: string;
  readonly model: string;
  readonly timeoutSeconds: number;
  readonly inputMicroUsdPerMillionTokens: number;
  readonly outputMicroUsdPerMillionTokens: number;
}

export interface StandaloneServiceConfig {
  readonly scopeId: string;
  readonly transport: { readonly host: "127.0.0.1"; readonly port: number };
  readonly model: StandaloneModelConfig;
  readonly limits: {
    readonly maxConcurrentRequests: number;
    readonly maxQueuedRequests: number;
    readonly maxToolRounds: number;
    readonly maxContextMessages: number;
    readonly maxContextCharacters: number;
    readonly perPlayerCooldownSeconds: number;
    readonly dailyRequestsPerPlayer: number;
    readonly monthlyBudgetMicroUsd: number;
    readonly providerRoundReservationMicroUsd: number;
  };
  readonly privacy: StandaloneClientConfig["privacy"];
}

export interface ResolvedStandaloneConfig {
  readonly profile: "client";
  readonly scopeId: string;
  readonly subjectId: string;
  readonly connectorToken: string;
  readonly allowedClientTools: readonly ClientToolId[];
  readonly service: StandaloneServiceConfig;
  readonly webEvidence?: {
    readonly provider: "brave";
    readonly apiKey: string;
    readonly requestCostMicroUsd: number;
    readonly monthlyBudgetMicroUsd: number;
    readonly defaultAuthorization: "off";
    readonly persistentAuthorizationEnabled: boolean;
    readonly country: string;
    readonly searchLanguage: string;
  };
}

export interface StandaloneConfigWarning {
  readonly code: "CONFIG_FILE_PERMISSIONS_WIDE" | "MODEL_CUSTOM_BASE_URL";
  readonly field?: string;
}

export interface LoadStandaloneConfigOptions {
  readonly configPath?: string;
  readonly cwd?: string;
  readonly environment?: Readonly<Record<string, string | undefined>>;
}

export interface LoadedStandaloneConfig {
  readonly config: StandaloneClientConfig;
  readonly resolved: ResolvedStandaloneConfig;
  readonly paths: {
    readonly rootDirectory: string;
    readonly sqlite: string;
    readonly logDirectory: string;
  };
  readonly warnings: readonly StandaloneConfigWarning[];
}

function contained(root: string, configured: string, field: string): string {
  const candidate = resolve(root, configured);
  const path = relative(root, candidate);
  if (path === "" || path === "." || path === ".." || path.startsWith(`..${sep}`)) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_ESCAPE",
      stage: "config",
      field,
      safeMessage: "A standalone Runtime path escapes its private root.",
    });
  }
  return candidate;
}

async function readPrivateFile(
  path: string,
  maximumBytes: number,
): Promise<{ bytes: Buffer; wide: boolean }> {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const metadata = await handle.stat();
    if (
      !metadata.isFile() ||
      metadata.size < 1 ||
      metadata.size > maximumBytes ||
      metadata.nlink !== 1
    ) {
      throw new Error("invalid private file");
    }
    const bytes = Buffer.alloc(metadata.size);
    let offset = 0;
    while (offset < bytes.length) {
      const read = await handle.read(bytes, offset, bytes.length - offset, offset);
      if (read.bytesRead === 0) break;
      offset += read.bytesRead;
    }
    if (offset !== bytes.length) throw new Error("short private file read");
    return { bytes, wide: process.platform !== "win32" && (metadata.mode & 0o077) !== 0 };
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

async function resolveSecret(
  reference: z.infer<typeof secretReference>,
  root: string,
  environment: Readonly<Record<string, string | undefined>>,
  field: string,
): Promise<string> {
  if (reference.source === "credential_store") {
    throw new RuntimeStartupError({
      code: "SECRET_REFERENCE_UNSUPPORTED",
      stage: "config",
      field,
      safeMessage: "The configured secret store is unavailable.",
    });
  }
  let value: string | undefined;
  if (reference.source === "environment") {
    if (!/^[A-Z_][A-Z0-9_]*$/u.test(reference.reference)) value = undefined;
    else value = environment[reference.reference];
  } else {
    const secret = await readPrivateFile(contained(root, reference.reference, field), 8192);
    if (secret.wide) {
      throw new RuntimeStartupError({
        code: "SECRET_FILE_INVALID",
        stage: "config",
        field,
        safeMessage: "A standalone secret file has broad permissions.",
      });
    }
    try {
      value = new TextDecoder("utf-8", { fatal: true }).decode(secret.bytes).replace(/\r?\n$/u, "");
    } catch {
      value = undefined;
    }
  }
  if (
    value === undefined ||
    value.trim().length < 1 ||
    value.length > 8192 ||
    PLACEHOLDER_SECRET.test(value) ||
    /\p{Cc}/u.test(value)
  ) {
    throw new RuntimeStartupError({
      code: field === "/webEvidence/apiKey" ? "SEARCH_API_KEY_MISSING" : "API_KEY_MISSING",
      stage: "config",
      field,
      safeMessage: "A required standalone secret is missing or invalid.",
    });
  }
  return value;
}

export async function loadStandaloneClientConfig(
  options: LoadStandaloneConfigOptions = {},
): Promise<LoadedStandaloneConfig> {
  const cwd = resolve(options.cwd ?? process.cwd());
  const requested = resolve(cwd, options.configPath ?? "config.local.yml");
  let canonical: string;
  try {
    canonical = await realpath(requested);
  } catch (error) {
    throw new RuntimeStartupError({
      code: "CONFIG_NOT_FOUND",
      stage: "config",
      safeMessage: "The standalone Runtime configuration file was not found.",
      cause: error,
    });
  }
  if (canonical !== requested) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_SYMLINK",
      stage: "config",
      safeMessage: "The standalone Runtime configuration path must not use a symbolic link.",
    });
  }
  let configFile;
  try {
    configFile = await readPrivateFile(canonical, MAXIMUM_CONFIG_BYTES);
  } catch (error) {
    throw new RuntimeStartupError({
      code: "CONFIG_READ_FAILED",
      stage: "config",
      safeMessage: "The standalone Runtime configuration could not be read safely.",
      cause: error,
    });
  }
  let source: string;
  try {
    source = new TextDecoder("utf-8", { fatal: true }).decode(configFile.bytes);
  } catch (error) {
    throw new RuntimeStartupError({
      code: "CONFIG_PARSE_FAILED",
      stage: "config",
      safeMessage: "The standalone Runtime configuration is not valid UTF-8.",
      cause: error,
    });
  }
  const document = parseDocument(source, {
    merge: false,
    prettyErrors: false,
    schema: "core",
    uniqueKeys: true,
  });
  if (document.errors.length > 0 || document.warnings.length > 0) {
    throw new RuntimeStartupError({
      code: "CONFIG_PARSE_FAILED",
      stage: "config",
      safeMessage: "The standalone Runtime configuration is not valid YAML.",
    });
  }
  const parsed = schema.safeParse(document.toJS({ maxAliasCount: 0 }));
  if (!parsed.success) {
    throw new RuntimeStartupError({
      code: "CONFIG_SCHEMA_INVALID",
      stage: "config",
      field: `/${parsed.error.issues[0]?.path.join("/") ?? ""}`,
      safeMessage: "The standalone Runtime configuration violates the client profile schema.",
    });
  }
  const config = parsed.data;
  const root = dirname(canonical);
  const environment = options.environment ?? process.env;
  const [connectorToken, modelApiKey, searchApiKey] = await Promise.all([
    resolveSecret(config.transport.connectorToken, root, environment, "/transport/connectorToken"),
    resolveSecret(config.model.apiKey, root, environment, "/model/apiKey"),
    config.webEvidence === undefined
      ? Promise.resolve(undefined)
      : resolveSecret(config.webEvidence.apiKey, root, environment, "/webEvidence/apiKey"),
  ]);
  if (
    connectorToken.length < 32 ||
    connectorToken === modelApiKey ||
    (searchApiKey !== undefined &&
      (searchApiKey === connectorToken || searchApiKey === modelApiKey))
  ) {
    throw new RuntimeStartupError({
      code: "SECRET_REUSE",
      stage: "config",
      safeMessage: "Standalone Runtime secrets must be distinct.",
    });
  }
  const model: StandaloneModelConfig = {
    provider: config.model.provider,
    ...(config.model.baseUrl === null ? {} : { baseUrl: config.model.baseUrl }),
    apiKey: modelApiKey,
    model: config.model.model,
    timeoutSeconds: config.model.timeoutSeconds,
    inputMicroUsdPerMillionTokens: config.model.inputMicroUsdPerMillionTokens,
    outputMicroUsdPerMillionTokens: config.model.outputMicroUsdPerMillionTokens,
  };
  const service: StandaloneServiceConfig = {
    scopeId: config.identity.installationId,
    transport: { host: config.transport.host, port: config.transport.port },
    model,
    limits: {
      maxConcurrentRequests: config.limits.maxConcurrentRequests,
      maxQueuedRequests: config.limits.maxQueuedRequests,
      maxToolRounds: config.limits.maxToolRounds,
      maxContextMessages: config.limits.maxContextMessages,
      maxContextCharacters: config.limits.maxContextCharacters,
      perPlayerCooldownSeconds: config.limits.requestCooldownSeconds,
      dailyRequestsPerPlayer: config.limits.dailyRequests,
      monthlyBudgetMicroUsd: config.limits.monthlyBudgetMicroUsd,
      providerRoundReservationMicroUsd: config.limits.providerRoundReservationMicroUsd,
    },
    privacy: config.privacy,
  };
  return {
    config,
    resolved: {
      profile: "client",
      scopeId: config.identity.installationId,
      subjectId: config.identity.installationId,
      connectorToken,
      allowedClientTools: config.toolPolicy.allowed,
      service,
      ...(config.webEvidence === undefined || searchApiKey === undefined
        ? {}
        : {
            webEvidence: {
              provider: config.webEvidence.provider,
              apiKey: searchApiKey,
              requestCostMicroUsd: config.webEvidence.requestCostMicroUsd,
              monthlyBudgetMicroUsd: config.webEvidence.monthlyBudgetMicroUsd,
              defaultAuthorization: config.webEvidence.defaultAuthorization,
              persistentAuthorizationEnabled: config.webEvidence.persistentAuthorizationEnabled,
              country: config.webEvidence.country,
              searchLanguage: config.webEvidence.searchLanguage,
            },
          }),
    },
    paths: {
      rootDirectory: root,
      sqlite: contained(root, config.storage.sqlitePath, "/storage/sqlitePath"),
      logDirectory: contained(root, config.logging.directory, "/logging/directory"),
    },
    warnings: [
      ...(configFile.wide ? [{ code: "CONFIG_FILE_PERMISSIONS_WIDE" as const }] : []),
      ...(config.model.baseUrl === null
        ? []
        : [{ code: "MODEL_CUSTOM_BASE_URL" as const, field: "/model/baseUrl" }]),
    ],
  };
}
