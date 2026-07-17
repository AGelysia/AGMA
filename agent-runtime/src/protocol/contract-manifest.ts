import { access, readFile } from "node:fs/promises";
import { join } from "node:path";

import { z } from "zod";

import {
  ProtocolSchemaError,
  type SchemaRegistry,
  type SchemaValidationError,
  resolveProtocolPath,
} from "./schema-registry.js";
import { runSemanticValidator, type SemanticValidationError } from "./semantic-validation.js";
import { SUPPORTED_PROTOCOL_VERSION } from "../version.js";

const schemaValidationSchema = z.object({
  schema: z.string().trim().min(1),
  documentPointer: z.string(),
  expectedValid: z.boolean(),
});

const semanticValidationSchema = z.object({
  validator: z.string().trim().min(1),
  expectedValid: z.boolean(),
  errorCode: z.string().trim().min(1).optional(),
});

const contractCaseSchema = z.object({
  id: z.string().trim().min(1),
  category: z.string().trim().min(1),
  file: z.string().trim().min(1),
  validations: z.array(schemaValidationSchema).min(1),
  semanticValidation: semanticValidationSchema.optional(),
});

const contractManifestSchema = z.object({
  manifestVersion: z.literal("1.0"),
  schemaDraft: z.literal("https://json-schema.org/draft/2020-12/schema"),
  protocolVersion: z.literal(SUPPORTED_PROTOCOL_VERSION),
  formatAssertionsRequired: z.literal(true),
  cases: z.array(contractCaseSchema).min(1),
});

export type ContractSchemaValidation = z.infer<typeof schemaValidationSchema>;
export type ContractSemanticValidation = z.infer<typeof semanticValidationSchema>;
export type ContractCase = z.infer<typeof contractCaseSchema>;
export type ContractManifest = z.infer<typeof contractManifestSchema>;

export interface ContractSchemaEvaluation {
  readonly validation: ContractSchemaValidation;
  readonly actualValid: boolean;
  readonly errors: readonly SchemaValidationError[];
}

export interface ContractSemanticEvaluation {
  readonly validation: ContractSemanticValidation;
  readonly actualValid: boolean;
  readonly errors: readonly SemanticValidationError[];
}

export interface ContractCaseEvaluation {
  readonly contractCase: ContractCase;
  readonly fixturePath: string;
  readonly schemaEvaluations: readonly ContractSchemaEvaluation[];
  readonly semanticEvaluation?: ContractSemanticEvaluation;
}

export class ContractManifestError extends Error {
  public constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ContractManifestError";
  }
}

async function parseJsonFile(path: string, description: string): Promise<unknown> {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    throw new ContractManifestError(`Unable to read ${description} at ${path}`, { cause: error });
  }
}

export async function loadContractManifest(
  protocolRoot: string,
  manifestReference = "fixtures/manifest.json",
): Promise<ContractManifest> {
  let manifestPath: string;
  try {
    manifestPath = resolveProtocolPath(protocolRoot, manifestReference);
  } catch (error) {
    throw new ContractManifestError("Contract manifest path is invalid", { cause: error });
  }

  const manifest = await parseJsonFile(manifestPath, "contract manifest");
  const parsed = contractManifestSchema.safeParse(manifest);
  if (!parsed.success) {
    throw new ContractManifestError(
      `Contract manifest is invalid: ${z.prettifyError(parsed.error)}`,
    );
  }

  const ids = new Set<string>();
  for (const contractCase of parsed.data.cases) {
    if (ids.has(contractCase.id)) {
      throw new ContractManifestError(
        `Contract manifest contains duplicate case id ${contractCase.id}`,
      );
    }
    ids.add(contractCase.id);
  }

  return parsed.data;
}

async function resolveFixturePath(protocolRoot: string, reference: string): Promise<string> {
  let directPath: string;
  try {
    directPath = resolveProtocolPath(protocolRoot, reference);
  } catch (error) {
    throw new ContractManifestError("Fixture path is invalid", { cause: error });
  }

  try {
    await access(directPath);
    return directPath;
  } catch {
    if (reference.replaceAll("\\", "/").startsWith("fixtures/")) {
      return directPath;
    }
  }

  try {
    return resolveProtocolPath(protocolRoot, join("fixtures", reference));
  } catch (error) {
    throw new ContractManifestError("Fixture path is invalid", { cause: error });
  }
}

function decodePointerToken(token: string, pointer: string): string {
  if (/~(?:[^01]|$)/u.test(token)) {
    throw new ContractManifestError(`Invalid JSON Pointer escape in ${JSON.stringify(pointer)}`);
  }
  return token.replaceAll("~1", "/").replaceAll("~0", "~");
}

export function resolveDocumentPointer(document: unknown, pointer: string): unknown {
  if (pointer === "") {
    return document;
  }
  if (!pointer.startsWith("/")) {
    throw new ContractManifestError(`JSON Pointer must be empty or start with '/': ${pointer}`);
  }

  let current = document;
  for (const encodedToken of pointer.slice(1).split("/")) {
    const token = decodePointerToken(encodedToken, pointer);
    if (Array.isArray(current)) {
      if (!/^(?:0|[1-9]\d*)$/u.test(token)) {
        throw new ContractManifestError(
          `JSON Pointer ${JSON.stringify(pointer)} contains invalid array index ${JSON.stringify(token)}`,
        );
      }
      const index = Number(token);
      if (index >= current.length) {
        throw new ContractManifestError(
          `JSON Pointer ${JSON.stringify(pointer)} does not exist in fixture`,
        );
      }
      current = current[index];
      continue;
    }

    if (typeof current === "object" && current !== null && Object.hasOwn(current, token)) {
      current = (current as Record<string, unknown>)[token];
      continue;
    }

    throw new ContractManifestError(
      `JSON Pointer ${JSON.stringify(pointer)} does not exist in fixture`,
    );
  }

  return current;
}

export async function evaluateContractCase(
  registry: SchemaRegistry,
  contractCase: ContractCase,
): Promise<ContractCaseEvaluation> {
  const fixturePath = await resolveFixturePath(registry.protocolRoot, contractCase.file);
  const fixture = await parseJsonFile(fixturePath, `fixture ${contractCase.id}`);
  const schemaEvaluations: ContractSchemaEvaluation[] = [];

  for (const validation of contractCase.validations) {
    const selectedDocument = resolveDocumentPointer(fixture, validation.documentPointer);
    try {
      const result = registry.validate(validation.schema, selectedDocument);
      schemaEvaluations.push({
        validation,
        actualValid: result.valid,
        errors: result.errors,
      });
    } catch (error) {
      if (error instanceof ProtocolSchemaError) {
        throw new ContractManifestError(
          `Contract ${contractCase.id} references unknown schema ${validation.schema}`,
          { cause: error },
        );
      }
      throw error;
    }
  }

  const semanticValidation = contractCase.semanticValidation;
  const semanticEvaluation =
    semanticValidation === undefined
      ? undefined
      : {
          validation: semanticValidation,
          ...(() => {
            const result = runSemanticValidator(semanticValidation.validator, fixture);
            return { actualValid: result.valid, errors: result.errors };
          })(),
        };

  return {
    contractCase,
    fixturePath,
    schemaEvaluations,
    ...(semanticEvaluation === undefined ? {} : { semanticEvaluation }),
  };
}
