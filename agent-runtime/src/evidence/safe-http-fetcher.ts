import { lookup } from "node:dns/promises";
import type { IncomingHttpHeaders } from "node:http";
import { request as httpsRequest } from "node:https";
import { isIP } from "node:net";
import { brotliDecompress, gunzip, inflate } from "node:zlib";

const FETCH_TIMEOUT_MILLISECONDS = 5_000;
const MAXIMUM_REDIRECTS = 3;
const MAXIMUM_COMPRESSED_BYTES = 256 * 1024;
const MAXIMUM_DECOMPRESSED_BYTES = 1024 * 1024;
const ALLOWED_MEDIA_TYPES = new Set(["text/html", "text/plain", "application/xhtml+xml"]);

export type SafeFetchFailureCode =
  | "FETCH_URL_REJECTED"
  | "FETCH_ADDRESS_REJECTED"
  | "FETCH_TIMEOUT"
  | "FETCH_REDIRECT_REJECTED"
  | "FETCH_RESPONSE_TOO_LARGE"
  | "FETCH_MIME_REJECTED"
  | "FETCH_ENCODING_REJECTED"
  | "FETCH_UNAVAILABLE";

export class SafeFetchError extends Error {
  public readonly code: SafeFetchFailureCode;

  public constructor(code: SafeFetchFailureCode, cause?: unknown) {
    super(code, { cause });
    this.name = "SafeFetchError";
    this.code = code;
  }
}

export interface ResolvedAddress {
  readonly address: string;
  readonly family: 4 | 6;
}

export interface PinnedHttpsRequest {
  readonly url: URL;
  readonly address: ResolvedAddress;
  readonly signal: AbortSignal;
  readonly maximumBytes: number;
}

export interface PinnedHttpsResponse {
  readonly status: number;
  readonly headers: IncomingHttpHeaders;
  readonly body: Buffer;
}

export interface PinnedHttpsTransport {
  request(input: PinnedHttpsRequest): Promise<PinnedHttpsResponse>;
}

export interface SafeFetchedPage {
  readonly url: string;
  readonly mediaType: "text/html" | "text/plain" | "application/xhtml+xml";
  readonly body: string;
  readonly retrievedAt: string;
}

export interface SafeHttpFetcherOptions {
  readonly resolveHost?: (hostname: string) => Promise<readonly ResolvedAddress[]>;
  readonly transport?: PinnedHttpsTransport;
  readonly now?: () => Date;
  readonly timeoutMilliseconds?: number;
  readonly maximumRedirects?: number;
  readonly maximumCompressedBytes?: number;
  readonly maximumDecompressedBytes?: number;
}

function parseIpv4(address: string): readonly number[] | undefined {
  const parts = address.split(".");
  if (parts.length !== 4) return undefined;
  const octets = parts.map((part) => (/^(?:0|[1-9][0-9]{0,2})$/u.test(part) ? Number(part) : -1));
  return octets.every((octet) => octet >= 0 && octet <= 255) ? octets : undefined;
}

function publicIpv4(address: string): boolean {
  const octets = parseIpv4(address);
  if (octets === undefined) return false;
  const [a = -1, b = -1] = octets;
  return !(
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && (b === 0 || b === 168)) ||
    (a === 198 && (b === 18 || b === 19 || b === 51)) ||
    (a === 203 && b === 0) ||
    a >= 224
  );
}

function expandIpv6(address: string): readonly number[] | undefined {
  const normalized = address.toLowerCase().split("%", 1)[0] ?? "";
  if (normalized.includes(".")) return undefined;
  const halves = normalized.split("::");
  if (halves.length > 2) return undefined;
  const left = (halves[0] ?? "").split(":").filter(Boolean);
  const right = (halves[1] ?? "").split(":").filter(Boolean);
  if (halves.length === 1 && left.length !== 8) return undefined;
  const missing = 8 - left.length - right.length;
  if (missing < (halves.length === 2 ? 1 : 0)) return undefined;
  const words = [...left, ...Array.from({ length: missing }, () => "0"), ...right];
  if (words.length !== 8 || words.some((word) => !/^[0-9a-f]{1,4}$/u.test(word))) return undefined;
  return words.map((word) => Number.parseInt(word, 16));
}

function publicIpv6(address: string): boolean {
  const words = expandIpv6(address);
  if (words === undefined) return false;
  const first = words[0] ?? 0;
  const second = words[1] ?? 0;
  return first >= 0x2000 && first <= 0x3fff && !(first === 0x2001 && second === 0x0db8);
}

export function isPublicInternetAddress(address: string): boolean {
  const family = isIP(address);
  return family === 4 ? publicIpv4(address) : family === 6 ? publicIpv6(address) : false;
}

async function defaultResolveHost(hostname: string): Promise<readonly ResolvedAddress[]> {
  const addresses = await lookup(hostname, { all: true, verbatim: true });
  return addresses.flatMap((entry) =>
    entry.family === 4 || entry.family === 6
      ? [{ address: entry.address, family: entry.family }]
      : [],
  );
}

class NodePinnedHttpsTransport implements PinnedHttpsTransport {
  public request(input: PinnedHttpsRequest): Promise<PinnedHttpsResponse> {
    return new Promise((resolve, reject) => {
      let settled = false;
      const finishReject = (error: unknown): void => {
        if (settled) return;
        settled = true;
        reject(error);
      };
      const request = httpsRequest(
        {
          protocol: "https:",
          hostname: input.address.address,
          port: 443,
          servername: input.url.hostname,
          method: "GET",
          path: `${input.url.pathname}${input.url.search}`,
          headers: {
            accept: "text/html, application/xhtml+xml, text/plain;q=0.8",
            "accept-encoding": "gzip, deflate, br",
            host: input.url.host,
            "user-agent": "AGMA-Standalone/0.2 web-evidence",
          },
          rejectUnauthorized: true,
        },
        (response) => {
          const declared = response.headers["content-length"];
          if (declared !== undefined && Number(declared) > input.maximumBytes) {
            response.destroy();
            finishReject(new SafeFetchError("FETCH_RESPONSE_TOO_LARGE"));
            return;
          }
          const chunks: Buffer[] = [];
          let length = 0;
          response.on("data", (chunk: Buffer) => {
            length += chunk.length;
            if (length > input.maximumBytes) {
              response.destroy(new SafeFetchError("FETCH_RESPONSE_TOO_LARGE"));
              return;
            }
            chunks.push(chunk);
          });
          response.once("error", finishReject);
          response.once("end", () => {
            if (settled) return;
            settled = true;
            resolve({
              status: response.statusCode ?? 0,
              headers: response.headers,
              body: Buffer.concat(chunks, length),
            });
          });
        },
      );
      request.once("error", finishReject);
      const abort = (): void => {
        request.destroy(input.signal.reason);
      };
      input.signal.addEventListener("abort", abort, { once: true });
      request.once("close", () => input.signal.removeEventListener("abort", abort));
      request.end();
    });
  }
}

function validateUrl(value: string | URL): URL {
  let url: URL;
  try {
    url = value instanceof URL ? new URL(value) : new URL(value);
  } catch (error) {
    throw new SafeFetchError("FETCH_URL_REJECTED", error);
  }
  if (
    url.protocol !== "https:" ||
    url.username.length > 0 ||
    url.password.length > 0 ||
    url.hash.length > 0 ||
    (url.port.length > 0 && url.port !== "443") ||
    url.hostname.length < 1 ||
    url.href.length > 2048
  ) {
    throw new SafeFetchError("FETCH_URL_REJECTED");
  }
  return url;
}

function decompress(
  operation: (
    body: Buffer,
    options: { readonly maxOutputLength: number },
    callback: (error: Error | null, result: Buffer) => void,
  ) => void,
  body: Buffer,
  maximumBytes: number,
): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    operation(body, { maxOutputLength: maximumBytes }, (error: Error | null, result: Buffer) => {
      if (error !== null) reject(new SafeFetchError("FETCH_ENCODING_REJECTED", error));
      else resolve(result);
    });
  });
}

async function decodedBody(response: PinnedHttpsResponse, maximumBytes: number): Promise<Buffer> {
  const encoding = String(response.headers["content-encoding"] ?? "identity")
    .trim()
    .toLowerCase();
  let body: Buffer;
  if (encoding === "identity" || encoding.length === 0) body = response.body;
  else if (encoding === "gzip") {
    body = await decompress(
      gunzip as Parameters<typeof decompress>[0],
      response.body,
      maximumBytes,
    );
  } else if (encoding === "deflate") {
    body = await decompress(
      inflate as Parameters<typeof decompress>[0],
      response.body,
      maximumBytes,
    );
  } else if (encoding === "br") {
    body = await decompress(
      brotliDecompress as Parameters<typeof decompress>[0],
      response.body,
      maximumBytes,
    );
  } else {
    throw new SafeFetchError("FETCH_ENCODING_REJECTED");
  }
  if (body.length > maximumBytes) throw new SafeFetchError("FETCH_RESPONSE_TOO_LARGE");
  return body;
}

export class SafeHttpFetcher {
  readonly #resolveHost: (hostname: string) => Promise<readonly ResolvedAddress[]>;
  readonly #transport: PinnedHttpsTransport;
  readonly #now: () => Date;
  readonly #timeoutMilliseconds: number;
  readonly #maximumRedirects: number;
  readonly #maximumCompressedBytes: number;
  readonly #maximumDecompressedBytes: number;

  public constructor(options: SafeHttpFetcherOptions = {}) {
    this.#resolveHost = options.resolveHost ?? defaultResolveHost;
    this.#transport = options.transport ?? new NodePinnedHttpsTransport();
    this.#now = options.now ?? (() => new Date());
    this.#timeoutMilliseconds = options.timeoutMilliseconds ?? FETCH_TIMEOUT_MILLISECONDS;
    this.#maximumRedirects = options.maximumRedirects ?? MAXIMUM_REDIRECTS;
    this.#maximumCompressedBytes = options.maximumCompressedBytes ?? MAXIMUM_COMPRESSED_BYTES;
    this.#maximumDecompressedBytes = options.maximumDecompressedBytes ?? MAXIMUM_DECOMPRESSED_BYTES;
  }

  public async fetchPage(value: string, signal: AbortSignal): Promise<SafeFetchedPage> {
    const deadline = AbortSignal.timeout(this.#timeoutMilliseconds);
    const boundedSignal = AbortSignal.any([signal, deadline]);
    let url = validateUrl(value);
    for (let redirect = 0; redirect <= this.#maximumRedirects; redirect += 1) {
      let addresses: readonly ResolvedAddress[];
      const literalHostname = url.hostname.replace(/^\[|\]$/gu, "");
      if (isIP(literalHostname) !== 0) {
        addresses = [{ address: literalHostname, family: isIP(literalHostname) as 4 | 6 }];
      } else {
        try {
          addresses = await this.#resolveHost(url.hostname);
        } catch (error) {
          throw new SafeFetchError("FETCH_UNAVAILABLE", error);
        }
      }
      if (
        addresses.length === 0 ||
        addresses.some((entry) => !isPublicInternetAddress(entry.address))
      ) {
        throw new SafeFetchError("FETCH_ADDRESS_REJECTED");
      }
      let response: PinnedHttpsResponse;
      try {
        response = await this.#transport.request({
          url,
          address: addresses[0]!,
          signal: boundedSignal,
          maximumBytes: this.#maximumCompressedBytes,
        });
      } catch (error) {
        if (boundedSignal.aborted) throw new SafeFetchError("FETCH_TIMEOUT", error);
        if (error instanceof SafeFetchError) throw error;
        throw new SafeFetchError("FETCH_UNAVAILABLE", error);
      }
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        if (redirect === this.#maximumRedirects) {
          throw new SafeFetchError("FETCH_REDIRECT_REJECTED");
        }
        const location = response.headers.location;
        if (typeof location !== "string") throw new SafeFetchError("FETCH_REDIRECT_REJECTED");
        try {
          url = validateUrl(new URL(location, url));
        } catch (error) {
          throw new SafeFetchError("FETCH_REDIRECT_REJECTED", error);
        }
        continue;
      }
      if (response.status < 200 || response.status >= 300) {
        throw new SafeFetchError("FETCH_UNAVAILABLE");
      }
      const mediaType = String(response.headers["content-type"] ?? "")
        .split(";", 1)[0]!
        .trim()
        .toLowerCase();
      if (!ALLOWED_MEDIA_TYPES.has(mediaType)) throw new SafeFetchError("FETCH_MIME_REJECTED");
      const body = await decodedBody(response, this.#maximumDecompressedBytes);
      let text: string;
      try {
        text = new TextDecoder("utf-8", { fatal: true }).decode(body);
      } catch (error) {
        throw new SafeFetchError("FETCH_ENCODING_REJECTED", error);
      }
      return {
        url: url.href,
        mediaType: mediaType as SafeFetchedPage["mediaType"],
        body: text,
        retrievedAt: this.#now().toISOString(),
      };
    }
    throw new SafeFetchError("FETCH_REDIRECT_REJECTED");
  }
}
