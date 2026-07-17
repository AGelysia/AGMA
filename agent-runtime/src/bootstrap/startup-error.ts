export const runtimeStartupErrorCodes = [
  "CONFIG_NOT_FOUND",
  "CONFIG_READ_FAILED",
  "CONFIG_TOO_LARGE",
  "CONFIG_PARSE_FAILED",
  "CONFIG_ENV_SYNTAX",
  "CONFIG_ENV_MISSING",
  "CONFIG_VERSION_UNSUPPORTED",
  "CONFIG_SCHEMA_INVALID",
  "CONFIG_PATH_ESCAPE",
  "CONFIG_PATH_SYMLINK",
  "CONFIG_PATH_INVALID",
  "CONFIG_INSECURE_PERMISSIONS",
  "PATH_INSECURE_PERMISSIONS",
  "API_KEY_MISSING",
  "SERVER_TOKEN_MISSING",
  "SECRET_PLACEHOLDER",
  "SECRET_REUSE",
  "LOG_DIRECTORY_UNAVAILABLE",
  "SQLITE_OPEN_FAILED",
  "SQLITE_READ_FAILED",
  "SQLITE_WRITE_FAILED",
  "SQLITE_INTEGRITY_FAILED",
  "SQLITE_BUSY",
  "KNOWLEDGE_DIRECTORY_UNAVAILABLE",
  "KNOWLEDGE_CONTENT_INVALID",
  "PROTOCOL_SCHEMA_UNAVAILABLE",
  "PROVIDER_UNSUPPORTED",
  "PROVIDER_TIMEOUT",
  "PROVIDER_AUTH_FAILED",
  "PROVIDER_UNAVAILABLE",
  "MODEL_UNAVAILABLE",
  "MODEL_HEALTH_FAILED",
  "LISTEN_FAILED",
  "STARTUP_INTERNAL_ERROR",
] as const;

export type RuntimeStartupErrorCode = (typeof runtimeStartupErrorCodes)[number];

export type RuntimeStartupStage =
  | "config"
  | "logging"
  | "protocol"
  | "sqlite"
  | "knowledge"
  | "provider"
  | "listen"
  | "startup";

export interface RuntimeConfigIssue {
  readonly field: string;
  readonly rule: string;
}

export interface RuntimeStartupErrorOptions {
  readonly code: RuntimeStartupErrorCode;
  readonly stage: RuntimeStartupStage;
  readonly safeMessage: string;
  readonly field?: string;
  readonly issues?: readonly RuntimeConfigIssue[];
  readonly cause?: unknown;
}

export interface SafeStartupDiagnostic {
  readonly code: RuntimeStartupErrorCode;
  readonly stage: RuntimeStartupStage;
  readonly message: string;
  readonly field?: string;
  readonly issues?: readonly RuntimeConfigIssue[];
}

export class RuntimeStartupError extends Error {
  public readonly code: RuntimeStartupErrorCode;
  public readonly stage: RuntimeStartupStage;
  public readonly field: string | undefined;
  public readonly issues: readonly RuntimeConfigIssue[] | undefined;

  public constructor(options: RuntimeStartupErrorOptions) {
    super(options.safeMessage, { cause: options.cause });
    this.name = "RuntimeStartupError";
    this.code = options.code;
    this.stage = options.stage;
    this.field = options.field;
    this.issues = options.issues;
  }

  public toSafeDiagnostic(): SafeStartupDiagnostic {
    return {
      code: this.code,
      stage: this.stage,
      message: this.message,
      ...(this.field === undefined ? {} : { field: this.field }),
      ...(this.issues === undefined ? {} : { issues: this.issues }),
    };
  }
}

export function asRuntimeStartupError(error: unknown): RuntimeStartupError {
  if (error instanceof RuntimeStartupError) {
    return error;
  }

  return new RuntimeStartupError({
    code: "STARTUP_INTERNAL_ERROR",
    stage: "startup",
    safeMessage: "Runtime startup failed unexpectedly.",
    cause: error,
  });
}
