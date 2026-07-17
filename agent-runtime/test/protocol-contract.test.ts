import { describe, expect, it } from "vitest";

import { evaluateContractCase, loadContractManifest } from "../src/protocol/contract-manifest.js";
import { SchemaRegistry, defaultProtocolRoot } from "../src/protocol/schema-registry.js";

function formatSchemaFailure(
  caseId: string,
  schema: string,
  pointer: string,
  errors: readonly { readonly instancePath: string; readonly message: string }[],
): string {
  return [
    `${caseId}: ${schema} at ${pointer || "/"}`,
    ...errors.map((error) => `${error.instancePath || "/"}: ${error.message}`),
  ].join("\n");
}

function formatSemanticFailure(
  caseId: string,
  validator: string,
  errors: readonly {
    readonly code: string;
    readonly instancePath: string;
    readonly message: string;
  }[],
): string {
  return [
    `${caseId}: ${validator}`,
    ...errors.map((error) => `${error.code} ${error.instancePath || "/"}: ${error.message}`),
  ].join("\n");
}

describe("shared protocol contract manifest", () => {
  it("matches every schema and semantic expectation independently", async () => {
    const protocolRoot = defaultProtocolRoot();
    const registry = await SchemaRegistry.load(protocolRoot);
    const manifest = await loadContractManifest(protocolRoot);

    expect(manifest.cases.length).toBeGreaterThan(0);
    expect(manifest.cases.some(({ category }) => category === "version_mismatch")).toBe(true);

    for (const contractCase of manifest.cases) {
      const evaluation = await evaluateContractCase(registry, contractCase);

      for (const schemaEvaluation of evaluation.schemaEvaluations) {
        const { validation } = schemaEvaluation;
        expect(
          schemaEvaluation.actualValid,
          formatSchemaFailure(
            contractCase.id,
            validation.schema,
            validation.documentPointer,
            schemaEvaluation.errors,
          ),
        ).toBe(validation.expectedValid);
      }

      const semanticEvaluation = evaluation.semanticEvaluation;
      if (semanticEvaluation !== undefined) {
        const { validation } = semanticEvaluation;
        expect(
          semanticEvaluation.actualValid,
          formatSemanticFailure(contractCase.id, validation.validator, semanticEvaluation.errors),
        ).toBe(validation.expectedValid);

        if (validation.errorCode !== undefined) {
          expect(
            semanticEvaluation.errors.map(({ code }) => code),
            formatSemanticFailure(contractCase.id, validation.validator, semanticEvaluation.errors),
          ).toContain(validation.errorCode);
          if (validation.validator === "recipe-view-v2") {
            expect(semanticEvaluation.errors[0]?.code).toBe(validation.errorCode);
          }
        }
      }
    }
  });
});
