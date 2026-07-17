import { DatabaseSync } from "node:sqlite";

import { describe, expect, it, vi } from "vitest";

import type { RuntimeConfig } from "../src/config/runtime-config.js";
import { MarkdownKnowledgeIndex } from "../src/knowledge/markdown-index.js";
import type {
  ModelGenerationRequest,
  ModelGenerationResult,
  ModelProvider,
} from "../src/providers/model-provider.js";
import {
  AgentRequestService,
  type AgentRuntimeResponse,
} from "../src/requests/agent-request-service.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { SqliteProjectRepository } from "../src/storage/project-repository.js";
import { LocalToolExecutor, type LocalToolExecution } from "../src/tools/local-tool-executor.js";
import { ToolRegistry } from "../src/tools/tool-registry.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";

const PLAYER = "11111111-1111-4111-8111-111111111111";
const REQUEST = "22222222-2222-4222-8222-222222222222";
const PROJECT = "33333333-3333-4333-8333-333333333333";

function buildPreviewArguments(revision = 3): Readonly<Record<string, unknown>> {
  return {
    projectId: PROJECT,
    revision,
    operation: "create",
    dimension: "minecraft:overworld",
    bounds: {
      min: { x: 0, y: 64, z: 0 },
      max: { x: 2, y: 66, z: 2 },
    },
    origin: { x: 0, y: 64, z: 0 },
    pattern: "hollow",
    blockState: "minecraft:stone",
    rotation: 0,
    mirror: "NONE",
  };
}

function config(): RuntimeConfig {
  return {
    configVersion: 2,
    server: { id: "test-server" },
    transport: {
      host: "127.0.0.1",
      port: 38_127,
      serverToken: "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    },
    model: {
      provider: "openai",
      apiKey: "test-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      model: "test-model",
      timeoutSeconds: 2,
      inputMicroUsdPerMillionTokens: 1_000_000,
      outputMicroUsdPerMillionTokens: 4_000_000,
    },
    storage: { sqlitePath: "./data/runtime.db" },
    logging: { directory: "./logs", level: "info" },
    limits: {
      maxConcurrentRequests: 1,
      maxQueuedRequests: 1,
      maxToolRounds: 4,
      maxContextMessages: 30,
      maxContextCharacters: 32_768,
      perPlayerCooldownSeconds: 0,
      dailyRequestsPerPlayer: 100,
      monthlyBudgetUsd: 10,
      providerRoundReservationMicroUsd: 50_000,
    },
    privacy: {
      storeConversations: false,
      retentionDays: 0,
      logMessageContent: false,
      logToolCalls: true,
    },
  };
}

function provider(
  generate: (request: ModelGenerationRequest) => Promise<ModelGenerationResult>,
): ModelProvider {
  return { check: vi.fn().mockResolvedValue({ ok: true }), generate: vi.fn(generate) };
}

describe("Runtime-local Tool execution", () => {
  it("persists a bounded project without emitting a Paper tool.call", async () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, "2026-07-13T00:00:00.000Z");
      const projectIds = [
        "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444",
      ];
      const projects = new SqliteProjectRepository(database, {
        randomUuid: () => projectIds.shift() ?? "55555555-5555-4555-8555-555555555555",
      });
      const registry = new ToolRegistry(await SchemaRegistry.load());
      let generation = 0;
      let localOutput = "";
      const adapter = provider(async (request) => {
        generation += 1;
        if (generation === 1) {
          expect(request.tools.map((tool) => tool.id)).toContain("project.create");
          return {
            type: "tool_call",
            providerCallId: "provider-call-1",
            providerName: "project_create",
            arguments: {
              name: "Harbor",
              summary: "A compact trading harbor.",
              goals: ["Create three market stalls"],
              constraints: ["Keep the existing lighthouse"],
            },
            continuation: { provider: "openai", items: [] },
          };
        }
        localOutput = request.toolOutput?.output ?? "";
        return { type: "final", fallbackText: "Saved the Harbor project." };
      });
      const serviceIds = [
        "66666666-6666-4666-8666-666666666666",
        "77777777-7777-4777-8777-777777777777",
        "88888888-8888-4888-8888-888888888888",
      ];
      const service = new AgentRequestService({
        provider: adapter,
        config: config(),
        tools: registry,
        localTools: new LocalToolExecutor(new MarkdownKnowledgeIndex(), projects),
        now: () => Date.parse("2026-07-13T00:00:00.000Z"),
        randomUuid: () => serviceIds.shift() ?? "99999999-9999-4999-8999-999999999999",
      });
      const responses: AgentRuntimeResponse[] = [];
      service.submit(
        {
          requestId: REQUEST,
          playerUuid: PLAYER,
          sessionId: null,
          module: "project",
          message: "Save this as my Harbor project.",
        },
        (response) => responses.push(response),
      );
      await vi.waitFor(() => expect(service.activeCount).toBe(0));

      expect(responses.some((response) => response.type === "tool.call")).toBe(false);
      expect(responses.at(-1)).toMatchObject({
        type: "agent.complete",
        payload: { fallbackText: "Saved the Harbor project." },
      });
      expect(localOutput).toContain('"source":"runtime_storage"');
      expect(localOutput).toContain('"outcome":"CREATED"');
      expect(
        projects.listOwned({ serverId: "test-server", playerUuid: PLAYER }).projects,
      ).toHaveLength(1);
    } finally {
      database.close();
    }
  });

  it.each([
    ["List my current projects.", "project_create"],
    ["How do I create a project?", "project_create"],
    ["Do not save this plan as a project.", "project_create"],
    ["If I asked you to create a project, what would happen?", "project_create"],
    ["What should I change in my project?", "project_update"],
    ["不要把这个计划保存为项目。", "project_create"],
    ["这个项目应该怎么修改？", "project_update"],
  ])(
    "does not mutate project storage without direct imperative intent: %s",
    async (message, tool) => {
      const execute = vi.fn<LocalToolExecution["execute"]>();
      const service = new AgentRequestService({
        provider: provider(async () => ({
          type: "tool_call",
          providerCallId: "provider-mutation-call",
          providerName: tool,
          arguments:
            tool === "project_create"
              ? {
                  name: "Harbor",
                  summary: "A compact trading harbor.",
                  goals: [],
                  constraints: [],
                }
              : {
                  projectId: "77777777-7777-4777-8777-777777777777",
                  expectedRevision: 1,
                  name: "Harbor",
                  summary: "A compact trading harbor.",
                  goals: [],
                  constraints: [],
                },
          continuation: { provider: "openai", items: [] },
        })),
        config: config(),
        tools: new ToolRegistry(await SchemaRegistry.load()),
        localTools: { execute },
      });
      const responses: AgentRuntimeResponse[] = [];
      service.submit(
        {
          requestId: REQUEST,
          playerUuid: PLAYER,
          sessionId: null,
          module: "project",
          message,
        },
        (response) => responses.push(response),
      );
      await vi.waitFor(() => expect(service.activeCount).toBe(0));

      expect(execute).not.toHaveBeenCalled();
      expect(responses.at(-1)).toMatchObject({
        type: "agent.error",
        payload: { code: "TOOL_REJECTED" },
      });
    },
  );

  it("passes cancellation to a pending local Tool and never creates a Paper call", async () => {
    let signal: AbortSignal | undefined;
    const localTools: LocalToolExecution = {
      execute: (call) => {
        signal = call.signal;
        return new Promise((resolve, reject) => {
          call.signal.addEventListener("abort", () => reject(call.signal.reason), { once: true });
        });
      },
    };
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-call-1",
      providerName: "server_docs_search",
      arguments: { query: "rules" },
      continuation: { provider: "openai", items: [] },
    }));
    const service = new AgentRequestService({
      provider: adapter,
      config: config(),
      tools: new ToolRegistry(await SchemaRegistry.load()),
      localTools,
      randomUuid: (() => {
        const ids = [
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        ];
        return () => ids.shift() ?? "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
      })(),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST,
        playerUuid: PLAYER,
        sessionId: null,
        module: "guide",
        message: "What are the rules?",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(signal).toBeDefined());
    expect(service.cancel(REQUEST, PLAYER)).toBe(true);
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(signal?.aborted).toBe(true);
    expect(responses).toEqual([]);
  });

  it("rejects a build preview that was not bound to an owned project read", async () => {
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-build-call",
      providerName: "build_preview_create",
      arguments: buildPreviewArguments(),
      continuation: { provider: "openai", items: [] },
    }));
    const service = new AgentRequestService({
      provider: adapter,
      config: config(),
      tools: new ToolRegistry(await SchemaRegistry.load()),
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST,
        playerUuid: PLAYER,
        sessionId: null,
        module: "build",
        message: "Preview my project.",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() => expect(service.activeCount).toBe(0));

    expect(responses.some((response) => response.type === "tool.call")).toBe(false);
    expect(responses.at(-1)).toMatchObject({
      type: "agent.error",
      payload: { code: "TOOL_REJECTED" },
    });
  });

  it("emits a Paper build call only for the exact owned project revision read in this request", async () => {
    let generation = 0;
    const adapter = provider(async () => {
      generation += 1;
      if (generation === 1) {
        return {
          type: "tool_call",
          providerCallId: "provider-project-call",
          providerName: "project_read",
          arguments: { projectId: PROJECT },
          continuation: { provider: "openai", items: [] },
        };
      }
      if (generation === 2) {
        return {
          type: "tool_call",
          providerCallId: "provider-build-call",
          providerName: "build_preview_create",
          arguments: buildPreviewArguments(),
          continuation: { provider: "openai", items: [] },
        };
      }
      return { type: "final", fallbackText: "I changed the world." };
    });
    const localTools: LocalToolExecution = {
      execute: async (call) => {
        expect(call.descriptor.id).toBe("project.read");
        return {
          status: "succeeded",
          source: "runtime_storage",
          trust: "verified",
          result: {
            project: {
              projectId: PROJECT,
              name: "Harbor",
              summary: "A compact trading harbor.",
              goals: ["Create three market stalls"],
              constraints: ["Keep the lighthouse"],
              status: "ACTIVE",
              revision: 3,
              createdAt: "2026-07-13T00:00:00.000Z",
              updatedAt: "2026-07-13T00:00:00.000Z",
            },
          },
          error: null,
        };
      },
    };
    const service = new AgentRequestService({
      provider: adapter,
      config: config(),
      tools: new ToolRegistry(await SchemaRegistry.load()),
      localTools,
    });
    const responses: AgentRuntimeResponse[] = [];
    service.submit(
      {
        requestId: REQUEST,
        playerUuid: PLAYER,
        sessionId: null,
        module: "build",
        message: "Preview my current Harbor project.",
      },
      (response) => responses.push(response),
    );
    await vi.waitFor(() =>
      expect(responses.some((response) => response.type === "tool.call")).toBe(true),
    );

    const call = responses.find((response) => response.type === "tool.call");
    expect(call).toMatchObject({
      type: "tool.call",
      payload: { tool: "build.preview.create", arguments: { projectId: PROJECT, revision: 3 } },
    });
    if (call?.type !== "tool.call") {
      throw new Error("missing Paper build call");
    }
    expect(
      service.acceptToolResult(REQUEST, {
        toolCallId: call.payload.toolCallId,
        sessionId: call.payload.sessionId,
        playerUuid: call.payload.playerUuid,
        tool: call.payload.tool,
        sequence: call.payload.sequence,
        status: "succeeded",
        source: "paper_api",
        trust: "authoritative",
        result: {
          previewId: "44444444-4444-4444-8444-444444444444",
          projectId: PROJECT,
          revision: 3,
          dimension: "minecraft:overworld",
          bounds: {
            min: { x: 0, y: 64, z: 0 },
            max: { x: 2, y: 66, z: 2 },
          },
          baseRegionHash: "a".repeat(64),
          changeSetHash: "b".repeat(64),
          targetBlockCount: 26,
          changeCount: 26,
          difference: { added: 26, replaced: 0, removed: 0 },
          previewStatus: "server_validated",
          worldWriteEnabled: false,
        },
        error: null,
      }),
    ).toBe("accepted");
    await vi.waitFor(() => expect(service.activeCount).toBe(0));
    expect(responses.at(-1)).toMatchObject({
      type: "agent.complete",
      payload: {
        fallbackText: expect.stringContaining(
          "No blocks were changed; loading the preview is an explicit client action.",
        ),
      },
    });
  });
});
