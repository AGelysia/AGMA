import type { IncomingHttpHeaders } from "node:http";
import { gzipSync } from "node:zlib";

import { describe, expect, it, vi } from "vitest";

import {
  isPublicInternetAddress,
  type PinnedHttpsRequest,
  type PinnedHttpsResponse,
  type PinnedHttpsTransport,
  SafeFetchError,
  SafeHttpFetcher,
} from "../src/evidence/safe-http-fetcher.js";

class FakeTransport implements PinnedHttpsTransport {
  readonly requests: PinnedHttpsRequest[] = [];
  readonly #responses: PinnedHttpsResponse[];

  public constructor(responses: readonly PinnedHttpsResponse[]) {
    this.#responses = [...responses];
  }

  public async request(input: PinnedHttpsRequest): Promise<PinnedHttpsResponse> {
    this.requests.push(input);
    const response = this.#responses.shift();
    if (response === undefined) throw new Error("missing fake response");
    return response;
  }
}

function response(
  status: number,
  body: Buffer | string,
  headers: IncomingHttpHeaders = { "content-type": "text/html" },
): PinnedHttpsResponse {
  return { status, headers, body: Buffer.isBuffer(body) ? body : Buffer.from(body) };
}

describe("safe HTTPS evidence fetcher", () => {
  it("rejects private, loopback, link-local, multicast, metadata, and reserved addresses", () => {
    for (const address of [
      "0.0.0.0",
      "10.0.0.1",
      "100.64.0.1",
      "127.0.0.1",
      "169.254.169.254",
      "172.16.0.1",
      "192.168.1.1",
      "192.0.2.1",
      "224.0.0.1",
      "::1",
      "fe80::1",
      "fc00::1",
      "ff02::1",
      "2001:db8::1",
    ]) {
      expect(isPublicInternetAddress(address), address).toBe(false);
    }
    expect(isPublicInternetAddress("93.184.216.34")).toBe(true);
    expect(isPublicInternetAddress("2606:2800:220:1:248:1893:25c8:1946")).toBe(true);
  });

  it("pins the resolved public address and revalidates every redirect hop", async () => {
    const transport = new FakeTransport([
      response(302, "", { location: "https://next.example/final" }),
      response(200, "<html><p>Useful Minecraft evidence text for testing.</p></html>"),
    ]);
    const resolveHost = vi
      .fn()
      .mockResolvedValueOnce([{ address: "93.184.216.34", family: 4 }])
      .mockResolvedValueOnce([{ address: "142.250.72.14", family: 4 }]);
    const fetcher = new SafeHttpFetcher({ transport, resolveHost });

    const page = await fetcher.fetchPage(
      "https://start.example/path",
      new AbortController().signal,
    );

    expect(page.url).toBe("https://next.example/final");
    expect(resolveHost.mock.calls).toEqual([["start.example"], ["next.example"]]);
    expect(transport.requests.map((request) => request.address.address)).toEqual([
      "93.184.216.34",
      "142.250.72.14",
    ]);
  });

  it("rejects mixed public/private DNS answers before transport to prevent rebinding", async () => {
    const transport = new FakeTransport([]);
    const fetcher = new SafeHttpFetcher({
      transport,
      resolveHost: vi.fn().mockResolvedValue([
        { address: "93.184.216.34", family: 4 },
        { address: "127.0.0.1", family: 4 },
      ]),
    });

    await expect(
      fetcher.fetchPage("https://mixed.example/", new AbortController().signal),
    ).rejects.toMatchObject<Partial<SafeFetchError>>({ code: "FETCH_ADDRESS_REJECTED" });
    expect(transport.requests).toEqual([]);
  });

  it("rejects redirect-to-private, non-HTTPS/file URLs, MIME, raw size, and gzip expansion", async () => {
    const privateRedirect = new SafeHttpFetcher({
      transport: new FakeTransport([
        response(302, "", { location: "https://private.example/secret" }),
      ]),
      resolveHost: async (host) => [
        { address: host === "private.example" ? "10.0.0.1" : "93.184.216.34", family: 4 },
      ],
    });
    await expect(
      privateRedirect.fetchPage("https://public.example/", new AbortController().signal),
    ).rejects.toMatchObject({ code: "FETCH_ADDRESS_REJECTED" });

    const safeAddress = async () => [{ address: "93.184.216.34", family: 4 }] as const;
    for (const url of ["http://example.com", "file:///etc/passwd", "https://user@example.com/"]) {
      await expect(
        new SafeHttpFetcher({ resolveHost: safeAddress }).fetchPage(
          url,
          new AbortController().signal,
        ),
      ).rejects.toMatchObject({ code: "FETCH_URL_REJECTED" });
    }

    const mime = new SafeHttpFetcher({
      resolveHost: safeAddress,
      transport: new FakeTransport([response(200, "binary", { "content-type": "image/png" })]),
    });
    await expect(
      mime.fetchPage("https://example.com/image", new AbortController().signal),
    ).rejects.toMatchObject({ code: "FETCH_MIME_REJECTED" });

    const raw = new SafeHttpFetcher({
      resolveHost: safeAddress,
      transport: new FakeTransport([response(200, "x".repeat(65))]),
      maximumCompressedBytes: 64,
      maximumDecompressedBytes: 64,
    });
    await expect(
      raw.fetchPage("https://example.com/large", new AbortController().signal),
    ).rejects.toMatchObject({ code: "FETCH_RESPONSE_TOO_LARGE" });

    const compressed = new SafeHttpFetcher({
      resolveHost: safeAddress,
      transport: new FakeTransport([
        response(200, gzipSync("x".repeat(1024)), {
          "content-type": "text/html",
          "content-encoding": "gzip",
        }),
      ]),
      maximumDecompressedBytes: 64,
    });
    await expect(
      compressed.fetchPage("https://example.com/bomb", new AbortController().signal),
    ).rejects.toMatchObject({ code: "FETCH_ENCODING_REJECTED" });
  });
});
