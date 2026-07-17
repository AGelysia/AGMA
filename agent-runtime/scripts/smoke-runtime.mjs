import { startRuntime } from "../dist/bootstrap/index.js";

const configPath = process.argv[2];
if (configPath === undefined) {
  throw new Error("Usage: node scripts/smoke-runtime.mjs <config-path>");
}

const runtime = await startRuntime({
  configPath,
  modelProvider: {
    async check() {
      return { ok: true };
    },
    async generate({ signal, input }) {
      if (signal.aborted) {
        throw signal.reason;
      }
      if (input.at(-1)?.role !== "user") {
        throw new Error("Smoke provider did not receive a bounded user context");
      }
      return { type: "final", fallbackText: "Paper smoke response." };
    },
  },
});

process.stdout.write("PAPER_SMOKE_RUNTIME_READY\n");

let stopping = false;
async function stop() {
  if (stopping) {
    return;
  }
  stopping = true;
  await runtime.close();
}

process.once("SIGINT", stop);
process.once("SIGTERM", stop);
