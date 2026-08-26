import { appendFileSync, renameSync, statSync, unlinkSync } from "node:fs";
import { join } from "node:path";

import type { RuntimeStartupError } from "../bootstrap/startup-error.js";
import type { RuntimeConfig, RuntimeConfigWarning } from "../config/runtime-config.js";

export type RuntimeLogLevel = RuntimeConfig["logging"]["level"];

export interface RuntimeLogSink {
  write(line: string): void;
}

export interface RuntimeLogFileOptions {
  readonly directory: string;
  readonly maximumBytes?: number;
}

export interface RuntimeLoggerOptions {
  readonly sink?: RuntimeLogSink;
  readonly level?: RuntimeLogLevel;
  readonly file?: RuntimeLogFileOptions;
  readonly now?: () => Date;
}

const stdoutSink: RuntimeLogSink = {
  write: (line) => process.stdout.write(line),
};

export const silentLogSink: RuntimeLogSink = {
  write: () => undefined,
};

const RUNTIME_LOG_FILE_NAME = "runtime.log";
const DEFAULT_MAXIMUM_LOG_FILE_BYTES = 5 * 1024 * 1024;
const MAXIMUM_ERROR_NAME_LENGTH = 128;
const MAXIMUM_ERROR_MESSAGE_LENGTH = 512;
const MAXIMUM_ERROR_FRAME_LENGTH = 256;
const MAXIMUM_ERROR_STACK_FRAMES = 4;
const LEVEL_RANKS: Readonly<Record<RuntimeLogLevel, number>> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
};

class RuntimeLogFile implements RuntimeLogSink {
  readonly #path: string;
  readonly #rotatedPath: string;
  readonly #maximumBytes: number;
  #size: number | undefined;

  public constructor(options: RuntimeLogFileOptions) {
    this.#path = join(options.directory, RUNTIME_LOG_FILE_NAME);
    this.#rotatedPath = `${this.#path}.1`;
    this.#maximumBytes = options.maximumBytes ?? DEFAULT_MAXIMUM_LOG_FILE_BYTES;
  }

  public write(line: string): void {
    try {
      const size = this.#currentSize();
      const bytes = Buffer.byteLength(line, "utf8");
      if (size > 0 && size + bytes > this.#maximumBytes) {
        this.#rotate();
      }
      appendFileSync(this.#path, line, { mode: 0o600 });
      this.#size = (this.#size ?? 0) + bytes;
    } catch {
      // The stdout stream stays authoritative when the log file cannot be written.
    }
  }

  #currentSize(): number {
    if (this.#size === undefined) {
      try {
        this.#size = statSync(this.#path).size;
      } catch {
        this.#size = 0;
      }
    }
    return this.#size;
  }

  #rotate(): void {
    try {
      unlinkSync(this.#rotatedPath);
    } catch {
      // No earlier generation exists before the first rotation.
    }
    renameSync(this.#path, this.#rotatedPath);
    this.#size = 0;
  }
}

function truncate(value: string, maximumLength: number): string {
  return value.length <= maximumLength ? value : `${value.slice(0, maximumLength)}[truncated]`;
}

function errorFrames(error: Error): readonly string[] | undefined {
  if (error.stack === undefined) {
    return undefined;
  }
  const frames = error.stack
    .split("\n")
    .slice(1, MAXIMUM_ERROR_STACK_FRAMES + 1)
    .map((frame) => truncate(frame.trim(), MAXIMUM_ERROR_FRAME_LENGTH))
    .filter((frame) => frame.length > 0);
  return frames.length === 0 ? undefined : frames;
}

function describeError(cause: unknown): {
  readonly error: {
    readonly name: string;
    readonly message: string;
    readonly stack?: readonly string[];
  };
} {
  if (cause instanceof Error) {
    const frames = errorFrames(cause);
    return {
      error: {
        name: truncate(cause.name, MAXIMUM_ERROR_NAME_LENGTH),
        message: truncate(cause.message, MAXIMUM_ERROR_MESSAGE_LENGTH),
        ...(frames === undefined ? {} : { stack: frames }),
      },
    };
  }
  return {
    error: {
      name: typeof cause,
      message: truncate(String(cause), MAXIMUM_ERROR_MESSAGE_LENGTH),
    },
  };
}

export class RuntimeLogger {
  readonly #sink: RuntimeLogSink;
  readonly #now: () => Date;
  #level: RuntimeLogLevel;
  #file: RuntimeLogSink | undefined;

  public constructor(options: RuntimeLoggerOptions = {}) {
    this.#sink = options.sink ?? stdoutSink;
    this.#level = options.level ?? "info";
    this.#file = options.file === undefined ? undefined : new RuntimeLogFile(options.file);
    this.#now = options.now ?? (() => new Date());
  }

  public setLevel(level: RuntimeLogLevel): void {
    this.#level = level;
  }

  public useLogDirectory(directory: string): void {
    this.#file = new RuntimeLogFile({ directory });
  }

  public configWarning(warning: RuntimeConfigWarning): void {
    this.#write("warn", {
      event: "runtime.config.warning",
      code: warning.code,
      ...(warning.field === undefined ? {} : { field: warning.field }),
    });
  }

  public startupFailure(error: RuntimeStartupError): void {
    this.#write("error", { event: "runtime.startup.failed", ...error.toSafeDiagnostic() });
  }

  public ready(port: number): void {
    this.#write("info", { event: "runtime.ready", host: "127.0.0.1", port });
  }

  public stopped(): void {
    this.#write("info", { event: "runtime.stopped" });
  }

  public runtimeError(code: string, requestId: string, cause: unknown): void {
    // Only structured metadata is recorded: player message content, tool payloads, and
    // secret material must never be passed here, independent of the privacy configuration.
    this.#write("error", {
      event: "runtime.error",
      code,
      requestId,
      ...describeError(cause),
    });
  }

  #write(level: RuntimeLogLevel, record: Readonly<Record<string, unknown>>): void {
    if (LEVEL_RANKS[level] < LEVEL_RANKS[this.#level]) {
      return;
    }
    const line = `${JSON.stringify({ timestamp: this.#now().toISOString(), level, ...record })}\n`;
    this.#sink.write(line);
    this.#file?.write(line);
  }
}
