import { describe, expect, it, vi } from "vitest";

import { AnthropicMessagesProvider } from "../src/providers/anthropic-messages-provider.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
} from "../src/providers/model-provider.js";

const API_KEY = "private-anthropic-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function generationRequest(
  overrides: Partial<ModelGenerationRequest> = {},
): ModelGenerationRequest {
  return {
    provider: "anthropic",
    model: "claude-test",
    apiKey: API_KEY,
    instructions: "trusted module prompt",
    input: [{ role: "user", content: "private player prompt" }],
    tools: [],
    maxOutputTokens: 1024,
    signal: new AbortController().signal,
    ...overrides,
  };
}

function textMessage(text = "  Place four planks.  "): Record<string, unknown> {
  return {
    id: "msg_test",
    type: "message",
    role: "assistant",
    content: [{ type: "text", text }],
    stop_reason: "end_turn",
  };
}

const tool = {
  id: "server.info.read",
  providerName: "server_info_read",
  description: "Read server info.",
  parameters: {
    type: "object",
    properties: {},
    required: [],
    additionalProperties: false,
  },
} as const;

describe("Anthropic Messages provider", () => {
  it("checks the encoded configured model with Anthropic authentication headers", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(jsonResponse({ id: "claude-test-20260715", type: "model" }));
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    await expect(
      provider.check({
        provider: "anthropic",
        model: "claude/test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.anthropic.com/v1/models/claude%2Ftest");
    expect(init).toMatchObject({ method: "GET", redirect: "error" });
    expect(init.headers).toEqual({
      "x-api-key": API_KEY,
      "anthropic-version": "2023-06-01",
    });
  });

  it("uses an injected base URL for health and generation endpoints", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: "claude-test" }))
      .mockResolvedValueOnce(jsonResponse(textMessage()));
    const provider = new AnthropicMessagesProvider({
      baseUrl: "https://gateway.example.test/anthropic/v1/",
      fetch: fetchImplementation,
    });

    await provider.check({
      provider: "anthropic",
      model: "claude-test",
      apiKey: API_KEY,
      signal: new AbortController().signal,
    });
    await provider.generate(generationRequest());

    expect(fetchImplementation.mock.calls[0]?.[0]).toBe(
      "https://gateway.example.test/anthropic/v1/models/claude-test",
    );
    expect(fetchImplementation.mock.calls[1]?.[0]).toBe(
      "https://gateway.example.test/anthropic/v1/messages",
    );
  });

  it("keeps custom-endpoint HTTP failure billability conservative", async () => {
    const provider = new AnthropicMessagesProvider({
      baseUrl: "https://gateway.example.test/anthropic/v1",
      fetch: vi.fn().mockResolvedValue(new Response(null, { status: 500 })),
    });

    await expect(provider.generate(generationRequest())).rejects.toMatchObject({
      code: "PROVIDER_UNAVAILABLE",
      accountingDisposition: "BILLABILITY_UNKNOWN",
    });
  });

  it.each(["max_tokens", "model_context_window_exceeded"])(
    "rejects truncated text stopped by %s",
    async (stopReason) => {
      const provider = new AnthropicMessagesProvider({
        fetch: vi.fn().mockResolvedValue(
          jsonResponse({
            ...textMessage("partial response"),
            stop_reason: stopReason,
          }),
        ),
      });

      await expect(provider.generate(generationRequest())).rejects.toMatchObject({
        code: "MODEL_RESPONSE_INVALID",
      });
    },
  );

  it("rejects oversized tool arguments before local execution", async () => {
    const provider = new AnthropicMessagesProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          type: "message",
          role: "assistant",
          content: [
            {
              type: "tool_use",
              id: "call-large",
              name: "server_info_read",
              input: { value: "x".repeat(16 * 1024) },
            },
          ],
          stop_reason: "tool_use",
        }),
      ),
    });

    await expect(provider.generate(generationRequest({ tools: [tool] }))).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
    });
  });

  it("accepts the final tool result round after the Runtime closes the tool allowlist", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          type: "message",
          role: "assistant",
          content: [
            {
              type: "tool_use",
              id: "call-final",
              name: "server_info_read",
              input: {},
            },
          ],
          stop_reason: "tool_use",
        }),
      )
      .mockResolvedValueOnce(jsonResponse(textMessage("Final answer.")));
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });
    const first = await provider.generate(generationRequest({ tools: [tool] }));
    if (first.type !== "tool_call") {
      throw new Error("expected an Anthropic tool call");
    }

    await expect(
      provider.generate(
        generationRequest({
          tools: [],
          continuation: first.continuation,
          toolOutput: {
            providerCallId: first.providerCallId,
            output: '{"status":"succeeded","result":{}}',
          },
        }),
      ),
    ).resolves.toEqual({ type: "final", fallbackText: "Final answer." });
    const finalBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as Record<string, unknown>;
    expect(finalBody["tools"]).toBeUndefined();
    expect(finalBody["tool_choice"]).toBeUndefined();
  });

  it("sends system instructions and returns bounded text with usage", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        ...textMessage(),
        usage: {
          input_tokens: 5,
          output_tokens: 4,
          cache_creation_input_tokens: 0,
        },
      }),
    );
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    await expect(provider.generate(generationRequest())).resolves.toEqual({
      type: "final",
      fallbackText: "Place four planks.",
      usage: { inputTokens: 5, outputTokens: 4 },
    });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.anthropic.com/v1/messages");
    expect(init.headers).toEqual({
      "x-api-key": API_KEY,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    });
    expect(init).toMatchObject({ method: "POST", redirect: "error" });
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(body).toMatchObject({
      model: "claude-test",
      max_tokens: 1024,
      system: "trusted module prompt",
      messages: [{ role: "user", content: "private player prompt" }],
    });
    expect(body).not.toHaveProperty("tools");
    expect(body).not.toHaveProperty("tool_choice");
  });

  it.each([
    [401, "MODEL_AUTHENTICATION_FAILED"],
    [403, "MODEL_AUTHENTICATION_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "MODEL_RATE_LIMITED"],
    [500, "PROVIDER_UNAVAILABLE"],
    [529, "PROVIDER_UNAVAILABLE"],
  ] as const)("maps generation HTTP %s without exposing its body", async (status, code) => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(new Response("private upstream detail", { status }));
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    const operation = provider.generate(generationRequest());
    await expect(operation).rejects.toMatchObject({ code, accountingDisposition: "NOT_BILLABLE" });
    await expect(operation).rejects.not.toThrow(/private upstream detail/u);
  });

  it.each([
    [401, "PROVIDER_AUTH_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "PROVIDER_UNAVAILABLE"],
    [500, "PROVIDER_UNAVAILABLE"],
    [400, "MODEL_HEALTH_FAILED"],
  ] as const)("maps health HTTP %s to %s", async (status, code) => {
    const provider = new AnthropicMessagesProvider({
      fetch: vi.fn().mockResolvedValue(new Response("private detail", { status })),
    });

    await expect(
      provider.check({
        provider: "anthropic",
        model: "claude-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: false, code });
  });

  it("propagates cancellation without translating it into an availability failure", async () => {
    const controller = new AbortController();
    const fetchImplementation = vi.fn(
      async (_input: string | URL | Request, init?: RequestInit): Promise<Response> =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), {
            once: true,
          });
        }),
    );
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });
    const operation = provider.generate(generationRequest({ signal: controller.signal }));

    controller.abort(new Error("cancelled by request owner"));
    await expect(operation).rejects.toThrow("cancelled by request owner");
  });

  it("continues one tool use with a matching tool_result message", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          type: "message",
          role: "assistant",
          content: [
            { type: "text", text: "I will inspect the server." },
            {
              type: "tool_use",
              id: "provider-call-1",
              name: "server_info_read",
              input: {},
            },
          ],
          stop_reason: "tool_use",
          usage: { input_tokens: 10, output_tokens: 7 },
        }),
      )
      .mockResolvedValueOnce(jsonResponse(textMessage("The server is ready.")));
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });
    const base = generationRequest({ tools: [tool] });

    const first = await provider.generate(base);
    expect(first).toMatchObject({
      type: "tool_call",
      providerCallId: "provider-call-1",
      providerName: "server_info_read",
      arguments: {},
      usage: { inputTokens: 10, outputTokens: 7 },
      continuation: { provider: "anthropic" },
    });
    if (first.type !== "tool_call") {
      throw new Error("expected a tool call");
    }

    await expect(
      provider.generate({
        ...base,
        continuation: first.continuation,
        toolOutput: {
          providerCallId: first.providerCallId,
          output: '{"status":"succeeded","result":{"onlinePlayers":1}}',
        },
      }),
    ).resolves.toEqual({ type: "final", fallbackText: "The server is ready." });

    const firstBody = JSON.parse(
      String((fetchImplementation.mock.calls[0]?.[1] as RequestInit).body),
    ) as Record<string, unknown>;
    expect(firstBody["tools"]).toEqual([
      {
        name: "server_info_read",
        description: "Read server info.",
        input_schema: tool.parameters,
        strict: true,
      },
    ]);
    expect(firstBody["tool_choice"]).toEqual({
      type: "auto",
      disable_parallel_tool_use: true,
    });

    const secondBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as { messages: unknown[] };
    expect(secondBody.messages).toEqual([
      { role: "user", content: "private player prompt" },
      {
        role: "assistant",
        content: [
          { type: "text", text: "I will inspect the server." },
          {
            type: "tool_use",
            id: "provider-call-1",
            name: "server_info_read",
            input: {},
          },
        ],
      },
      {
        role: "user",
        content: [
          {
            type: "tool_result",
            tool_use_id: "provider-call-1",
            content: '{"status":"succeeded","result":{"onlinePlayers":1}}',
          },
        ],
      },
    ]);
  });

  it.each([
    {
      ...textMessage(),
      content: [],
    },
    {
      ...textMessage(),
      content: [{ type: "text", text: "\ud800" }],
    },
    {
      ...textMessage(),
      content: [{ type: "text", text: "no call" }],
      stop_reason: "tool_use",
    },
    {
      ...textMessage(),
      content: [{ type: "tool_use", id: "call-1", name: "server_info_read", input: [] }],
      stop_reason: "tool_use",
    },
    {
      ...textMessage(),
      content: [
        { type: "tool_use", id: "call-1", name: "server_info_read", input: {} },
        { type: "tool_use", id: "call-2", name: "server_info_read", input: {} },
      ],
      stop_reason: "tool_use",
    },
  ])("rejects an invalid successful Messages response", async (response) => {
    const provider = new AnthropicMessagesProvider({
      fetch: vi.fn().mockResolvedValue(jsonResponse(response)),
    });

    await expect(provider.generate(generationRequest({ tools: [tool] }))).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
    });
  });

  it("rejects malformed, oversized, and interrupted response bodies without private detail", async () => {
    const responses = [
      new Response("not-json", { status: 200 }),
      new Response("{}", {
        status: 200,
        headers: { "Content-Length": String(1024 * 1024 + 1) },
      }),
      new Response(
        new ReadableStream({
          pull(controller): void {
            controller.error(new Error("private stream failure"));
          },
        }),
        { status: 200 },
      ),
    ];

    for (const response of responses) {
      const provider = new AnthropicMessagesProvider({
        fetch: vi.fn().mockResolvedValue(response),
      });
      const operation = provider.generate(generationRequest());
      await expect(operation).rejects.toBeInstanceOf(ModelGenerationError);
      await expect(operation).rejects.not.toThrow(/private stream failure/u);
    }
  });

  it("rejects foreign provider requests before making a request", async () => {
    const fetchImplementation = vi.fn();
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    await expect(
      provider.check({
        provider: "openai",
        model: "claude-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: false, code: "PROVIDER_UNSUPPORTED" });
    await expect(
      provider.generate(generationRequest({ provider: "openai" })),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it.each([
    {
      provider: "openai",
      items: [
        {
          role: "assistant",
          content: [
            { type: "tool_use", id: "provider-call-1", name: "server_info_read", input: {} },
          ],
        },
      ],
    },
    {
      provider: "anthropic",
      items: [
        {
          role: "assistant",
          content: [
            { type: "tool_use", id: "provider-call-1", name: "server_info_read", input: {} },
          ],
          untrusted: "extra field",
        },
      ],
    },
    {
      provider: "anthropic",
      items: [
        {
          role: "assistant",
          content: [{ type: "text", text: "missing tool use" }],
        },
      ],
    },
  ] as const)("rejects a foreign or malformed continuation", async (continuation) => {
    const fetchImplementation = vi.fn();
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    await expect(
      provider.generate(
        generationRequest({
          tools: [tool],
          continuation,
          toolOutput: { providerCallId: "provider-call-1", output: "{}" },
        }),
      ),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it("rejects duplicate tool names and mismatched tool output identifiers", async () => {
    const fetchImplementation = vi.fn();
    const provider = new AnthropicMessagesProvider({ fetch: fetchImplementation });

    await expect(
      provider.generate(generationRequest({ tools: [tool, { ...tool, id: "duplicate" }] })),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(
      provider.generate(
        generationRequest({
          tools: [tool],
          continuation: {
            provider: "anthropic",
            items: [
              {
                role: "assistant",
                content: [
                  {
                    type: "tool_use",
                    id: "provider-call-1",
                    name: "server_info_read",
                    input: {},
                  },
                ],
              },
            ],
          },
          toolOutput: { providerCallId: "different-call", output: "{}" },
        }),
      ),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it("rejects a provider response that reuses a prior tool_use identifier", async () => {
    const provider = new AnthropicMessagesProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          type: "message",
          role: "assistant",
          content: [
            {
              type: "tool_use",
              id: "provider-call-1",
              name: "server_info_read",
              input: {},
            },
          ],
          stop_reason: "tool_use",
        }),
      ),
    });

    await expect(
      provider.generate(
        generationRequest({
          tools: [tool],
          continuation: {
            provider: "anthropic",
            items: [
              {
                role: "assistant",
                content: [
                  {
                    type: "tool_use",
                    id: "provider-call-1",
                    name: "server_info_read",
                    input: {},
                  },
                ],
              },
            ],
          },
          toolOutput: { providerCallId: "provider-call-1", output: "{}" },
        }),
      ),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
  });
});
