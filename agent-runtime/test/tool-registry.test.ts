import { readFile } from "node:fs/promises";

import { describe, expect, it } from "vitest";

import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { ToolRegistry } from "../src/tools/tool-registry.js";
import { coreToolIds } from "../src/tools/tool-types.js";

const schemas = await SchemaRegistry.load();
const clientSchemas = await SchemaRegistry.load(
  new URL("../../standalone-client/contracts/", import.meta.url).pathname,
);
const registry = new ToolRegistry(schemas);
const fixture = JSON.parse(
  await readFile(
    new URL("../../protocol/fixtures/valid/core-tool-contracts.json", import.meta.url),
    "utf8",
  ),
) as Record<string, { arguments: Record<string, unknown>; result: Record<string, unknown> }>;
const localFixture = JSON.parse(
  await readFile(
    new URL("../../protocol/fixtures/valid/runtime-local-tool-contracts.json", import.meta.url),
    "utf8",
  ),
) as Record<string, { arguments: Record<string, unknown>; result: Record<string, unknown> }>;
const buildFixture = JSON.parse(
  await readFile(
    new URL("../../protocol/fixtures/valid/build-preview-create-tool.json", import.meta.url),
    "utf8",
  ),
) as { arguments: Record<string, unknown>; result: Record<string, unknown> };
const clientFixture = JSON.parse(
  await readFile(
    new URL(
      "../../standalone-client/contracts/fixtures/valid/connector-tool-contracts.json",
      import.meta.url,
    ),
    "utf8",
  ),
) as Record<string, Record<string, unknown>>;
const standaloneFixture = JSON.parse(
  await readFile(
    new URL(
      "../../standalone-client/contracts/fixtures/valid/standalone-contracts.json",
      import.meta.url,
    ),
    "utf8",
  ),
) as { resource: Record<string, unknown> };

describe("Runtime Tool Registry", () => {
  it("registers no Paper or local tools in the C1 client profile", () => {
    const clientRegistry = new ToolRegistry(schemas, "client");

    expect(clientRegistry.list()).toEqual([]);
    expect(clientRegistry.byProviderName("server_info_read")).toBeUndefined();
    expect(() => clientRegistry.forAllowlist(["server.info.read"])).toThrow();
  });

  it("registers only explicit client Tools and activates the negotiated capability subset", () => {
    const clientRegistry = new ToolRegistry(clientSchemas, "client", [
      "game.resource.search",
      "game.process.lookup",
      "game.process.plan",
      "game.inventory.snapshot",
    ]);

    expect(clientRegistry.list().map((tool) => tool.id)).toEqual([
      "game.resource.search",
      "game.process.lookup",
      "game.process.plan",
      "game.inventory.snapshot",
    ]);
    expect(clientRegistry.list().every((tool) => tool.execution === "connector_remote")).toBe(true);
    expect(clientRegistry.forAllowlist(["game.resource.search", "game.process.plan"])).toEqual([]);

    clientRegistry.activateClientCapabilities(["game.resource.search", "paper.command"]);
    expect(
      clientRegistry
        .forAllowlist(["game.resource.search", "game.process.plan"])
        .map((tool) => tool.id),
    ).toEqual(["game.resource.search"]);
    expect(clientRegistry.byProviderName("game_resource_search")?.source).toBe("client_catalog");
    expect(clientRegistry.byProviderName("game_process_plan")).toBeUndefined();
    expect(clientRegistry.byProviderName("server_info_read")).toBeUndefined();

    const search = clientRegistry.byProviderName("game_resource_search");
    if (search === undefined) throw new Error("missing client search Tool");
    expect(
      clientRegistry.validateArguments(search, clientFixture["resourceSearchArguments"] ?? {}),
    ).toBe(true);
    expect(clientRegistry.validateArguments(search, { query: "iron", limit: 21 })).toBe(false);
    expect(
      clientRegistry.validateResult(search, {
        status: "succeeded",
        source: "client_catalog",
        trust: "client_visible",
        result: clientFixture["resourceSearchResult"] ?? {},
        error: null,
      }),
    ).toBe(true);
    const resultWithCandidate = {
      ...(clientFixture["resourceSearchResult"] ?? {}),
      visibility: "singleplayer",
      completeness: "complete",
      candidates: [
        {
          rank: 1,
          score: 1,
          matchedBy: "exact_id",
          resource: standaloneFixture.resource,
        },
      ],
    };
    expect(
      clientRegistry.validateResult(search, {
        status: "succeeded",
        source: "client_catalog",
        trust: "client_visible",
        result: resultWithCandidate,
        error: null,
      }),
    ).toBe(true);
    expect(
      clientRegistry.validateResult(search, {
        status: "succeeded",
        source: "client_catalog",
        trust: "client_visible",
        result: { ...resultWithCandidate, generationId: "different-generation" },
        error: null,
      }),
    ).toBe(false);

    clientRegistry.activateClientCapabilities(["game.process.plan"]);
    const plan = clientRegistry.byProviderName("game_process_plan");
    if (plan === undefined) throw new Error("missing client planner Tool");
    expect(
      clientRegistry.validateResult(plan, {
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: clientFixture["processPlanResult"] ?? {},
        error: null,
      }),
    ).toBe(true);
    const route = (clientFixture["processPlanResult"]?.["routes"] as readonly unknown[])[0] as
      | Readonly<Record<string, unknown>>
      | undefined;
    expect(
      clientRegistry.validateResult(plan, {
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: {
          ...(clientFixture["processPlanResult"] ?? {}),
          routes: route === undefined ? [] : [{ ...route, materials: undefined }],
        },
        error: null,
      }),
    ).toBe(false);
  });

  it("exposes unique provider-safe remote and local tools with self-contained parameters", () => {
    const tools = registry.list();

    expect(tools.map((tool) => tool.id)).toEqual(coreToolIds);
    expect(new Set(tools.map((tool) => tool.providerName)).size).toBe(coreToolIds.length);
    expect(tools.every((tool) => /^[A-Za-z0-9_-]{1,64}$/u.test(tool.providerName))).toBe(true);
    const providerSchemas = JSON.stringify(tools.map((tool) => tool.parameters));
    expect(providerSchemas).not.toMatch(
      /"(?:\$schema|\$id|\$ref|maxProperties|minLength|maxLength)":|"pattern":"/u,
    );
    expect(registry.byProviderName("server_info_read")?.parameters).toEqual({
      type: "object",
      properties: {},
      required: [],
      additionalProperties: false,
    });
    expect(registry.byProviderName("server_recipe_lookup")?.id).toBe("server.recipe.lookup");
    expect(registry.byProviderName("server.recipe.lookup")).toBeUndefined();
    expect(
      tools.filter((tool) => tool.execution === "runtime_local").map((tool) => tool.id),
    ).toEqual([
      "server.docs.search",
      "project.list",
      "project.read",
      "project.create",
      "project.update",
    ]);
    expect(registry.byProviderName("landmark_search")?.execution).toBe("paper_remote");
    expect(registry.byProviderName("build_preview_create")).toMatchObject({
      id: "build.preview.create",
      execution: "paper_remote",
      source: "paper_api",
      trust: "authoritative",
    });
  });

  it("validates closed arguments and authoritative typed results", () => {
    const context = registry.byProviderName("player_context_read");
    const recipe = registry.byProviderName("server_recipe_lookup");
    if (context === undefined || recipe === undefined) {
      throw new Error("missing test tools");
    }

    expect(registry.validateArguments(context, {})).toBe(true);
    expect(registry.validateArguments(context, { extra: true })).toBe(false);
    expect(registry.validateArguments(recipe, fixture["recipeLookup"]?.arguments ?? {})).toBe(true);
    expect(registry.validateArguments(recipe, { itemId: "not-an-item" })).toBe(false);
    expect(
      registry.validateResult(context, {
        toolCallId: "11111111-1111-4111-8111-111111111111",
        sessionId: "22222222-2222-4222-8222-222222222222",
        playerUuid: "33333333-3333-4333-8333-333333333333",
        tool: context.id,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: fixture["playerContext"]?.result ?? {},
        error: null,
      }),
    ).toBe(true);
    expect(
      registry.validateResult(context, {
        toolCallId: "11111111-1111-4111-8111-111111111111",
        sessionId: "22222222-2222-4222-8222-222222222222",
        playerUuid: "33333333-3333-4333-8333-333333333333",
        tool: context.id,
        sequence: 0,
        status: "succeeded",
        source: "paper_api",
        trust: "untrusted",
        result: fixture["playerContext"]?.result ?? {},
        error: null,
      }),
    ).toBe(false);
  });

  it("validates the Paper-owned bounded build preview contract", () => {
    const preview = registry.byProviderName("build_preview_create");
    if (preview === undefined) {
      throw new Error("missing build preview tool");
    }

    expect(registry.validateArguments(preview, buildFixture.arguments)).toBe(true);
    expect(registry.validateArguments(preview, { ...buildFixture.arguments, apply: true })).toBe(
      false,
    );
    expect(
      registry.validateResult(preview, {
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: buildFixture.result,
        error: null,
      }),
    ).toBe(true);
    expect(
      registry.validateResult(preview, {
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: { ...buildFixture.result, worldWriteEnabled: true },
        error: null,
      }),
    ).toBe(false);
  });

  it("validates untrusted documentation and verified Runtime storage results", () => {
    const docs = registry.byProviderName("server_docs_search");
    const create = registry.byProviderName("project_create");
    if (docs === undefined || create === undefined) {
      throw new Error("missing local test tools");
    }

    expect(registry.validateArguments(docs, localFixture["docsSearch"]?.arguments ?? {})).toBe(
      true,
    );
    expect(
      registry.validateResult(docs, {
        status: "succeeded",
        source: "server_docs",
        trust: "untrusted",
        result: localFixture["docsSearch"]?.result ?? {},
        error: null,
      }),
    ).toBe(true);
    expect(
      registry.validateArguments(create, { ...localFixture["projectCreate"]?.arguments }),
    ).toBe(true);
    expect(
      registry.validateArguments(create, {
        ...localFixture["projectCreate"]?.arguments,
        playerUuid: "11111111-1111-4111-8111-111111111111",
      }),
    ).toBe(false);
    expect(
      registry.validateResult(create, {
        status: "succeeded",
        source: "runtime_storage",
        trust: "verified",
        result: localFixture["projectCreate"]?.result ?? {},
        error: null,
      }),
    ).toBe(true);
  });
});
