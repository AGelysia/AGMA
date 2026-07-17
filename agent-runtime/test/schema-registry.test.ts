import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  ProtocolSchemaError,
  SchemaRegistry,
  resolveProtocolPath,
} from "../src/protocol/schema-registry.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

async function createProtocolRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "minecraft-agent-runtime-schema-"));
  temporaryDirectories.push(root);
  await mkdir(join(root, "schemas"));
  await writeFile(
    join(root, "schemas", "example.schema.json"),
    JSON.stringify({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      $id: "https://minecraft-agent.test/schemas/example.schema.json",
      type: "object",
      additionalProperties: false,
      required: ["name"],
      properties: {
        name: { type: "string", minLength: 1 },
      },
    }),
  );
  return root;
}

describe("SchemaRegistry", () => {
  it("loads schemas by protocol-relative path and validates all errors", async () => {
    const root = await createProtocolRoot();
    const registry = await SchemaRegistry.load(root);

    expect(registry.validate("schemas/example.schema.json", { name: "runtime" })).toEqual({
      valid: true,
      errors: [],
    });

    const invalid = registry.validate("example.schema.json", { name: "", extra: true });
    expect(invalid.valid).toBe(false);
    expect(invalid.errors.map(({ keyword }) => keyword)).toEqual(
      expect.arrayContaining(["additionalProperties", "minLength"]),
    );
  });

  it("rejects unknown schemas and paths outside protocol", async () => {
    const root = await createProtocolRoot();
    const registry = await SchemaRegistry.load(root);

    expect(() => registry.validate("missing.schema.json", {})).toThrow(ProtocolSchemaError);
    expect(() => resolveProtocolPath(root, "../secret.json")).toThrow(ProtocolSchemaError);
  });
});
