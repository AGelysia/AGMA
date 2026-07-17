import { createHmac } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import { evaluateContractCase, loadContractManifest } from "../src/protocol/contract-manifest.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";

const contractRoot = fileURLToPath(new URL("../../standalone-client/contracts/", import.meta.url));

interface Source {
  generationId: string;
}

interface Resource {
  source: Source;
}

interface ProcessRecord {
  source: Source;
  workstations: Resource[];
  inputs: { groupId: string; alternatives: Resource[] }[];
  catalysts: { resource: Resource; returnedResource: Resource | null }[];
  outputs: { resource: Resource }[];
  energy: Resource | null;
  stages: { index: number; inputGroupIds: string[] }[];
}

interface GuideAnswer {
  target: Resource | null;
  targetResolution: "exact" | "player_selected" | "ambiguous" | "not_found";
  materials: { resource: Resource }[];
  processes: ProcessRecord[];
  bossAndDrops: { claimIds: string[] }[];
  preparation: { claimIds: string[] }[];
  evidence: { claimId: string }[];
  snapshot: Source;
}

interface StandaloneFixture {
  publicTestToken: string;
  process: ProcessRecord;
  guide: GuideAnswer;
  notFoundGuide: GuideAnswer;
  connectorRequest: ConnectorHello;
  connectorResponse: ConnectorHello;
}

interface ConnectorHello {
  schemaVersion: string;
  connectorKind: string;
  type: string;
  messageId: string;
  requestId: string | null;
  timestamp: string;
  nonce: string;
  component: string;
  componentVersion: string;
  scopeId: string;
  supportedProtocolVersions: string[];
  selectedProtocolVersion: string | null;
  capabilities: { id: string; version: number }[];
  authentication: {
    scheme: string;
    keyId: string;
    challenge: string;
    proof: string;
  };
}

function processSemanticsValid(process: ProcessRecord): boolean {
  const generationId = process.source.generationId;
  const inputGroupIds = new Set(process.inputs.map((input) => input.groupId));
  if (inputGroupIds.size !== process.inputs.length) return false;
  if (
    process.stages.some(
      (stage, index) =>
        stage.index !== index || stage.inputGroupIds.some((groupId) => !inputGroupIds.has(groupId)),
    )
  ) {
    return false;
  }

  const resources = [
    ...process.workstations,
    ...process.inputs.flatMap((input) => input.alternatives),
    ...process.catalysts.flatMap((catalyst) =>
      catalyst.returnedResource === null
        ? [catalyst.resource]
        : [catalyst.resource, catalyst.returnedResource],
    ),
    ...process.outputs.map((output) => output.resource),
    ...(process.energy === null ? [] : [process.energy]),
  ];
  return resources.every((resource) => resource.source.generationId === generationId);
}

function guideSemanticsValid(guide: GuideAnswer): boolean {
  const generationId = guide.snapshot.generationId;
  const unresolved = ["ambiguous", "not_found"].includes(guide.targetResolution);
  const hasEmptyUnresolvedShape =
    guide.target === null && guide.materials.length === 0 && guide.processes.length === 0;
  if (unresolved !== hasEmptyUnresolvedShape) return false;
  if (guide.target !== null && guide.target.source.generationId !== generationId) {
    return false;
  }
  if (
    guide.materials.some((material) => material.resource.source.generationId !== generationId) ||
    guide.processes.some(
      (process) => process.source.generationId !== generationId || !processSemanticsValid(process),
    )
  ) {
    return false;
  }

  const claimIds = new Set(guide.evidence.map((claim) => claim.claimId));
  if (claimIds.size !== guide.evidence.length) return false;
  return [...guide.bossAndDrops, ...guide.preparation].every((binding) =>
    binding.claimIds.every((claimId) => claimIds.has(claimId)),
  );
}

function connectorTranscript(hello: ConnectorHello): string {
  return [
    "agma-connector-handshake-v1",
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
    hello.capabilities.map((capability) => `${capability.id}=${capability.version}`).join(","),
    hello.authentication.scheme,
    hello.authentication.keyId,
    hello.authentication.challenge,
  ].join("\n");
}

function connectorProof(token: string, hello: ConnectorHello): string {
  return createHmac("sha256", token).update(connectorTranscript(hello), "utf8").digest("base64url");
}

function canonicalBase64Url(value: string, minimumBytes: number, maximumBytes: number): boolean {
  if (!/^[A-Za-z0-9_-]+$/u.test(value) || value.length % 4 === 1) return false;
  const decoded = Buffer.from(value, "base64url");
  return (
    decoded.length >= minimumBytes &&
    decoded.length <= maximumBytes &&
    decoded.toString("base64url") === value
  );
}

function connectorSemanticsValid(hello: ConnectorHello): boolean {
  const ids = hello.capabilities.map((capability) => capability.id);
  const sortedIds = [...ids].sort();
  return (
    ids.every((id, index) => id === sortedIds[index]) &&
    new Set(ids).size === ids.length &&
    canonicalBase64Url(hello.nonce, 16, 64) &&
    canonicalBase64Url(hello.authentication.challenge, 16, 96) &&
    canonicalBase64Url(hello.authentication.proof, 32, 32)
  );
}

describe("standalone client contract manifest", () => {
  it("requires bounded accounting and an explicit controlled source array on every completion", async () => {
    const registry = await SchemaRegistry.load(contractRoot);
    const valid = {
      sessionId: null,
      text: "bounded answer",
      costMicroUsd: 19,
      costKind: "reported",
      sources: [],
    };

    expect(registry.validate("connector-complete.schema.json", valid).valid).toBe(true);
    expect(
      registry.validate("connector-complete.schema.json", {
        sessionId: null,
        text: "missing accounting",
      }).valid,
    ).toBe(false);
    expect(
      registry.validate("connector-complete.schema.json", {
        ...valid,
        costKind: "unknown",
      }).valid,
    ).toBe(false);
    const withoutSources = {
      sessionId: valid.sessionId,
      text: valid.text,
      costMicroUsd: valid.costMicroUsd,
      costKind: valid.costKind,
    };
    expect(registry.validate("connector-complete.schema.json", withoutSources).valid).toBe(false);
  });

  it("matches every isolated schema expectation", async () => {
    const registry = await SchemaRegistry.load(contractRoot);
    const manifest = await loadContractManifest(contractRoot);

    expect(manifest.cases).toHaveLength(8);
    for (const contractCase of manifest.cases) {
      const evaluation = await evaluateContractCase(registry, contractCase);
      for (const schemaEvaluation of evaluation.schemaEvaluations) {
        expect(
          schemaEvaluation.actualValid,
          `${contractCase.id}: ${schemaEvaluation.validation.schema}`,
        ).toBe(schemaEvaluation.validation.expectedValid);
      }
    }
  });

  it("rejects stale generations and dangling references", async () => {
    const fixture = JSON.parse(
      await readFile(
        new URL(
          "../../standalone-client/contracts/fixtures/valid/standalone-contracts.json",
          import.meta.url,
        ),
        "utf8",
      ),
    ) as StandaloneFixture;

    expect(processSemanticsValid(fixture.process)).toBe(true);
    expect(guideSemanticsValid(fixture.guide)).toBe(true);
    expect(guideSemanticsValid(fixture.notFoundGuide)).toBe(true);

    const badStage = structuredClone(fixture.process);
    badStage.stages[0]!.inputGroupIds[0] = "missing";
    expect(processSemanticsValid(badStage)).toBe(false);

    const badGeneration = structuredClone(fixture.process);
    badGeneration.outputs[0]!.resource.source.generationId = "Other-generation";
    expect(processSemanticsValid(badGeneration)).toBe(false);

    const missingClaim = structuredClone(fixture.guide);
    missingClaim.bossAndDrops[0]!.claimIds[0] = "claim.missing";
    expect(guideSemanticsValid(missingClaim)).toBe(false);

    const staleTarget = structuredClone(fixture.guide);
    staleTarget.target!.source.generationId = "Other-generation";
    expect(guideSemanticsValid(staleTarget)).toBe(false);

    expect(fixture.connectorResponse.requestId).toBe(fixture.connectorRequest.messageId);
    expect(connectorSemanticsValid(fixture.connectorRequest)).toBe(true);
    expect(connectorSemanticsValid(fixture.connectorResponse)).toBe(true);
    expect(fixture.connectorResponse.authentication.challenge).toBe(
      fixture.connectorRequest.authentication.challenge,
    );
    expect(fixture.connectorResponse.nonce).not.toBe(fixture.connectorRequest.nonce);
    expect(connectorProof(fixture.publicTestToken, fixture.connectorRequest)).toBe(
      fixture.connectorRequest.authentication.proof,
    );
    expect(connectorProof(fixture.publicTestToken, fixture.connectorResponse)).toBe(
      fixture.connectorResponse.authentication.proof,
    );

    const tamperedHello = structuredClone(fixture.connectorRequest);
    tamperedHello.componentVersion = "0.2.1";
    expect(connectorProof(fixture.publicTestToken, tamperedHello)).not.toBe(
      fixture.connectorRequest.authentication.proof,
    );

    const duplicateCapability = structuredClone(fixture.connectorRequest);
    duplicateCapability.capabilities.push({
      id: "game.resource.search",
      version: 2,
    });
    expect(connectorSemanticsValid(duplicateCapability)).toBe(false);

    const unsortedCapabilities = structuredClone(fixture.connectorRequest);
    unsortedCapabilities.capabilities = [
      { id: "game.zeta", version: 1 },
      { id: "game.alpha", version: 1 },
    ];
    expect(connectorSemanticsValid(unsortedCapabilities)).toBe(false);

    const nonCanonicalNonce = structuredClone(fixture.connectorRequest);
    nonCanonicalNonce.nonce = `${nonCanonicalNonce.nonce.slice(0, -1)}B`;
    expect(connectorSemanticsValid(nonCanonicalNonce)).toBe(false);

    const nonCanonicalChallenge = structuredClone(fixture.connectorRequest);
    nonCanonicalChallenge.authentication.challenge = `${nonCanonicalChallenge.authentication.challenge.slice(0, -1)}B`;
    expect(connectorSemanticsValid(nonCanonicalChallenge)).toBe(false);

    const nonCanonicalProof = structuredClone(fixture.connectorRequest);
    nonCanonicalProof.authentication.proof = `${nonCanonicalProof.authentication.proof.slice(0, -1)}B`;
    expect(connectorSemanticsValid(nonCanonicalProof)).toBe(false);
  });
});
