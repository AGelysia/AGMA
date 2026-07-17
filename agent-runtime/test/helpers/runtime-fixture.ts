import { createServer } from "node:net";
import { chmod, mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const TEST_API_KEY = "test-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
export const TEST_SERVER_TOKEN = "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

export function runtimeEnvironment(
  overrides: Readonly<Record<string, string | undefined>> = {},
): Readonly<Record<string, string | undefined>> {
  return {
    OPENAI_API_KEY: TEST_API_KEY,
    MINECRAFT_AGENT_SERVER_TOKEN: TEST_SERVER_TOKEN,
    ...overrides,
  };
}

export function validRuntimeConfig(port = 38_127): string {
  return `configVersion: 2
server:
  id: test-server
transport:
  host: 127.0.0.1
  port: ${String(port)}
  serverToken: \${MINECRAFT_AGENT_SERVER_TOKEN}
model:
  provider: openai
  apiKey: \${OPENAI_API_KEY}
  model: test-model
  timeoutSeconds: 2
  inputMicroUsdPerMillionTokens: 1000000
  outputMicroUsdPerMillionTokens: 4000000
storage:
  sqlitePath: ./data/runtime.db
logging:
  directory: ./logs
  level: info
limits:
  maxConcurrentRequests: 2
  maxQueuedRequests: 8
  maxToolRounds: 4
  perPlayerCooldownSeconds: 1
  dailyRequestsPerPlayer: 100
  monthlyBudgetUsd: 10
  providerRoundReservationMicroUsd: 50000
privacy:
  storeConversations: true
  retentionDays: 7
  logMessageContent: false
  logToolCalls: true
`;
}

export async function temporaryRuntimeDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "minecraft-agent-runtime-"));
  await chmod(directory, 0o700);
  return directory;
}

export async function writeRuntimeConfig(
  directory: string,
  source = validRuntimeConfig(),
  fileName = "config.local.yml",
): Promise<string> {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const path = join(directory, fileName);
  await writeFile(path, source, { encoding: "utf8", mode: 0o600 });
  await chmod(path, 0o600);
  return path;
}

export async function findAvailablePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  if (address === null || typeof address === "string") {
    server.close();
    throw new Error("Unable to allocate a test port");
  }
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error === undefined ? resolve() : reject(error)));
  });
  return address.port;
}
