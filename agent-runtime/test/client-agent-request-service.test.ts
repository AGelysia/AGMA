import { describe, expect, it, vi } from "vitest";
import { DatabaseSync } from "node:sqlite";

import type { EvidenceClaim } from "../src/evidence/evidence-normalizer.js";
import type { WebEvidenceCollector } from "../src/evidence/web-evidence-pipeline.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelProvider,
} from "../src/providers/model-provider.js";
import { RuntimeLogger } from "../src/observability/runtime-logger.js";
import {
  ClientAgentRequestService,
  type ClientAgentServiceConfig,
} from "../src/requests/client-agent-request-service.js";
import type { AgentRuntimeResponse } from "../src/requests/agent-request-service.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { SqliteProjectRepository } from "../src/storage/project-repository.js";
import { ClientToolRegistry } from "../src/tools/client-tool-registry.js";
import { ProjectToolExecutor } from "../src/tools/project-tool-executor.js";
import type { UsageAccounting } from "../src/usage/usage-accounting.js";

const REQUEST_ID = "11111111-1111-4111-8111-111111111111";
const SUBJECT_ID = "22222222-2222-4222-8222-222222222222";
const AUTHORIZATION_ID = "33333333-3333-4333-8333-333333333333";

function claim(
  character: string,
  statement: string,
  options: {
    readonly match?: "match" | "mismatch" | "unknown";
    readonly conflicts?: readonly string[];
    readonly title?: string;
    readonly url?: string;
    readonly warnings?: readonly string[];
  } = {},
): EvidenceClaim {
  return {
    claimId: `claim.${character.repeat(24)}`,
    statement,
    sourceUrl: options.url ?? `https://${character}.example/guide`,
    sourceTitle: options.title ?? `${character.toUpperCase()} guide`,
    publisher: "Fixture",
    retrievedAt: "2026-07-17T00:00:00.000Z",
    evidenceSpanSha256: character.repeat(64),
    applicability: {
      minecraftVersion: "1.21.11",
      modVersions: {},
      modpackVersion: null,
      match: options.match ?? "match",
    },
    sourceQuality: 0.8,
    conflicts: options.conflicts ?? [],
    warnings: options.warnings ?? [],
  };
}

function config(): ClientAgentServiceConfig {
  return {
    scopeId: "client-installation",
    model: {
      provider: "openai",
      apiKey: "provider-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      model: "test-model",
      timeoutSeconds: 2,
    },
    limits: {
      maxConcurrentRequests: 1,
      maxQueuedRequests: 1,
      maxToolRounds: 4,
      maxContextMessages: 30,
      maxContextCharacters: 32_768,
      perPlayerCooldownSeconds: 0,
      dailyRequestsPerPlayer: 100,
    },
  };
}

function capturingLogger(): { readonly lines: string[]; readonly logger: RuntimeLogger } {
  const lines: string[] = [];
  return {
    lines,
    logger: new RuntimeLogger({
      now: () => new Date("2026-07-17T00:00:00.000Z"),
      sink: { write: (line) => lines.push(line) },
    }),
  };
}

async function registry(): Promise<ClientToolRegistry> {
  return registryWith(["game.inventory.snapshot"]);
}

async function registryWith(allowed: readonly string[]): Promise<ClientToolRegistry> {
  const schemas = await SchemaRegistry.load(
    new URL("../../standalone-client/contracts/", import.meta.url),
  );
  const tools = new ClientToolRegistry(schemas, allowed);
  tools.activateClientCapabilities(allowed);
  return tools;
}

function planResult(inventoryApplied: boolean, target = "minecraft:iron_pickaxe") {
  return {
    generationId: "generation-001",
    status: "complete",
    target: { resourceId: target, amount: 1 },
    routes: [
      {
        rank: 1,
        complete: true,
        rankingReasons: [
          "complete_route",
          "fewer_unresolved_resources",
          "fewer_process_steps",
          "stable_process_identity_tiebreak",
        ],
        steps: [
          {
            index: 0,
            processId: "minecraft:iron_pickaxe",
            batches: 1,
            inputs: [{ resourceId: "minecraft:iron_ingot", amount: 3 }],
            outputs: [{ resourceId: target, amount: 1 }],
          },
        ],
        materials: [{ resourceId: "minecraft:iron_ingot", amount: 3 }],
        inventoryUsed: [],
        unresolved: [],
        workstations: ["minecraft:crafting_table"],
        issues: [],
      },
    ],
    unresolved: [],
    cycles: [],
    exploredNodes: 2,
    inventoryApplied,
    warnings: [],
  };
}

describe("client-only Agent request service", () => {
  it("does not publish unsupported provider facts while web access is off", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "final",
        fallbackText: "Diamonds always generate at Y=99 in this version.",
      }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith([]),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Where do diamonds generate?",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing offline completion");
    expect(completion.payload.fallbackText).toContain("Unknown:");
    expect(completion.payload.fallbackText).not.toContain("Y=99");
    expect(completion.payload.sources).toEqual([]);
    expect(adapter.generate).toHaveBeenCalledTimes(1);
  });

  it("preflights inventory and a pinned target plan without asking the model to calculate", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockRejectedValue(new Error("model must not run")),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(["game.inventory.snapshot", "game.process.plan"]),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "How do I make this?",
        localContext: {
          minecraftVersion: "1.21.11",
          catalogGenerationId: "generation-001",
          target: {
            id: "minecraft:iron_pickaxe",
            displayName: "Iron Pickaxe",
            modId: "minecraft",
            modVersion: "1.21.11",
          },
        },
        inventoryAuthorization: {
          authorizationId: AUTHORIZATION_ID,
          generationId: "generation-001",
          resourceIds: ["minecraft:iron_ingot"],
        },
      },
      (response) => responses.push(response),
    );

    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const inventoryCall = responses[0];
    if (inventoryCall?.type !== "tool.call") throw new Error("missing inventory preflight");
    expect(inventoryCall.payload.tool).toBe("game.inventory.snapshot");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: inventoryCall.payload.toolCallId,
        sessionId: inventoryCall.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: inventoryCall.payload.tool,
        sequence: inventoryCall.payload.sequence,
        status: "succeeded",
        source: "client_context",
        trust: "client_visible",
        result: {
          generationId: "generation-001",
          authorizationId: AUTHORIZATION_ID,
          entries: [],
          truncated: false,
          warnings: [],
        },
        error: null,
      }),
    ).toBe("accepted");

    await vi.waitFor(() => expect(responses[1]?.type).toBe("tool.call"));
    const planCall = responses[1];
    if (planCall?.type !== "tool.call") throw new Error("missing plan preflight");
    expect(planCall.payload).toMatchObject({
      tool: "game.process.plan",
      arguments: {
        generationId: "generation-001",
        resourceId: "minecraft:iron_pickaxe",
        amount: 1,
      },
    });
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: planCall.payload.toolCallId,
        sessionId: planCall.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: planCall.payload.tool,
        sequence: planCall.payload.sequence,
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: planResult(true),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    expect(completion).toMatchObject({
      type: "agent.complete",
      payload: { costMicroUsd: 0, costKind: "estimated", sources: [] },
    });
    if (completion?.type !== "agent.complete") throw new Error("missing completion");
    expect(completion.payload.fallbackText).toContain("3 x minecraft:iron_ingot");
    expect(completion.payload.fallbackText).toContain("Inventory applied: yes");
    expect(adapter.generate).not.toHaveBeenCalled();
  });

  it("rejects a pinned plan result for a different target", async () => {
    const service = new ClientAgentRequestService({
      provider: {
        check: vi.fn().mockResolvedValue({ ok: true }),
        generate: vi.fn().mockRejectedValue(new Error("model must not run")),
      },
      config: config(),
      tools: await registryWith(["game.process.plan"]),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Plan this target.",
        localContext: {
          minecraftVersion: "1.21.11",
          catalogGenerationId: "generation-001",
          target: { id: "minecraft:iron_pickaxe" },
        },
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const call = responses[0];
    if (call?.type !== "tool.call") throw new Error("missing plan preflight");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: planResult(false, "minecraft:diamond_pickaxe"),
        error: null,
      }),
    ).toBe("violation");
    expect(service.cancel(REQUEST_ID, SUBJECT_ID)).toBe(true);
  });

  it("publishes only current cited evidence and downgrades uncited, conflicting, or mismatched claims", async () => {
    const valid = claim("a", "The Boss Core drop rate is 12.5% in Minecraft version 1.21.11.", {
      title: "Reviewed boss guide",
      url: "https://valid.example/boss",
      warnings: ["The source publication date is unavailable."],
    });
    const conflicting = claim("b", "The boss waits at coordinate X=10.", {
      conflicts: [`claim.${"e".repeat(24)}`],
      title: "Conflicting coordinate guide",
    });
    const mismatched = claim("c", "This applies to Minecraft version 1.20.1.", {
      match: "mismatch",
      title: "Wrong-version guide",
    });
    const unused = claim("d", "An unused web statement.", { title: "Unused guide" });
    const webEvidence = {
      collect: vi.fn().mockResolvedValue({
        status: "complete",
        query: "bounded query",
        claims: [valid, conflicting, mismatched, unused],
        warnings: [],
        searchCostMicroUsd: 5000,
      }),
    } satisfies WebEvidenceCollector;
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "final",
        fallbackText: [
          `${valid.statement} [${valid.claimId}]`,
          `${conflicting.statement} [${conflicting.claimId}]`,
          `${mismatched.statement} [${mismatched.claimId}]`,
          "An uncited webpage says to use a hidden command.",
        ].join("\n"),
      }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      webEvidence,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "What is the Boss Core drop rate?",
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing controlled completion");
    const text = completion.payload.fallbackText;
    expect(completion.payload).toMatchObject({
      costMicroUsd: 5000,
      costKind: "estimated",
    });
    expect(completion.payload.sources).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          claimId: valid.claimId,
          title: "Reviewed boss guide",
          url: "https://valid.example/boss",
          publisher: "Fixture",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          applicability: expect.objectContaining({
            match: "match",
            minecraftVersion: "1.21.11",
          }),
          warnings: ["The source publication date is unavailable."],
        }),
        expect.objectContaining({ claimId: conflicting.claimId }),
        expect.objectContaining({
          claimId: mismatched.claimId,
          applicability: expect.objectContaining({ match: "mismatch" }),
        }),
      ]),
    );
    expect(text).toContain(valid.statement);
    expect(text).toContain(`[${valid.claimId}]`);
    expect(text).toContain("Sources:");
    expect(text).toContain("Reviewed boss guide | Fixture | https://valid.example/boss");
    expect(text).toContain("The source publication date is unavailable.");
    expect(text).toContain("Unknown:");
    expect(text).toContain("conflicting claim");
    expect(text).toContain("applicability is unknown or mismatched");
    expect(text).toContain("did not cite a claim from this evidence request");
    expect(text).not.toContain(conflicting.statement);
    expect(text).not.toContain(mismatched.statement);
    expect(text).not.toContain("hidden command");
    expect(text).toContain("Conflicting coordinate guide");
    expect(text).toContain("Wrong-version guide");
    expect(text).not.toContain("Unused guide");
    expect(adapter.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        tools: [],
        input: expect.arrayContaining([
          expect.objectContaining({ content: expect.stringContaining(valid.claimId) }),
        ]),
      }),
    );
  });

  it("runs local Tools before web collection and never dispatches a web-synthesis Tool call", async () => {
    const evidence = claim("f", "The boss may drop a core in Minecraft version 1.21.11.");
    const events: string[] = [];
    const generated: ModelGenerationRequest[] = [];
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(async (request: ModelGenerationRequest) => {
        generated.push(request);
        events.push(`generate:${String(generated.length)}`);
        if (generated.length === 1) {
          return {
            type: "tool_call" as const,
            providerCallId: "local-search",
            providerName: "game_resource_search",
            arguments: {
              query: "iron ingot",
              limit: 5,
            },
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        return { type: "final" as const, fallbackText: `[${evidence.claimId}]` };
      }),
    };
    const webEvidence = {
      collect: vi.fn(async () => {
        events.push("collect");
        return {
          status: "complete" as const,
          query: "bounded query",
          claims: [evidence],
          warnings: [],
          searchCostMicroUsd: 5000,
        };
      }),
    } satisfies WebEvidenceCollector;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(["game.resource.search"]),
      webEvidence,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Find iron, then verify the boss drop online.",
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const call = responses[0];
    if (call?.type !== "tool.call") throw new Error("missing local Tool call");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: call.payload.playerUuid,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_catalog",
        trust: "client_visible",
        result: {
          generationId: "generation-001",
          visibility: "no_world",
          completeness: "unavailable",
          candidates: [],
          ambiguous: false,
          truncated: false,
          warnings: [],
        },
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(events).toEqual(["generate:1", "generate:2", "collect", "generate:3"]);
    expect(generated[0]?.input.at(-1)?.content).not.toContain(evidence.claimId);
    // The local phase continues after the search result so multi-round flows (e.g. build
    // preview) can chain Tools; web synthesis still never receives Tool calls.
    expect(generated[2]).toMatchObject({ tools: [] });
    expect(generated[2]?.input.at(-1)?.content).toContain(evidence.claimId);
    expect(responses.filter((response) => response.type === "tool.call")).toHaveLength(1);
    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing controlled completion");
    expect(completion.payload.fallbackText).toContain("Local catalog result:");
    expect(completion.payload.fallbackText).toContain(evidence.statement);
    expect(completion.payload.sources).toHaveLength(1);
  });

  it("rejects inconsistent local and web contexts before collecting web evidence", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockRejectedValue(new Error("model must not run")),
    };
    const webEvidence = {
      collect: vi.fn().mockRejectedValue(new Error("web search must not run")),
    } satisfies WebEvidenceCollector;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(["game.process.plan"]),
      webEvidence,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Check this plan online.",
        localContext: {
          minecraftVersion: "1.21.11",
          catalogGenerationId: "generation-001",
          target: {
            id: "minecraft:iron_pickaxe",
            modId: "minecraft",
            modVersion: "1.21.11",
          },
        },
        webAuthorization: "once",
        webContext: {
          minecraftVersion: "1.18.2",
          target: {
            id: "minecraft:iron_pickaxe",
            modId: "minecraft",
            modVersion: "1.18.2",
          },
        },
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const call = responses[0];
    if (call?.type !== "tool.call") throw new Error("missing plan preflight");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: planResult(false),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses.at(-1)).toMatchObject({
      type: "agent.error",
      payload: { code: "TOOL_REJECTED" },
    });
    expect(webEvidence.collect).not.toHaveBeenCalled();
    expect(adapter.generate).not.toHaveBeenCalled();
  });

  it("preserves a deterministic local plan when web synthesis is unavailable", async () => {
    const evidence = claim("g", "A current guide contains an external process note.");
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockRejectedValue(new ModelGenerationError("MODEL_UNAVAILABLE")),
    };
    const webEvidence = {
      collect: vi.fn().mockResolvedValue({
        status: "complete",
        query: "bounded query",
        claims: [evidence],
        warnings: [],
        searchCostMicroUsd: 5000,
      }),
    } satisfies WebEvidenceCollector;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(["game.process.plan"]),
      webEvidence,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Plan this and check for current external notes.",
        localContext: {
          minecraftVersion: "1.21.11",
          catalogGenerationId: "generation-001",
          target: { id: "minecraft:iron_pickaxe" },
        },
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(responses[0]?.type).toBe("tool.call"));
    const call = responses[0];
    if (call?.type !== "tool.call") throw new Error("missing plan preflight");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_planner",
        trust: "deterministic",
        result: planResult(false),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing degraded completion");
    expect(completion.payload.fallbackText).toContain("Deterministic local process plan:");
    expect(completion.payload.fallbackText).toContain("3 x minecraft:iron_ingot");
    expect(completion.payload.fallbackText).toContain(
      "Unknown:\n- Web evidence was collected, but synthesis was unavailable.",
    );
    expect(completion.payload.fallbackText).not.toContain(evidence.statement);
    expect(completion.payload.sources).toEqual([]);
    expect(adapter.generate).toHaveBeenCalledTimes(1);
  });

  it("does not invoke a second model round when web collection returns zero evidence", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "final",
        fallbackText: "An unsupported local-phase guess.",
      }),
    };
    const webEvidence = {
      collect: vi.fn().mockResolvedValue({
        status: "no_evidence",
        query: "bounded query",
        claims: [],
        warnings: [],
        searchCostMicroUsd: 5000,
      }),
    } satisfies WebEvidenceCollector;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith([]),
      webEvidence,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Find a current external answer.",
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing no-evidence completion");
    expect(completion.payload.fallbackText).toContain(
      "Unknown:\n- No current applicable web evidence was available for this request.",
    );
    expect(completion.payload.fallbackText).not.toContain("unsupported local-phase guess");
    expect(completion.payload.sources).toEqual([]);
    expect(webEvidence.collect).toHaveBeenCalledTimes(1);
    expect(adapter.generate).toHaveBeenCalledTimes(1);
  });

  it("adds estimated Search cost to reported provider rounds as a mixed total", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "No cited result." }),
    };
    const webEvidence = {
      collect: vi.fn().mockResolvedValue({
        status: "no_evidence",
        query: "bounded query",
        claims: [],
        warnings: [],
        searchCostMicroUsd: 5000,
      }),
    } satisfies WebEvidenceCollector;
    const usage = {
      admitRequest: vi.fn().mockReturnValue({ accepted: true }),
      rollbackAdmission: vi.fn().mockReturnValue(true),
      reserveProviderRound: vi.fn().mockReturnValue({ accepted: true }),
      markProviderRoundStarted: vi.fn().mockReturnValue(true),
      releaseProviderRound: vi.fn().mockReturnValue(true),
      recordProviderUsage: vi
        .fn()
        .mockReturnValue({ inserted: true, usageKind: "REPORTED", costMicroUsd: 10 }),
      closeRequest: vi.fn().mockReturnValue(true),
      snapshot: vi.fn(() => {
        throw new Error("not used");
      }),
    } as UsageAccounting;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      webEvidence,
      usage,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Search for a current guide.",
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses.at(-1)).toMatchObject({
      type: "agent.complete",
      payload: { costMicroUsd: 5010, costKind: "mixed", sources: [] },
    });
    expect(usage.recordProviderUsage).toHaveBeenCalledTimes(1);
  });

  it("rejects an inventory Tool call before connector dispatch when authorization is absent", async () => {
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "tool_call",
        providerCallId: "call-1",
        providerName: "game_inventory_snapshot",
        arguments: {
          authorizationId: AUTHORIZATION_ID,
          generationId: "generation-001",
          resourceIds: ["minecraft:iron_ingot"],
        },
        continuation: { provider: "openai", items: [] },
      }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Read inventory",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses).toMatchObject([{ type: "agent.error", payload: { code: "TOOL_REJECTED" } }]);
    expect(responses.some((response) => response.type === "tool.call")).toBe(false);
  });

  it("logs an unexpected request failure with the request id but never the prompt", async () => {
    const captured = capturingLogger();
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "unused" }),
    };
    const webEvidence = {
      collect: vi.fn().mockRejectedValue(new Error("evidence collector crashed")),
    } satisfies WebEvidenceCollector;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      webEvidence,
      logger: captured.logger,
    });
    const responses: AgentRuntimeResponse[] = [];

    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Search for a current guide.",
        webAuthorization: "once",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses).toMatchObject([
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
    ]);
    const logs = captured.lines.join("");
    expect(logs).toContain('"event":"runtime.error"');
    expect(logs).toContain('"code":"RUNTIME_INTERNAL_ERROR"');
    expect(logs).toContain(`"requestId":"${REQUEST_ID}"`);
    expect(logs).toContain("evidence collector crashed");
    expect(logs).not.toContain("current guide");
  });

  it("logs a usage admission failure before responding with a stable internal error", async () => {
    const captured = capturingLogger();
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "unused" }),
    };
    const usage = {
      admitRequest: vi.fn(() => {
        throw new Error("sqlite disk is full");
      }),
      rollbackAdmission: vi.fn().mockReturnValue(true),
      reserveProviderRound: vi.fn().mockReturnValue({ accepted: true }),
      markProviderRoundStarted: vi.fn().mockReturnValue(true),
      releaseProviderRound: vi.fn().mockReturnValue(true),
      recordProviderUsage: vi
        .fn()
        .mockReturnValue({ inserted: true, usageKind: "REPORTED", costMicroUsd: 10 }),
      closeRequest: vi.fn().mockReturnValue(true),
      snapshot: vi.fn(() => {
        throw new Error("not used");
      }),
    } as UsageAccounting;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      usage,
      logger: captured.logger,
    });
    const responses: AgentRuntimeResponse[] = [];

    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Search for a current guide.",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses).toMatchObject([
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
    ]);
    const logs = captured.lines.join("");
    expect(logs).toContain('"code":"USAGE_ADMISSION_FAILED"');
    expect(logs).toContain(`"requestId":"${REQUEST_ID}"`);
    expect(logs).toContain("sqlite disk is full");
  });

  it("fails closed after a durable usage write fails", async () => {
    const captured = capturingLogger();
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "provider response" }),
    };
    const usage = {
      admitRequest: vi.fn().mockReturnValue({ accepted: true }),
      rollbackAdmission: vi.fn().mockReturnValue(true),
      reserveProviderRound: vi.fn().mockReturnValue({ accepted: true }),
      markProviderRoundStarted: vi.fn().mockReturnValue(true),
      releaseProviderRound: vi.fn().mockReturnValue(true),
      recordProviderUsage: vi.fn(() => {
        throw new Error("simulated accounting write failure");
      }),
      closeRequest: vi.fn().mockReturnValue(true),
      snapshot: vi.fn(() => {
        throw new Error("not used");
      }),
    } as UsageAccounting;
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      usage,
      logger: captured.logger,
    });
    const responses: AgentRuntimeResponse[] = [];

    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Search for a current guide.",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    service.submit(
      {
        requestId: "44444444-4444-4444-8444-444444444444",
        playerUuid: "55555555-5555-4555-8555-555555555555",
        sessionId: null,
        module: "general",
        message: "Search again.",
      },
      (response) => responses.push(response),
    );

    expect(responses).toMatchObject([
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
      { type: "agent.error", payload: { code: "RUNTIME_INTERNAL_ERROR" } },
    ]);
    expect(adapter.generate).toHaveBeenCalledOnce();
    expect(usage.admitRequest).toHaveBeenCalledOnce();
    const logs = captured.lines.join("");
    expect(logs).toContain('"code":"USAGE_ACCOUNTING_FAILED"');
    expect(logs).toContain(`"requestId":"${REQUEST_ID}"`);
    expect(logs).toContain("simulated accounting write failure");
  });

  it("logs a failed transport response instead of surfacing the rejection", async () => {
    const captured = capturingLogger();
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "client answer" }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registry(),
      logger: captured.logger,
    });

    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "Search for a current guide.",
      },
      () => {
        throw new Error("connector socket closed");
      },
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const logs = captured.lines.join("");
    expect(logs).toContain('"code":"TRANSPORT_RESPONSE_FAILED"');
    expect(logs).toContain(`"requestId":"${REQUEST_ID}"`);
    expect(logs).toContain("connector socket closed");
  });
});

describe("client build preview flow", () => {
  const ALL_CLIENT_TOOLS = [
    "game.resource.search",
    "game.process.lookup",
    "game.process.uses",
    "game.process.plan",
    "game.inventory.snapshot",
    "game.player.context.read",
    "project.list",
    "project.read",
    "project.create",
    "project.update",
    "build.preview.create",
  ];
  const PROJECT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const PREVIEW_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";

  function localProjectTools(): {
    readonly localTools: ProjectToolExecutor;
    readonly projects: SqliteProjectRepository;
  } {
    const database = new DatabaseSync(":memory:");
    migrateRuntimeStorage(database, "2026-07-17T00:00:00.000Z");
    const ids = [PROJECT_ID, "55555555-5555-4555-8555-555555555555"];
    const projects = new SqliteProjectRepository(database, {
      randomUuid: () => ids.shift() ?? "66666666-6666-4666-8666-666666666666",
    });
    return { localTools: new ProjectToolExecutor(projects), projects };
  }

  function previewArguments(): Readonly<Record<string, unknown>> {
    return {
      projectId: PROJECT_ID,
      revision: 1,
      operation: "create",
      dimension: "minecraft:overworld",
      origin: { x: 0, y: -60, z: 0 },
      rotation: 0,
      mirror: "NONE",
      shapes: [
        {
          bounds: { min: { x: 0, y: -60, z: 0 }, max: { x: 4, y: -53, z: 4 } },
          pattern: "solid",
          blockState: "minecraft:stone",
        },
      ],
    };
  }

  function previewResult(): Readonly<Record<string, unknown>> {
    return {
      previewId: PREVIEW_ID,
      projectId: PROJECT_ID,
      revision: 1,
      dimension: "minecraft:overworld",
      bounds: { min: { x: 0, y: -60, z: 0 }, max: { x: 4, y: -53, z: 4 } },
      baseRegionHash: "a".repeat(64),
      changeSetHash: "b".repeat(64),
      targetBlockCount: 200,
      changeCount: 200,
      difference: { added: 200, replaced: 0, removed: 0 },
      previewStatus: "client_validated",
      worldWriteEnabled: false,
    };
  }

  function playerContextResult(): Readonly<Record<string, unknown>> {
    return {
      dimension: "minecraft:overworld",
      position: { x: 8, y: -60, z: 8 },
      yaw: 90.0,
      pitch: -5.5,
    };
  }

  function buildAdapter(): ModelProvider {
    let round = 0;
    return {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(async () => {
        round += 1;
        if (round === 1) {
          return {
            type: "tool_call" as const,
            providerCallId: "context",
            providerName: "game_player_context_read",
            arguments: {},
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 2) {
          return {
            type: "tool_call" as const,
            providerCallId: "create",
            providerName: "project_create",
            arguments: {
              name: "Stone tower",
              summary: "A 5x8x5 solid stone tower.",
              goals: ["build the tower"],
              constraints: [],
            },
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 3) {
          return {
            type: "tool_call" as const,
            providerCallId: "read",
            providerName: "project_read",
            arguments: { projectId: PROJECT_ID },
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 4) {
          return {
            type: "tool_call" as const,
            providerCallId: "preview",
            providerName: "build_preview_create",
            arguments: previewArguments(),
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        return { type: "final" as const, fallbackText: "The tower preview is ready." };
      }),
    };
  }

  async function respondToPreviewCall(
    service: ClientAgentRequestService,
    responses: readonly AgentRuntimeResponse[],
  ): Promise<void> {
    await vi.waitFor(() => expect(responses.length).toBeGreaterThan(0));
    const contextCall = responses[0];
    if (contextCall?.type !== "tool.call") throw new Error("missing player context Tool call");
    expect(contextCall.payload.tool).toBe("game.player.context.read");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: contextCall.payload.toolCallId,
        sessionId: contextCall.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: contextCall.payload.tool,
        sequence: contextCall.payload.sequence,
        status: "succeeded",
        source: "client_context",
        trust: "client_visible",
        result: playerContextResult(),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(responses.length).toBeGreaterThan(1));
    const call = responses[1];
    if (call?.type !== "tool.call") throw new Error("missing preview Tool call");
    expect(call.payload.tool).toBe("build.preview.create");
    expect(call.payload.arguments).toMatchObject({
      projectId: PROJECT_ID,
      revision: 1,
      shapes: [{ pattern: "solid", blockState: "minecraft:stone" }],
    });
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_context",
        trust: "client_visible",
        result: previewResult(),
        error: null,
      }),
    ).toBe("accepted");
  }

  it("creates a preview through the multi-round project flow and forces an honest completion", async () => {
    const { localTools, projects } = localProjectTools();
    const adapter = buildAdapter();
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(ALL_CLIENT_TOOLS),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "保存项目：建一座 5x8x5 的石塔，并给我投影预览。",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await respondToPreviewCall(service, responses);
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing completion");
    expect(completion.payload.fallbackText).toContain(PREVIEW_ID);
    expect(completion.payload.fallbackText).toContain(PROJECT_ID);
    expect(completion.payload.fallbackText).toContain("未改动任何方块");
    expect(completion.payload.fallbackText).not.toContain("The tower preview is ready.");
    expect(adapter.generate).toHaveBeenCalledTimes(5);
    const stored = projects.findOwned(PROJECT_ID, {
      serverId: "client-installation",
      playerUuid: SUBJECT_ID,
    });
    expect(stored?.name).toBe("Stone tower");
    expect(stored?.revision).toBe(1);
    // Runtime-local project rounds never reach the connector.
    expect(
      responses.filter(
        (response) => response.type === "tool.call" && response.payload.tool.startsWith("project."),
      ),
    ).toHaveLength(0);
  });

  it("permits project creation from build intent without explicit save verbs", async () => {
    const { localTools } = localProjectTools();
    const adapter = buildAdapter();
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(ALL_CLIENT_TOOLS),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "帮我建一座石塔并投影预览",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await respondToPreviewCall(service, responses);
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    expect(responses.at(-1)?.type).toBe("agent.complete");
  });

  it("feeds an unverified preview back so the model can bind the project and retry", async () => {
    const { localTools, projects } = localProjectTools();
    let round = 0;
    const toolOutputs: string[] = [];
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(async (request: ModelGenerationRequest) => {
        round += 1;
        if (request.toolOutput !== undefined) toolOutputs.push(request.toolOutput.output);
        if (round === 1) {
          return {
            type: "tool_call" as const,
            providerCallId: "preview-early",
            providerName: "build_preview_create",
            arguments: previewArguments(),
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 2) {
          return {
            type: "tool_call" as const,
            providerCallId: "create",
            providerName: "project_create",
            arguments: {
              name: "Stone tower",
              summary: "A 5x8x5 solid stone tower.",
              goals: ["build the tower"],
              constraints: [],
            },
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 3) {
          return {
            type: "tool_call" as const,
            providerCallId: "read",
            providerName: "project_read",
            arguments: { projectId: PROJECT_ID },
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        if (round === 4) {
          return {
            type: "tool_call" as const,
            providerCallId: "preview",
            providerName: "build_preview_create",
            arguments: previewArguments(),
            continuation: { provider: "openai" as const, items: [] },
          };
        }
        return { type: "final" as const, fallbackText: "Preview ready." };
      }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(ALL_CLIENT_TOOLS),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "保存项目：直接投影预览石塔。",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(responses.length).toBeGreaterThan(0));
    const call = responses[0];
    if (call?.type !== "tool.call") throw new Error("missing preview Tool call");
    expect(call.payload.tool).toBe("build.preview.create");
    expect(
      service.acceptToolResult(REQUEST_ID, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: SUBJECT_ID,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "client_context",
        trust: "client_visible",
        result: previewResult(),
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    // The first premature preview was returned as a recoverable failure, and the model then
    // walked project_create -> project_read -> preview to success.
    expect(toolOutputs[0]).toContain("PREVIEW_PROJECT_UNVERIFIED");
    expect(
      projects.findOwned(PROJECT_ID, {
        serverId: "client-installation",
        playerUuid: SUBJECT_ID,
      }),
    ).toBeDefined();
    const completion = responses.at(-1);
    if (completion?.type !== "agent.complete") throw new Error("missing completion");
    expect(completion.payload.fallbackText).toContain(PREVIEW_ID);
  });

  it("permits project creation when a direct build request carries a leftover question", async () => {
    const { localTools } = localProjectTools();
    const adapter = buildAdapter();
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(ALL_CLIENT_TOOLS),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        // A draft question left in the box makes the whole typed message carry a trailing
        // question; the direct build request at the start still permits the local project.
        message: "这个物品保存项目：在我身旁建一座两层小楼并给我投影预览。怎么获得？",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await respondToPreviewCall(service, responses);
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    expect(responses.at(-1)?.type).toBe("agent.complete");
  });

  it("rejects project persistence for plain questions", async () => {
    const { localTools } = localProjectTools();
    const adapter: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "tool_call",
        providerCallId: "create",
        providerName: "project_create",
        arguments: {
          name: "Stone tower",
          summary: "A 5x8x5 solid stone tower.",
          goals: [],
          constraints: [],
        },
        continuation: { provider: "openai", items: [] },
      }),
    };
    const service = new ClientAgentRequestService({
      provider: adapter,
      config: config(),
      tools: await registryWith(ALL_CLIENT_TOOLS),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST_ID,
        playerUuid: SUBJECT_ID,
        sessionId: null,
        module: "general",
        message: "How do I build a stone tower?",
        webAuthorization: "off",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    const terminal = responses.at(-1);
    if (terminal?.type !== "agent.error") throw new Error("missing error terminal");
    expect(terminal.payload.code).toBe("TOOL_REJECTED");
  });
});
