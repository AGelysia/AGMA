import { constants } from "node:fs";
import { lstat, open, realpath } from "node:fs/promises";
import { basename, dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { TextDecoder } from "node:util";

import { parseDocument } from "yaml";
import { z, type ZodIssue } from "zod";

import { RuntimeStartupError, type RuntimeConfigIssue } from "../bootstrap/startup-error.js";
import { modelProviderIds } from "../providers/model-provider.js";

const MAX_CONFIG_BYTES = 64 * 1024;
const MAX_BASE_URL_LENGTH = 2048;
const PAPER_CONFIG_VERSION = 2;
const CLIENT_CONFIG_VERSION = 3;
const ENVIRONMENT_REFERENCE = /^\$\{([A-Z_][A-Z0-9_]*)\}$/u;
const PLACEHOLDER_SECRET = /^(?:change-?me|replace-with-|your[-_])/iu;

const relativePathSchema = z
  .string()
  .min(1)
  .max(512)
  .superRefine((value, context) => {
    const segments = value.split(/[\\/]/u);
    const looksLikeWindowsAbsolute = /^(?:[A-Za-z]:[\\/]|\\\\|\/\/)/u.test(value);

    if (
      value.includes("\0") ||
      value.includes("\\") ||
      isAbsolute(value) ||
      looksLikeWindowsAbsolute ||
      segments.includes("..")
    ) {
      context.addIssue({
        code: "custom",
        message: "must be a contained relative path using forward slashes",
      });
    }
  });

const privateDirectoryPathSchema = relativePathSchema.refine(
  (value) => value.split("/").some((segment) => segment !== "." && segment.length > 0),
  "must name a private subdirectory",
);

const sqlitePathSchema = relativePathSchema.refine(
  (value) =>
    value.split("/").filter((segment) => segment !== "." && segment.length > 0).length >= 2,
  "must place the database inside a private subdirectory",
);

const secretSchema = z
  .string()
  .min(1)
  .max(8192)
  .refine((value) => value.trim().length > 0, "must not be blank")
  .refine(
    (value) =>
      [...value].every((character) => {
        const codePoint = character.codePointAt(0);
        return codePoint !== undefined && codePoint > 0x1f && codePoint !== 0x7f;
      }),
    "must not contain control characters",
  );

const transportPortSchema = z.preprocess(
  (value) =>
    typeof value === "string" && /^[0-9]{1,5}$/u.test(value) ? Number.parseInt(value, 10) : value,
  z.number().int().min(1024).max(65_535),
);

const baseUrlSchema = z
  .string()
  .min(1)
  .max(MAX_BASE_URL_LENGTH)
  .transform((value, context) => {
    if (
      value !== value.trim() ||
      [...value].some((character) => {
        const codePoint = character.codePointAt(0);
        return codePoint === undefined || codePoint <= 0x1f || codePoint === 0x7f;
      }) ||
      !/^https?:\/\//iu.test(value)
    ) {
      context.addIssue({ code: "custom", message: "must be an absolute HTTP URL" });
      return z.NEVER;
    }

    let parsed: URL;
    try {
      parsed = new URL(value);
    } catch {
      context.addIssue({ code: "custom", message: "must be a valid URL" });
      return z.NEVER;
    }

    const isSecure = parsed.protocol === "https:";
    const isLoopbackHttp =
      parsed.protocol === "http:" &&
      (parsed.hostname === "127.0.0.1" || parsed.hostname === "[::1]") &&
      /^http:\/\/(?:127\.0\.0\.1|\[::1\])(?::[0-9]+)?(?:\/|$)/iu.test(value);
    if (!isSecure && !isLoopbackHttp) {
      context.addIssue({
        code: "custom",
        message: "must use HTTPS unless the host is an IP loopback address",
      });
      return z.NEVER;
    }
    if (parsed.username.length > 0 || parsed.password.length > 0) {
      context.addIssue({ code: "custom", message: "must not contain credentials" });
      return z.NEVER;
    }
    if (value.includes("?") || value.includes("#")) {
      context.addIssue({ code: "custom", message: "must not contain a query or fragment" });
      return z.NEVER;
    }

    const pathname = parsed.pathname.replace(/\/+$/u, "");
    return pathname.length === 0 ? parsed.origin : `${parsed.origin}${pathname}`;
  });

const runtimeConfigSchema = z
  .object({
    configVersion: z.literal(PAPER_CONFIG_VERSION),
    server: z
      .object({
        id: z
          .string()
          .min(1)
          .max(64)
          .regex(/^[a-z0-9][a-z0-9._-]*$/u),
      })
      .strict(),
    transport: z
      .object({
        host: z.literal("127.0.0.1"),
        port: transportPortSchema,
        serverToken: secretSchema.refine(
          (value) => value.length >= 32,
          "must contain at least 32 characters",
        ),
      })
      .strict(),
    model: z
      .object({
        provider: z.enum(modelProviderIds),
        baseUrl: baseUrlSchema.optional(),
        apiKey: secretSchema,
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
        if (model.provider === "openai-compatible" && model.baseUrl === undefined) {
          context.addIssue({
            code: "custom",
            path: ["baseUrl"],
            message: "is required for the openai-compatible provider",
          });
        }
      }),
    storage: z
      .object({
        sqlitePath: sqlitePathSchema,
      })
      .strict(),
    logging: z
      .object({
        directory: privateDirectoryPathSchema,
        level: z.enum(["debug", "info", "warn", "error"]),
      })
      .strict(),
    knowledge: z
      .object({
        roots: z
          .array(
            z
              .object({
                directory: privateDirectoryPathSchema,
                kind: z.enum(["server_rules", "local_docs"]),
              })
              .strict(),
          )
          .max(8),
      })
      .strict()
      .superRefine((knowledge, context) => {
        const directories = new Set<string>();
        knowledge.roots.forEach((root, index) => {
          if (directories.has(root.directory)) {
            context.addIssue({
              code: "custom",
              path: ["roots", index, "directory"],
              message: "must not duplicate another knowledge root",
            });
          }
          directories.add(root.directory);
        });
      })
      .optional(),
    limits: z
      .object({
        maxConcurrentRequests: z.number().int().min(1).max(64),
        maxQueuedRequests: z.number().int().min(0).max(10_000),
        maxToolRounds: z.number().int().min(1).max(8),
        maxContextMessages: z.number().int().min(1).max(100).default(30),
        maxContextCharacters: z.number().int().min(4096).max(65_536).default(32_768),
        perPlayerCooldownSeconds: z.number().int().min(0).max(3600),
        dailyRequestsPerPlayer: z.number().int().min(1).max(1_000_000),
        monthlyBudgetUsd: z
          .number()
          .finite()
          .min(0)
          .max(1_000_000)
          .refine((value) => Number.isSafeInteger(value * 1_000_000), {
            message: "must use at most six decimal places",
          }),
        providerRoundReservationMicroUsd: z.number().int().min(1).max(1_000_000_000_000),
      })
      .strict(),
    privacy: z
      .object({
        storeConversations: z.boolean(),
        retentionDays: z.number().int().min(0).max(3650),
        logMessageContent: z.boolean(),
        logToolCalls: z.boolean(),
      })
      .strict(),
  })
  .strict()
  .superRefine((config, context) => {
    if (!config.privacy.storeConversations && config.privacy.retentionDays !== 0) {
      context.addIssue({
        code: "custom",
        path: ["privacy", "retentionDays"],
        message: "must be zero when conversations are not stored",
      });
    }
  });

const secretReferenceSchema = z
  .object({
    source: z.enum(["environment", "credential_store", "private_file"]),
    reference: z
      .string()
      .min(1)
      .max(256)
      .regex(/^[A-Za-z0-9][A-Za-z0-9._/-]*$/u)
      .refine((value) => !value.split("/").includes(".."), "must not contain parent traversal"),
  })
  .strict();

const clientRuntimeConfigSchema = z
  .object({
    configVersion: z.literal(CLIENT_CONFIG_VERSION),
    profile: z.literal("client"),
    identity: z
      .object({
        installationId: z.uuid(),
        scope: z.literal("installation"),
      })
      .strict(),
    transport: z
      .object({
        host: z.literal("127.0.0.1"),
        port: transportPortSchema,
        connectorToken: secretReferenceSchema,
        authenticationDomain: z.literal("agma-connector-handshake-v1"),
      })
      .strict(),
    model: z
      .object({
        provider: z.enum(modelProviderIds),
        baseUrl: baseUrlSchema
          .refine(
            (value) => value.startsWith("https://") || value.startsWith("http://127.0.0.1"),
            "must use HTTPS unless the host is literal IPv4 loopback",
          )
          .nullable(),
        apiKey: secretReferenceSchema,
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
          context.addIssue({
            code: "custom",
            path: ["baseUrl"],
            message: "is required for the openai-compatible provider",
          });
        }
      }),
    storage: z.object({ sqlitePath: sqlitePathSchema }).strict(),
    logging: z
      .object({
        directory: privateDirectoryPathSchema,
        level: z.enum(["debug", "info", "warn", "error"]),
      })
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
            z.enum([
              "paper.command",
              "paper.permission",
              "server.payload",
              "world.write",
              "arbitrary.web.fetch",
            ]),
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
        apiKey: secretReferenceSchema,
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
      .superRefine((webEvidence, context) => {
        if (webEvidence.monthlyBudgetMicroUsd < webEvidence.requestCostMicroUsd) {
          context.addIssue({
            code: "custom",
            path: ["monthlyBudgetMicroUsd"],
            message: "must fund at least one configured search request",
          });
        }
      })
      .optional(),
    storagePolicy: z
      .object({
        scope: z.literal("installation"),
        separateFromPaper: z.literal(true),
      })
      .strict(),
  })
  .strict()
  .superRefine((config, context) => {
    if (!config.privacy.storeConversations && config.privacy.retentionDays !== 0) {
      context.addIssue({
        code: "custom",
        path: ["privacy", "retentionDays"],
        message: "must be zero when conversations are not stored",
      });
    }
    if (config.toolPolicy.allowed.length !== new Set(config.toolPolicy.allowed).size) {
      context.addIssue({
        code: "custom",
        path: ["toolPolicy", "allowed"],
        message: "must contain unique tool identifiers",
      });
    }
    if (config.toolPolicy.denied.length !== new Set(config.toolPolicy.denied).size) {
      context.addIssue({
        code: "custom",
        path: ["toolPolicy", "denied"],
        message: "must contain unique capability identifiers",
      });
    }
    for (const required of [
      "paper.command",
      "server.payload",
      "world.write",
      "arbitrary.web.fetch",
    ]) {
      if (!config.toolPolicy.denied.includes(required as never)) {
        context.addIssue({
          code: "custom",
          path: ["toolPolicy", "denied"],
          message: "must retain every mandatory client denial",
        });
        break;
      }
    }
    if (
      config.transport.connectorToken.source === config.model.apiKey.source &&
      config.transport.connectorToken.reference === config.model.apiKey.reference
    ) {
      context.addIssue({
        code: "custom",
        path: ["transport", "connectorToken"],
        message: "must use a distinct reference from the model API key",
      });
    }
  });

export type RuntimeConfig = z.infer<typeof runtimeConfigSchema>;
export type ClientRuntimeConfig = z.infer<typeof clientRuntimeConfigSchema>;
export type RuntimeConfigDocument = RuntimeConfig | ClientRuntimeConfig;

export interface ResolvedRuntimeConfig {
  readonly profile: "paper" | "client";
  readonly allowedClientTools: readonly ClientRuntimeConfig["toolPolicy"]["allowed"][number][];
  readonly scopeId: string;
  readonly subjectId: string;
  readonly authenticationSecret: string;
  readonly transport: RuntimeConfig["transport"];
  readonly model: RuntimeConfig["model"];
  readonly storage: RuntimeConfig["storage"];
  readonly logging: RuntimeConfig["logging"];
  readonly knowledge?: RuntimeConfig["knowledge"];
  readonly limits: RuntimeConfig["limits"];
  readonly privacy: RuntimeConfig["privacy"];
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

export function runtimeServiceConfig(resolved: ResolvedRuntimeConfig): RuntimeConfig {
  return {
    configVersion: PAPER_CONFIG_VERSION,
    server: { id: resolved.scopeId },
    transport: resolved.transport,
    model: resolved.model,
    storage: resolved.storage,
    logging: resolved.logging,
    ...(resolved.knowledge === undefined ? {} : { knowledge: resolved.knowledge }),
    limits: resolved.limits,
    privacy: resolved.privacy,
  };
}
export type KnowledgeRootKind = "server_rules" | "local_docs";

export const runtimeConfigWarningCodes = [
  "CONFIG_INLINE_SECRET",
  "CONFIG_FILE_PERMISSIONS_WIDE",
  "MODEL_CUSTOM_BASE_URL",
  "PRIVACY_MESSAGE_LOGGING_ENABLED",
] as const;

export type RuntimeConfigWarningCode = (typeof runtimeConfigWarningCodes)[number];

export interface RuntimeConfigWarning {
  readonly code: RuntimeConfigWarningCode;
  readonly field?: string;
}

export interface RuntimeConfigPaths {
  readonly configFile: string;
  readonly rootDirectory: string;
  readonly sqlite: string;
  readonly logDirectory: string;
  readonly knowledgeRoots: readonly RuntimeKnowledgeRootPath[];
}

export interface RuntimeKnowledgeRootPath {
  readonly directory: string;
  readonly kind: KnowledgeRootKind;
}

export interface LoadedRuntimeConfig {
  readonly config: RuntimeConfigDocument;
  readonly resolved: ResolvedRuntimeConfig;
  readonly paths: RuntimeConfigPaths;
  readonly warnings: readonly RuntimeConfigWarning[];
}

export interface LoadRuntimeConfigOptions {
  readonly configPath?: string;
  readonly environment?: Readonly<Record<string, string | undefined>>;
  readonly workingDirectory?: string;
}

interface ExpandedConfiguration {
  readonly value: unknown;
  readonly environmentFields: ReadonlySet<string>;
}

function jsonPointer(path: readonly PropertyKey[]): string {
  if (path.length === 0) {
    return "/";
  }

  return `/${path
    .map(String)
    .map((segment) => segment.replaceAll("~", "~0").replaceAll("/", "~1"))
    .join("/")}`;
}

function missingEnvironmentError(field: string): RuntimeStartupError {
  if (field === "/model/apiKey") {
    return new RuntimeStartupError({
      code: "API_KEY_MISSING",
      stage: "config",
      field,
      safeMessage: "The model API key is required.",
    });
  }
  if (field === "/transport/serverToken") {
    return new RuntimeStartupError({
      code: "SERVER_TOKEN_MISSING",
      stage: "config",
      field,
      safeMessage: "The Runtime server token is required.",
    });
  }

  return new RuntimeStartupError({
    code: "CONFIG_ENV_MISSING",
    stage: "config",
    field,
    safeMessage: "A required configuration environment variable is missing.",
  });
}

function expandEnvironmentReferences(
  input: unknown,
  environment: Readonly<Record<string, string | undefined>>,
): ExpandedConfiguration {
  const environmentFields = new Set<string>();

  const visit = (value: unknown, path: readonly PropertyKey[]): unknown => {
    if (path.length > 32) {
      throw new RuntimeStartupError({
        code: "CONFIG_SCHEMA_INVALID",
        stage: "config",
        field: jsonPointer(path.slice(0, 32)),
        safeMessage: "Runtime configuration nesting exceeds the supported limit.",
      });
    }

    if (typeof value === "string") {
      const field = jsonPointer(path);
      const match = ENVIRONMENT_REFERENCE.exec(value);
      if (match !== null) {
        const name = match[1];
        const replacement = name === undefined ? undefined : environment[name];
        if (replacement === undefined || replacement.trim().length === 0) {
          throw missingEnvironmentError(field);
        }
        if (replacement.includes("${")) {
          throw new RuntimeStartupError({
            code: "CONFIG_ENV_SYNTAX",
            stage: "config",
            field,
            safeMessage: "Recursive environment references are not supported.",
          });
        }

        environmentFields.add(field);
        return replacement;
      }

      if (value.includes("${")) {
        throw new RuntimeStartupError({
          code: "CONFIG_ENV_SYNTAX",
          stage: "config",
          field,
          safeMessage: "Environment references must occupy an entire configuration value.",
        });
      }
      return value;
    }

    if (Array.isArray(value)) {
      return value.map((entry, index) => visit(entry, [...path, index]));
    }

    if (typeof value === "object" && value !== null) {
      return Object.fromEntries(
        Object.entries(value).map(([key, entry]) => [key, visit(entry, [...path, key])]),
      );
    }

    return value;
  };

  return {
    value: visit(input, []),
    environmentFields,
  };
}

function safeIssues(issues: readonly ZodIssue[]): readonly RuntimeConfigIssue[] {
  const safe: RuntimeConfigIssue[] = issues.map((issue) => ({
    // Unknown key names are untrusted and may themselves contain secret material.
    field: jsonPointer(issue.path),
    rule: issue.code,
  }));

  return safe.sort((left, right) =>
    left.field === right.field
      ? left.rule.localeCompare(right.rule)
      : left.field.localeCompare(right.field),
  );
}

function schemaError(issues: readonly ZodIssue[]): RuntimeStartupError {
  const details = safeIssues(issues);
  const apiKeyIssue = details.find(
    (issue) => issue.field === "/model/apiKey" || issue.field.startsWith("/model/apiKey/"),
  );
  if (apiKeyIssue !== undefined) {
    return new RuntimeStartupError({
      code: "API_KEY_MISSING",
      stage: "config",
      field: apiKeyIssue.field,
      issues: details,
      safeMessage: "The model API key is missing or invalid.",
    });
  }

  const serverTokenIssue = details.find((issue) => issue.field === "/transport/serverToken");
  if (serverTokenIssue !== undefined) {
    return new RuntimeStartupError({
      code: "SERVER_TOKEN_MISSING",
      stage: "config",
      field: serverTokenIssue.field,
      issues: details,
      safeMessage: "The Runtime server token is missing or invalid.",
    });
  }

  const connectorTokenIssue = details.find(
    (issue) =>
      issue.field === "/transport/connectorToken" ||
      issue.field.startsWith("/transport/connectorToken/"),
  );
  if (connectorTokenIssue !== undefined) {
    return new RuntimeStartupError({
      code: "CONNECTOR_TOKEN_MISSING",
      stage: "config",
      field: connectorTokenIssue.field,
      issues: details,
      safeMessage: "The Runtime connector token reference is missing or invalid.",
    });
  }

  return new RuntimeStartupError({
    code: "CONFIG_SCHEMA_INVALID",
    stage: "config",
    issues: details,
    safeMessage: "Runtime configuration does not satisfy the required schema.",
    ...(details[0] === undefined ? {} : { field: details[0].field }),
  });
}

function assertSupportedConfigVersion(input: unknown): void {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return;
  }
  const version = (input as Readonly<Record<string, unknown>>)["configVersion"];
  if (
    typeof version !== "number" ||
    version === PAPER_CONFIG_VERSION ||
    version === CLIENT_CONFIG_VERSION
  ) {
    return;
  }

  throw new RuntimeStartupError({
    code: "CONFIG_VERSION_UNSUPPORTED",
    stage: "config",
    field: "/configVersion",
    safeMessage:
      version === 1
        ? "Runtime configuration version 1 is no longer supported. Upgrade to configVersion 2."
        : "Runtime configuration version is unsupported. Use configVersion 2 or 3.",
  });
}

function assertPaperSecretValues(config: RuntimeConfig): void {
  const secrets = [
    ["/model/apiKey", config.model.apiKey],
    ["/transport/serverToken", config.transport.serverToken],
  ] as const;

  for (const [field, secret] of secrets) {
    if (PLACEHOLDER_SECRET.test(secret)) {
      throw new RuntimeStartupError({
        code: "SECRET_PLACEHOLDER",
        stage: "config",
        field,
        safeMessage: "A placeholder secret cannot be used to start the Runtime.",
      });
    }
  }

  if (config.model.apiKey === config.transport.serverToken) {
    throw new RuntimeStartupError({
      code: "SECRET_REUSE",
      stage: "config",
      field: "/transport/serverToken",
      safeMessage: "The Runtime server token must not reuse the model API key.",
    });
  }
}

interface ResolvedClientSecrets {
  readonly connectorToken: string;
  readonly modelApiKey: string;
  readonly searchApiKey?: string;
}

function clientSecretError(
  field: "/transport/connectorToken" | "/model/apiKey" | "/webEvidence/apiKey",
  code:
    | "CONNECTOR_TOKEN_MISSING"
    | "API_KEY_MISSING"
    | "SEARCH_API_KEY_MISSING"
    | "SECRET_FILE_INVALID",
  message: string,
  cause?: unknown,
): RuntimeStartupError {
  return new RuntimeStartupError({
    code,
    stage: "config",
    field,
    safeMessage: message,
    ...(cause === undefined ? {} : { cause }),
  });
}

async function readPrivateSecret(
  rootDirectory: string,
  reference: string,
  field: "/transport/connectorToken" | "/model/apiKey" | "/webEvidence/apiKey",
): Promise<string> {
  const path = resolveContainedPath(rootDirectory, reference, field);
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const metadata = await handle.stat();
    if (
      !metadata.isFile() ||
      metadata.size < 1 ||
      metadata.size > 8192 ||
      metadata.nlink !== 1 ||
      (process.platform !== "win32" && (metadata.mode & 0o077) !== 0)
    ) {
      throw clientSecretError(
        field,
        "SECRET_FILE_INVALID",
        "A private secret file is missing or does not satisfy the required file policy.",
      );
    }
    const bytes = Buffer.alloc(metadata.size);
    let offset = 0;
    while (offset < bytes.length) {
      const result = await handle.read(bytes, offset, bytes.length - offset, offset);
      if (result.bytesRead === 0) break;
      offset += result.bytesRead;
    }
    if (offset !== bytes.length) {
      throw clientSecretError(
        field,
        "SECRET_FILE_INVALID",
        "A private secret file changed while it was being read.",
      );
    }
    let value: string;
    try {
      value = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch (error) {
      throw clientSecretError(
        field,
        "SECRET_FILE_INVALID",
        "A private secret file is not valid UTF-8.",
        error,
      );
    }
    value = value.replace(/\r?\n$/u, "");
    if (value.includes("\r") || value.includes("\n") || !secretSchema.safeParse(value).success) {
      throw clientSecretError(
        field,
        "SECRET_FILE_INVALID",
        "A private secret file contains an invalid secret value.",
      );
    }
    return value;
  } catch (error) {
    if (error instanceof RuntimeStartupError) throw error;
    throw clientSecretError(
      field,
      "SECRET_FILE_INVALID",
      "A private secret file could not be opened safely.",
      error,
    );
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

async function resolveClientSecret(
  reference: ClientRuntimeConfig["model"]["apiKey"],
  field: "/transport/connectorToken" | "/model/apiKey" | "/webEvidence/apiKey",
  rootDirectory: string,
  environment: Readonly<Record<string, string | undefined>>,
): Promise<string> {
  if (reference.source === "credential_store") {
    throw new RuntimeStartupError({
      code: "SECRET_REFERENCE_UNSUPPORTED",
      stage: "config",
      field,
      safeMessage: "This secret reference source is not available in the C1 Runtime.",
    });
  }
  if (reference.source === "private_file") {
    return readPrivateSecret(rootDirectory, reference.reference, field);
  }
  if (!/^[A-Z_][A-Z0-9_]*$/u.test(reference.reference)) {
    throw new RuntimeStartupError({
      code: "CONFIG_SCHEMA_INVALID",
      stage: "config",
      field: `${field}/reference`,
      safeMessage: "An environment secret reference is invalid.",
    });
  }
  const value = environment[reference.reference];
  if (value === undefined || value.trim().length === 0) {
    throw clientSecretError(
      field,
      field === "/model/apiKey"
        ? "API_KEY_MISSING"
        : field === "/webEvidence/apiKey"
          ? "SEARCH_API_KEY_MISSING"
          : "CONNECTOR_TOKEN_MISSING",
      field === "/model/apiKey"
        ? "The model API key is required."
        : field === "/webEvidence/apiKey"
          ? "The web Search API key is required."
          : "The Runtime connector token is required.",
    );
  }
  if (!secretSchema.safeParse(value).success) {
    throw clientSecretError(
      field,
      field === "/model/apiKey"
        ? "API_KEY_MISSING"
        : field === "/webEvidence/apiKey"
          ? "SEARCH_API_KEY_MISSING"
          : "CONNECTOR_TOKEN_MISSING",
      field === "/model/apiKey"
        ? "The model API key is invalid."
        : field === "/webEvidence/apiKey"
          ? "The web Search API key is invalid."
          : "The Runtime connector token is invalid.",
    );
  }
  return value;
}

async function resolveClientSecrets(
  config: ClientRuntimeConfig,
  rootDirectory: string,
  environment: Readonly<Record<string, string | undefined>>,
): Promise<ResolvedClientSecrets> {
  const [connectorToken, modelApiKey, searchApiKey] = await Promise.all([
    resolveClientSecret(
      config.transport.connectorToken,
      "/transport/connectorToken",
      rootDirectory,
      environment,
    ),
    resolveClientSecret(config.model.apiKey, "/model/apiKey", rootDirectory, environment),
    config.webEvidence === undefined
      ? Promise.resolve(undefined)
      : resolveClientSecret(
          config.webEvidence.apiKey,
          "/webEvidence/apiKey",
          rootDirectory,
          environment,
        ),
  ]);
  if (connectorToken.length < 32) {
    throw clientSecretError(
      "/transport/connectorToken",
      "CONNECTOR_TOKEN_MISSING",
      "The Runtime connector token must contain at least 32 characters.",
    );
  }
  if (PLACEHOLDER_SECRET.test(connectorToken) || PLACEHOLDER_SECRET.test(modelApiKey)) {
    throw new RuntimeStartupError({
      code: "SECRET_PLACEHOLDER",
      stage: "config",
      field: PLACEHOLDER_SECRET.test(connectorToken)
        ? "/transport/connectorToken"
        : "/model/apiKey",
      safeMessage: "A placeholder secret cannot be used to start the Runtime.",
    });
  }
  if (searchApiKey !== undefined && PLACEHOLDER_SECRET.test(searchApiKey)) {
    throw new RuntimeStartupError({
      code: "SECRET_PLACEHOLDER",
      stage: "config",
      field: "/webEvidence/apiKey",
      safeMessage: "A placeholder secret cannot be used to start web evidence search.",
    });
  }
  if (connectorToken === modelApiKey) {
    throw new RuntimeStartupError({
      code: "SECRET_REUSE",
      stage: "config",
      field: "/transport/connectorToken",
      safeMessage: "The Runtime connector token must not reuse the model API key.",
    });
  }
  if (
    searchApiKey !== undefined &&
    (searchApiKey === connectorToken || searchApiKey === modelApiKey)
  ) {
    throw new RuntimeStartupError({
      code: "SECRET_REUSE",
      stage: "config",
      field: "/webEvidence/apiKey",
      safeMessage: "The web Search API key must not reuse another Runtime secret.",
    });
  }
  return {
    connectorToken,
    modelApiKey,
    ...(searchApiKey === undefined ? {} : { searchApiKey }),
  };
}

function resolveContainedPath(
  rootDirectory: string,
  configuredPath: string,
  field: string,
): string {
  const candidate = resolve(rootDirectory, configuredPath);
  const relativePath = relative(rootDirectory, candidate);
  if (relativePath === ".." || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath)) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_ESCAPE",
      stage: "config",
      field,
      safeMessage: "A configured path escapes the configuration directory.",
    });
  }
  return candidate;
}

function isErrno(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { readonly code?: unknown }).code === code
  );
}

async function readConfigurationFile(configFile: string): Promise<{
  readonly source: string;
  readonly permissionsWide: boolean;
}> {
  let handle;
  let pathIdentity: { readonly dev: number; readonly ino: number };
  try {
    const pathMetadata = await lstat(configFile);
    if (pathMetadata.isSymbolicLink()) {
      throw new RuntimeStartupError({
        code: "CONFIG_PATH_SYMLINK",
        stage: "config",
        safeMessage: "Runtime configuration must not be a symbolic link.",
      });
    }
    pathIdentity = { dev: pathMetadata.dev, ino: pathMetadata.ino };
    handle = await open(configFile, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (error) {
    if (error instanceof RuntimeStartupError) {
      throw error;
    }
    if (isErrno(error, "ENOENT")) {
      throw new RuntimeStartupError({
        code: "CONFIG_NOT_FOUND",
        stage: "config",
        safeMessage: "Runtime configuration file was not found.",
        cause: error,
      });
    }
    if (isErrno(error, "ELOOP")) {
      throw new RuntimeStartupError({
        code: "CONFIG_PATH_SYMLINK",
        stage: "config",
        safeMessage: "Runtime configuration must not be a symbolic link.",
        cause: error,
      });
    }
    throw new RuntimeStartupError({
      code: "CONFIG_READ_FAILED",
      stage: "config",
      safeMessage: "Runtime configuration file could not be read.",
      cause: error,
    });
  }

  try {
    const metadata = await handle.stat();
    if (metadata.dev !== pathIdentity.dev || metadata.ino !== pathIdentity.ino) {
      throw new RuntimeStartupError({
        code: "CONFIG_READ_FAILED",
        stage: "config",
        safeMessage: "Runtime configuration file changed while it was being opened.",
      });
    }
    if (!metadata.isFile() || metadata.nlink !== 1) {
      throw new RuntimeStartupError({
        code: "CONFIG_READ_FAILED",
        stage: "config",
        safeMessage: "Runtime configuration path is not a private regular file.",
      });
    }
    if (metadata.size > MAX_CONFIG_BYTES) {
      throw new RuntimeStartupError({
        code: "CONFIG_TOO_LARGE",
        stage: "config",
        safeMessage: `Runtime configuration exceeds ${String(MAX_CONFIG_BYTES)} bytes.`,
      });
    }
    if (process.platform !== "win32" && (metadata.mode & 0o022) !== 0) {
      throw new RuntimeStartupError({
        code: "CONFIG_INSECURE_PERMISSIONS",
        stage: "config",
        safeMessage: "Runtime configuration must not be writable by group or other users.",
      });
    }

    const buffer = Buffer.alloc(MAX_CONFIG_BYTES + 1);
    let bytesRead = 0;
    while (bytesRead < buffer.length) {
      const result = await handle.read(buffer, bytesRead, buffer.length - bytesRead, bytesRead);
      if (result.bytesRead === 0) {
        break;
      }
      bytesRead += result.bytesRead;
    }
    if (bytesRead > MAX_CONFIG_BYTES) {
      throw new RuntimeStartupError({
        code: "CONFIG_TOO_LARGE",
        stage: "config",
        safeMessage: `Runtime configuration exceeds ${String(MAX_CONFIG_BYTES)} bytes.`,
      });
    }

    return {
      source: buffer.toString("utf8", 0, bytesRead),
      permissionsWide: process.platform !== "win32" && (metadata.mode & 0o077) !== 0,
    };
  } catch (error) {
    if (error instanceof RuntimeStartupError) {
      throw error;
    }
    throw new RuntimeStartupError({
      code: "CONFIG_READ_FAILED",
      stage: "config",
      safeMessage: "Runtime configuration file could not be read.",
      cause: error,
    });
  } finally {
    await handle.close().catch(() => undefined);
  }
}

function parseYaml(source: string): unknown {
  try {
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
        safeMessage: "Runtime configuration is not valid restricted YAML.",
      });
    }
    return document.toJS({ maxAliasCount: 0 });
  } catch (error) {
    if (error instanceof RuntimeStartupError) {
      throw error;
    }
    throw new RuntimeStartupError({
      code: "CONFIG_PARSE_FAILED",
      stage: "config",
      safeMessage: "Runtime configuration is not valid restricted YAML.",
      cause: error,
    });
  }
}

export async function loadRuntimeConfig(
  options: LoadRuntimeConfigOptions = {},
): Promise<LoadedRuntimeConfig> {
  const environment = options.environment ?? process.env;
  const workingDirectory = resolve(options.workingDirectory ?? process.cwd());
  const requestedPath =
    options.configPath ?? environment["MINECRAFT_AGENT_CONFIG"] ?? "config.local.yml";
  if (requestedPath.trim().length === 0 || requestedPath.includes("\0")) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_INVALID",
      stage: "config",
      safeMessage: "Runtime configuration path is invalid.",
    });
  }

  const requestedConfigFile = resolve(workingDirectory, requestedPath);
  const { source, permissionsWide } = await readConfigurationFile(requestedConfigFile);
  let rootDirectory;
  try {
    rootDirectory = await realpath(dirname(requestedConfigFile));
  } catch (error) {
    throw new RuntimeStartupError({
      code: "CONFIG_READ_FAILED",
      stage: "config",
      safeMessage: "Runtime configuration file changed while it was being read.",
      cause: error,
    });
  }
  const configFile = resolve(rootDirectory, basename(requestedConfigFile));
  const parsed = parseYaml(source);
  const expanded = expandEnvironmentReferences(parsed, environment);
  assertSupportedConfigVersion(expanded.value);
  const version =
    typeof expanded.value === "object" && expanded.value !== null && !Array.isArray(expanded.value)
      ? (expanded.value as Readonly<Record<string, unknown>>)["configVersion"]
      : undefined;
  const result =
    version === CLIENT_CONFIG_VERSION
      ? clientRuntimeConfigSchema.safeParse(expanded.value)
      : runtimeConfigSchema.safeParse(expanded.value);
  if (!result.success) {
    throw schemaError(result.error.issues);
  }

  const config: RuntimeConfigDocument = result.data;
  const inlineSecretFields =
    config.configVersion === PAPER_CONFIG_VERSION
      ? ["/model/apiKey", "/transport/serverToken"].filter(
          (field) => !expanded.environmentFields.has(field),
        )
      : [];
  const firstInlineSecretField = inlineSecretFields[0];
  if (permissionsWide && firstInlineSecretField !== undefined) {
    throw new RuntimeStartupError({
      code: "CONFIG_INSECURE_PERMISSIONS",
      stage: "config",
      field: firstInlineSecretField,
      safeMessage: "Inline secrets require a private Runtime configuration file.",
    });
  }

  const warnings: RuntimeConfigWarning[] = [];
  if (permissionsWide) {
    warnings.push({ code: "CONFIG_FILE_PERMISSIONS_WIDE" });
  }
  for (const field of inlineSecretFields) {
    warnings.push({ code: "CONFIG_INLINE_SECRET", field });
  }
  if (config.model.baseUrl !== undefined && config.model.baseUrl !== null) {
    warnings.push({ code: "MODEL_CUSTOM_BASE_URL", field: "/model/baseUrl" });
  }
  if (config.privacy.logMessageContent) {
    warnings.push({ code: "PRIVACY_MESSAGE_LOGGING_ENABLED", field: "/privacy/logMessageContent" });
  }

  let resolved: ResolvedRuntimeConfig;
  if (config.configVersion === PAPER_CONFIG_VERSION) {
    assertPaperSecretValues(config);
    resolved = {
      profile: "paper",
      allowedClientTools: [],
      scopeId: config.server.id,
      subjectId: "00000000-0000-4000-8000-000000000000",
      authenticationSecret: config.transport.serverToken,
      transport: config.transport,
      model: config.model,
      storage: config.storage,
      logging: config.logging,
      ...(config.knowledge === undefined ? {} : { knowledge: config.knowledge }),
      limits: config.limits,
      privacy: config.privacy,
    };
  } else {
    const secrets = await resolveClientSecrets(config, rootDirectory, environment);
    resolved = {
      profile: "client",
      allowedClientTools: config.toolPolicy.allowed,
      scopeId: config.identity.installationId,
      subjectId: config.identity.installationId,
      authenticationSecret: secrets.connectorToken,
      transport: {
        host: config.transport.host,
        port: config.transport.port,
        serverToken: secrets.connectorToken,
      },
      model: {
        provider: config.model.provider,
        ...(config.model.baseUrl === null ? {} : { baseUrl: config.model.baseUrl }),
        apiKey: secrets.modelApiKey,
        model: config.model.model,
        timeoutSeconds: config.model.timeoutSeconds,
        inputMicroUsdPerMillionTokens: config.model.inputMicroUsdPerMillionTokens,
        outputMicroUsdPerMillionTokens: config.model.outputMicroUsdPerMillionTokens,
      },
      storage: config.storage,
      logging: config.logging,
      limits: {
        maxConcurrentRequests: config.limits.maxConcurrentRequests,
        maxQueuedRequests: config.limits.maxQueuedRequests,
        maxToolRounds: config.limits.maxToolRounds,
        maxContextMessages: config.limits.maxContextMessages,
        maxContextCharacters: config.limits.maxContextCharacters,
        perPlayerCooldownSeconds: config.limits.requestCooldownSeconds,
        dailyRequestsPerPlayer: config.limits.dailyRequests,
        monthlyBudgetUsd: config.limits.monthlyBudgetMicroUsd / 1_000_000,
        providerRoundReservationMicroUsd: config.limits.providerRoundReservationMicroUsd,
      },
      privacy: config.privacy,
      ...(config.webEvidence === undefined || secrets.searchApiKey === undefined
        ? {}
        : {
            webEvidence: {
              provider: config.webEvidence.provider,
              apiKey: secrets.searchApiKey,
              requestCostMicroUsd: config.webEvidence.requestCostMicroUsd,
              monthlyBudgetMicroUsd: config.webEvidence.monthlyBudgetMicroUsd,
              defaultAuthorization: config.webEvidence.defaultAuthorization,
              persistentAuthorizationEnabled: config.webEvidence.persistentAuthorizationEnabled,
              country: config.webEvidence.country,
              searchLanguage: config.webEvidence.searchLanguage,
            },
          }),
    };
  }

  return {
    config,
    resolved,
    paths: {
      configFile,
      rootDirectory,
      sqlite: resolveContainedPath(rootDirectory, config.storage.sqlitePath, "/storage/sqlitePath"),
      logDirectory: resolveContainedPath(
        rootDirectory,
        config.logging.directory,
        "/logging/directory",
      ),
      knowledgeRoots: (config.configVersion === 2 ? (config.knowledge?.roots ?? []) : []).map(
        (root, index) => ({
          directory: resolveContainedPath(
            rootDirectory,
            root.directory,
            `/knowledge/roots/${String(index)}/directory`,
          ),
          kind: root.kind,
        }),
      ),
    },
    warnings,
  };
}
