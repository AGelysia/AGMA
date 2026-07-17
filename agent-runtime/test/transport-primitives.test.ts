import { readFile } from "node:fs/promises";

import { describe, expect, it } from "vitest";

import {
  createHandshakeProof,
  decodeCanonicalBase64Url,
  handshakeProofTranscript,
  verifyHandshakeProof,
  type HandshakeProofFields,
} from "../src/transport/handshake-authentication.js";
import { HandshakeReplayCache } from "../src/transport/replay-cache.js";
import { parseStrictJson, StrictJsonError } from "../src/transport/strict-json.js";

interface GoldenHandshake {
  readonly publicTestToken: string;
  readonly paper: GoldenEnvelope;
  readonly runtime: GoldenEnvelope;
}

interface GoldenEnvelope {
  readonly serverId: string;
  readonly type: "paper.hello" | "runtime.hello";
  readonly timestamp: string;
  readonly nonce: string;
  readonly payload: {
    readonly component: "paper" | "runtime";
    readonly componentVersion: string;
    readonly authentication: {
      readonly challenge: string;
      readonly proof: string;
    };
  };
}

function proofFields(envelope: GoldenEnvelope): HandshakeProofFields {
  return {
    serverId: envelope.serverId,
    type: envelope.type,
    timestamp: envelope.timestamp,
    nonce: envelope.nonce,
    component: envelope.payload.component,
    componentVersion: envelope.payload.componentVersion,
    challenge: envelope.payload.authentication.challenge,
  };
}

describe("handshake authentication", () => {
  it("matches both shared cross-language HMAC vectors", async () => {
    const fixtureUrl = new URL(
      "../../protocol/fixtures/valid/handshake-proof-v1.json",
      import.meta.url,
    );
    const fixture = JSON.parse(await readFile(fixtureUrl, "utf8")) as GoldenHandshake;

    for (const envelope of [fixture.paper, fixture.runtime]) {
      const fields = proofFields(envelope);
      expect(createHandshakeProof(fixture.publicTestToken, fields)).toBe(
        envelope.payload.authentication.proof,
      );
      expect(
        verifyHandshakeProof(
          fixture.publicTestToken,
          fields,
          envelope.payload.authentication.proof,
        ),
      ).toBe(true);
    }
  });

  it("uses the protocol's exact ordered transcript and rejects altered proofs", () => {
    const fields: HandshakeProofFields = {
      serverId: "test-server",
      type: "paper.hello",
      timestamp: "2026-07-12T00:00:00.000Z",
      nonce: "AAECAwQFBgcICQoLDA0ODw",
      component: "paper",
      componentVersion: "0.1.0",
      challenge: "ICEiIyQlJicoKSorLC0uLw",
    };

    expect(handshakeProofTranscript(fields).split("\n")).toEqual([
      "minecraft-agent-handshake-v1",
      "test-server",
      "paper.hello",
      "2026-07-12T00:00:00.000Z",
      "AAECAwQFBgcICQoLDA0ODw",
      "paper",
      "0.1.0",
      "ICEiIyQlJicoKSorLC0uLw",
    ]);
    const proof = createHandshakeProof("a-public-test-token-with-32-chars", fields);
    expect(verifyHandshakeProof("a-public-test-token-with-32-chars", fields, proof)).toBe(true);
    expect(verifyHandshakeProof("a-public-test-token-with-32-chars", fields, `${proof}=`)).toBe(
      false,
    );
    expect(verifyHandshakeProof("another-public-test-token-value", fields, proof)).toBe(false);
  });

  it("accepts only canonical base64url", () => {
    expect(decodeCanonicalBase64Url("AAECAwQFBgcICQoLDA0ODw")?.length).toBe(16);
    expect(decodeCanonicalBase64Url("AAECAwQFBgcICQoLDA0ODw=")).toBeUndefined();
    expect(decodeCanonicalBase64Url("+AECAwQFBgcICQoLDA0ODw")).toBeUndefined();
    expect(decodeCanonicalBase64Url("a")).toBeUndefined();
  });
});

describe("strict JSON", () => {
  it("parses valid nested JSON", () => {
    expect(parseStrictJson('{"outer":{"items":[1,true,null,"value"]}}')).toEqual({
      outer: { items: [1, true, null, "value"] },
    });
  });

  it.each([
    ['{"value":1,"value":2}', "literal duplicate"],
    ['{"value":1,"\\u0076alue":2}', "escaped duplicate"],
    ['{"outer":{"value":1,"value":2}}', "nested duplicate"],
  ])("rejects %s (%s)", (source) => {
    expect(() => parseStrictJson(source)).toThrow(StrictJsonError);
  });

  it("enforces bounded nesting and token counts", () => {
    expect(() => parseStrictJson("[[[0]]]", { maximumDepth: 1 })).toThrow(StrictJsonError);
    expect(() => parseStrictJson("[1,2,3]", { maximumTokens: 3 })).toThrow(StrictJsonError);
  });
});

describe("handshake replay cache", () => {
  it("rejects either repeated identity across calls until expiry", () => {
    const cache = new HandshakeReplayCache({ ttlMilliseconds: 1000, maximumEntries: 4 });

    expect(cache.accept("message-a", "nonce-a", 100)).toBe(true);
    expect(cache.accept("message-a", "nonce-b", 200)).toBe(false);
    expect(cache.accept("message-b", "nonce-a", 200)).toBe(false);
    expect(cache.accept("message-a", "nonce-a", 1100)).toBe(true);
  });

  it("fails closed at its bound until entries expire", () => {
    const cache = new HandshakeReplayCache({ ttlMilliseconds: 1000, maximumEntries: 2 });

    expect(cache.accept("message-a", "nonce-a", 0)).toBe(true);
    expect(cache.accept("message-b", "nonce-b", 1)).toBe(false);
    expect(cache.size).toBe(2);
    expect(cache.accept("message-b", "nonce-b", 1000)).toBe(true);
  });
});
