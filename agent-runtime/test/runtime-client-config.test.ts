import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import { loadRuntimeConfig } from "../src/config/runtime-config.js";
import {
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_API_KEY,
  TEST_CONNECTOR_TOKEN,
  TEST_SEARCH_KEY,
  validClientRuntimeConfig,
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
  code: string,
  field: string,
): Promise<RuntimeStartupError> {
  try {
    await operation;
  } catch (error) {
    expect(error).toBeInstanceOf(RuntimeStartupError);
    expect(error).toMatchObject({ code, field });
    return error as RuntimeStartupError;
  }
  throw new Error("Expected Runtime startup to fail");
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("Runtime client profile configuration", () => {
  it("loads a strict version 3 profile and resolves secrets outside the raw document", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory, validClientRuntimeConfig());

    const loaded = await loadRuntimeConfig({
      configPath,
      environment: runtimeEnvironment(),
    });

    expect(loaded.config.configVersion).toBe(3);
    expect(loaded.resolved).toMatchObject({
      profile: "client",
      scopeId: "11111111-1111-4111-8111-111111111111",
      subjectId: "11111111-1111-4111-8111-111111111111",
      authenticationSecret: TEST_CONNECTOR_TOKEN,
      model: { apiKey: TEST_API_KEY },
      limits: {
        perPlayerCooldownSeconds: 1,
        dailyRequestsPerPlayer: 100,
        monthlyBudgetUsd: 10,
      },
    });
    expect(JSON.stringify(loaded.config)).not.toContain(TEST_CONNECTOR_TOKEN);
    expect(JSON.stringify(loaded.config)).not.toContain(TEST_API_KEY);
    expect(loaded.warnings).toEqual([{ code: "MODEL_CUSTOM_BASE_URL", field: "/model/baseUrl" }]);
  });

  it("rejects missing, reused, and structurally mixed profile secrets without exposing them", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory, validClientRuntimeConfig());
    const sentinel = "same-secret-value-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    const missing = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ AGMA_CLIENT_CONNECTOR_TOKEN: undefined }),
      }),
      "CONNECTOR_TOKEN_MISSING",
      "/transport/connectorToken",
    );
    const reused = await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({
          AGMA_CLIENT_CONNECTOR_TOKEN: sentinel,
          AGMA_TEST_PROVIDER_KEY: sentinel,
        }),
      }),
      "SECRET_REUSE",
      "/transport/connectorToken",
    );
    const mixedPath = await writeRuntimeConfig(
      directory,
      `${validClientRuntimeConfig()}server:\n  id: forbidden\n`,
      "mixed.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: mixedPath, environment: runtimeEnvironment() }),
      "CONFIG_SCHEMA_INVALID",
      "/",
    );
    expect(JSON.stringify(missing.toSafeDiagnostic())).not.toContain(TEST_CONNECTOR_TOKEN);
    expect(JSON.stringify(reused.toSafeDiagnostic())).not.toContain(sentinel);
  });

  it("loads an explicit client Tool allowlist and rejects unavailable credential-store references", async () => {
    const directory = await fixtureDirectory();
    const toolPath = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig().replace("  allowed: []", "  allowed: [game.resource.search]"),
      "tool.yml",
    );
    const toolConfig = await loadRuntimeConfig({
      configPath: toolPath,
      environment: runtimeEnvironment(),
    });
    expect(toolConfig.resolved.allowedClientTools).toEqual(["game.resource.search"]);

    const credentialPath = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig().replace(
        "  apiKey:\n    source: environment\n    reference: AGMA_TEST_PROVIDER_KEY",
        "  apiKey:\n    source: credential_store\n    reference: agma/provider",
      ),
      "credential.yml",
    );
    await expectStartupError(
      loadRuntimeConfig({ configPath: credentialPath, environment: runtimeEnvironment() }),
      "SECRET_REFERENCE_UNSUPPORTED",
      "/model/apiKey",
    );
  });

  it("reads a contained private secret file and rejects broad permissions", async () => {
    const directory = await fixtureDirectory();
    const secrets = join(directory, "secrets");
    const secretFile = join(secrets, "provider.key");
    await mkdir(secrets, { mode: 0o700 });
    await writeFile(secretFile, `${TEST_API_KEY}\n`, { mode: 0o600 });
    await chmod(secretFile, 0o600);
    const source = validClientRuntimeConfig().replace(
      "  apiKey:\n    source: environment\n    reference: AGMA_TEST_PROVIDER_KEY",
      "  apiKey:\n    source: private_file\n    reference: secrets/provider.key",
    );
    const configPath = await writeRuntimeConfig(directory, source, "private-file.yml");

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });
    expect(loaded.resolved.model.apiKey).toBe(TEST_API_KEY);

    if (process.platform !== "win32") {
      await chmod(secretFile, 0o644);
      await expectStartupError(
        loadRuntimeConfig({ configPath, environment: runtimeEnvironment() }),
        "SECRET_FILE_INVALID",
        "/model/apiKey",
      );
    }
  });

  it("resolves optional Brave Search cost and authorization policy without storing the key", async () => {
    const directory = await fixtureDirectory();
    const source = validClientRuntimeConfig().replace(
      "storagePolicy:",
      `webEvidence:
  provider: brave
  apiKey:
    source: environment
    reference: AGMA_TEST_SEARCH_KEY
  requestCostMicroUsd: 5000
  monthlyBudgetMicroUsd: 50000
  defaultAuthorization: off
  persistentAuthorizationEnabled: false
  country: US
  searchLanguage: en
storagePolicy:`,
    );
    const configPath = await writeRuntimeConfig(directory, source, "web.yml");

    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });

    expect(loaded.resolved.webEvidence).toEqual({
      provider: "brave",
      apiKey: TEST_SEARCH_KEY,
      requestCostMicroUsd: 5000,
      monthlyBudgetMicroUsd: 50000,
      defaultAuthorization: "off",
      persistentAuthorizationEnabled: false,
      country: "US",
      searchLanguage: "en",
    });
    expect(JSON.stringify(loaded.config)).not.toContain(TEST_SEARCH_KEY);

    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ AGMA_TEST_SEARCH_KEY: undefined }),
      }),
      "SEARCH_API_KEY_MISSING",
      "/webEvidence/apiKey",
    );
    await expectStartupError(
      loadRuntimeConfig({
        configPath,
        environment: runtimeEnvironment({ AGMA_TEST_SEARCH_KEY: TEST_API_KEY }),
      }),
      "SECRET_REUSE",
      "/webEvidence/apiKey",
    );
  });
});
