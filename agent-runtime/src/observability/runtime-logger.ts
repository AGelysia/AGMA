import type { RuntimeStartupError } from "../bootstrap/startup-error.js";
import type { RuntimeConfigWarning } from "../config/runtime-config.js";

export interface RuntimeLogSink {
  write(line: string): void;
}

export interface RuntimeLoggerOptions {
  readonly sink?: RuntimeLogSink;
  readonly now?: () => Date;
}

const stdoutSink: RuntimeLogSink = {
  write: (line) => process.stdout.write(line),
};

export class RuntimeLogger {
  readonly #sink: RuntimeLogSink;
  readonly #now: () => Date;

  public constructor(options: RuntimeLoggerOptions = {}) {
    this.#sink = options.sink ?? stdoutSink;
    this.#now = options.now ?? (() => new Date());
  }

  public configWarning(warning: RuntimeConfigWarning): void {
    this.#write({
      level: "warn",
      event: "runtime.config.warning",
      code: warning.code,
      ...(warning.field === undefined ? {} : { field: warning.field }),
    });
  }

  public startupFailure(error: RuntimeStartupError): void {
    this.#write({
      level: "error",
      event: "runtime.startup.failed",
      ...error.toSafeDiagnostic(),
    });
  }

  public ready(port: number): void {
    this.#write({
      level: "info",
      event: "runtime.ready",
      host: "127.0.0.1",
      port,
    });
  }

  public stopped(): void {
    this.#write({ level: "info", event: "runtime.stopped" });
  }

  #write(record: Readonly<Record<string, unknown>>): void {
    this.#sink.write(`${JSON.stringify({ timestamp: this.#now().toISOString(), ...record })}\n`);
  }
}
