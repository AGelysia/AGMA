import { constants } from "node:fs";
import { open, realpath } from "node:fs/promises";
import { basename, dirname, isAbsolute, relative, resolve, sep } from "node:path";

import { parseDocument } from "yaml";
import { z, type ZodIssue } from "zod";

import { RuntimeStartupError, type RuntimeConfigIssue } from "../bootstrap/startup-error.js";
import { modelProviderIds } from "../providers/model-provider.js";

const MAX_CONFIG_BYTES = 64 * 1024;
const MAX_BASE_URL_LENGTH = 2048;
const CURRENT_CONFIG_VERSION = 2;
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
    configVersion: z.literal(CURRENT_CONFIG_VERSION),
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

export type RuntimeConfig = z.infer<typeof runtimeConfigSchema>;
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
  readonly config: RuntimeConfig;
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
  const apiKeyIssue = details.find((issue) => issue.field === "/model/apiKey");
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
  if (typeof version !== "number" || version === CURRENT_CONFIG_VERSION) {
    return;
  }

  throw new RuntimeStartupError({
    code: "CONFIG_VERSION_UNSUPPORTED",
    stage: "config",
    field: "/configVersion",
    safeMessage:
      version === 1
        ? "Runtime configuration version 1 is no longer supported. Upgrade to configVersion 2."
        : "Runtime configuration version is unsupported. Use configVersion 2.",
  });
}

function assertSecretValues(config: RuntimeConfig): void {
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
  try {
    handle = await open(configFile, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (error) {
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
    if (!metadata.isFile()) {
      throw new RuntimeStartupError({
        code: "CONFIG_READ_FAILED",
        stage: "config",
        safeMessage: "Runtime configuration path is not a regular file.",
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
      permissionsWide: (metadata.mode & 0o077) !== 0,
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
  const result = runtimeConfigSchema.safeParse(expanded.value);
  if (!result.success) {
    throw schemaError(result.error.issues);
  }

  assertSecretValues(result.data);
  const inlineSecretFields = ["/model/apiKey", "/transport/serverToken"].filter(
    (field) => !expanded.environmentFields.has(field),
  );
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
  if (result.data.model.baseUrl !== undefined) {
    warnings.push({ code: "MODEL_CUSTOM_BASE_URL", field: "/model/baseUrl" });
  }
  if (result.data.privacy.logMessageContent) {
    warnings.push({ code: "PRIVACY_MESSAGE_LOGGING_ENABLED", field: "/privacy/logMessageContent" });
  }

  return {
    config: result.data,
    paths: {
      configFile,
      rootDirectory,
      sqlite: resolveContainedPath(
        rootDirectory,
        result.data.storage.sqlitePath,
        "/storage/sqlitePath",
      ),
      logDirectory: resolveContainedPath(
        rootDirectory,
        result.data.logging.directory,
        "/logging/directory",
      ),
      knowledgeRoots: (result.data.knowledge?.roots ?? []).map((root, index) => ({
        directory: resolveContainedPath(
          rootDirectory,
          root.directory,
          `/knowledge/roots/${String(index)}/directory`,
        ),
        kind: root.kind,
      })),
    },
    warnings,
  };
}
