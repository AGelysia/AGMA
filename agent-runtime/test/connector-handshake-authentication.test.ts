import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import {
  connectorHandshakeTranscript,
  createConnectorHandshakeProof,
  type ConnectorHello,
  validateConnectorHelloSemantics,
  verifyConnectorHandshakeProof,
} from "../src/transport/connector-handshake-authentication.js";

interface GoldenFixture {
  readonly publicTestToken: string;
  readonly connectorRequest: ConnectorHello;
  readonly connectorResponse: ConnectorHello;
}

const fixturePath = fileURLToPath(
  new URL(
    "../../standalone-client/contracts/fixtures/valid/standalone-contracts.json",
    import.meta.url,
  ),
);

async function fixture(): Promise<GoldenFixture> {
  return JSON.parse(await readFile(fixturePath, "utf8")) as GoldenFixture;
}

describe("standalone connector handshake authentication", () => {
  it("matches the independently verified C0 request and response golden proofs", async () => {
    const golden = await fixture();

    expect(connectorHandshakeTranscript(golden.connectorRequest).split("\n")).toHaveLength(17);
    expect(createConnectorHandshakeProof(golden.publicTestToken, golden.connectorRequest)).toBe(
      golden.connectorRequest.authentication.proof,
    );
    expect(createConnectorHandshakeProof(golden.publicTestToken, golden.connectorResponse)).toBe(
      golden.connectorResponse.authentication.proof,
    );
    expect(verifyConnectorHandshakeProof(golden.publicTestToken, golden.connectorRequest)).toBe(
      true,
    );
    expect(validateConnectorHelloSemantics(golden.connectorRequest)).toBe(true);
    expect(validateConnectorHelloSemantics(golden.connectorResponse)).toBe(true);
  });

  it("binds scope, negotiation and ordering into the proof and rejects non-canonical values", async () => {
    const golden = await fixture();
    const changedScope = {
      ...golden.connectorRequest,
      scopeId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    };
    expect(createConnectorHandshakeProof(golden.publicTestToken, changedScope)).not.toBe(
      golden.connectorRequest.authentication.proof,
    );

    const unsorted = {
      ...golden.connectorRequest,
      capabilities: [
        { id: "game.zeta", version: 1 },
        { id: "game.alpha", version: 1 },
      ],
    };
    expect(validateConnectorHelloSemantics(unsorted)).toBe(false);

    const invalidProof = {
      ...golden.connectorRequest,
      authentication: {
        ...golden.connectorRequest.authentication,
        proof: `${golden.connectorRequest.authentication.proof.slice(0, -1)}B`,
      },
    };
    expect(validateConnectorHelloSemantics(invalidProof)).toBe(false);
    expect(verifyConnectorHandshakeProof(golden.publicTestToken, invalidProof)).toBe(false);
  });
});
