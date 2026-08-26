import { performance } from "node:perf_hooks";

import { RuntimeStartupError, type RuntimeStartupErrorCode } from "../bootstrap/startup-error.js";
import type { ModelProviderId } from "../providers/model-provider.js";

export const modelProviderFailureCodes = [
  "PROVIDER_UNSUPPORTED",
  "PROVIDER_AUTH_FAILED",
  "PROVIDER_UNAVAILABLE",
  "MODEL_UNAVAILABLE",
  "MODEL_HEALTH_FAILED",
] as const satisfies readonly RuntimeStartupErrorCode[];

export type ModelProviderFailureCode = (typeof modelProviderFailureCodes)[number];

export interface ModelProviderHealthRequest {
  readonly provider: ModelProviderId;
  readonly model: string;
  readonly apiKey: string;
  readonly signal: AbortSignal;
}

export interface ModelProviderHealthConfig {
  readonly model: {
    readonly provider: ModelProviderId;
    readonly model: string;
    readonly apiKey: string;
    readonly timeoutSeconds: number;
  };
}

export type ModelProviderHealthResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly code: ModelProviderFailureCode };

export interface ModelProviderHealthCheck {
  check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult>;
}

/** Upper bound for the cheap background re-probe served by the /health view. */
export const DEFAULT_PROVIDER_PROBE_TIMEOUT_MILLISECONDS = 5000;

/**
 * Cheap, bounded provider probe used to keep the /health view live without
 * performing a billable generation.
 */
export interface ProviderHealthProbe {
  readonly timeoutMilliseconds: number;
  run(signal: AbortSignal): Promise<ModelProviderHealthResult>;
}

export function createProviderHealthProbe(
  config: ModelProviderHealthConfig,
  healthCheck: ModelProviderHealthCheck,
): ProviderHealthProbe {
  return {
    timeoutMilliseconds: Math.min(
      DEFAULT_PROVIDER_PROBE_TIMEOUT_MILLISECONDS,
      config.model.timeoutSeconds * 1000,
    ),
    run: (signal) =>
      healthCheck.check({
        provider: config.model.provider,
        model: config.model.model,
        apiKey: config.model.apiKey,
        signal,
      }),
  };
}

export interface ProviderHealthProbeRegistration {
  readonly probe: ProviderHealthProbe;
  readonly result: ModelProviderHealthResult;
  readonly probedAt: number;
}

/**
 * Last successful startup provider check. RuntimeHealthState snapshots this at
 * construction so views re-probe the provider the runtime actually started
 * with, without any bootstrap wiring changes.
 */
let registeredProviderProbe: ProviderHealthProbeRegistration | undefined;

export function registerProviderHealthProbe(registration: ProviderHealthProbeRegistration): void {
  registeredProviderProbe = registration;
}

export function currentProviderHealthProbe(): ProviderHealthProbeRegistration | undefined {
  return registeredProviderProbe;
}

export class UnsupportedProductionProviderHealthCheck implements ModelProviderHealthCheck {
  public async check(): Promise<ModelProviderHealthResult> {
    return Promise.resolve({ ok: false, code: "PROVIDER_UNSUPPORTED" });
  }
}

function providerFailure(code: ModelProviderFailureCode): RuntimeStartupError {
  const messages: Record<ModelProviderFailureCode, string> = {
    PROVIDER_UNSUPPORTED: "No production model health adapter is available for this provider.",
    PROVIDER_AUTH_FAILED: "Model provider authentication failed.",
    PROVIDER_UNAVAILABLE: "Model provider is unavailable.",
    MODEL_UNAVAILABLE: "The configured model is unavailable.",
    MODEL_HEALTH_FAILED: "Model provider health check failed.",
  };
  const fields: Record<ModelProviderFailureCode, string> = {
    PROVIDER_UNSUPPORTED: "/model/provider",
    PROVIDER_AUTH_FAILED: "/model/apiKey",
    PROVIDER_UNAVAILABLE: "/model/provider",
    MODEL_UNAVAILABLE: "/model/model",
    MODEL_HEALTH_FAILED: "/model/provider",
  };

  return new RuntimeStartupError({
    code,
    stage: "provider",
    field: fields[code],
    safeMessage: messages[code],
  });
}

export async function checkModelProvider(
  config: ModelProviderHealthConfig,
  healthCheck: ModelProviderHealthCheck,
  timeoutMilliseconds = config.model.timeoutSeconds * 1000,
  cancellationSignal?: AbortSignal,
): Promise<number> {
  const controller = new AbortController();
  const startedAt = performance.now();

  type Outcome =
    | { readonly kind: "result"; readonly result: ModelProviderHealthResult }
    | { readonly kind: "error" }
    | { readonly kind: "timeout" }
    | { readonly kind: "cancelled" };

  const operation: Promise<Outcome> = Promise.resolve()
    .then(() =>
      healthCheck.check({
        provider: config.model.provider,
        model: config.model.model,
        apiKey: config.model.apiKey,
        signal: controller.signal,
      }),
    )
    .then(
      (result) => ({ kind: "result", result }) as const,
      () => ({ kind: "error" }) as const,
    );

  let timeoutHandle: NodeJS.Timeout | undefined;
  const timeout = new Promise<Outcome>((resolveTimeout) => {
    timeoutHandle = setTimeout(() => resolveTimeout({ kind: "timeout" }), timeoutMilliseconds);
  });

  let cancel: (() => void) | undefined;
  const cancellation = new Promise<Outcome>((resolveCancellation) => {
    if (cancellationSignal === undefined) {
      return;
    }
    cancel = () => {
      resolveCancellation({ kind: "cancelled" });
      controller.abort();
    };
    if (cancellationSignal.aborted) {
      cancel();
    } else {
      cancellationSignal.addEventListener("abort", cancel, { once: true });
    }
  });

  let outcome: Outcome;
  try {
    outcome = await Promise.race([operation, timeout, cancellation]);
  } finally {
    if (timeoutHandle !== undefined) {
      clearTimeout(timeoutHandle);
    }
    if (cancel !== undefined) {
      cancellationSignal?.removeEventListener("abort", cancel);
    }
  }

  if (outcome.kind === "cancelled") {
    throw new RuntimeStartupError({
      code: "STARTUP_INTERNAL_ERROR",
      stage: "startup",
      safeMessage: "Runtime startup was cancelled.",
    });
  }
  if (outcome.kind === "timeout") {
    controller.abort();
    throw new RuntimeStartupError({
      code: "PROVIDER_TIMEOUT",
      stage: "provider",
      field: "/model/timeoutSeconds",
      safeMessage: "Model provider health check timed out.",
    });
  }
  if (outcome.kind === "error") {
    controller.abort();
    throw providerFailure("MODEL_HEALTH_FAILED");
  }
  if (!outcome.result.ok) {
    throw providerFailure(outcome.result.code);
  }

  registerProviderHealthProbe({
    probe: createProviderHealthProbe(config, healthCheck),
    result: outcome.result,
    probedAt: Date.now(),
  });

  return Math.max(0, Math.round(performance.now() - startedAt));
}
