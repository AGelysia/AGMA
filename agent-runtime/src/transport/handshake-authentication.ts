import { createHmac, timingSafeEqual } from "node:crypto";

const HANDSHAKE_DOMAIN = "minecraft-agent-handshake-v1";

export interface HandshakeProofFields {
  readonly serverId: string;
  readonly type: "paper.hello" | "runtime.hello";
  readonly timestamp: string;
  readonly nonce: string;
  readonly component: "paper" | "runtime";
  readonly componentVersion: string;
  readonly challenge: string;
}

export function handshakeProofTranscript(fields: HandshakeProofFields): string {
  return [
    HANDSHAKE_DOMAIN,
    fields.serverId,
    fields.type,
    fields.timestamp,
    fields.nonce,
    fields.component,
    fields.componentVersion,
    fields.challenge,
  ].join("\n");
}

export function createHandshakeProof(token: string, fields: HandshakeProofFields): string {
  return createHmac("sha256", token)
    .update(handshakeProofTranscript(fields), "utf8")
    .digest("base64url");
}

export function decodeCanonicalBase64Url(
  encoded: string,
  options: { readonly expectedBytes?: number } = {},
): Buffer | undefined {
  if (!/^[A-Za-z0-9_-]+$/u.test(encoded) || encoded.length % 4 === 1) {
    return undefined;
  }

  const decoded = Buffer.from(encoded, "base64url");
  if (
    decoded.toString("base64url") !== encoded ||
    (options.expectedBytes !== undefined && decoded.length !== options.expectedBytes)
  ) {
    return undefined;
  }
  return decoded;
}

export function verifyHandshakeProof(
  token: string,
  fields: HandshakeProofFields,
  suppliedProof: string,
): boolean {
  const supplied = decodeCanonicalBase64Url(suppliedProof, {
    expectedBytes: 32,
  });
  if (supplied === undefined) {
    return false;
  }

  const expected = createHmac("sha256", token)
    .update(handshakeProofTranscript(fields), "utf8")
    .digest();
  return timingSafeEqual(expected, supplied);
}
