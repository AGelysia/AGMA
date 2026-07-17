import { describe, expect, it, vi } from "vitest";

import { OpenAiChatCompletionsProvider } from "../src/providers/openai-chat-completions-provider.js";

const API_KEY = "private-chat-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function request(overrides: Readonly<Record<string, unknown>> = {}) {
  return {
    provider: "deepseek" as const,
    model: "deepseek-test",
    apiKey: API_KEY,
    instructions: "trusted module prompt",
    input: [{ role: "user" as const, content: "private player prompt" }],
    tools: [],
    maxOutputTokens: 1024,
    signal: new AbortController().signal,
    ...overrides,
  };
}

describe("OpenAI-compatible Chat Completions provider", () => {
  it("checks an exact DeepSeek model through the non-billable model list", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        object: "list",
        data: [{ id: "deepseek-test", object: "model", owned_by: "deepseek" }],
      }),
    );
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: fetchImplementation,
    });

    await expect(
      provider.check({
        provider: "deepseek",
        model: "deepseek-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.deepseek.com/models");
    expect(init).toMatchObject({
      method: "GET",
      redirect: "error",
      headers: { Authorization: `Bearer ${API_KEY}` },
    });
  });

  it("uses a configured compatible base URL and returns bounded text with usage", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        choices: [
          {
            index: 0,
            finish_reason: "stop",
            message: { role: "assistant", content: "  Use lime wool.  " },
          },
        ],
        usage: { prompt_tokens: 12, completion_tokens: 5, total_tokens: 17 },
      }),
    );
    const provider = new OpenAiChatCompletionsProvider({
      provider: "openai-compatible",
      baseUrl: "https://models.example.test/api/v1",
      fetch: fetchImplementation,
    });

    await expect(
      provider.generate({
        ...request(),
        provider: "openai-compatible",
        model: "vendor/model",
      }),
    ).resolves.toEqual({
      type: "final",
      fallbackText: "Use lime wool.",
      usage: { inputTokens: 12, outputTokens: 5 },
    });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://models.example.test/api/v1/chat/completions");
    expect(init.redirect).toBe("error");
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(body).toMatchObject({
      model: "vendor/model",
      messages: [
        { role: "system", content: "trusted module prompt" },
        { role: "user", content: "private player prompt" },
      ],
      max_tokens: 1024,
      stream: false,
      tools: [],
      tool_choice: "none",
    });
    expect(body).not.toHaveProperty("thinking");
  });

  it("disables DeepSeek thinking and continues serial function calls", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          choices: [
            {
              index: 0,
              finish_reason: "tool_calls",
              message: {
                role: "assistant",
                content: "I will inspect the server.",
                reasoning_content: "must not be retained",
                tool_calls: [
                  {
                    id: "call-1",
                    type: "function",
                    function: { name: "server_info_read", arguments: "{}" },
                  },
                ],
              },
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          choices: [
            {
              index: 0,
              finish_reason: "stop",
              message: { role: "assistant", content: "Server is ready." },
            },
          ],
        }),
      );
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: fetchImplementation,
    });
    const base = request({
      tools: [
        {
          id: "server.info.read",
          providerName: "server_info_read",
          description: "Read server information.",
          parameters: { type: "object", properties: {}, additionalProperties: false },
        },
      ],
    });

    const first = await provider.generate(base);
    expect(first).toMatchObject({
      type: "tool_call",
      providerCallId: "call-1",
      providerName: "server_info_read",
      arguments: {},
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
    ).resolves.toEqual({ type: "final", fallbackText: "Server is ready." });

    const firstBody = JSON.parse(
      String((fetchImplementation.mock.calls[0]?.[1] as RequestInit).body),
    ) as Record<string, unknown>;
    expect(firstBody["thinking"]).toEqual({ type: "disabled" });
    expect(firstBody["tools"]).toEqual([
      {
        type: "function",
        function: {
          name: "server_info_read",
          description: "Read server information.",
          parameters: { type: "object", properties: {}, additionalProperties: false },
        },
      },
    ]);
    const secondBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as { messages: Array<Record<string, unknown>> };
    expect(secondBody.messages.slice(-2)).toEqual([
      {
        role: "assistant",
        content: "I will inspect the server.",
        tool_calls: [
          {
            id: "call-1",
            type: "function",
            function: { name: "server_info_read", arguments: "{}" },
          },
        ],
      },
      {
        role: "tool",
        tool_call_id: "call-1",
        content: '{"status":"succeeded","result":{"onlinePlayers":1}}',
      },
    ]);
    expect(JSON.stringify(secondBody)).not.toContain("must not be retained");
  });

  it("rejects mismatched provider state, unknown tools, and parallel calls", async () => {
    const unknownTool = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          choices: [
            {
              index: 0,
              finish_reason: "tool_calls",
              message: {
                role: "assistant",
                content: null,
                tool_calls: [
                  {
                    id: "call-1",
                    type: "function",
                    function: { name: "not_registered", arguments: "{}" },
                  },
                ],
              },
            },
          ],
        }),
      ),
    });
    await expect(
      unknownTool.generate(
        request({
          tools: [
            {
              id: "server.info.read",
              providerName: "server_info_read",
              description: "Read server information.",
              parameters: { type: "object" },
            },
          ],
        }),
      ),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });

    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn(),
    });
    await expect(
      provider.generate({ ...request(), provider: "openai-compatible" }),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    await expect(
      provider.generate({
        ...request(),
        continuation: {
          provider: "openai",
          items: [],
        },
        toolOutput: { providerCallId: "call-1", output: "{}" },
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(
      provider.generate({
        ...request(),
        continuation: {
          provider: "deepseek",
          items: [{ role: "tool", tool_call_id: "call-1", content: "{}" }],
        },
        toolOutput: { providerCallId: "call-1", output: "{}" },
      }),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    await expect(
      provider.generate({
        ...request(),
        continuation: {
          provider: "deepseek",
          items: [
            {
              role: "assistant",
              content: null,
              tool_calls: [
                {
                  id: "call-1",
                  type: "function",
                  function: { name: "server_info_read", arguments: "{}" },
                },
              ],
            },
          ],
        },
        toolOutput: {
          providerCallId: "call-1",
          output: "x".repeat(1024 * 1024 + 1),
        },
      }),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
  });

  it("never executes a tool call reported with a truncated finish reason", async () => {
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          choices: [
            {
              index: 0,
              finish_reason: "length",
              message: {
                role: "assistant",
                content: null,
                tool_calls: [
                  {
                    id: "call-truncated",
                    type: "function",
                    function: { name: "server_info_read", arguments: "{}" },
                  },
                ],
              },
            },
          ],
        }),
      ),
    });

    await expect(
      provider.generate(
        request({
          tools: [
            {
              id: "server.info.read",
              providerName: "server_info_read",
              description: "Read server information.",
              parameters: { type: "object" },
            },
          ],
        }),
      ),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
  });

  it.each([
    [401, "MODEL_AUTHENTICATION_FAILED"],
    [403, "MODEL_AUTHENTICATION_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "MODEL_RATE_LIMITED"],
    [500, "PROVIDER_UNAVAILABLE"],
  ] as const)("maps HTTP %s without exposing private upstream detail", async (status, code) => {
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn().mockResolvedValue(new Response("private upstream detail", { status })),
    });
    const operation = provider.generate(request());
    await expect(operation).rejects.toMatchObject({ code });
    await expect(operation).rejects.not.toThrow(/private upstream detail/u);
  });

  it("rejects malformed, empty, multiple-choice, and oversized responses", async () => {
    const cases = [
      jsonResponse({ choices: [] }),
      jsonResponse({
        choices: [
          {
            index: 0,
            message: { role: "assistant", content: "missing completion marker" },
          },
        ],
      }),
      jsonResponse({
        choices: [
          { index: 0, finish_reason: "stop", message: { role: "assistant", content: "one" } },
          { index: 1, finish_reason: "stop", message: { role: "assistant", content: "two" } },
        ],
      }),
      new Response("x".repeat(1024 * 1024 + 1), {
        status: 200,
        headers: { "Content-Length": String(1024 * 1024 + 1) },
      }),
    ];
    for (const response of cases) {
      const provider = new OpenAiChatCompletionsProvider({
        provider: "deepseek",
        fetch: vi.fn().mockResolvedValue(response),
      });
      await expect(provider.generate(request())).rejects.toMatchObject({
        code: "MODEL_RESPONSE_INVALID",
      });
    }
  });

  it("propagates caller cancellation without wrapping its reason", async () => {
    const controller = new AbortController();
    const fetchImplementation = vi.fn(
      async (_input: string | URL | Request, init?: RequestInit): Promise<Response> =>
        await new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), {
            once: true,
          });
        }),
    );
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: fetchImplementation,
    });
    const operation = provider.generate(request({ signal: controller.signal }));
    controller.abort(new Error("cancelled by request owner"));
    await expect(operation).rejects.toThrow("cancelled by request owner");
  });

  it("requires a base URL for the generic compatible profile", () => {
    expect(() => new OpenAiChatCompletionsProvider({ provider: "openai-compatible" })).toThrow(
      TypeError,
    );
  });

  it("rejects an oversized request before sending private content", async () => {
    const fetchImplementation = vi.fn();
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: fetchImplementation,
    });

    await expect(
      provider.generate(request({ instructions: "x".repeat(1024 * 1024) })),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it("normalizes an interrupted provider response stream without leaking its error", async () => {
    const body = new ReadableStream<Uint8Array>({
      pull: (controller) => controller.error(new Error("private upstream stream detail")),
    });
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn().mockResolvedValue(new Response(body, { status: 200 })),
    });

    const operation = provider.generate(request());
    await expect(operation).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(operation).rejects.not.toThrow(/private upstream stream detail/u);
  });

  it("preserves caller cancellation while a provider response body is being read", async () => {
    const controller = new AbortController();
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined;
    const body = new ReadableStream<Uint8Array>({
      start: (current) => {
        streamController = current;
      },
    });
    const provider = new OpenAiChatCompletionsProvider({
      provider: "deepseek",
      fetch: vi.fn().mockResolvedValue(new Response(body, { status: 200 })),
    });
    const operation = provider.generate(request({ signal: controller.signal }));

    await Promise.resolve();
    controller.abort(new Error("cancelled while reading"));
    streamController?.error(new Error("private aborted stream detail"));
    await expect(operation).rejects.toThrow("cancelled while reading");
  });
});
