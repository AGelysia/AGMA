import { describe, expect, it, vi } from "vitest";

import type { EvidenceClaim } from "../src/evidence/evidence-normalizer.js";
import type { WebEvidenceCollector } from "../src/evidence/web-evidence-pipeline.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelProvider,
} from "../src/providers/model-provider.js";
import {
  ClientAgentRequestService,
  type ClientAgentServiceConfig,
} from "../src/requests/client-agent-request-service.js";
import type { AgentRuntimeResponse } from "../src/requests/agent-request-service.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { ClientToolRegistry } from "../src/tools/client-tool-registry.js";
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
      generate: vi.fn(async (request) => {
        generated.push(request);
        events.push(`generate:${String(generated.length)}`);
        if (generated.length === 1) {
          return {
            type: "tool_call",
            providerCallId: "local-search",
            providerName: "game_resource_search",
            arguments: {
              query: "iron ingot",
              limit: 5,
            },
            continuation: { provider: "openai", items: [] },
          };
        }
        return { type: "final", fallbackText: `[${evidence.claimId}]` };
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

    expect(events).toEqual(["generate:1", "collect", "generate:2"]);
    expect(generated[0]?.input.at(-1)?.content).not.toContain(evidence.claimId);
    expect(generated[1]).toMatchObject({ tools: [] });
    expect(generated[1]?.input.at(-1)?.content).toContain(evidence.claimId);
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
});
