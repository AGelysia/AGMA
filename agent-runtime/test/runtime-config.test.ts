import { chmod, rm, symlink } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import { loadRuntimeConfig } from "../src/config/runtime-config.js";
import {
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_API_KEY,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const temporaryDirectories: string[] = [];

async function fixtureDirectory(): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return directory;
}

async function expectStartupError(
  operation: Promise<unknown>,
  code: RuntimeStartupError["code"],
  field?: string,
): Promise<RuntimeStartupError> {
  try {
    await operation;
  } catch (error) {
    expect(error).toBeInstanceOf(RuntimeStartupError);
    const startupError = error as RuntimeStartupError;
    expect(startupError.code).toBe(code);
    if (field !== undefined) {
      expect(startupError.field).toBe(field);
    }
    return startupError;
  }
  throw new Error("Expected RuntimeStartupError");
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("runtime configuration", () => {
  it("loads strict YAML, substitutes whole environment scalars, and resolves local paths", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });

    expect(loaded.config.configVersion).toBe(2);
    expect(loaded.config.model.apiKey).toBe(TEST_API_KEY);
    expect(loaded.config.model.inputMicroUsdPerMillionTokens).toBe(1_000_000);
    expect(loaded.config.model.outputMicroUsdPerMillionTokens).toBe(4_000_000);
    expect(loaded.config.limits.providerRoundReservationMicroUsd).toBe(50_000);
    expect(loaded.config.transport.host).toBe("127.0.0.1");
    expect(loaded.paths.rootDirectory).toBe(directory);
    expect(loaded.paths.sqlite).toBe(join(directory, "data/runtime.db"));
    expect(loaded.paths.logDirectory).toBe(join(directory, "logs"));
    expect(loaded.paths.knowledgeRoots).toEqual([]);
    expect(loaded.warnings).toEqual([]);
  });

  it("accepts a managed transport port supplied as one whole environment scalar", async () => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "port: 38127",
      "port: ${AGMA_MANAGED_RUNTIME_PORT}",
    );
    const configPath = await writeRuntimeConfig(directory, source, "managed-port.yml");

    const loaded = await loadRuntimeConfig({
      configPath,
      environment: runtimeEnvironment({ AGMA_MANAGED_RUNTIME_PORT: "39127" }),
    });

    expect(loaded.config.transport.port).toBe(39_127);
  });

  it.each(["openai", "anthropic", "deepseek", "gemini"] as const)(
    "loads the %s provider without changing configVersion 2 defaults",
    async (provider) => {
      const directory = await fixtureDirectory();
      const source = validRuntimeConfig().replace("provider: openai", `provider: ${provider}`);
      const configPath = await writeRuntimeConfig(directory, source, `${provider}.yml`);

      const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });

      expect(loaded.config.configVersion).toBe(2);
      expect(loaded.config.model.provider).toBe(provider);
      expect(loaded.config.model.baseUrl).toBeUndefined();
    },
  );

  it.each(["openai", "anthropic", "deepseek", "gemini", "openai-compatible"] as const)(
    "loads and normalizes an environment-provided base URL for the %s provider",
    async (provider) => {
      const directory = await fixtureDirectory();
      const source = validRuntimeConfig().replace(
        "provider: openai",
        `provider: ${provider}\n  baseUrl: \${MODEL_BASE_URL}`,
      );
      const configPath = await writeRuntimeConfig(directory, source, `${provider}-base-url.yml`);

      const loaded = await loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ MODEL_BASE_URL: "https://models.example.test/v1///" }),
      });

      expect(loaded.config.model.provider).toBe(provider);
      expect(loaded.config.model.baseUrl).toBe("https://models.example.test/v1");
      expect(loaded.warnings).toContainEqual({
        code: "MODEL_CUSTOM_BASE_URL",
        field: "/model/baseUrl",
      });
    },
  );

  it("requires an explicit base URL for the openai-compatible provider", async () => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace("provider: openai", "provider: openai-compatible");
    const configPath = await writeRuntimeConfig(directory, source, "compatible-missing-url.yml");

    await expectStartupError(
      loadRuntimeConfig({ configPath, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/model/baseUrl",
    );
  });

  it.each([
    ["https://models.example.test/", "https://models.example.test"],
    ["http://127.0.0.1:11434/v1/", "http://127.0.0.1:11434/v1"],
    ["http://[::1]:11434/v1///", "http://[::1]:11434/v1"],
  ])("accepts and canonicalizes base URL %s", async (baseUrl, expected) => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "provider: openai",
      `provider: openai-compatible\n  baseUrl: ${baseUrl}`,
    );
    const configPath = await writeRuntimeConfig(directory, source, "valid-base-url.yml");

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });
    expect(loaded.config.model.baseUrl).toBe(expected);
  });

  it.each([
    ["remote HTTP", "http://models.example.test/v1"],
    ["localhost HTTP", "http://localhost:11434/v1"],
    ["abbreviated IPv4", "http://127.1:11434/v1"],
    ["expanded IPv6", "http://[0:0:0:0:0:0:0:1]:11434/v1"],
    ["unsupported scheme", "ftp://models.example.test/v1"],
    ["credentials", "https://user:password@models.example.test/v1"],
    ["query", "https://models.example.test/v1?token=secret-query-value"],
    ["fragment", "https://models.example.test/v1#secret-fragment-value"],
    ["leading whitespace", " https://models.example.test/v1"],
    ["malformed", "not-a-url"],
  ])("rejects unsafe base URL syntax without exposing it: %s", async (_case, baseUrl) => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "provider: openai",
      "provider: openai-compatible\n  baseUrl: ${MODEL_BASE_URL}",
    );
    const configPath = await writeRuntimeConfig(directory, source, "unsafe-base-url.yml");

    const error = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ MODEL_BASE_URL: baseUrl }),
      }),
      "CONFIG_SCHEMA_INVALID",
      "/model/baseUrl",
    );
    expect(JSON.stringify(error.toSafeDiagnostic())).not.toContain(baseUrl);
  });

  it("bounds base URLs and keeps unknown model keys out of diagnostics", async () => {
    const directory = await fixtureDirectory();
    const oversizedUrl = `https://models.example.test/${"x".repeat(2049)}`;
    const oversizedSource = validRuntimeConfig().replace(
      "provider: openai",
      `provider: openai-compatible\n  baseUrl: ${oversizedUrl}`,
    );
    const oversizedPath = await writeRuntimeConfig(
      directory,
      oversizedSource,
      "oversized-base-url.yml",
    );
    const unknownSecretKey = `endpoint-${TEST_API_KEY}`;
    const unknownSource = validRuntimeConfig().replace(
      "  apiKey: ${OPENAI_API_KEY}",
      `  apiKey: \${OPENAI_API_KEY}\n  ${unknownSecretKey}: true`,
    );
    const unknownPath = await writeRuntimeConfig(directory, unknownSource, "unknown-model-key.yml");

    await expectStartupError(
      loadRuntimeConfig({ configPath: oversizedPath, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/model/baseUrl",
    );
    const unknownError = await expectStartupError(
      loadRuntimeConfig({ configPath: unknownPath, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/model",
    );
    expect(JSON.stringify(unknownError.toSafeDiagnostic())).not.toContain(unknownSecretKey);
  });

  it("uses a stable missing-environment diagnostic for base URLs", async () => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "provider: openai",
      "provider: openai-compatible\n  baseUrl: ${MODEL_BASE_URL}",
    );
    const configPath = await writeRuntimeConfig(directory, source, "missing-base-url-env.yml");

    await expectStartupError(
      loadRuntimeConfig({ configPath, environment: runtimeEnvironment() }),
      "CONFIG_ENV_MISSING",
      "/model/baseUrl",
    );
  });

  it("rejects legacy configVersion 1 with a stable upgrade diagnostic", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("configVersion: 2", "configVersion: 1"),
      "legacy-v1.yml",
    );

    const error = await expectStartupError(
      loadRuntimeConfig({ configPath, environment: runtimeEnvironment() }),
      "CONFIG_VERSION_UNSUPPORTED",
      "/configVersion",
    );
    expect(error.toSafeDiagnostic()).toEqual({
      code: "CONFIG_VERSION_UNSUPPORTED",
      stage: "config",
      field: "/configVersion",
      message:
        "Runtime configuration version 1 is no longer supported. Upgrade to configVersion 2.",
    });
  });

  it("resolves optional bounded knowledge roots without changing legacy configuration", async () => {
    const directory = await fixtureDirectory();
    const source = validRuntimeConfig().replace(
      "limits:\n",
      "knowledge:\n  roots:\n    - directory: ./knowledge/rules\n      kind: server_rules\n    - directory: ./knowledge/docs\n      kind: local_docs\nlimits:\n",
    );
    const configPath = await writeRuntimeConfig(directory, source, "knowledge.yml");

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });
    expect(loaded.paths.knowledgeRoots).toEqual([
      { directory: join(directory, "knowledge/rules"), kind: "server_rules" },
      { directory: join(directory, "knowledge/docs"), kind: "local_docs" },
    ]);
  });

  it("uses stable secret errors for missing environment values", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: undefined }),
      }),
      "API_KEY_MISSING",
      "/model/apiKey",
    );
  });

  it("reports concrete known fields without echoing untrusted unknown key names", async () => {
    const directory = await fixtureDirectory();
    const invalidPort = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("port: 38127", "port: not-a-number"),
      "invalid-port.yml",
    );
    const unknownSecretKey = `unknown-${TEST_API_KEY}`;
    const unknownKey = await writeRuntimeConfig(
      directory,
      `${validRuntimeConfig()}${unknownSecretKey}: true\n`,
      "unknown-key.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: invalidPort, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/transport/port",
    );
    const unknownError = await expectStartupError(
      loadRuntimeConfig({ configPath: unknownKey, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/",
    );
    expect(JSON.stringify(unknownError.toSafeDiagnostic())).not.toContain(unknownSecretKey);
  });

  it("requires bounded micro-USD pricing and an exact monthly budget", async () => {
    const directory = await fixtureDirectory();
    const fractionalBudget = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("monthlyBudgetUsd: 10", "monthlyBudgetUsd: 0.0000001"),
      "fractional-budget.yml",
    );
    const zeroReservation = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace(
        "providerRoundReservationMicroUsd: 50000",
        "providerRoundReservationMicroUsd: 0",
      ),
      "zero-reservation.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: fractionalBudget, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/limits/monthlyBudgetUsd",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: zeroReservation, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/limits/providerRoundReservationMicroUsd",
    );
  });

  it("rejects partial, recursive, duplicate, and path-escape configuration syntax", async () => {
    const directory = await fixtureDirectory();
    const partial = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("${OPENAI_API_KEY}", "prefix-${OPENAI_API_KEY}"),
      "partial.yml",
    );
    const recursive = await writeRuntimeConfig(directory, validRuntimeConfig(), "recursive.yml");
    const duplicate = await writeRuntimeConfig(
      directory,
      `${validRuntimeConfig()}server:\n  id: duplicate\n`,
      "duplicate.yml",
    );
    const alias = await writeRuntimeConfig(
      directory,
      "value: &shared [1]\ncopy: *shared\n",
      "alias.yml",
    );
    const escape = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("./data/runtime.db", "../runtime.db"),
      "escape.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: partial, environment: runtimeEnvironment() }),
      "CONFIG_ENV_SYNTAX",
      "/model/apiKey",
    );
    await expectStartupError(
      loadRuntimeConfig({
        configPath: recursive,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "${SECOND}" }),
      }),
      "CONFIG_ENV_SYNTAX",
      "/model/apiKey",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: duplicate, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: alias, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: escape, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/storage/sqlitePath",
    );
  });

  it("bounds file size and deeply nested YAML before configuration traversal", async () => {
    const directory = await fixtureDirectory();
    const deep = await writeRuntimeConfig(
      directory,
      `${"[".repeat(5000)}1${"]".repeat(5000)}`,
      "deep.yml",
    );
    const oversized = await writeRuntimeConfig(
      directory,
      `#${"x".repeat(64 * 1024)}\n`,
      "oversized.yml",
    );

    await expectStartupError(
      loadRuntimeConfig({ configPath: deep, environment: runtimeEnvironment() }),
      "CONFIG_PARSE_FAILED",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: oversized, environment: runtimeEnvironment() }),
      "CONFIG_TOO_LARGE",
    );
  });

  it("treats structural text from an environment variable as a scalar, then validates it", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "key\nunexpected: injected" }),
      }),
      "API_KEY_MISSING",
      "/model/apiKey",
    );
  });

  it("rejects placeholder and reused secrets without exposing their values", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);

    const placeholder = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: "replace-with-real-key" }),
      }),
      "SECRET_PLACEHOLDER",
      "/model/apiKey",
    );
    const reused = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ MINECRAFT_AGENT_SERVER_TOKEN: TEST_API_KEY }),
      }),
      "SECRET_REUSE",
      "/transport/serverToken",
    );

    expect(JSON.stringify(placeholder.toSafeDiagnostic())).not.toContain("replace-with-real-key");
    expect(JSON.stringify(reused.toSafeDiagnostic())).not.toContain(TEST_API_KEY);
  });

  it("warns separately but rejects broad permissions combined with inline secrets", async () => {
    const directory = await fixtureDirectory();
    const inlineSource = validRuntimeConfig()
      .replace("${OPENAI_API_KEY}", TEST_API_KEY)
      .replace(
        "${MINECRAFT_AGENT_SERVER_TOKEN}",
        "inline-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      );
    const target = await writeRuntimeConfig(directory, inlineSource, "target.yml");

    const loaded = await loadRuntimeConfig({ configPath: target, environment: {} });
    expect(loaded.warnings.map((warning) => warning.code)).toEqual([
      "CONFIG_INLINE_SECRET",
      "CONFIG_INLINE_SECRET",
    ]);

    const broad = await writeRuntimeConfig(directory, validRuntimeConfig(), "broad.yml");
    await chmod(broad, 0o644);
    const broadLoaded = await loadRuntimeConfig({
      configPath: broad,
      environment: runtimeEnvironment(),
    });
    expect(broadLoaded.warnings).toEqual([{ code: "CONFIG_FILE_PERMISSIONS_WIDE" }]);

    await chmod(target, 0o644);
    await expectStartupError(
      loadRuntimeConfig({ configPath: target, environment: {} }),
      "CONFIG_INSECURE_PERMISSIONS",
      "/model/apiKey",
    );

    const link = join(directory, "linked.yml");
    await symlink(target, link);
    await expectStartupError(
      loadRuntimeConfig({ configPath: link, environment: runtimeEnvironment() }),
      "CONFIG_PATH_SYMLINK",
    );
  });

  it("rejects writable configuration and state paths rooted beside the config", async () => {
    const directory = await fixtureDirectory();
    const writable = await writeRuntimeConfig(directory, validRuntimeConfig(), "writable.yml");
    await chmod(writable, 0o660);
    await expectStartupError(
      loadRuntimeConfig({ configPath: writable, environment: runtimeEnvironment() }),
      "CONFIG_INSECURE_PERMISSIONS",
    );

    const rootLog = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("directory: ./logs", "directory: ."),
      "root-log.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: rootLog, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/logging/directory",
    );

    const rootDatabase = await writeRuntimeConfig(
      directory,
      validRuntimeConfig().replace("./data/runtime.db", "runtime.db"),
      "root-database.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: rootDatabase, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/storage/sqlitePath",
    );
  });
});
