import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { inflateRawSync } from "node:zlib";

import { parseStrictJson } from "../transport/strict-json.js";
import { SUPPORTED_PROTOCOL_VERSION } from "../version.js";

export interface SemanticValidationError {
  readonly code: string;
  readonly rule: string;
  readonly instancePath: string;
  readonly message: string;
}

export interface SemanticValidationResult {
  readonly valid: boolean;
  readonly errors: readonly SemanticValidationError[];
}

type JsonRecord = Record<string, unknown>;

interface HandshakeGoldenSide {
  readonly protocolVersion: string;
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly type: string;
  readonly timestamp: string;
  readonly nonce: string;
  readonly component: string;
  readonly componentVersion: string;
  readonly supportedProtocolVersions: readonly unknown[];
  readonly selectedProtocolVersion: unknown;
  readonly scheme: string;
  readonly keyId: string;
  readonly challenge: string;
  readonly proof: string;
}

interface ChunkDescriptor {
  readonly record: JsonRecord;
  readonly path: string;
  readonly index: number;
  readonly encodedData: string;
}

interface ChunkCollection {
  readonly chunks: readonly ChunkDescriptor[];
  readonly metadataRecords: readonly JsonRecord[];
  readonly path: string;
}

const INDEX_FIELDS = ["chunkIndex", "index"] as const;
const SUPPORTED_CLIENT_PROTOCOL_VERSIONS = new Set(["1.0", "1.1"]);
const DATA_FIELDS = ["data", "dataBase64", "chunkData", "payloadBase64"] as const;
const CHUNK_COUNT_FIELDS = ["chunkCount", "totalChunks"] as const;
const CHUNK_BYTES_FIELDS = ["chunkBytes", "byteLength"] as const;
const CHUNK_HASH_FIELDS = ["chunkSha256", "sha256"] as const;
const TOTAL_COMPRESSED_BYTES_FIELDS = [
  "totalCompressedBytes",
  "compressedBytes",
  "totalBytes",
] as const;
const TOTAL_UNCOMPRESSED_BYTES_FIELDS = ["totalUncompressedBytes", "uncompressedBytes"] as const;
const CONTENT_HASH_FIELDS = ["contentSha256", "contentHash", "sha256"] as const;
const PROPOSAL_ARGUMENT_HASH_DOMAIN = "minecraft-agent/proposal-arguments/v1";
const PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES = 65_536;
const PROPOSAL_ARGUMENT_NODE_LIMIT = 4_096;
const PROPOSAL_ARGUMENT_DEPTH_LIMIT = 32;
const CAPABILITY_PLUGIN_VERSION_RANGE =
  /^(?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2}(?: (?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2})*$/u;
const CAPABILITY_PLUGIN_VERSION = /^(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2}$/u;
const CAPABILITY_PLUGIN_COMPARISON =
  /^(>=|<=|=|>|<)((?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){0,2})$/u;

export const BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES = 64 * 1024 * 1024;
export const BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES = 16 * 1024 * 1024;

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function escapeJsonPointer(value: string): string {
  return value.replaceAll("~", "~0").replaceAll("/", "~1");
}

function childPath(parent: string, child: string | number): string {
  return `${parent}/${escapeJsonPointer(String(child))}`;
}

function semanticError(
  code: string,
  rule: string,
  instancePath: string,
  message: string,
): SemanticValidationError {
  return { code, rule, instancePath, message };
}

function readHandshakeGoldenSide(value: unknown): HandshakeGoldenSide | undefined {
  if (!isRecord(value) || !isRecord(value["payload"])) {
    return undefined;
  }
  const payload = value["payload"];
  if (!isRecord(payload["authentication"])) {
    return undefined;
  }
  const authentication = payload["authentication"];
  const strings = [
    value["protocolVersion"],
    value["messageId"],
    value["requestId"],
    value["serverId"],
    value["type"],
    value["timestamp"],
    value["nonce"],
    payload["component"],
    payload["componentVersion"],
    authentication["scheme"],
    authentication["keyId"],
    authentication["challenge"],
    authentication["proof"],
  ];
  if (strings.some((field) => typeof field !== "string")) {
    return undefined;
  }
  if (!Array.isArray(payload["supportedProtocolVersions"])) {
    return undefined;
  }

  return {
    protocolVersion: value["protocolVersion"] as string,
    messageId: value["messageId"] as string,
    requestId: value["requestId"] as string,
    serverId: value["serverId"] as string,
    type: value["type"] as string,
    timestamp: value["timestamp"] as string,
    nonce: value["nonce"] as string,
    component: payload["component"] as string,
    componentVersion: payload["componentVersion"] as string,
    supportedProtocolVersions: payload["supportedProtocolVersions"],
    selectedProtocolVersion: payload["selectedProtocolVersion"],
    scheme: authentication["scheme"] as string,
    keyId: authentication["keyId"] as string,
    challenge: authentication["challenge"] as string,
    proof: authentication["proof"] as string,
  };
}

function decodeCanonicalBase64Url(value: string, expectedBytes?: number): Buffer | undefined {
  if (!/^[A-Za-z\d_-]+$/u.test(value)) {
    return undefined;
  }
  const decoded = Buffer.from(value, "base64url");
  if (
    decoded.toString("base64url") !== value ||
    (expectedBytes === undefined ? decoded.length < 16 : decoded.length !== expectedBytes)
  ) {
    return undefined;
  }
  return decoded;
}

function handshakeTranscript(side: HandshakeGoldenSide): string {
  return [
    "minecraft-agent-handshake-v1",
    side.serverId,
    side.type,
    side.timestamp,
    side.nonce,
    side.component,
    side.componentVersion,
    side.challenge,
  ].join("\n");
}

function handshakeProofMatches(token: string, side: HandshakeGoldenSide): boolean {
  const suppliedProof = decodeCanonicalBase64Url(side.proof, 32);
  if (suppliedProof === undefined) {
    return false;
  }
  const expectedProof = createHmac("sha256", token)
    .update(handshakeTranscript(side), "utf8")
    .digest();
  return timingSafeEqual(suppliedProof, expectedProof);
}

export function validateHandshakeProof(value: unknown): SemanticValidationError[] {
  const rule = "handshake-proof-v1";
  if (!isRecord(value) || typeof value["publicTestToken"] !== "string") {
    return [
      semanticError(
        "HANDSHAKE_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "golden exchange must contain a public test token",
      ),
    ];
  }
  const paper = readHandshakeGoldenSide(value["paper"]);
  const runtime = readHandshakeGoldenSide(value["runtime"]);
  if (paper === undefined || runtime === undefined) {
    return [
      semanticError(
        "HANDSHAKE_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "golden exchange must contain complete Paper and Runtime hello envelopes",
      ),
    ];
  }

  if (
    paper.protocolVersion !== SUPPORTED_PROTOCOL_VERSION ||
    runtime.protocolVersion !== SUPPORTED_PROTOCOL_VERSION ||
    paper.type !== "paper.hello" ||
    runtime.type !== "runtime.hello" ||
    paper.component !== "paper" ||
    runtime.component !== "runtime" ||
    paper.scheme !== "hmac-sha256" ||
    runtime.scheme !== "hmac-sha256"
  ) {
    return [
      semanticError(
        "HANDSHAKE_IDENTITY_INVALID",
        rule,
        "",
        "hello direction, component identity, scheme, or protocol version is invalid",
      ),
    ];
  }
  if (
    paper.messageId !== paper.requestId ||
    runtime.requestId !== paper.requestId ||
    runtime.messageId === paper.messageId ||
    runtime.serverId !== paper.serverId ||
    paper.keyId !== paper.serverId ||
    runtime.keyId !== paper.serverId
  ) {
    return [
      semanticError(
        "HANDSHAKE_CORRELATION_INVALID",
        rule,
        "",
        "response correlation or configured server identity does not match the request",
      ),
    ];
  }
  if (
    paper.supportedProtocolVersions.length !== 1 ||
    paper.supportedProtocolVersions[0] !== SUPPORTED_PROTOCOL_VERSION ||
    runtime.supportedProtocolVersions.length !== 1 ||
    runtime.supportedProtocolVersions[0] !== SUPPORTED_PROTOCOL_VERSION ||
    paper.selectedProtocolVersion !== null ||
    runtime.selectedProtocolVersion !== SUPPORTED_PROTOCOL_VERSION
  ) {
    return [
      semanticError(
        "HANDSHAKE_NEGOTIATION_INVALID",
        rule,
        "",
        "Runtime must select the one protocol version advertised by Paper",
      ),
    ];
  }
  if (runtime.challenge !== paper.challenge) {
    return [
      semanticError(
        "HANDSHAKE_CHALLENGE_MISMATCH",
        rule,
        "/runtime/payload/authentication/challenge",
        "Runtime must echo Paper's challenge",
      ),
    ];
  }
  if (
    paper.nonce === runtime.nonce ||
    decodeCanonicalBase64Url(paper.nonce) === undefined ||
    decodeCanonicalBase64Url(runtime.nonce) === undefined ||
    decodeCanonicalBase64Url(paper.challenge) === undefined ||
    decodeCanonicalBase64Url(paper.proof, 32) === undefined ||
    decodeCanonicalBase64Url(runtime.proof, 32) === undefined
  ) {
    return [
      semanticError(
        "HANDSHAKE_BASE64URL_INVALID",
        rule,
        "",
        "nonces, challenge, and proofs must be distinct where required and canonical unpadded base64url",
      ),
    ];
  }
  if (
    !handshakeProofMatches(value["publicTestToken"], paper) ||
    !handshakeProofMatches(value["publicTestToken"], runtime)
  ) {
    return [
      semanticError(
        "HANDSHAKE_PROOF_INVALID",
        rule,
        "",
        "one or more handshake proofs do not match the fixed transcript",
      ),
    ];
  }
  return [];
}

function collectRecords(value: unknown, maximumDepth = 3): JsonRecord[] {
  const records: JsonRecord[] = [];

  function visit(candidate: unknown, depth: number): void {
    if (!isRecord(candidate) || depth > maximumDepth) {
      return;
    }
    records.push(candidate);
    for (const nested of Object.values(candidate)) {
      if (isRecord(nested)) {
        visit(nested, depth + 1);
      }
    }
  }

  visit(value, 0);
  return records;
}

function fieldValue(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): { readonly name: string; readonly value: unknown } | undefined {
  for (const record of records) {
    for (const name of fieldNames) {
      if (Object.hasOwn(record, name)) {
        return { name, value: record[name] };
      }
    }
  }
  return undefined;
}

function numberField(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): number | undefined {
  const field = fieldValue(records, fieldNames);
  return typeof field?.value === "number" && Number.isSafeInteger(field.value)
    ? field.value
    : undefined;
}

function stringField(
  records: readonly JsonRecord[],
  fieldNames: readonly string[],
): string | undefined {
  const field = fieldValue(records, fieldNames);
  return typeof field?.value === "string" ? field.value : undefined;
}

function findChunkDescriptor(value: unknown, path: string): ChunkDescriptor | undefined {
  for (const record of collectRecords(value)) {
    const index = numberField([record], INDEX_FIELDS);
    const data = stringField([record], DATA_FIELDS);
    if (index !== undefined && data !== undefined) {
      return {
        record,
        path,
        index,
        encodedData: data,
      };
    }
  }
  return undefined;
}

function findChunkCollections(value: unknown): ChunkCollection[] {
  const collections: ChunkCollection[] = [];

  function visit(candidate: unknown, path: string, ancestors: readonly JsonRecord[]): void {
    if (Array.isArray(candidate)) {
      const descriptors = candidate.flatMap((item, index) => {
        const descriptor = findChunkDescriptor(item, childPath(path, index));
        return descriptor === undefined ? [] : [descriptor];
      });
      const nonChunkRecords = candidate.flatMap((item) =>
        findChunkDescriptor(item, path) === undefined ? collectRecords(item) : [],
      );

      if (
        descriptors.length > 0 &&
        (descriptors.length === candidate.length || nonChunkRecords.length > 0)
      ) {
        collections.push({
          chunks: descriptors,
          metadataRecords: [
            ...ancestors,
            ...nonChunkRecords,
            ...descriptors.map(({ record }) => record),
          ],
          path,
        });
        return;
      }

      candidate.forEach((item, index) => visit(item, childPath(path, index), ancestors));
      return;
    }

    if (!isRecord(candidate)) {
      return;
    }

    const nextAncestors = [...collectRecords(candidate, 1), ...ancestors];
    for (const [name, nested] of Object.entries(candidate)) {
      visit(nested, childPath(path, name), nextAncestors);
    }
  }

  visit(value, "", []);
  return collections;
}

function decodeCanonicalBase64(value: string): Buffer | undefined {
  if (
    value.length % 4 !== 0 ||
    !/^(?:[A-Za-z\d+/]{4})*(?:[A-Za-z\d+/]{2}==|[A-Za-z\d+/]{3}=)?$/u.test(value)
  ) {
    return undefined;
  }

  const decoded = Buffer.from(value, "base64");
  return decoded.toString("base64") === value ? decoded : undefined;
}

function sha256(value: Buffer | string): string {
  return createHash("sha256").update(value).digest("hex");
}

function hasValidUnicode(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) {
        return false;
      }
      index += 1;
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function canonicalizeJson(
  value: unknown,
  maximumBytes = PROPOSAL_ARGUMENT_CANONICAL_LIMIT_BYTES,
  maximumNodes = PROPOSAL_ARGUMENT_NODE_LIMIT,
  maximumDepth = PROPOSAL_ARGUMENT_DEPTH_LIMIT,
): string | undefined {
  const budget = { nodes: 0, textBytes: 0 };

  function encode(candidate: unknown, depth: number): string | undefined {
    budget.nodes += 1;
    if (depth > maximumDepth || budget.nodes > maximumNodes) {
      return undefined;
    }
    if (candidate === null) {
      return "null";
    }
    if (typeof candidate === "boolean") {
      return candidate ? "true" : "false";
    }
    if (typeof candidate === "number") {
      return Number.isFinite(candidate) ? JSON.stringify(candidate) : undefined;
    }
    if (typeof candidate === "string") {
      budget.textBytes += Buffer.byteLength(candidate, "utf8");
      if (!hasValidUnicode(candidate) || budget.textBytes > maximumBytes) {
        return undefined;
      }
      return JSON.stringify(candidate);
    }
    if (Array.isArray(candidate)) {
      const encoded: string[] = [];
      for (let index = 0; index < candidate.length; index += 1) {
        if (!Object.hasOwn(candidate, index)) {
          return undefined;
        }
        const item = encode(candidate[index], depth + 1);
        if (item === undefined) {
          return undefined;
        }
        encoded.push(item);
      }
      return `[${encoded.join(",")}]`;
    }
    if (!isRecord(candidate)) {
      return undefined;
    }
    const prototype = Object.getPrototypeOf(candidate) as unknown;
    if (prototype !== Object.prototype && prototype !== null) {
      return undefined;
    }

    const encoded: string[] = [];
    for (const key of Object.keys(candidate).sort()) {
      budget.textBytes += Buffer.byteLength(key, "utf8");
      if (!hasValidUnicode(key) || budget.textBytes > maximumBytes) {
        return undefined;
      }
      const item = encode(candidate[key], depth + 1);
      if (item === undefined) {
        return undefined;
      }
      encoded.push(`${JSON.stringify(key)}:${item}`);
    }
    return `{${encoded.join(",")}}`;
  }

  const canonical = encode(value, 0);
  return canonical !== undefined && Buffer.byteLength(canonical, "utf8") <= maximumBytes
    ? canonical
    : undefined;
}

export function validateProposalArgumentHash(value: unknown): SemanticValidationError[] {
  const rule = "proposal-argument-hash-v1";
  if (!isRecord(value) || !isRecord(value["hashContract"]) || !isRecord(value["proposal"])) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_STRUCTURE_INVALID",
        rule,
        "",
        "proposal argument hash fixture is incomplete",
      ),
    ];
  }

  const hashContract = value["hashContract"];
  const proposal = value["proposal"];
  if (
    hashContract["algorithm"] !== "SHA-256" ||
    hashContract["domainUtf8"] !== PROPOSAL_ARGUMENT_HASH_DOMAIN ||
    hashContract["separatorHex"] !== "00" ||
    hashContract["canonicalization"] !== "RFC8785" ||
    !isRecord(proposal["arguments"])
  ) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_CONTRACT_INVALID",
        rule,
        "/hashContract",
        "proposal argument hash contract metadata is invalid",
      ),
    ];
  }

  const canonical = canonicalizeJson(proposal["arguments"]);
  if (canonical === undefined) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICALIZATION_INVALID",
        rule,
        "/proposal/arguments",
        "proposal arguments cannot be canonicalized as RFC 8785 JSON",
      ),
    ];
  }
  if (hashContract["canonicalArguments"] !== canonical) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICAL_MISMATCH",
        rule,
        "/hashContract/canonicalArguments",
        "documented canonical arguments do not match the proposal",
      ),
    ];
  }

  const canonicalBytes = Buffer.from(canonical, "utf8");
  if (hashContract["canonicalUtf8ByteLength"] !== canonicalBytes.length) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_CANONICAL_LENGTH_MISMATCH",
        rule,
        "/hashContract/canonicalUtf8ByteLength",
        "documented canonical argument length is incorrect",
      ),
    ];
  }

  const expectedHash = sha256(
    Buffer.concat([
      Buffer.from(PROPOSAL_ARGUMENT_HASH_DOMAIN, "utf8"),
      Buffer.from([0]),
      canonicalBytes,
    ]),
  );
  if (hashContract["argumentHash"] !== expectedHash || proposal["argumentHash"] !== expectedHash) {
    return [
      semanticError(
        "PROPOSAL_ARGUMENT_HASH_MISMATCH",
        rule,
        "/proposal/argumentHash",
        "proposal argument hash does not match the canonical arguments",
      ),
    ];
  }
  return [];
}

function decodeContent(
  compressedContent: Buffer,
  compression: string,
  declaredUncompressedBytes: number | undefined,
): { readonly content?: Buffer; readonly error?: string } {
  if (
    declaredUncompressedBytes === undefined ||
    declaredUncompressedBytes < 1 ||
    declaredUncompressedBytes > BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES
  ) {
    return {
      error: `declared uncompressed length must be between 1 and ${BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES} bytes`,
    };
  }

  try {
    switch (compression.toLowerCase()) {
      case "identity":
        return { content: compressedContent };
      case "gzip":
        return decodeSingleGzipMember(compressedContent, declaredUncompressedBytes);
      default:
        return { error: `unsupported build preview encoding ${JSON.stringify(compression)}` };
    }
  } catch (error) {
    return {
      error: `unable to decompress transfer content: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

function decodeSingleGzipMember(
  source: Buffer,
  maximumOutputLength: number,
): { readonly content?: Buffer; readonly error?: string } {
  if (source.length < 18 || source[0] !== 0x1f || source[1] !== 0x8b || source[2] !== 8) {
    return { error: "gzip header is invalid" };
  }
  const flags = source[3] ?? 0;
  if ((flags & 0xe0) !== 0) {
    return { error: "gzip reserved flags are set" };
  }
  if ((flags & 0x02) !== 0) {
    return { error: "gzip header checksum flag is unsupported" };
  }
  let offset = 10;
  const requireBytes = (count: number): boolean => offset + count <= source.length - 8;
  if ((flags & 0x04) !== 0) {
    if (!requireBytes(2)) return { error: "gzip extra header is truncated" };
    const length = source.readUInt16LE(offset);
    offset += 2;
    if (!requireBytes(length)) return { error: "gzip extra field is truncated" };
    offset += length;
  }
  for (const flag of [0x08, 0x10]) {
    if ((flags & flag) === 0) continue;
    while (offset < source.length - 8 && source[offset] !== 0) offset += 1;
    if (offset >= source.length - 8) return { error: "gzip string header is truncated" };
    offset += 1;
  }
  try {
    const inflated = inflateRawSync(source.subarray(offset), {
      info: true,
      maxOutputLength: Math.min(maximumOutputLength, BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES),
    }) as unknown as {
      readonly buffer: Buffer;
      readonly engine: { readonly bytesWritten: number };
    };
    const consumed = inflated.engine.bytesWritten;
    const trailer = offset + consumed;
    if (trailer + 8 !== source.length) {
      return { error: "gzip must contain exactly one member with no trailing data" };
    }
    const expectedCrc = source.readUInt32LE(trailer);
    const expectedSize = source.readUInt32LE(trailer + 4);
    if (
      crc32(inflated.buffer) !== expectedCrc ||
      inflated.buffer.length % 0x1_0000_0000 !== expectedSize
    ) {
      return { error: "gzip trailer checksum or size is invalid" };
    }
    return { content: inflated.buffer };
  } catch (error) {
    return {
      error: `unable to inflate gzip member: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

let crcTable: readonly number[] | undefined;

function crc32(content: Buffer): number {
  crcTable ??= Array.from({ length: 256 }, (_, index) => {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value & 1) === 0 ? value >>> 1 : 0xedb8_8320 ^ (value >>> 1);
    }
    return value >>> 0;
  });
  let value = 0xffff_ffff;
  for (const byte of content) {
    value = (crcTable[(value ^ byte) & 0xff] ?? 0) ^ (value >>> 8);
  }
  return (value ^ 0xffff_ffff) >>> 0;
}

interface BuildContentIssue {
  readonly code: string;
  readonly path: string;
  readonly message: string;
}

function validateBuildContent(
  metadata: JsonRecord,
  content: Buffer,
): BuildContentIssue | undefined {
  const text = content.toString("utf8");
  if (!Buffer.from(text, "utf8").equals(content)) {
    return {
      code: "CONTENT_JSON_INVALID",
      path: "/chunks",
      message: "content is not strict UTF-8",
    };
  }
  let parsed: unknown;
  try {
    parsed = parseStrictJson(text, { maximumDepth: 16, maximumTokens: 1_500_000 });
  } catch {
    return {
      code: "CONTENT_JSON_INVALID",
      path: "/chunks",
      message: "content is not duplicate-free strict JSON",
    };
  }
  if (!isRecord(parsed) || parsed["version"] !== 1 || !Array.isArray(parsed["blocks"])) {
    return {
      code: "CONTENT_JSON_INVALID",
      path: "/chunks",
      message: "palette-v1 shape is invalid",
    };
  }
  const canonical = canonicalizeJson(
    parsed,
    BUILD_PREVIEW_UNCOMPRESSED_HARD_LIMIT_BYTES,
    1_500_000,
    16,
  );
  if (canonical !== text) {
    return {
      code: "CONTENT_CANONICAL_MISMATCH",
      path: "/chunks",
      message: "palette-v1 content must be its RFC 8785 representation",
    };
  }

  const palette = metadata["palette"];
  if (!Array.isArray(palette)) {
    return { code: "PALETTE_ID_INVALID", path: "/palette", message: "palette is missing" };
  }
  const paletteCanonical = canonicalizeJson(palette, 1_048_576, 65_536, 16);
  if (
    paletteCanonical === undefined ||
    sha256(Buffer.from(paletteCanonical, "utf8")) !== metadata["paletteHash"]
  ) {
    return {
      code: "PALETTE_HASH_MISMATCH",
      path: "/paletteHash",
      message: "paletteHash does not match the canonical palette",
    };
  }
  const states = new Set<string>();
  let previousState: string | undefined;
  for (let index = 0; index < palette.length; index += 1) {
    const entry = palette[index];
    if (!isRecord(entry) || entry["id"] !== index || !isRecord(entry["properties"])) {
      return {
        code: "PALETTE_ID_INVALID",
        path: `/palette/${String(index)}`,
        message: "palette IDs must be contiguous and match array order",
      };
    }
    const blockId = entry["blockId"];
    if (typeof blockId !== "string" || blockId === "minecraft:air") {
      return {
        code: "PALETTE_STATE_INVALID",
        path: `/palette/${String(index)}/blockId`,
        message: "air and malformed states cannot be explicit palette entries",
      };
    }
    const properties = entry["properties"];
    const pairs: string[] = [];
    for (const key of Object.keys(properties).sort()) {
      const property = properties[key];
      if (typeof property !== "string") {
        return {
          code: "PALETTE_STATE_INVALID",
          path: `/palette/${String(index)}/properties`,
          message: "palette property values must be strings",
        };
      }
      pairs.push(`${key}=${property}`);
    }
    const state = `${blockId}${pairs.length === 0 ? "" : `[${pairs.join(",")}]`}`;
    if (states.has(state)) {
      return {
        code: "PALETTE_STATE_DUPLICATE",
        path: `/palette/${String(index)}`,
        message: "canonical palette states must be unique",
      };
    }
    // Match Java String.compareTo: protocol state strings are ASCII and sort by code unit.
    if (previousState !== undefined && previousState >= state) {
      return {
        code: "PALETTE_ORDER_INVALID",
        path: `/palette/${String(index)}`,
        message: "canonical palette states must be sorted",
      };
    }
    states.add(state);
    previousState = state;
  }

  const bounds = metadata["bounds"];
  const minimum = isRecord(bounds) && isRecord(bounds["min"]) ? bounds["min"] : undefined;
  const maximum = isRecord(bounds) && isRecord(bounds["max"]) ? bounds["max"] : undefined;
  const coordinate = (position: JsonRecord | undefined, key: string): number | undefined => {
    const value = position?.[key];
    return typeof value === "number" && Number.isSafeInteger(value) ? value : undefined;
  };
  const minX = coordinate(minimum, "x");
  const minY = coordinate(minimum, "y");
  const minZ = coordinate(minimum, "z");
  const maxX = coordinate(maximum, "x");
  const maxY = coordinate(maximum, "y");
  const maxZ = coordinate(maximum, "z");
  const origin = isRecord(metadata["origin"]) ? metadata["origin"] : undefined;
  const originX = coordinate(origin, "x");
  const originY = coordinate(origin, "y");
  const originZ = coordinate(origin, "z");
  if (
    minX === undefined ||
    minY === undefined ||
    minZ === undefined ||
    maxX === undefined ||
    maxY === undefined ||
    maxZ === undefined ||
    minX > maxX ||
    minY > maxY ||
    minZ > maxZ ||
    originX === undefined ||
    originY === undefined ||
    originZ === undefined ||
    originX < minX ||
    originX > maxX ||
    originY < minY ||
    originY > maxY ||
    originZ < minZ ||
    originZ > maxZ ||
    maxX - minX + 1 > 32 ||
    maxY - minY + 1 > 32 ||
    maxZ - minZ + 1 > 32
  ) {
    return {
      code: "BLOCK_GEOMETRY_INVALID",
      path: "/bounds",
      message: "bounds or origin violate the preview geometry policy",
    };
  }
  const volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
  if (volume > 4096) {
    return {
      code: "BLOCK_GEOMETRY_INVALID",
      path: "/bounds",
      message: "bounds exceed the preview volume policy",
    };
  }
  const blocks = parsed["blocks"];
  if (metadata["blockCount"] !== blocks.length || blocks.length > volume) {
    return {
      code: "BLOCK_COUNT_MISMATCH",
      path: "/blockCount",
      message: "blockCount must equal the complete non-air target block list",
    };
  }
  let previous: readonly [number, number, number] | undefined;
  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    if (!isRecord(block)) {
      return {
        code: "BLOCK_GEOMETRY_INVALID",
        path: `/blocks/${String(index)}`,
        message: "block is invalid",
      };
    }
    const x = block["x"];
    const y = block["y"];
    const z = block["z"];
    const state = block["state"];
    if (
      typeof x !== "number" ||
      !Number.isSafeInteger(x) ||
      typeof y !== "number" ||
      !Number.isSafeInteger(y) ||
      typeof z !== "number" ||
      !Number.isSafeInteger(z) ||
      typeof state !== "number" ||
      !Number.isSafeInteger(state) ||
      x < minX ||
      x > maxX ||
      y < minY ||
      y > maxY ||
      z < minZ ||
      z > maxZ ||
      state < 0 ||
      state >= palette.length
    ) {
      return {
        code: "BLOCK_GEOMETRY_INVALID",
        path: `/blocks/${String(index)}`,
        message: "block position or palette reference is invalid",
      };
    }
    const current = [y, z, x] as const;
    if (
      previous !== undefined &&
      (current[0] < previous[0] ||
        (current[0] === previous[0] && current[1] < previous[1]) ||
        (current[0] === previous[0] && current[1] === previous[1] && current[2] <= previous[2]))
    ) {
      return {
        code: "BLOCK_ORDER_INVALID",
        path: `/blocks/${String(index)}`,
        message: "blocks must be unique and sorted by y, z, x",
      };
    }
    previous = current;
  }
  const difference = metadata["difference"];
  if (
    !isRecord(difference) ||
    !["added", "replaced", "removed"].every(
      (key) =>
        typeof difference[key] === "number" &&
        Number.isSafeInteger(difference[key]) &&
        (difference[key] as number) >= 0,
    ) ||
    (difference["added"] as number) +
      (difference["replaced"] as number) +
      (difference["removed"] as number) >
      volume
  ) {
    return {
      code: "DIFFERENCE_COUNT_INVALID",
      path: "/difference",
      message: "difference counts exceed the bounded region",
    };
  }
  return undefined;
}

export function validateProtocolVersion(
  value: unknown,
  supportedVersion = SUPPORTED_PROTOCOL_VERSION,
): SemanticValidationError[] {
  const errors: SemanticValidationError[] = [];
  const visited = new WeakSet<object>();

  function visit(candidate: unknown, path: string): void {
    if (typeof candidate !== "object" || candidate === null || visited.has(candidate)) {
      return;
    }
    visited.add(candidate);

    if (Array.isArray(candidate)) {
      candidate.forEach((item, index) => visit(item, childPath(path, index)));
      return;
    }

    for (const [name, nested] of Object.entries(candidate)) {
      const nestedPath = childPath(path, name);
      const versionSupported =
        name === "clientProtocolVersion"
          ? nested === null ||
            (typeof nested === "string" && SUPPORTED_CLIENT_PROTOCOL_VERSIONS.has(nested))
          : nested === supportedVersion;
      if ((name === "protocolVersion" || name === "clientProtocolVersion") && !versionSupported) {
        errors.push(
          semanticError(
            "PROTOCOL_VERSION_UNSUPPORTED",
            "protocol-version-compatible",
            nestedPath,
            `unsupported protocol version ${JSON.stringify(nested)}; expected ${JSON.stringify(supportedVersion)}`,
          ),
        );
      }
      visit(nested, nestedPath);
    }
  }

  visit(value, "");
  return errors;
}

export function validateBuildPreviewChunks(value: unknown): SemanticValidationError[] {
  const rule = "build-preview-transfer-v1";
  const collections = findChunkCollections(value);

  if (collections.length === 0) {
    return [
      semanticError("CHUNK_SET_INCOMPLETE", rule, "", "no transfer chunk collection was found"),
    ];
  }

  for (const collection of collections) {
    const expectedChunkCount = numberField(collection.metadataRecords, CHUNK_COUNT_FIELDS);
    const seenIndexes = new Set<number>();
    for (const chunk of collection.chunks) {
      if (seenIndexes.has(chunk.index)) {
        return [
          semanticError(
            "CHUNK_INDEX_DUPLICATE",
            rule,
            chunk.path,
            `chunkIndex ${chunk.index} is duplicated`,
          ),
        ];
      }
      seenIndexes.add(chunk.index);
    }

    const sortedIndexes = [...seenIndexes].sort((left, right) => left - right);
    if (
      expectedChunkCount === undefined ||
      expectedChunkCount !== collection.chunks.length ||
      sortedIndexes.some((index, position) => index !== position)
    ) {
      return [
        semanticError(
          "CHUNK_SET_INCOMPLETE",
          rule,
          collection.path,
          "chunk indexes must be exactly 0..chunkCount-1",
        ),
      ];
    }

    const totalCompressedBytes = numberField(
      collection.metadataRecords,
      TOTAL_COMPRESSED_BYTES_FIELDS,
    );
    if (
      totalCompressedBytes === undefined ||
      totalCompressedBytes < 1 ||
      totalCompressedBytes > BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES
    ) {
      return [
        semanticError(
          "CONTENT_COMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          "declared compressed length is missing or exceeds the hard limit",
        ),
      ];
    }

    const orderedChunks = [...collection.chunks].sort((left, right) => left.index - right.index);
    const decodedChunks: Buffer[] = [];
    let decodedByteCount = 0;
    for (const chunk of orderedChunks) {
      const chunkRecords = [chunk.record];
      const decoded = decodeCanonicalBase64(chunk.encodedData);
      if (decoded === undefined) {
        return [
          semanticError(
            "CHUNK_BASE64_INVALID",
            rule,
            chunk.path,
            "chunk data is not canonical base64",
          ),
        ];
      }
      decodedByteCount += decoded.length;
      if (
        decodedByteCount > totalCompressedBytes ||
        decodedByteCount > BUILD_PREVIEW_COMPRESSED_HARD_LIMIT_BYTES
      ) {
        return [
          semanticError(
            "CONTENT_COMPRESSED_LENGTH_MISMATCH",
            rule,
            chunk.path,
            "decoded chunks exceed the declared compressed length or hard limit",
          ),
        ];
      }
      decodedChunks.push(decoded);

      const declaredChunkBytes = numberField(chunkRecords, CHUNK_BYTES_FIELDS);
      if (declaredChunkBytes === undefined || declaredChunkBytes !== decoded.length) {
        return [
          semanticError(
            "CHUNK_LENGTH_MISMATCH",
            rule,
            chunk.path,
            `declared chunk byte length ${String(declaredChunkBytes)} does not match decoded length ${decoded.length}`,
          ),
        ];
      }

      const declaredChunkHash = stringField(chunkRecords, CHUNK_HASH_FIELDS);
      if (declaredChunkHash === undefined || sha256(decoded) !== declaredChunkHash.toLowerCase()) {
        return [
          semanticError(
            "CHUNK_HASH_MISMATCH",
            rule,
            chunk.path,
            "chunk sha256 does not match decoded chunk data",
          ),
        ];
      }
    }

    const compressedContent = Buffer.concat(decodedChunks, decodedByteCount);
    if (totalCompressedBytes !== compressedContent.length) {
      return [
        semanticError(
          "CONTENT_COMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          `declared compressed length ${String(totalCompressedBytes)} does not match ${compressedContent.length} transfer bytes`,
        ),
      ];
    }

    const totalUncompressedBytes = numberField(
      collection.metadataRecords,
      TOTAL_UNCOMPRESSED_BYTES_FIELDS,
    );
    const compression = (
      stringField(collection.metadataRecords, ["compression", "encoding"]) ?? "identity"
    ).replace(/\+base64$/u, "");
    const decodedContent = decodeContent(compressedContent, compression, totalUncompressedBytes);
    if (decodedContent.error !== undefined || decodedContent.content === undefined) {
      return [
        semanticError(
          "CONTENT_DECOMPRESSION_FAILED",
          rule,
          collection.path,
          decodedContent.error ?? "unable to decode transfer content",
        ),
      ];
    }

    if (
      totalUncompressedBytes === undefined ||
      totalUncompressedBytes !== decodedContent.content.length
    ) {
      return [
        semanticError(
          "CONTENT_UNCOMPRESSED_LENGTH_MISMATCH",
          rule,
          collection.path,
          `declared uncompressed length ${String(totalUncompressedBytes)} does not match ${decodedContent.content.length} content bytes`,
        ),
      ];
    }

    const contentHash = stringField(collection.metadataRecords, CONTENT_HASH_FIELDS);
    if (contentHash === undefined || sha256(decodedContent.content) !== contentHash.toLowerCase()) {
      return [
        semanticError(
          "CONTENT_HASH_MISMATCH",
          rule,
          collection.path,
          "contentHash does not match reassembled uncompressed content",
        ),
      ];
    }
    const metadata = collection.metadataRecords.find(
      (record) => record["contentFormat"] === "minecraft-agent.palette-v1",
    );
    if (metadata !== undefined) {
      const contentIssue = validateBuildContent(metadata, decodedContent.content);
      if (contentIssue !== undefined) {
        return [semanticError(contentIssue.code, rule, contentIssue.path, contentIssue.message)];
      }
    }
  }

  return [];
}

export function validateRecipeView(value: unknown): SemanticValidationError[] {
  const rule = "recipe-view-v1";
  if (!isRecord(value) || !Array.isArray(value["recipes"])) {
    return [
      semanticError(
        "RECIPE_VIEW_STRUCTURE_INVALID",
        rule,
        "",
        "recipe view must contain a recipes array",
      ),
    ];
  }

  const errors: SemanticValidationError[] = [];
  const recipes = value["recipes"];
  const selectedRecipe = value["selectedRecipe"];
  if (
    typeof selectedRecipe !== "number" ||
    !Number.isSafeInteger(selectedRecipe) ||
    selectedRecipe < 0 ||
    selectedRecipe >= recipes.length
  ) {
    errors.push(
      semanticError(
        "RECIPE_SELECTED_INDEX_OUT_OF_RANGE",
        rule,
        "/selectedRecipe",
        "selectedRecipe must identify an entry in recipes",
      ),
    );
  }

  const recipeIds = new Set<string>();
  recipes.forEach((recipe, recipeIndex) => {
    if (!isRecord(recipe)) {
      return;
    }
    const recipePath = `/recipes/${recipeIndex}`;
    const recipeId = recipe["recipeId"];
    if (typeof recipeId === "string") {
      if (recipeIds.has(recipeId)) {
        errors.push(
          semanticError(
            "RECIPE_ID_DUPLICATE",
            rule,
            `${recipePath}/recipeId`,
            `recipeId ${JSON.stringify(recipeId)} is duplicated`,
          ),
        );
      }
      recipeIds.add(recipeId);
    }

    const layout = recipe["layout"];
    if (!isRecord(layout) || !Array.isArray(layout["ingredients"])) {
      return;
    }
    const width = layout["width"];
    const height = layout["height"];
    if (typeof width !== "number" || typeof height !== "number") {
      return;
    }

    const slots = new Set<number>();
    const positions = new Set<string>();
    layout["ingredients"].forEach((ingredient, ingredientIndex) => {
      if (!isRecord(ingredient)) {
        return;
      }
      const ingredientPath = `${recipePath}/layout/ingredients/${ingredientIndex}`;
      const slot = ingredient["slot"];
      const x = ingredient["x"];
      const y = ingredient["y"];
      if (typeof x === "number" && typeof y === "number") {
        if (x < 0 || x >= width || y < 0 || y >= height) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_OUT_OF_BOUNDS",
              rule,
              ingredientPath,
              `ingredient position (${x}, ${y}) is outside ${width}x${height} layout`,
            ),
          );
        }
        const position = `${x},${y}`;
        if (positions.has(position)) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_DUPLICATE",
              rule,
              ingredientPath,
              `ingredient position (${x}, ${y}) is duplicated`,
            ),
          );
        }
        positions.add(position);
      }
      if (typeof slot === "number") {
        if (slots.has(slot)) {
          errors.push(
            semanticError(
              "RECIPE_INGREDIENT_DUPLICATE",
              rule,
              `${ingredientPath}/slot`,
              `ingredient slot ${slot} is duplicated`,
            ),
          );
        }
        slots.add(slot);
      }
      if (
        typeof slot === "number" &&
        typeof x === "number" &&
        typeof y === "number" &&
        slot !== y * width + x
      ) {
        errors.push(
          semanticError(
            "RECIPE_SLOT_COORDINATE_MISMATCH",
            rule,
            `${ingredientPath}/slot`,
            `ingredient slot ${slot} does not match row-major index ${y * width + x}`,
          ),
        );
      }
    });
  });

  return errors;
}

interface RecipeV2Inspection {
  readonly recipe: JsonRecord;
  readonly path: string;
  readonly logicalSlots: Set<number>;
  readonly choices: Array<{ readonly value: unknown; readonly path: string }>;
  readonly itemStacks: Array<{ readonly value: unknown; readonly path: string }>;
}

const RECIPE_V2_COOKING_TYPES = new Set(["smelting", "blasting", "smoking", "campfire_cooking"]);

function recipeV2Failure(
  code: string,
  instancePath: string,
  message: string,
): SemanticValidationError[] {
  return [semanticError(code, "recipe-view-v2", instancePath, message)];
}

function expectedRecipeV2Layout(recipeType: unknown): string | undefined {
  if (recipeType === "shaped" || recipeType === "shapeless") {
    return "grid";
  }
  if (RECIPE_V2_COOKING_TYPES.has(String(recipeType)) || recipeType === "stonecutting") {
    return "single_input";
  }
  if (recipeType === "smithing_transform" || recipeType === "smithing_trim") {
    return "smithing";
  }
  if (recipeType === "transmute") {
    return "transmute";
  }
  if (recipeType === "complex" || recipeType === "custom") {
    return "unsupported";
  }
  return undefined;
}

function inspectRecipeV2Layout(
  inspection: RecipeV2Inspection,
): SemanticValidationError | undefined {
  const layout = inspection.recipe["layout"];
  if (!isRecord(layout)) {
    return semanticError(
      "RECIPE_LAYOUT_INVALID",
      "recipe-view-v2",
      `${inspection.path}/layout`,
      "recipe layout must be an object",
    );
  }
  const expectedKind = expectedRecipeV2Layout(inspection.recipe["recipeType"]);
  if (expectedKind === undefined || layout["kind"] !== expectedKind) {
    return semanticError(
      "RECIPE_LAYOUT_INVALID",
      "recipe-view-v2",
      `${inspection.path}/layout/kind`,
      "recipe type and layout kind do not agree",
    );
  }

  if (expectedKind === "grid") {
    const width = layout["width"];
    const height = layout["height"];
    const ingredients = layout["ingredients"];
    if (
      !Number.isSafeInteger(width) ||
      !Number.isSafeInteger(height) ||
      Number(width) < 1 ||
      Number(width) > 3 ||
      Number(height) < 1 ||
      Number(height) > 3 ||
      !Array.isArray(ingredients) ||
      ingredients.length < 1 ||
      ingredients.length > 9
    ) {
      return semanticError(
        "RECIPE_LAYOUT_INVALID",
        "recipe-view-v2",
        `${inspection.path}/layout`,
        "grid dimensions and ingredients must remain bounded",
      );
    }
    const slots = new Set<number>();
    const positions = new Set<string>();
    for (let index = 0; index < ingredients.length; index += 1) {
      const ingredient = ingredients[index];
      const ingredientPath = `${inspection.path}/layout/ingredients/${index}`;
      if (!isRecord(ingredient)) {
        return semanticError(
          "RECIPE_LAYOUT_INVALID",
          "recipe-view-v2",
          ingredientPath,
          "grid ingredient must be an object",
        );
      }
      const slot = ingredient["slot"];
      const x = ingredient["x"];
      const y = ingredient["y"];
      if (!Number.isSafeInteger(slot) || !Number.isSafeInteger(x) || !Number.isSafeInteger(y)) {
        return semanticError(
          "RECIPE_LAYOUT_INVALID",
          "recipe-view-v2",
          ingredientPath,
          "grid ingredient coordinates must be integers",
        );
      }
      const slotNumber = Number(slot);
      const xNumber = Number(x);
      const yNumber = Number(y);
      if (xNumber < 0 || xNumber >= Number(width) || yNumber < 0 || yNumber >= Number(height)) {
        return semanticError(
          "RECIPE_INGREDIENT_OUT_OF_BOUNDS",
          "recipe-view-v2",
          ingredientPath,
          "grid ingredient is outside the declared dimensions",
        );
      }
      const position = `${xNumber},${yNumber}`;
      if (slots.has(slotNumber) || positions.has(position)) {
        return semanticError(
          "RECIPE_INGREDIENT_DUPLICATE",
          "recipe-view-v2",
          ingredientPath,
          "grid ingredient repeats a slot or coordinate",
        );
      }
      if (slotNumber !== yNumber * Number(width) + xNumber) {
        return semanticError(
          "RECIPE_SLOT_COORDINATE_MISMATCH",
          "recipe-view-v2",
          `${ingredientPath}/slot`,
          "grid slot does not match its row-major coordinate",
        );
      }
      slots.add(slotNumber);
      positions.add(position);
      inspection.logicalSlots.add(slotNumber);
      inspection.choices.push({
        value: ingredient["ingredient"],
        path: `${ingredientPath}/ingredient`,
      });
    }
    return undefined;
  }

  if (expectedKind === "single_input") {
    inspection.logicalSlots.add(0);
    inspection.choices.push({
      value: layout["ingredient"],
      path: `${inspection.path}/layout/ingredient`,
    });
  } else if (expectedKind === "smithing") {
    ["template", "base", "addition"].forEach((field, slot) => {
      inspection.logicalSlots.add(slot);
      inspection.choices.push({
        value: layout[field],
        path: `${inspection.path}/layout/${field}`,
      });
    });
  } else if (expectedKind === "transmute") {
    ["input", "material"].forEach((field, slot) => {
      inspection.logicalSlots.add(slot);
      inspection.choices.push({
        value: layout[field],
        path: `${inspection.path}/layout/${field}`,
      });
    });
  } else if (layout["reason"] !== "UNSUPPORTED_RECIPE_LAYOUT") {
    return semanticError(
      "RECIPE_LAYOUT_INVALID",
      "recipe-view-v2",
      `${inspection.path}/layout/reason`,
      "unsupported layout must carry its stable reason",
    );
  }
  return undefined;
}

function inspectRecipeV2Choice(
  inspection: RecipeV2Inspection,
  choice: { readonly value: unknown; readonly path: string },
): SemanticValidationError | undefined {
  if (!isRecord(choice.value) || !Array.isArray(choice.value["alternatives"])) {
    return semanticError(
      "RECIPE_INGREDIENT_CHOICE_INVALID",
      "recipe-view-v2",
      choice.path,
      "ingredient choice must contain an alternatives array",
    );
  }
  const choiceType = choice.value["choiceType"];
  const alternatives = choice.value["alternatives"];
  if (choiceType === "unsupported") {
    if (
      choice.value["reason"] !== "UNSUPPORTED_INGREDIENT_CHOICE" ||
      alternatives.length !== 0 ||
      Object.hasOwn(choice.value, "tagId")
    ) {
      return semanticError(
        "RECIPE_INGREDIENT_CHOICE_INVALID",
        "recipe-view-v2",
        choice.path,
        "unsupported choice must retain its stable reason and no alternatives",
      );
    }
    return undefined;
  }
  if (
    choiceType !== "material" &&
    choiceType !== "exact" &&
    choiceType !== "item_type" &&
    choiceType !== "tag"
  ) {
    return semanticError(
      "RECIPE_INGREDIENT_CHOICE_INVALID",
      "recipe-view-v2",
      `${choice.path}/choiceType`,
      "ingredient choice type is unsupported",
    );
  }
  if (
    alternatives.length < 1 ||
    alternatives.length > 64 ||
    (choiceType === "tag") !== Object.hasOwn(choice.value, "tagId") ||
    Object.hasOwn(choice.value, "reason")
  ) {
    return semanticError(
      "RECIPE_INGREDIENT_CHOICE_INVALID",
      "recipe-view-v2",
      choice.path,
      "choice alternatives or tag metadata are inconsistent",
    );
  }
  alternatives.forEach((value, index) => {
    inspection.itemStacks.push({ value, path: `${choice.path}/alternatives/${index}` });
  });
  return undefined;
}

function recipeV2ComponentInvalid(value: unknown): boolean {
  if (!isRecord(value) || !isRecord(value["components"])) {
    return true;
  }
  const components = value["components"];
  const damage = components["damage"];
  const maximumDamage = components["maxDamage"];
  const unsafeDisplayText = (text: unknown): boolean => {
    if (typeof text !== "string" || !hasValidUnicode(text)) {
      return true;
    }
    return [...text].some((character) => {
      const codePoint = character.codePointAt(0);
      return (
        codePoint === undefined ||
        codePoint <= 0x1f ||
        (codePoint >= 0x7f && codePoint <= 0x9f) ||
        codePoint === 0x061c ||
        codePoint === 0x200e ||
        codePoint === 0x200f ||
        (codePoint >= 0x202a && codePoint <= 0x202e) ||
        (codePoint >= 0x2066 && codePoint <= 0x2069)
      );
    });
  };
  const customName = components["customName"];
  const lore = components["lore"];
  return (
    (typeof damage === "number" && typeof maximumDamage === "number" && damage > maximumDamage) ||
    (customName !== undefined && unsafeDisplayText(customName)) ||
    (lore !== undefined && (!Array.isArray(lore) || lore.some((line) => unsafeDisplayText(line))))
  );
}

/** Applies recipe-view-v2 semantic rules in stable first-error order. */
export function validateRecipeViewV2(value: unknown): SemanticValidationError[] {
  if (
    !isRecord(value) ||
    value["schemaVersion"] !== "2.0" ||
    !Array.isArray(value["recipes"]) ||
    value["recipes"].length < 1 ||
    value["recipes"].length > 16
  ) {
    return recipeV2Failure(
      "RECIPE_VIEW_STRUCTURE_INVALID",
      "",
      "recipe view v2 must contain one to sixteen recipes",
    );
  }
  const recipes = value["recipes"];
  const selectedRecipe = value["selectedRecipe"];
  if (
    !Number.isSafeInteger(selectedRecipe) ||
    Number(selectedRecipe) < 0 ||
    Number(selectedRecipe) >= recipes.length
  ) {
    return recipeV2Failure(
      "RECIPE_SELECTED_INDEX_OUT_OF_RANGE",
      "/selectedRecipe",
      "selectedRecipe must identify an entry in recipes",
    );
  }

  const totalMatches = value["totalMatches"];
  const truncated = value["truncated"];
  if (
    !Number.isSafeInteger(totalMatches) ||
    Number(totalMatches) < recipes.length ||
    typeof truncated !== "boolean" ||
    (!truncated && Number(totalMatches) !== recipes.length)
  ) {
    return recipeV2Failure(
      "RECIPE_RESULT_SUMMARY_INVALID",
      "/totalMatches",
      "totalMatches and truncated do not describe the published recipes",
    );
  }

  const inspections: RecipeV2Inspection[] = [];
  const recipeIds = new Set<string>();
  for (let index = 0; index < recipes.length; index += 1) {
    const recipe = recipes[index];
    if (!isRecord(recipe) || typeof recipe["recipeId"] !== "string") {
      return recipeV2Failure(
        "RECIPE_VIEW_STRUCTURE_INVALID",
        `/recipes/${index}`,
        "recipe entry is incomplete",
      );
    }
    if (recipeIds.has(recipe["recipeId"])) {
      return recipeV2Failure(
        "RECIPE_ID_DUPLICATE",
        `/recipes/${index}/recipeId`,
        "recipe IDs must identify distinct variants",
      );
    }
    recipeIds.add(recipe["recipeId"]);
    const inspection: RecipeV2Inspection = {
      recipe,
      path: `/recipes/${index}`,
      logicalSlots: new Set(),
      choices: [],
      itemStacks: [],
    };
    if (recipe["result"] !== null) {
      inspection.itemStacks.push({ value: recipe["result"], path: `${inspection.path}/result` });
    }
    inspections.push(inspection);
  }

  for (const inspection of inspections) {
    const error = inspectRecipeV2Layout(inspection);
    if (error !== undefined) {
      return [error];
    }
  }
  for (const inspection of inspections) {
    for (const choice of inspection.choices) {
      const error = inspectRecipeV2Choice(inspection, choice);
      if (error !== undefined) {
        return [error];
      }
    }
  }
  for (const inspection of inspections) {
    const processing = inspection.recipe["processing"];
    const cooking = RECIPE_V2_COOKING_TYPES.has(String(inspection.recipe["recipeType"]));
    if (cooking) {
      if (
        !isRecord(processing) ||
        !Number.isSafeInteger(processing["timeTicks"]) ||
        Number(processing["timeTicks"]) < 0 ||
        Number(processing["timeTicks"]) > 120_000 ||
        typeof processing["experience"] !== "number" ||
        !Number.isFinite(processing["experience"]) ||
        processing["experience"] < 0 ||
        processing["experience"] > 1_000_000
      ) {
        return recipeV2Failure(
          "RECIPE_PROCESSING_INVALID",
          `${inspection.path}/processing`,
          "cooking recipes require bounded processing data",
        );
      }
    } else if (processing !== undefined) {
      return recipeV2Failure(
        "RECIPE_PROCESSING_INVALID",
        `${inspection.path}/processing`,
        "non-cooking recipes must not carry processing data",
      );
    }
  }
  for (const inspection of inspections) {
    const source = inspection.recipe["source"];
    if (
      !isRecord(source) ||
      (source["kind"] === "server_registry" && source["providerId"] !== null) ||
      (source["kind"] === "plugin_provider" && typeof source["providerId"] !== "string") ||
      (source["kind"] !== "server_registry" && source["kind"] !== "plugin_provider")
    ) {
      return recipeV2Failure(
        "RECIPE_SOURCE_INVALID",
        `${inspection.path}/source`,
        "recipe source and provider identity do not agree",
      );
    }
  }
  for (const inspection of inspections) {
    const remainingItems = inspection.recipe["remainingItems"];
    if (!Array.isArray(remainingItems)) {
      return recipeV2Failure(
        "RECIPE_REMAINING_ITEM_INVALID",
        `${inspection.path}/remainingItems`,
        "remainingItems must be an array",
      );
    }
    const remainingSlots = new Set<number>();
    for (let index = 0; index < remainingItems.length; index += 1) {
      const remaining = remainingItems[index];
      const path = `${inspection.path}/remainingItems/${index}`;
      if (!isRecord(remaining) || !Number.isSafeInteger(remaining["slot"])) {
        return recipeV2Failure(
          "RECIPE_REMAINING_ITEM_INVALID",
          path,
          "remaining item must identify an integer input slot",
        );
      }
      const slot = Number(remaining["slot"]);
      if (!inspection.logicalSlots.has(slot) || remainingSlots.has(slot)) {
        return recipeV2Failure(
          "RECIPE_REMAINING_ITEM_INVALID",
          `${path}/slot`,
          "remaining item slot is absent or duplicated",
        );
      }
      remainingSlots.add(slot);
      inspection.itemStacks.push({ value: remaining["item"], path: `${path}/item` });
    }
  }
  for (const inspection of inspections) {
    for (const stack of inspection.itemStacks) {
      if (recipeV2ComponentInvalid(stack.value)) {
        return recipeV2Failure(
          "RECIPE_COMPONENT_INVALID",
          `${stack.path}/components`,
          "item display components violate their cross-field invariants",
        );
      }
    }
  }
  return [];
}

export function validateCapabilityManifest(value: unknown): SemanticValidationError[] {
  const rule = "capability-manifest-v1";
  if (
    !isRecord(value) ||
    !isRecord(value["requirements"]) ||
    !Array.isArray(value["requirements"]["plugins"]) ||
    !isRecord(value["execution"]) ||
    !isRecord(value["arguments"]) ||
    !isRecord(value["effects"]) ||
    !isRecord(value["confirmation"]) ||
    !isRecord(value["reversibility"])
  ) {
    return [
      semanticError(
        "CAPABILITY_STRUCTURE_INVALID",
        rule,
        "",
        "capability manifest is missing a required closed object",
      ),
    ];
  }

  const execution = value["execution"];
  const argumentsRecord = value["arguments"];
  const plugins = value["requirements"]["plugins"];
  const effects = value["effects"];
  const confirmation = value["confirmation"];
  const template = execution["template"];
  const commandRoot = execution["commandRoot"];

  if (typeof template === "string") {
    const placeholderPattern = /\{([a-z][a-zA-Z\d_]{0,63})\}/gu;
    const matches = [...template.matchAll(placeholderPattern)];
    if (/[{}]/u.test(template.replace(placeholderPattern, ""))) {
      return [
        semanticError(
          "CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED",
          rule,
          "/execution/template",
          "template contains an incomplete or malformed placeholder",
        ),
      ];
    }

    for (const match of matches) {
      const start = match.index ?? -1;
      const end = start + match[0].length;
      if (
        start <= 0 ||
        template[start - 1] !== " " ||
        (end < template.length && template[end] !== " ")
      ) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED",
            rule,
            "/execution/template",
            "template placeholders must occupy complete command tokens",
          ),
        ];
      }
    }

    const placeholderNames = matches.map((match) => match[1]!);
    for (const placeholder of placeholderNames) {
      if (!Object.hasOwn(argumentsRecord, placeholder)) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED",
            rule,
            "/execution/template",
            `template placeholder ${JSON.stringify(placeholder)} is not declared in arguments`,
          ),
        ];
      }
    }

    const seenPlaceholders = new Set<string>();
    for (const placeholder of placeholderNames) {
      if (seenPlaceholders.has(placeholder)) {
        return [
          semanticError(
            "CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE",
            rule,
            "/execution/template",
            `template placeholder ${JSON.stringify(placeholder)} appears more than once`,
          ),
        ];
      }
      seenPlaceholders.add(placeholder);
    }

    for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
      if (
        isRecord(argument) &&
        argument["required"] === true &&
        !seenPlaceholders.has(argumentName)
      ) {
        return [
          semanticError(
            "CAPABILITY_REQUIRED_ARGUMENT_UNUSED",
            rule,
            `/arguments/${escapeJsonPointer(argumentName)}`,
            `required argument ${JSON.stringify(argumentName)} is not used by the template`,
          ),
        ];
      }
    }

    for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
      if (isRecord(argument) && argument["required"] === false) {
        return [
          semanticError(
            "CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED",
            rule,
            `/arguments/${escapeJsonPointer(argumentName)}`,
            "optional command template arguments are not supported in capability v1",
          ),
        ];
      }
    }

    const rootMatch = /^\/([^\s{]+)/u.exec(template);
    if (typeof commandRoot === "string" && rootMatch?.[1] !== commandRoot) {
      return [
        semanticError(
          "CAPABILITY_COMMAND_ROOT_MISMATCH",
          rule,
          "/execution/commandRoot",
          "commandRoot does not match the first command token in template",
        ),
      ];
    }
  }

  for (const [argumentName, argument] of Object.entries(argumentsRecord)) {
    if (!isRecord(argument)) {
      continue;
    }
    const minimum = argument["minimum"] ?? argument["minLength"];
    const maximum = argument["maximum"] ?? argument["maxLength"];
    if (typeof minimum === "number" && typeof maximum === "number" && minimum > maximum) {
      return [
        semanticError(
          "CAPABILITY_ARGUMENT_RANGE_INVALID",
          rule,
          `/arguments/${escapeJsonPointer(argumentName)}`,
          `argument minimum ${minimum} exceeds maximum ${maximum}`,
        ),
      ];
    }
  }

  const pluginNames = new Set<string>();
  for (let index = 0; index < plugins.length; index += 1) {
    const plugin = plugins[index];
    if (!isRecord(plugin) || typeof plugin["name"] !== "string") {
      return [
        semanticError(
          "CAPABILITY_STRUCTURE_INVALID",
          rule,
          `/requirements/plugins/${index}`,
          "plugin requirement is not a closed name and version object",
        ),
      ];
    }
    const normalizedName = plugin["name"].toLowerCase();
    if (pluginNames.has(normalizedName)) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_REQUIREMENT_DUPLICATE",
          rule,
          `/requirements/plugins/${index}/name`,
          `plugin ${JSON.stringify(plugin["name"])} is declared more than once`,
        ),
      ];
    }
    pluginNames.add(normalizedName);
    if (
      typeof plugin["version"] !== "string" ||
      !CAPABILITY_PLUGIN_VERSION_RANGE.test(plugin["version"]) ||
      plugin["version"].split(" ").length > 16
    ) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_RANGE_INVALID",
          rule,
          `/requirements/plugins/${index}/version`,
          "plugin version range must be an AND-list of closed numeric comparators",
        ),
      ];
    }
  }

  const effectCategory = effects["category"];
  const maximumBlocks = effects["maximumBlocks"];
  if (
    (effectCategory === "WRITE_WORLD" && typeof maximumBlocks !== "number") ||
    (effectCategory !== "WRITE_WORLD" && maximumBlocks !== null)
  ) {
    return [
      semanticError(
        "CAPABILITY_EFFECT_CONSTRAINT_INVALID",
        rule,
        "/effects/maximumBlocks",
        "only WRITE_WORLD requires a non-null maximumBlocks limit",
      ),
    ];
  }
  if (effectCategory !== "READ" && confirmation["required"] !== true) {
    return [
      semanticError(
        "CAPABILITY_CONFIRMATION_POLICY_INVALID",
        rule,
        "/confirmation/required",
        "every non-READ effect requires confirmation",
      ),
    ];
  }

  return [];
}

function capabilityPluginKey(manifest: JsonRecord): string | undefined {
  const requirements = manifest["requirements"];
  if (!isRecord(requirements) || !Array.isArray(requirements["plugins"])) {
    return undefined;
  }
  const entries: string[] = [];
  for (const plugin of requirements["plugins"]) {
    if (
      !isRecord(plugin) ||
      typeof plugin["name"] !== "string" ||
      typeof plugin["version"] !== "string"
    ) {
      return undefined;
    }
    entries.push(`${plugin["name"].toLowerCase()}\u0000${plugin["version"]}`);
  }
  return entries.sort().join("\u0001");
}

export function validateCapabilityPack(value: unknown): SemanticValidationError[] {
  const rule = "capability-pack-v1";
  if (!isRecord(value) || !Array.isArray(value["capabilities"])) {
    return [
      semanticError(
        "CAPABILITY_PACK_STRUCTURE_INVALID",
        rule,
        "",
        "capability pack golden must contain a capabilities array",
      ),
    ];
  }

  const capabilities = value["capabilities"];
  const byId = new Map<string, JsonRecord>();
  for (let index = 0; index < capabilities.length; index += 1) {
    const capability = capabilities[index];
    const manifestErrors = validateCapabilityManifest(capability);
    if (manifestErrors.length > 0) {
      const first = manifestErrors[0]!;
      return [
        {
          ...first,
          rule,
          instancePath: `/capabilities/${index}${first.instancePath}`,
        },
      ];
    }
    if (!isRecord(capability) || typeof capability["id"] !== "string") {
      return [
        semanticError(
          "CAPABILITY_PACK_STRUCTURE_INVALID",
          rule,
          `/capabilities/${index}`,
          "capability is missing its ID",
        ),
      ];
    }
    if (byId.has(capability["id"])) {
      return [
        semanticError(
          "CAPABILITY_PACK_ID_DUPLICATE",
          rule,
          `/capabilities/${index}/id`,
          `capability ID ${JSON.stringify(capability["id"])} is duplicated`,
        ),
      ];
    }
    byId.set(capability["id"], capability);
  }

  for (let index = 0; index < capabilities.length; index += 1) {
    const capability = capabilities[index]! as JsonRecord;
    const reversibility = capability["reversibility"] as JsonRecord;
    if (reversibility["type"] !== "capability") {
      continue;
    }
    const targetId = reversibility["capability"];
    const target = typeof targetId === "string" ? byId.get(targetId) : undefined;
    const sourceExecution = capability["execution"] as JsonRecord;
    const sourceEffects = capability["effects"] as JsonRecord;
    const targetExecution = target?.["execution"];
    const targetEffects = target?.["effects"];
    if (
      target === undefined ||
      target === capability ||
      target["status"] !== undefined ||
      !isRecord(targetExecution) ||
      !isRecord(targetEffects) ||
      targetExecution["source"] !== sourceExecution["source"] ||
      targetEffects["category"] !== sourceEffects["category"] ||
      targetEffects["scope"] !== sourceEffects["scope"] ||
      capabilityPluginKey(target) !== capabilityPluginKey(capability)
    ) {
      return [
        semanticError(
          "CAPABILITY_REVERSIBILITY_TARGET_INVALID",
          rule,
          `/capabilities/${index}/reversibility/capability`,
          "reversal target is missing, inactive, self-referential, or incompatible",
        ),
      ];
    }
  }
  return [];
}

function parseCapabilityVersion(value: string): [bigint, bigint, bigint] | undefined {
  if (value.length > 128 || !CAPABILITY_PLUGIN_VERSION.test(value)) {
    return undefined;
  }
  const components = value.split(".").map((component) => BigInt(component));
  return [components[0]!, components[1] ?? 0n, components[2] ?? 0n];
}

function compareCapabilityVersions(left: readonly bigint[], right: readonly bigint[]): number {
  for (let index = 0; index < 3; index += 1) {
    if (left[index]! < right[index]!) {
      return -1;
    }
    if (left[index]! > right[index]!) {
      return 1;
    }
  }
  return 0;
}

function capabilityVersionMatches(range: string, installed: readonly bigint[]): boolean {
  return range.split(" ").every((token) => {
    const comparison = CAPABILITY_PLUGIN_COMPARISON.exec(token);
    if (comparison === null) {
      return false;
    }
    const boundary = parseCapabilityVersion(comparison[2]!);
    if (boundary === undefined) {
      return false;
    }
    const order = compareCapabilityVersions(installed, boundary);
    switch (comparison[1]) {
      case "=":
        return order === 0;
      case ">":
        return order > 0;
      case ">=":
        return order >= 0;
      case "<":
        return order < 0;
      case "<=":
        return order <= 0;
      default:
        return false;
    }
  });
}

export function validateCapabilityPluginVersion(value: unknown): SemanticValidationError[] {
  const rule = "capability-plugin-version-v1";
  if (
    !isRecord(value) ||
    !isRecord(value["manifest"]) ||
    !Array.isArray(value["cases"]) ||
    value["cases"].length === 0
  ) {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "",
        "version golden must contain one manifest and a cases array",
      ),
    ];
  }
  const manifestErrors = validateCapabilityManifest(value["manifest"]);
  if (manifestErrors.length > 0) {
    return manifestErrors.map((error) => ({ ...error, rule }));
  }
  const requirements = value["manifest"]["requirements"];
  const plugins = isRecord(requirements) ? requirements["plugins"] : undefined;
  if (!Array.isArray(plugins) || plugins.length !== 1 || !isRecord(plugins[0])) {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "/manifest/requirements/plugins",
        "version golden manifest must contain exactly one plugin requirement",
      ),
    ];
  }
  const range = plugins[0]["version"];
  if (typeof range !== "string") {
    return [
      semanticError(
        "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
        rule,
        "/manifest/requirements/plugins/0/version",
        "version golden range is missing",
      ),
    ];
  }

  for (let index = 0; index < value["cases"].length; index += 1) {
    const testCase = value["cases"][index];
    if (
      !isRecord(testCase) ||
      typeof testCase["installedVersion"] !== "string" ||
      !["match", "mismatch", "invalid"].includes(String(testCase["expected"]))
    ) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID",
          rule,
          `/cases/${index}`,
          "version golden case is malformed",
        ),
      ];
    }
    const installed = parseCapabilityVersion(testCase["installedVersion"]);
    const actual =
      installed === undefined
        ? "invalid"
        : capabilityVersionMatches(range, installed)
          ? "match"
          : "mismatch";
    if (actual !== testCase["expected"]) {
      return [
        semanticError(
          "CAPABILITY_PLUGIN_VERSION_GOLDEN_MISMATCH",
          rule,
          `/cases/${index}`,
          `expected ${String(testCase["expected"])} but computed ${actual}`,
        ),
      ];
    }
  }
  return [];
}

export function validateViewNegotiation(value: unknown): SemanticValidationError[] {
  const rule = "view-negotiation-v1";
  if (!isRecord(value) || !isRecord(value["client"]) || !isRecord(value["view"])) {
    return [
      semanticError(
        "VIEW_NEGOTIATION_STRUCTURE_INVALID",
        rule,
        "",
        "view negotiation must contain client and view objects",
      ),
    ];
  }

  const client = value["client"];
  const view = value["view"];
  if (!SUPPORTED_CLIENT_PROTOCOL_VERSIONS.has(client["clientProtocolVersion"] as string)) {
    return [
      semanticError(
        "VIEW_PROTOCOL_UNSUPPORTED",
        rule,
        "/client/clientProtocolVersion",
        "client protocol version is not supported",
      ),
    ];
  }

  const requirements: Readonly<Record<string, readonly string[]>> = {
    text: ["overlay"],
    selection_list: ["overlay"],
    proposal: ["overlay"],
    item_stack: ["overlay", "itemIcons"],
    item_list: ["overlay", "itemIcons"],
    recipe: ["overlay", "itemIcons", "recipeView"],
    build_preview: ["overlay", "litematicaPreview"],
  };
  const capabilities = client["capabilities"];
  const requiredCapabilities =
    typeof view["viewType"] === "string" ? requirements[view["viewType"]] : undefined;
  if (!isRecord(capabilities) || requiredCapabilities === undefined) {
    return [
      semanticError(
        "VIEW_NEGOTIATION_STRUCTURE_INVALID",
        rule,
        "",
        "view type or client capabilities are invalid",
      ),
    ];
  }
  for (const capability of requiredCapabilities) {
    const requiredVersion =
      view["viewType"] === "recipe" &&
      capability === "recipeView" &&
      isRecord(view["content"]) &&
      view["content"]["schemaVersion"] === "2.0"
        ? 2
        : 1;
    if (capabilities[capability] !== requiredVersion) {
      return [
        semanticError(
          "VIEW_CAPABILITY_UNDECLARED",
          rule,
          `/client/capabilities/${capability}`,
          `client did not declare ${capability} version ${String(requiredVersion)}`,
        ),
      ];
    }
  }
  return [];
}

export function runSemanticValidator(validator: string, value: unknown): SemanticValidationResult {
  let errors: SemanticValidationError[];
  switch (normalizeRule(validator)) {
    case "handshake-proof-v1":
      errors = validateHandshakeProof(value);
      break;
    case "recipe-view-v1":
      errors = validateRecipeView(value);
      break;
    case "recipe-view-v2":
      errors = validateRecipeViewV2(value);
      break;
    case "build-preview-transfer-v1":
    case "reassemble-and-verify-sha256":
      errors = validateBuildPreviewChunks(value);
      break;
    case "capability-manifest-v1":
      errors = validateCapabilityManifest(value);
      break;
    case "capability-pack-v1":
      errors = validateCapabilityPack(value);
      break;
    case "capability-plugin-version-v1":
      errors = validateCapabilityPluginVersion(value);
      break;
    case "view-negotiation-v1":
      errors = validateViewNegotiation(value);
      break;
    case "proposal-argument-hash-v1":
      errors = validateProposalArgumentHash(value);
      break;
    case "protocol-version-compatible":
      errors = validateProtocolVersion(value);
      break;
    default:
      errors = [
        semanticError(
          "SEMANTIC_VALIDATOR_UNSUPPORTED",
          "semantic-validator-supported",
          "",
          `unsupported semantic validator ${JSON.stringify(validator)}`,
        ),
      ];
      break;
  }

  return { valid: errors.length === 0, errors };
}

function normalizeRule(rule: string): string {
  return rule
    .replace(/([a-z\d])([A-Z])/gu, "$1-$2")
    .replaceAll("_", "-")
    .trim()
    .toLowerCase();
}

export function validateContractSemantics(
  value: unknown,
  semanticRule?: string | readonly string[],
): SemanticValidationError[] {
  const errors = validateProtocolVersion(value);
  const rules =
    semanticRule === undefined ? [] : Array.isArray(semanticRule) ? semanticRule : [semanticRule];
  let chunksValidated = false;

  for (const originalRule of rules) {
    const rule = normalizeRule(originalRule);
    if (rule === "" || rule === "none" || rule.includes("protocol-version")) {
      continue;
    }

    if (rule === "proposal-argument-hash-v1") {
      errors.push(...validateProposalArgumentHash(value));
      continue;
    }

    if (
      rule === "build-preview-transfer-v1" ||
      rule === "reassemble-and-verify-sha256" ||
      rule.includes("chunk") ||
      rule.includes("base64") ||
      rule.includes("sha256")
    ) {
      if (!chunksValidated) {
        errors.push(...validateBuildPreviewChunks(value));
        chunksValidated = true;
      }
      continue;
    }

    if (rule === "recipe-view-v1") {
      errors.push(...validateRecipeView(value));
      continue;
    }

    if (rule === "recipe-view-v2") {
      errors.push(...validateRecipeViewV2(value));
      continue;
    }

    if (rule === "capability-manifest-v1") {
      errors.push(...validateCapabilityManifest(value));
      continue;
    }

    if (rule === "capability-pack-v1") {
      errors.push(...validateCapabilityPack(value));
      continue;
    }

    if (rule === "capability-plugin-version-v1") {
      errors.push(...validateCapabilityPluginVersion(value));
      continue;
    }

    if (rule === "view-negotiation-v1") {
      errors.push(...validateViewNegotiation(value));
      continue;
    }

    if (rule === "handshake-proof-v1") {
      errors.push(...validateHandshakeProof(value));
      continue;
    }

    errors.push(
      semanticError(
        "SEMANTIC_VALIDATOR_UNSUPPORTED",
        "semantic-rule-supported",
        "",
        `unsupported semantic rule ${JSON.stringify(originalRule)}`,
      ),
    );
  }

  return errors;
}
