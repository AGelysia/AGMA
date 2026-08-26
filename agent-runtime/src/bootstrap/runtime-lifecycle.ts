import type { Readable } from "node:stream";

import { RuntimeLogger } from "../observability/runtime-logger.js";
import { asRuntimeStartupError, RuntimeStartupError } from "./startup-error.js";

export interface RuntimeCliOptions {
  readonly configPath?: string;
  readonly managed: boolean;
}

/**
 * Parses the shared `--config <path> [--managed]` command surface. A host that
 * can start without an explicit configuration path keeps the empty-argument
 * form; every other shape must resolve to a configuration path.
 */
export function parseManagedRuntimeCli(
  arguments_: readonly string[],
  options: { readonly configPathRequired: boolean; readonly usage: string },
): RuntimeCliOptions {
  if (arguments_.length === 0 && !options.configPathRequired) {
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
    safeMessage: options.usage,
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

/**
 * Runs the shared process lifecycle for a managed Runtime host: parse the CLI,
 * observe the managed parent, start, then own shutdown through either the
 * parent closing, SIGINT, or SIGTERM.
 */
export async function runManagedRuntimeMain<
  Cli extends RuntimeCliOptions,
  Runtime extends { close(): Promise<void> },
>(options: {
  readonly parse: (arguments_: readonly string[]) => Cli;
  readonly start: (
    cli: Cli,
    logger: RuntimeLogger,
    signal: AbortSignal | undefined,
  ) => Promise<Runtime>;
}): Promise<void> {
  const logger = new RuntimeLogger();
  let cli: Cli;
  let runtime: Runtime;
  let managedParent: ManagedParentObservation | undefined;
  try {
    cli = options.parse(process.argv.slice(2));
    managedParent = cli.managed ? observeManagedParent(process.stdin) : undefined;
    runtime = await options.start(
      cli,
      logger,
      managedParent === undefined ? undefined : managedParent.signal,
    );
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
