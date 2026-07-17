import { describe, expect, it, vi } from "vitest";

import { GeminiGenerateContentProvider } from "../src/providers/gemini-generate-content-provider.js";
import { ModelGenerationError } from "../src/providers/model-provider.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import { ToolRegistry } from "../src/tools/tool-registry.js";

const API_KEY = "private-gemini-api-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const toolRegistry = new ToolRegistry(await SchemaRegistry.load());

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function textResponse(text = "  Place four planks.  "): Response {
  return jsonResponse({
    candidates: [
      {
        content: { role: "model", parts: [{ text }] },
        finishReason: "STOP",
      },
    ],
    usageMetadata: {
      promptTokenCount: 5,
      candidatesTokenCount: 4,
      toolUsePromptTokenCount: 3,
      thoughtsTokenCount: 2,
      totalTokenCount: 14,
    },
  });
}

function baseRequest() {
  return {
    provider: "gemini" as const,
    model: "gemini-test",
    apiKey: API_KEY,
    instructions: "trusted module prompt",
    input: [
      { role: "user" as const, content: "private player prompt" },
      { role: "assistant" as const, content: "prior assistant reply" },
    ],
    tools: [],
    maxOutputTokens: 1024,
    signal: new AbortController().signal,
  };
}

const serverInfoTool = {
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

describe("Gemini generateContent provider", () => {
  it("checks the exact model and generateContent support without generating", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(
      jsonResponse({
        name: "models/gemini/test",
        supportedGenerationMethods: ["countTokens", "generateContent"],
      }),
    );
    const provider = new GeminiGenerateContentProvider({
      baseUrl: "https://gemini.example.test/custom/v1beta/",
      fetch: fetchImplementation,
    });

    await expect(
      provider.check({
        provider: "gemini",
        model: "gemini/test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://gemini.example.test/custom/v1beta/models/gemini%2Ftest");
    expect(init).toMatchObject({
      method: "GET",
      redirect: "error",
      headers: { "x-goog-api-key": API_KEY },
    });
  });

  it("accepts a provider-resolved model alias returned by the requested model endpoint", async () => {
    const provider = new GeminiGenerateContentProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          name: "models/gemini-resolved-version",
          supportedGenerationMethods: ["generateContent"],
        }),
      ),
    });

    await expect(
      provider.check({
        provider: "gemini",
        model: "gemini-latest",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: true });
  });

  it.each([
    [{ name: "models/gemini-test", supportedGenerationMethods: ["embedContent"] }],
    [{ name: "models/gemini-test" }],
  ])("fails health checks for a model without generateContent", async (responseBody) => {
    const provider = new GeminiGenerateContentProvider({
      fetch: vi.fn().mockResolvedValue(jsonResponse(responseBody)),
    });

    await expect(
      provider.check({
        provider: "gemini",
        model: "gemini-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: false, code: "MODEL_HEALTH_FAILED" });
  });

  it("creates a native non-stored request and maps text plus usage", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(textResponse());
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });

    await expect(provider.generate(baseRequest())).resolves.toEqual({
      type: "final",
      fallbackText: "Place four planks.",
      usage: { inputTokens: 8, outputTokens: 6 },
    });

    const [url, init] = fetchImplementation.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
    );
    expect(init).toMatchObject({
      method: "POST",
      redirect: "error",
      headers: {
        "x-goog-api-key": API_KEY,
        "Content-Type": "application/json",
      },
    });
    expect(JSON.parse(String(init.body))).toEqual({
      systemInstruction: { parts: [{ text: "trusted module prompt" }] },
      contents: [
        { role: "user", parts: [{ text: "private player prompt" }] },
        { role: "model", parts: [{ text: "prior assistant reply" }] },
      ],
      generationConfig: { maxOutputTokens: 1024 },
      store: false,
      tools: [],
    });
  });

  it("keeps custom-endpoint HTTP failure billability conservative", async () => {
    const provider = new GeminiGenerateContentProvider({
      baseUrl: "https://gemini.example.test/v1beta",
      fetch: vi.fn().mockResolvedValue(new Response(null, { status: 500 })),
    });

    await expect(provider.generate(baseRequest())).rejects.toMatchObject({
      code: "PROVIDER_UNAVAILABLE",
      accountingDisposition: "BILLABILITY_UNKNOWN",
    });
  });

  it("continues a single AUTO function call with its id, name, and parsed result", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          candidates: [
            {
              content: {
                role: "model",
                parts: [
                  { text: "I will inspect the server." },
                  {
                    functionCall: {
                      id: "provider-call-1",
                      name: "server_info_read",
                      args: {},
                    },
                    thoughtSignature: "opaque-provider-state",
                  },
                ],
              },
              finishReason: "STOP",
            },
          ],
        }),
      )
      .mockResolvedValueOnce(textResponse("The server is ready."));
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });
    const request = { ...baseRequest(), tools: [serverInfoTool] };

    const first = await provider.generate(request);
    expect(first).toMatchObject({
      type: "tool_call",
      providerCallId: "provider-call-1",
      providerName: "server_info_read",
      arguments: {},
      continuation: { provider: "gemini" },
    });
    if (first.type !== "tool_call") {
      throw new Error("expected a Gemini function call");
    }

    await expect(
      provider.generate({
        ...request,
        continuation: first.continuation,
        toolOutput: {
          providerCallId: first.providerCallId,
          output: '{"status":"succeeded","result":{"onlinePlayers":1}}',
        },
      }),
    ).resolves.toMatchObject({
      type: "final",
      fallbackText: "The server is ready.",
      usage: { inputTokens: 8, outputTokens: 6 },
    });

    const firstBody = JSON.parse(
      String((fetchImplementation.mock.calls[0]?.[1] as RequestInit).body),
    ) as Record<string, unknown>;
    expect(firstBody["tools"]).toEqual([
      {
        functionDeclarations: [
          {
            name: "server_info_read",
            description: "Read server info.",
            parametersJsonSchema: serverInfoTool.parameters,
          },
        ],
      },
    ]);
    expect(firstBody["toolConfig"]).toEqual({
      functionCallingConfig: { mode: "AUTO" },
    });

    const secondBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as { contents: unknown[] };
    expect(secondBody.contents).toMatchObject([
      { role: "user" },
      { role: "model" },
      {
        role: "model",
        parts: [
          { text: "I will inspect the server." },
          {
            functionCall: { id: "provider-call-1", name: "server_info_read" },
            thoughtSignature: "opaque-provider-state",
          },
        ],
      },
      {
        role: "user",
        parts: [
          {
            functionResponse: {
              id: "provider-call-1",
              name: "server_info_read",
              response: { status: "succeeded", result: { onlinePlayers: 1 } },
            },
          },
        ],
      },
    ]);
  });

  it("correlates legacy function calls that omit an upstream id without inventing one upstream", async () => {
    const fetchImplementation = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          candidates: [
            {
              content: {
                role: "model",
                parts: [{ functionCall: { name: "server_info_read" } }],
              },
              finishReason: "STOP",
            },
          ],
        }),
      )
      .mockResolvedValueOnce(textResponse("Legacy call completed."));
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });
    const request = { ...baseRequest(), tools: [serverInfoTool] };

    const first = await provider.generate(request);
    expect(first).toMatchObject({
      type: "tool_call",
      providerCallId: "gemini-call-0-server_info_read",
      providerName: "server_info_read",
      arguments: {},
    });
    if (first.type !== "tool_call") {
      throw new Error("expected a legacy Gemini function call");
    }
    await provider.generate({
      ...request,
      continuation: first.continuation,
      toolOutput: {
        providerCallId: first.providerCallId,
        output: '{"status":"succeeded","result":{}}',
      },
    });

    const secondBody = JSON.parse(
      String((fetchImplementation.mock.calls[1]?.[1] as RequestInit).body),
    ) as { contents: Array<Record<string, unknown>> };
    const responseContent = secondBody.contents.at(-1) as {
      parts: Array<{ functionResponse: Record<string, unknown> }>;
    };
    expect(responseContent.parts[0]?.functionResponse).toEqual({
      name: "server_info_read",
      response: { status: "succeeded", result: {} },
    });
  });

  it("sends the real build tool as JSON Schema, including nullable and numeric enums", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(textResponse());
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });
    const buildTool = toolRegistry.byProviderName("build_preview_create");
    if (buildTool === undefined) {
      throw new Error("missing build preview tool");
    }

    await provider.generate({
      ...baseRequest(),
      tools: [buildTool],
    });
    const body = JSON.parse(
      String((fetchImplementation.mock.calls[0]?.[1] as RequestInit).body),
    ) as {
      tools: Array<{
        functionDeclarations: Array<{ parametersJsonSchema: Record<string, unknown> }>;
      }>;
    };
    expect(body.tools[0]?.functionDeclarations[0]?.parametersJsonSchema).toMatchObject({
      properties: {
        blockState: { type: ["string", "null"] },
        rotation: { type: "integer", enum: [0, 90, 180, 270] },
      },
    });
    expect(buildTool.parameters).toMatchObject({
      properties: { blockState: { type: ["string", "null"] } },
    });
  });

  it.each([
    {
      candidates: [],
      promptFeedback: { blockReason: "SAFETY" },
    },
    {
      candidates: [
        {
          content: { role: "model", parts: [{ text: "blocked" }] },
          finishReason: "SAFETY",
        },
      ],
    },
    {
      candidates: [
        {
          content: { role: "model", parts: [{ text: "blocked" }] },
          finishReason: "STOP",
          safetyRatings: [{ blocked: true }],
        },
      ],
    },
    {
      candidates: [
        {
          content: { role: "model", parts: [{ text: "blocked" }] },
          finishReason: "STOP",
        },
      ],
      promptFeedback: { safetyRatings: [{ blocked: true }] },
    },
    {
      candidates: [
        { content: { role: "model", parts: [{ text: "one" }] }, finishReason: "STOP" },
        { content: { role: "model", parts: [{ text: "two" }] }, finishReason: "STOP" },
      ],
    },
  ])("fails closed for safety blocks and multiple candidates", async (responseBody) => {
    const provider = new GeminiGenerateContentProvider({
      fetch: vi.fn().mockResolvedValue(jsonResponse(responseBody)),
    });

    await expect(provider.generate(baseRequest())).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
    });
  });

  it("fails closed for parallel or unregistered function calls", async () => {
    const response = (names: readonly string[]) =>
      jsonResponse({
        candidates: [
          {
            content: {
              role: "model",
              parts: names.map((name, index) => ({
                functionCall: { id: `call-${index}`, name, args: {} },
              })),
            },
            finishReason: "STOP",
          },
        ],
      });

    for (const names of [["server_info_read", "server_info_read"], ["unregistered_tool"]]) {
      const provider = new GeminiGenerateContentProvider({
        fetch: vi.fn().mockResolvedValue(response(names)),
      });
      await expect(
        provider.generate({ ...baseRequest(), tools: [serverInfoTool] }),
      ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    }
  });

  it("rejects mismatched continuations and non-object tool results before fetching", async () => {
    const fetchImplementation = vi.fn();
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });
    const continuation = {
      provider: "gemini" as const,
      items: [
        {
          role: "model",
          parts: [
            {
              functionCall: {
                id: "provider-call-1",
                name: "server_info_read",
                args: {},
              },
            },
          ],
        },
      ],
    };

    await expect(
      provider.generate({
        ...baseRequest(),
        tools: [serverInfoTool],
        continuation,
        toolOutput: { providerCallId: "wrong-call", output: "{}" },
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(
      provider.generate({
        ...baseRequest(),
        tools: [serverInfoTool],
        continuation,
        toolOutput: { providerCallId: "provider-call-1", output: "[]" },
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(
      provider.generate({
        ...baseRequest(),
        tools: [serverInfoTool],
        continuation: {
          provider: "gemini",
          items: [
            ...continuation.items,
            {
              role: "user",
              parts: [
                {
                  functionResponse: {
                    id: "provider-call-1",
                    name: "server_info_read",
                    response: {},
                  },
                },
              ],
            },
            ...continuation.items,
          ],
        },
        toolOutput: { providerCallId: "provider-call-1", output: "{}" },
      }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it.each([
    [401, "MODEL_AUTHENTICATION_FAILED"],
    [403, "MODEL_AUTHENTICATION_FAILED"],
    [404, "MODEL_UNAVAILABLE"],
    [429, "MODEL_RATE_LIMITED"],
    [500, "PROVIDER_UNAVAILABLE"],
  ] as const)("maps HTTP %s without exposing upstream details", async (status, code) => {
    const provider = new GeminiGenerateContentProvider({
      fetch: vi.fn().mockResolvedValue(new Response("private upstream detail", { status })),
    });

    const operation = provider.generate(baseRequest());
    await expect(operation).rejects.toMatchObject({ code });
    await expect(operation).rejects.not.toThrow(/private upstream detail/u);
  });

  it("rejects malformed and oversized successful responses", async () => {
    const responses = [
      new Response("not-json", { status: 200 }),
      new Response("{}", {
        status: 200,
        headers: { "Content-Length": String(1024 * 1024 + 1) },
      }),
      textResponse("\ud800"),
    ];

    for (const response of responses) {
      const provider = new GeminiGenerateContentProvider({
        fetch: vi.fn().mockResolvedValue(response),
      });
      await expect(provider.generate(baseRequest())).rejects.toBeInstanceOf(ModelGenerationError);
    }
  });

  it("rejects token counters whose combined accounting value is unsafe", async () => {
    const provider = new GeminiGenerateContentProvider({
      fetch: vi.fn().mockResolvedValue(
        jsonResponse({
          candidates: [
            {
              content: { role: "model", parts: [{ text: "answer" }] },
              finishReason: "STOP",
            },
          ],
          usageMetadata: {
            promptTokenCount: Number.MAX_SAFE_INTEGER,
            candidatesTokenCount: 1,
            toolUsePromptTokenCount: 1,
          },
        }),
      ),
    });

    await expect(provider.generate(baseRequest())).rejects.toMatchObject({
      code: "MODEL_RESPONSE_INVALID",
    });
  });

  it("requires Gemini requests and propagates cancellation", async () => {
    const wrongProviderFetch = vi.fn();
    const wrongProvider = new GeminiGenerateContentProvider({ fetch: wrongProviderFetch });
    await expect(
      wrongProvider.generate({ ...baseRequest(), provider: "openai" }),
    ).rejects.toMatchObject({ code: "MODEL_RESPONSE_INVALID" });
    await expect(
      wrongProvider.check({
        provider: "openai",
        model: "gemini-test",
        apiKey: API_KEY,
        signal: new AbortController().signal,
      }),
    ).resolves.toEqual({ ok: false, code: "PROVIDER_UNSUPPORTED" });
    expect(wrongProviderFetch).not.toHaveBeenCalled();

    const controller = new AbortController();
    const fetchImplementation = vi.fn(
      async (_input: string | URL | Request, init?: RequestInit): Promise<Response> =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), {
            once: true,
          });
        }),
    );
    const provider = new GeminiGenerateContentProvider({ fetch: fetchImplementation });
    const operation = provider.generate({ ...baseRequest(), signal: controller.signal });

    controller.abort(new Error("cancelled by request owner"));
    await expect(operation).rejects.toThrow("cancelled by request owner");
  });
});
