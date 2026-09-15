import { chmod, mkdir, rm, symlink, utimes, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

import { afterEach, describe, expect, it, vi } from "vitest";

import type { ModelProvider } from "../src/providers/model-provider.js";
import { loadStandaloneClientConfig } from "../src/config/standalone-client-config.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import {
  loadStandaloneKnowledge,
  StandaloneKnowledgeHotIndex,
  standaloneKnowledgeFingerprint,
} from "../src/standalone/knowledge/knowledge-loader.js";
import {
  StandaloneKnowledgeIndex,
  type StandaloneKnowledgeSearcher,
} from "../src/standalone/knowledge/knowledge-index.js";
import {
  startStandaloneClient,
  type StartedStandaloneRuntime,
} from "../src/standalone/bootstrap/index.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { SqliteProjectRepository } from "../src/storage/project-repository.js";
import { ClientToolRegistry } from "../src/tools/client-tool-registry.js";
import { ProjectToolExecutor } from "../src/tools/project-tool-executor.js";
import type { LocalToolCall } from "../src/tools/local-tool-executor.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  validClientRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const contractRoot = fileURLToPath(new URL("../../standalone-client/contracts/", import.meta.url));
const temporaryDirectories: string[] = [];
const runtimes: StartedStandaloneRuntime[] = [];

function provider(): ModelProvider {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
    generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "client only" }),
  };
}

async function fixtureRoot(name: string): Promise<string> {
  const base = await temporaryRuntimeDirectory();
  temporaryDirectories.push(base);
  const root = join(base, name);
  await mkdir(root, { recursive: true, mode: 0o700 });
  await chmod(root, 0o700);
  return root;
}

afterEach(async () => {
  await Promise.allSettled(runtimes.splice(0).map((runtime) => runtime.close()));
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

function executorCall(
  knowledge: StandaloneKnowledgeSearcher | undefined,
  argumentsValue: Readonly<Record<string, unknown>>,
): { readonly executor: ProjectToolExecutor; readonly call: LocalToolCall } {
  const database = new DatabaseSync(":memory:");
  migrateRuntimeStorage(database, "2026-07-13T00:00:00.000Z");
  const executor =
    knowledge === undefined
      ? new ProjectToolExecutor(new SqliteProjectRepository(database))
      : new ProjectToolExecutor(new SqliteProjectRepository(database), knowledge);
  const descriptor = {
    id: "local.knowledge.search",
    source: "local_docs",
    trust: "untrusted",
    execution: "runtime_local",
  } as const;
  return {
    executor,
    call: {
      descriptor,
      serverId: "11111111-1111-4111-8111-111111111111",
      playerUuid: "11111111-1111-4111-8111-111111111111",
      requestId: "22222222-2222-4222-8222-222222222222",
      toolCallId: "33333333-3333-4333-8333-333333333333",
      arguments: argumentsValue,
      now: Date.parse("2026-07-17T00:00:00Z"),
      signal: new AbortController().signal,
    },
  };
}

describe("standalone local knowledge", () => {
  it("yields an empty index for absent and empty roots", async () => {
    const empty = await fixtureRoot("empty-docs");
    const missing = join(empty, "not-created");

    await expect(loadStandaloneKnowledge([])).resolves.toMatchObject({ size: 0 });
    await expect(
      loadStandaloneKnowledge([{ directory: missing, kind: "local_docs" }]),
    ).resolves.toMatchObject({ size: 0 });
    await expect(
      loadStandaloneKnowledge([{ directory: empty, kind: "local_docs" }]),
    ).resolves.toMatchObject({ size: 0 });
    const result = await loadStandaloneKnowledge([{ directory: missing, kind: "local_docs" }]);
    expect(result.search("cobblestone")).toEqual({
      query: "cobblestone",
      matches: [],
      truncated: false,
    });
  });

  it("indexes bounded mod documents and finds excerpts with citations", async () => {
    const docs = await fixtureRoot("local-docs");
    await writeFile(
      join(docs, "agma-modbook-agma-fixture.md"),
      "# Agma Fixture Guide\n## Smelting\nSmelt agma dust in a kiln. [Reference](https://secret.invalid/)\n<script>hidden text</script>\n",
      { mode: 0o600 },
    );
    await writeFile(
      join(docs, "agma-modadv-agma-fixture.md"),
      "# Agma Fixture Advancements\n## First Steps\nCraft agma dust from crushed stone.\n",
      { mode: 0o600 },
    );

    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);
    const result = index.search("agma dust");

    expect(index.size).toBe(2);
    expect(result.query).toBe("agma dust");
    expect(result.truncated).toBe(false);
    expect(result.matches.length).toBe(2);
    const first = result.matches[0];
    expect(first?.kind).toBe("local_docs");
    expect(first?.documentId).toMatch(/^[0-9a-f]{64}$/u);
    expect(first?.citation).toMatch(
      /^local_docs\/[0-9a-f]{12}\/agma-mod(?:book|adv)-agma-fixture\.md#chunk-1$/u,
    );
    expect(first?.title.length).toBeGreaterThan(0);
    expect(first?.heading.length).toBeGreaterThan(0);
    expect(first?.excerpt.length).toBeGreaterThan(0);
    expect(JSON.stringify(result)).not.toContain("https://secret.invalid");
    expect(JSON.stringify(result)).not.toContain("hidden text");
    expect(index.search("kiln").matches[0]?.title).toBe("Agma Fixture Guide");
  });

  it("truncates the match list and skips unsafe files", async () => {
    const docs = await fixtureRoot("many-docs");
    for (let index = 0; index < 12; index += 1) {
      await writeFile(
        join(docs, `agma-modbook-mod-${String(index)}.md`),
        `# Mod ${String(index)}\n## Shared\nShared searchable text for every document.\n`,
        { mode: 0o600 },
      );
    }
    await writeFile(join(docs, "wide.md"), "# Wide\nworld writable shared text\n", {
      mode: 0o666,
    });
    await chmod(join(docs, "wide.md"), 0o666);
    await writeFile(join(docs, "large.md"), `# Large\n${"x".repeat(64 * 1024)}`, { mode: 0o600 });
    await symlink(join(docs, "agma-modbook-mod-0.md"), join(docs, "linked.md"), "file");

    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);
    const result = index.search("shared searchable");

    expect(result.matches).toHaveLength(8);
    expect(result.truncated).toBe(true);
    const serialized = JSON.stringify(result);
    expect(serialized).not.toContain("world writable");
    expect(result.matches.every((match) => !match.citation.includes("linked.md"))).toBe(true);
    expect(result.matches.every((match) => !match.citation.includes("large.md"))).toBe(true);
  });

  it("rejects unsafe or unbounded queries", async () => {
    const index = new StandaloneKnowledgeIndex();
    expect(() => index.search("")).toThrowError(TypeError);
    expect(() => index.search("x".repeat(257))).toThrowError(TypeError);
    expect(() => index.search("one\ttwo")).toThrowError(TypeError);
  });

  it("answers Chinese questions through CJK bigram tokens", async () => {
    const docs = await fixtureRoot("cjk-docs");
    await writeFile(
      join(docs, "agma-modbook-starlight.md"),
      "# 星辉工艺\n## 星辉熔炉\n星辉熔炉需要八块星尘砖和一个烈焰核心才能合成。\n",
      { mode: 0o600 },
    );
    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);

    const exact = index.search("星辉熔炉");
    expect(exact.matches.length).toBeGreaterThan(0);
    expect(exact.matches[0]?.excerpt).toContain("星辉熔炉");

    const question = index.search("星辉熔炉怎么合成");
    expect(question.matches.length).toBeGreaterThan(0);
    expect(question.matches[0]?.excerpt).toContain("星辉熔炉");

    expect(index.search("星辉熔炉的合成方法").matches.length).toBeGreaterThan(0);
    expect(index.search("下界合金").matches).toHaveLength(0);
  });

  it("ignores English function words in natural-language questions", async () => {
    const docs = await fixtureRoot("question-docs");
    await writeFile(
      join(docs, "agma-modbook-watering.md"),
      "# Farming\n## Watering Can\nCraft the watering can from three copper ingots and a bucket.\n",
      { mode: 0o600 },
    );
    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);

    const result = index.search("how do I craft the watering can");

    expect(result.matches).toHaveLength(1);
    expect(result.matches[0]?.heading).toBe("Watering Can");
  });

  it("falls back to the raw query when every token is a function word", async () => {
    const docs = await fixtureRoot("stopword-docs");
    await writeFile(join(docs, "agma-modbook-kilns.md"), "# Kilns\nA kiln fires clay.\n", {
      mode: 0o600,
    });
    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);

    expect(index.search("the how")).toMatchObject({ query: "the how", matches: [] });
    expect(index.search("怎么")).toMatchObject({ query: "怎么", matches: [] });
  });

  it("ranks full coverage first and keeps partial matches instead of dropping them", async () => {
    const docs = await fixtureRoot("fallback-docs");
    await writeFile(
      join(docs, "agma-modbook-watering.md"),
      "# Farming\n## Watering Can\nCraft the watering can from copper ingots.\n",
      { mode: 0o600 },
    );
    await writeFile(
      join(docs, "agma-modbook-crafting.md"),
      "# Crafting Basics\n## Workbench\nCraft stations unlock new recipes.\n",
      { mode: 0o600 },
    );
    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\n## Firing\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );
    const index = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);

    const result = index.search("craft watering");

    expect(result.truncated).toBe(false);
    expect(result.matches.map((match) => match.title)).toEqual(["Farming", "Crafting Basics"]);
  });

  it("computes a stable root fingerprint that moves with file set, content, and mtime", async () => {
    const docs = await fixtureRoot("fingerprint-docs");
    const roots = [{ directory: docs, kind: "local_docs" as const }];

    await expect(
      standaloneKnowledgeFingerprint([
        { directory: join(docs, "not-created"), kind: "local_docs" },
      ]),
    ).resolves.toMatch(/^[0-9a-f]{64}$/u);

    const empty = await standaloneKnowledgeFingerprint(roots);
    expect(await standaloneKnowledgeFingerprint(roots)).toBe(empty);

    const file = join(docs, "agma-modbook-kilns.md");
    await writeFile(file, "# Kilns\nA kiln fires clay.\n", { mode: 0o600 });
    const withFile = await standaloneKnowledgeFingerprint(roots);
    expect(withFile).not.toBe(empty);
    expect(await standaloneKnowledgeFingerprint(roots)).toBe(withFile);

    await writeFile(file, "# Kilns\nA kiln fires clay into bricks.\n", { mode: 0o600 });
    const rewritten = await standaloneKnowledgeFingerprint(roots);
    expect(rewritten).not.toBe(withFile);

    await utimes(file, new Date("2020-01-01T00:00:00Z"), new Date("2020-01-01T00:00:00Z"));
    expect(await standaloneKnowledgeFingerprint(roots)).not.toBe(rewritten);
  });

  it("rebuilds the hot index when the catalog rewrites the documents", async () => {
    const docs = await fixtureRoot("hot-docs");
    const roots = [{ directory: docs, kind: "local_docs" as const }];
    const knowledge = await StandaloneKnowledgeHotIndex.load(roots);

    expect(knowledge.size).toBe(0);
    expect((await knowledge.search("bricks")).matches).toHaveLength(0);
    expect(await knowledge.refresh()).toBe(false);

    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\n## Firing\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );

    const added = await knowledge.search("bricks");
    expect(added.matches).toHaveLength(1);
    expect(knowledge.size).toBeGreaterThan(0);
    expect(await knowledge.refresh()).toBe(false);

    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\n## Firing\nA kiln smelts every ore into metal ingots.\n",
      { mode: 0o600 },
    );

    expect((await knowledge.search("bricks")).matches).toHaveLength(0);
    expect((await knowledge.search("ingots")).matches).toHaveLength(1);
  });

  it("serves concurrent searches consistently while a rebuild is in flight", async () => {
    const docs = await fixtureRoot("concurrent-docs");
    const roots = [{ directory: docs, kind: "local_docs" as const }];
    const knowledge = await StandaloneKnowledgeHotIndex.load(roots);
    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );

    const results = await Promise.all(Array.from({ length: 8 }, () => knowledge.search("bricks")));

    expect(results.every((result) => result.matches.length === 1)).toBe(true);
  });

  it("executes local.knowledge.search through the hot index used at bootstrap", async () => {
    const docs = await fixtureRoot("hot-executor-docs");
    const knowledge = await StandaloneKnowledgeHotIndex.load([
      { directory: docs, kind: "local_docs" },
    ]);
    const { executor, call } = executorCall(knowledge, { query: "bricks" });

    const empty = await executor.execute(call);
    expect(empty.status).toBe("succeeded");
    expect(empty.result).toMatchObject({ query: "bricks", matches: [], truncated: false });

    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );

    const outcome = await executor.execute(call);
    expect(outcome.status).toBe("succeeded");
    const matches = outcome.result?.["matches"];
    expect(Array.isArray(matches)).toBe(true);
    expect(matches).toHaveLength(1);
  });

  it("executes local.knowledge.search through the project executor when configured", async () => {
    const docs = await fixtureRoot("executor-docs");
    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\n## Firing\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );
    const knowledge = await loadStandaloneKnowledge([{ directory: docs, kind: "local_docs" }]);
    const { executor, call } = executorCall(knowledge, { query: "bricks" });

    const outcome = await executor.execute(call);

    expect(outcome.status).toBe("succeeded");
    expect(outcome.source).toBe("local_docs");
    expect(outcome.trust).toBe("untrusted");
    expect(outcome.error).toBeNull();
    expect(outcome.result).toMatchObject({
      query: "bricks",
      truncated: false,
    });
    const matches = outcome.result?.["matches"];
    expect(Array.isArray(matches)).toBe(true);
    expect(matches).toHaveLength(1);

    const missing = await executor.execute({ ...call, arguments: { query: "netherite" } });
    expect(missing.status).toBe("succeeded");
    expect(missing.result).toMatchObject({ query: "netherite", matches: [], truncated: false });
  });

  it("keeps local.knowledge.search unregistered when no knowledge index is present", async () => {
    const { executor, call } = executorCall(undefined, { query: "bricks" });

    await expect(executor.execute(call)).rejects.toBeInstanceOf(TypeError);
  });

  it("registers both new descriptors with their schemas and trust levels", async () => {
    const registry = new ClientToolRegistry(await SchemaRegistry.load(contractRoot), [
      "game.block.inspect",
      "local.knowledge.search",
    ]);
    registry.activateClientCapabilities(["game.block.inspect"]);

    const knowledge = registry.byId("local.knowledge.search");
    expect(knowledge).toMatchObject({
      providerName: "local_knowledge_search",
      source: "local_docs",
      trust: "untrusted",
      execution: "runtime_local",
      argumentsSchema: "tools/local-knowledge-search-arguments.schema.json",
      resultSchema: "tools/local-knowledge-search-result.schema.json",
    });
    expect(registry.byProviderName("local_knowledge_search")?.id).toBe("local.knowledge.search");
    expect(registry.isClientToolActive("local.knowledge.search")).toBe(true);

    const inspect = registry.byId("game.block.inspect");
    expect(inspect).toMatchObject({
      providerName: "game_block_inspect",
      source: "client_context",
      trust: "client_visible",
      execution: "connector_remote",
      argumentsSchema: "tools/game-block-inspect-arguments.schema.json",
      resultSchema: "tools/game-block-inspect-result.schema.json",
    });
    expect(registry.byProviderName("game_block_inspect")?.id).toBe("game.block.inspect");

    expect(registry.validateArguments(knowledge!, { query: "kiln" })).toBe(true);
    expect(registry.validateArguments(knowledge!, {})).toBe(false);
    expect(registry.validateArguments(knowledge!, { query: "kiln", limit: 4 })).toBe(false);
    expect(registry.validateArguments(inspect!, {})).toBe(true);
    expect(registry.validateArguments(inspect!, { position: { x: 1, y: 64, z: -2 } })).toBe(true);
    expect(registry.validateArguments(inspect!, { position: { x: 1, y: 64 } })).toBe(false);
    expect(registry.validateArguments(inspect!, { x: 1, y: 64, z: -2 })).toBe(false);

    const validKnowledgeResult = {
      status: "succeeded",
      source: "local_docs",
      trust: "untrusted",
      result: {
        query: "kiln",
        matches: [
          {
            documentId: "a".repeat(64),
            citation: "local_docs/aaaaaaaaaaaa/agma-modbook-kilns.md#chunk-1",
            kind: "local_docs",
            title: "Kilns",
            heading: "Firing",
            excerpt: "A kiln fires clay into bricks.",
          },
        ],
        truncated: false,
      },
      error: null,
    } as const;
    expect(registry.validateResult(knowledge!, validKnowledgeResult, { query: "kiln" })).toBe(true);
    const wrongSource = {
      ...validKnowledgeResult,
      source: "client_context",
    } as const;
    expect(registry.validateResult(knowledge!, wrongSource, { query: "kiln" })).toBe(false);

    const validInspectResult = {
      status: "succeeded",
      source: "client_context",
      trust: "client_visible",
      result: {
        found: true,
        blockId: "minecraft:chest",
        position: { x: 10, y: 64, z: -4 },
        hasBlockEntity: true,
        blockEntity: { data: { Items: [{ id: "minecraft:stone", count: 3 }] } },
      },
      error: null,
    } as const;
    expect(registry.validateResult(inspect!, validInspectResult, {})).toBe(true);
    const staleEntity = {
      ...validInspectResult,
      result: { ...validInspectResult.result, hasBlockEntity: false },
    };
    expect(registry.validateResult(inspect!, staleEntity, {})).toBe(false);
  });

  it("resolves configured knowledge roots and rejects invalid ones", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const source = validClientRuntimeConfig().replace(
      "logging:\n  directory: ./logs\n  level: info\n",
      "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ./knowledge/local-docs\n      kind: local_docs\n",
    );
    const configPath = await writeRuntimeConfig(directory, source);

    const loaded = await loadStandaloneClientConfig({
      configPath,
      environment: runtimeEnvironment(),
    });

    expect(loaded.paths.knowledgeRoots).toEqual([
      { directory: join(directory, "knowledge", "local-docs"), kind: "local_docs" },
    ]);

    const withoutKnowledge = await loadStandaloneClientConfig({
      configPath: await writeRuntimeConfig(directory, validClientRuntimeConfig(), "plain.yml"),
      environment: runtimeEnvironment(),
    });
    expect(withoutKnowledge.paths.knowledgeRoots).toEqual([]);

    const duplicate = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig().replace(
        "logging:\n  directory: ./logs\n  level: info\n",
        "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ./knowledge/local-docs\n      kind: local_docs\n    - directory: ./knowledge/local-docs\n      kind: server_rules\n",
      ),
      "duplicate.yml",
    );
    await expect(
      loadStandaloneClientConfig({ configPath: duplicate, environment: runtimeEnvironment() }),
    ).rejects.toMatchObject({ code: "CONFIG_SCHEMA_INVALID" });

    const badKind = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig().replace(
        "logging:\n  directory: ./logs\n  level: info\n",
        "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ./knowledge/local-docs\n      kind: mod_docs\n",
      ),
      "bad-kind.yml",
    );
    await expect(
      loadStandaloneClientConfig({ configPath: badKind, environment: runtimeEnvironment() }),
    ).rejects.toMatchObject({ code: "CONFIG_SCHEMA_INVALID" });

    const escaped = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig().replace(
        "logging:\n  directory: ./logs\n  level: info\n",
        "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ../outside\n      kind: local_docs\n",
      ),
      "escaped.yml",
    );
    await expect(
      loadStandaloneClientConfig({ configPath: escaped, environment: runtimeEnvironment() }),
    ).rejects.toMatchObject({ code: "CONFIG_SCHEMA_INVALID" });
  });

  it("starts the standalone Runtime with configured knowledge roots and an absent docs directory", async () => {
    const port = await findAvailablePort();
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const docs = join(directory, "knowledge", "local-docs");
    await mkdir(docs, { recursive: true, mode: 0o700 });
    await chmod(docs, 0o700);
    await writeFile(
      join(docs, "agma-modbook-kilns.md"),
      "# Kilns\n## Firing\nA kiln fires clay into bricks.\n",
      { mode: 0o600 },
    );
    const source = validClientRuntimeConfig(port)
      .replace(
        "logging:\n  directory: ./logs\n  level: info\n",
        "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ./knowledge/local-docs\n      kind: local_docs\n",
      )
      .replace("  allowed: []", "  allowed: [local.knowledge.search]");
    const configPath = await writeRuntimeConfig(directory, source);

    const runtime = await startStandaloneClient({
      configPath,
      environment: runtimeEnvironment(),
      modelProvider: provider(),
      standaloneProtocolRoot: contractRoot,
      now: () => new Date("2026-07-17T00:00:00Z"),
    });
    runtimes.push(runtime);

    expect(runtime.profile).toBe("client");
    expect((await fetch(`http://127.0.0.1:${String(port)}/health`)).status).toBe(200);

    const knowledge = await loadStandaloneKnowledge(
      (await loadStandaloneClientConfig({ configPath, environment: runtimeEnvironment() })).paths
        .knowledgeRoots,
    );
    expect(knowledge.search("bricks").matches).toHaveLength(1);
    await runtime.close();

    const absent = await writeRuntimeConfig(
      directory,
      validClientRuntimeConfig(port)
        .replace(
          "logging:\n  directory: ./logs\n  level: info\n",
          "logging:\n  directory: ./logs\n  level: info\nknowledge:\n  roots:\n    - directory: ./knowledge/not-created\n      kind: local_docs\n",
        )
        .replace("  allowed: []", "  allowed: [local.knowledge.search]"),
      "absent.yml",
    );
    const withAbsentDocs = await startStandaloneClient({
      configPath: absent,
      environment: runtimeEnvironment(),
      modelProvider: provider(),
      standaloneProtocolRoot: contractRoot,
      now: () => new Date("2026-07-17T00:00:00Z"),
    });
    runtimes.push(withAbsentDocs);
    expect(withAbsentDocs.profile).toBe("client");
  });
});
