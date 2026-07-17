import { createHmac, timingSafeEqual } from "node:crypto";

export const CONNECTOR_HANDSHAKE_DOMAIN = "agma-connector-handshake-v1";
export const CONNECTOR_SCHEMA_VERSION = "1.0";
export const CONNECTOR_KIND = "standalone_client";
export const CONNECTOR_PROTOCOL_VERSION = "client-1.0";

export interface ConnectorCapability {
  readonly id: string;
  readonly version: number;
}

export interface ConnectorHello {
  readonly schemaVersion: typeof CONNECTOR_SCHEMA_VERSION;
  readonly connectorKind: typeof CONNECTOR_KIND;
  readonly type: "connector.hello";
  readonly messageId: string;
  readonly requestId: string | null;
  readonly timestamp: string;
  readonly nonce: string;
  readonly component: "standalone_client" | "runtime";
  readonly componentVersion: string;
  readonly scopeId: string;
  readonly supportedProtocolVersions: readonly (typeof CONNECTOR_PROTOCOL_VERSION)[];
  readonly selectedProtocolVersion: typeof CONNECTOR_PROTOCOL_VERSION | null;
  readonly capabilities: readonly ConnectorCapability[];
  readonly authentication: {
    readonly scheme: "hmac-sha256";
    readonly keyId: string;
    readonly challenge: string;
    readonly proof: string;
  };
}

export function decodeConnectorBase64Url(
  encoded: string,
  minimumBytes: number,
  maximumBytes: number,
): Buffer | undefined {
  if (
    !Number.isSafeInteger(minimumBytes) ||
    !Number.isSafeInteger(maximumBytes) ||
    minimumBytes < 0 ||
    maximumBytes < minimumBytes ||
    !/^[A-Za-z0-9_-]+$/u.test(encoded) ||
    encoded.length % 4 === 1
  ) {
    return undefined;
  }
  const decoded = Buffer.from(encoded, "base64url");
  if (
    decoded.length < minimumBytes ||
    decoded.length > maximumBytes ||
    decoded.toString("base64url") !== encoded
  ) {
    return undefined;
  }
  return decoded;
}

export function connectorHandshakeTranscript(hello: ConnectorHello): string {
  return [
    CONNECTOR_HANDSHAKE_DOMAIN,
    hello.schemaVersion,
    hello.connectorKind,
    hello.type,
    hello.messageId,
    hello.requestId ?? "-",
    hello.timestamp,
    hello.nonce,
    hello.component,
    hello.componentVersion,
    hello.scopeId,
    hello.supportedProtocolVersions.join(","),
    hello.selectedProtocolVersion ?? "-",
    hello.capabilities
      .map((capability) => `${capability.id}=${String(capability.version)}`)
      .join(","),
    hello.authentication.scheme,
    hello.authentication.keyId,
    hello.authentication.challenge,
  ].join("\n");
}

export function createConnectorHandshakeProof(token: string, hello: ConnectorHello): string {
  return createHmac("sha256", token)
    .update(connectorHandshakeTranscript(hello), "utf8")
    .digest("base64url");
}

export function verifyConnectorHandshakeProof(token: string, hello: ConnectorHello): boolean {
  const supplied = decodeConnectorBase64Url(hello.authentication.proof, 32, 32);
  if (supplied === undefined) return false;
  const expected = createHmac("sha256", token)
    .update(connectorHandshakeTranscript(hello), "utf8")
    .digest();
  return timingSafeEqual(expected, supplied);
}

export function validateConnectorHelloSemantics(hello: ConnectorHello): boolean {
  const ids = hello.capabilities.map((capability) => capability.id);
  const sorted = [...ids].sort();
  return (
    hello.schemaVersion === CONNECTOR_SCHEMA_VERSION &&
    hello.connectorKind === CONNECTOR_KIND &&
    hello.type === "connector.hello" &&
    hello.supportedProtocolVersions.length === 1 &&
    hello.supportedProtocolVersions[0] === CONNECTOR_PROTOCOL_VERSION &&
    ids.every((id, index) => id === sorted[index]) &&
    new Set(ids).size === ids.length &&
    decodeConnectorBase64Url(hello.nonce, 16, 64) !== undefined &&
    decodeConnectorBase64Url(hello.authentication.challenge, 16, 96) !== undefined &&
    decodeConnectorBase64Url(hello.authentication.proof, 32, 32) !== undefined &&
    ((hello.component === "standalone_client" &&
      hello.requestId === null &&
      hello.selectedProtocolVersion === null) ||
      (hello.component === "runtime" &&
        hello.requestId !== null &&
        hello.selectedProtocolVersion === CONNECTOR_PROTOCOL_VERSION))
  );
}
