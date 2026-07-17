import { describe, expect, it, vi } from "vitest";

import { ModelGenerationError } from "../src/providers/model-provider.js";
import { OpenAiResponsesProvider } from "../src/providers/openai-responses-provider.js";

const API_KEY = "private-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("OpenAI Responses provider", () => {
  it("checks the configured model without making a billable generation", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(jsonResponse({ id: "gpt-test", object: "model" }));
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    await expect(
      provider.check({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    expect(fetchImplementation).toHaveBeenCalledOnce();
    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.openai.com/v1/models/gpt-test");
    expect(init.method).toBe("GET");
    expect(init.redirect).toBe("error");
    expect(init.headers).toEqual({ Authorization: `Bearer ${API_KEY}` });
  });

  it("uses a configured Responses-compatible base URL without following redirects", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(jsonResponse({ id: "vendor/model", object: "model" }));
    const provider = new OpenAiResponsesProvider({
      baseUrl: "https://models.example.test/openai/v1/",
      fetch: fetchImplementation,
    });

    await expect(
      provider.check({
        provider: "openai",
        model: "vendor/model",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    expect(fetchImplementation.mock.calls[0]?.[0]).toBe(
      "https://models.example.test/openai/v1/models/vendor%2Fmodel",
    );
    expect((fetchImplementation.mock.calls[0]?.[1] as RequestInit).redirect).toBe("error");
  });

  it("keeps custom-endpoint HTTP failure billability conservative", async () => {
    const provider = new OpenAiResponsesProvider({
      baseUrl: "https://models.example.test/v1",
      fetch: vi.fn().mockResolvedValue(new Response(null, { status: 500 })),
    });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "private player prompt" }],
        tools: [],
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({
      code: "PROVIDER_UNAVAILABLE",
      accountingDisposition: "BILLABILITY_UNKNOWN",
    });
  });

  it("creates a non-stored text-only response and returns bounded fallback text", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        id: "resp_test",
        status: "completed",
        output: [
          {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "  Place four planks.  " }],
          },
        ],
        usage: { input_tokens: 5, output_tokens: 4, total_tokens: 9 },
      }),
    );
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "private player prompt" }],
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({
      type: "final",
      fallbackText: "Place four planks.",
      usage: { inputTokens: 5, outputTokens: 4 },
    });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.openai.com/v1/responses");
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(body).toMatchObject({
      model: "gpt-test",
      input: [{ role: "user", content: "private player prompt" }],
      instructions: "trusted module prompt",
      max_output_tokens: 1024,
      store: false,
      tools: [],
      tool_choice: "none",
      parallel_tool_calls: false,
      include: ["reasoning.encrypted_content"],
    });
    expect(init.headers).toEqual({
      Authorization: `Bearer ${API_KEY}`,
      "Content-Type": "application/json",
    });
  });

  it.each([
    [401, "MODEL_AUTHENTICATION_FAILED"],
    [403, "MODEL_AUTHENTICATION_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "MODEL_RATE_LIMITED"],
    [500, "PROVIDER_UNAVAILABLE"],
  ] as const)("maps HTTP %s without exposing the response body", async (status, code) => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValue(new Response("private upstream detail", { status }));
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    const operation = provider.generate({
      provider: "openai",
      model: "gpt-test",
      apiKey: API_KEY,
      instructions: "trusted module prompt",
      input: [{ role: "user", content: "private player prompt" }],
      maxOutputTokens: 1024,
      signal: new AbortController().signal,
    });
    await expect(operation).rejects.toMatchObject({ code });
    await expect(operation).rejects.not.toThrow(/private upstream detail/u);
  });

  it("rejects empty, malformed, and oversized successful responses", async () => {
    const cases = [
      jsonResponse({ output: [] }),
      jsonResponse({
        output: [
          {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "\ud800" }],
          },
        ],
      }),
      new Response("not-json", { status: 200 }),
      new Response("{}", {
        status: 200,
        headers: { "Content-Length": String(1024 * 1024 + 1) },
      }),
    ];

    for (const response of cases) {
      const provider = new OpenAiResponsesProvider({
        fetch: vi.fn().mockResolvedValue(response),
      });
      await expect(
        provider.generate({
          provider: "openai",
          model: "gpt-test",
          apiKey: API_KEY,
          instructions: "trusted module prompt",
          input: [{ role: "user", content: "private player prompt" }],
          maxOutputTokens: 1024,
          signal: new AbortController().signal,
        }),
      ).rejects.toBeInstanceOf(ModelGenerationError);
    }
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
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });
    const operation = provider.generate({
      provider: "openai",
      model: "gpt-test",
      apiKey: API_KEY,
      instructions: "trusted module prompt",
      input: [{ role: "user", content: "private player prompt" }],
      maxOutputTokens: 1024,
      signal: controller.signal,
    });

    controller.abort(new Error("cancelled by request owner"));
    await expect(operation).rejects.toThrow("cancelled by request owner");
  });

  it("classifies malformed local continuation state as not billable before fetch", async () => {
    const fetchImplementation = vi.fn();
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "private player prompt" }],
        tools: [],
        continuation: { provider: "anthropic", items: [] },
        toolOutput: { providerCallId: "call-1", output: "{}" },
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "private player prompt" }],
        tools: [],
        continuation: {
          provider: "openai",
          items: [
            {
              type: "function_call",
              call_id: "call-1",
              name: "server_info_read",
              arguments: "{}",
              status: "completed",
            },
          ],
        },
        toolOutput: {
          providerCallId: "call-1",
          output: "x".repeat(1024 * 1024 + 1),
        },
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
      accountingDisposition: "NOT_BILLABLE",
    });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it("continues two strict function calls with bounded call outputs before the final text", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          status: "completed",
          output: [
            {
              id: "msg_before_call",
              type: "message",
              role: "assistant",
              content: [{ type: "output_text", text: "I will check the live server." }],
            },
            {
              id: "reasoning_1",
              type: "reasoning",
              encrypted_content: "opaque-reasoning-state",
              summary: [],
            },
            {
              id: "call_item_1",
              type: "function_call",
              call_id: "provider-call-1",
              name: "server_info_read",
              arguments: "{}",
              status: "completed",
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          status: "completed",
          output: [
            {
              id: "call_item_2",
              type: "function_call",
              call_id: "provider-call-2",
              name: "server_info_read",
              arguments: "{}",
              status: "completed",
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          status: "completed",
          output: [
            {
              type: "message",
              role: "assistant",
              content: [{ type: "output_text", text: "The server is ready." }],
            },
          ],
        }),
      );
    const provider = new OpenAiResponsesProvider({ fetch: fetchImplementation });
    const base = {
      provider: "openai" as const,
      model: "gpt-test",
      apiKey: API_KEY,
      instructions: "trusted module prompt",
      input: [{ role: "user" as const, content: "read the server twice" }],
      tools: [
        {
          id: "server.info.read",
          providerName: "server_info_read",
          description: "Read server info.",
          parameters: { type: "object", additionalProperties: false, maxProperties: 0 },
        },
      ],
      maxOutputTokens: 1024,
      signal: new AbortController().signal,
    };

    const first = await provider.generate(base);
    expect(first).toMatchObject({
      type: "tool_call",
      providerCallId: "provider-call-1",
      providerName: "server_info_read",
      arguments: {},
    });
    if (first.type !== "tool_call") {
      throw new Error("expected first tool call");
    }
    const second = await provider.generate({
      ...base,
      continuation: first.continuation,
      toolOutput: {
        providerCallId: first.providerCallId,
        output: '{"status":"succeeded","result":{"onlinePlayers":1}}',
      },
    });
    if (second.type !== "tool_call") {
      throw new Error("expected second tool call");
    }
    const final = await provider.generate({
      ...base,
      continuation: second.continuation,
      toolOutput: {
        providerCallId: second.providerCallId,
        output: '{"status":"succeeded","result":{"onlinePlayers":1}}',
      },
    });

    expect(final).toEqual({ type: "final", fallbackText: "The server is ready." });
    const secondBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as Record<string, unknown>;
    expect(secondBody["tools"]).toMatchObject([
      { type: "function", name: "server_info_read", strict: true },
    ]);
    expect(secondBody["tool_choice"]).toBe("auto");
    expect(secondBody["input"]).toMatchObject([
      { role: "user", content: "read the server twice" },
      { type: "message", role: "assistant" },
      { type: "reasoning", encrypted_content: "opaque-reasoning-state" },
      { type: "function_call", call_id: "provider-call-1" },
      { type: "function_call_output", call_id: "provider-call-1" },
    ]);
    const thirdInput = (
      JSON.parse(String((fetchImplementation.mock.calls[2]?.[1] as RequestInit).body)) as {
        input: unknown[];
      }
    ).input;
    expect(
      thirdInput.filter((item) => (item as { type?: string }).type === "function_call_output"),
    ).toHaveLength(2);
  });

  it("does not execute an incomplete provider function call", async () => {
    const provider = new OpenAiResponsesProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          status: "completed",
          output: [
            {
              type: "function_call",
              call_id: "provider-call-1",
              name: "server_info_read",
              arguments: "{}",
              status: "incomplete",
            },
          ],
        }),
      ),
    });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "server info" }],
        tools: [
          {
            id: "server.info.read",
            providerName: "server_info_read",
            description: "Read server info.",
            parameters: {
              type: "object",
              properties: {},
              required: [],
              additionalProperties: false,
            },
          },
        ],
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
  });

  it("does not publish partial text from an incomplete Responses result", async () => {
    const provider = new OpenAiResponsesProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          status: "incomplete",
          incomplete_details: { reason: "max_output_tokens" },
          output: [
            {
              type: "message",
              role: "assistant",
              content: [{ type: "output_text", text: "partial answer" }],
            },
          ],
        }),
      ),
    });

    await expect(
      provider.generate({
        provider: "openai",
        model: "gpt-test",
        apiKey: API_KEY,
        instructions: "trusted module prompt",
        input: [{ role: "user", content: "private player prompt" }],
        tools: [],
        maxOutputTokens: 1024,
        signal: new AbortController().signal,
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
  });
});
