import { readdir, readFile } from "node:fs/promises";
import { basename, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { Ajv2020, type ErrorObject, type ValidateFunction } from "ajv/dist/2020.js";
import * as formatsModule from "ajv-formats";
import type { FormatsPlugin } from "ajv-formats";

const addFormats = ("default" in formatsModule
  ? formatsModule.default
  : formatsModule) as unknown as FormatsPlugin;

type JsonSchema = Record<string, unknown>;

export interface SchemaValidationError {
  readonly instancePath: string;
  readonly schemaPath: string;
  readonly keyword: string;
  readonly message: string;
  readonly params: Readonly<Record<string, unknown>>;
}

export interface SchemaValidationResult {
  readonly valid: boolean;
  readonly errors: readonly SchemaValidationError[];
}

export class ProtocolSchemaError extends Error {
  public constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ProtocolSchemaError";
  }
}

export function defaultProtocolRoot(): string {
  return fileURLToPath(new URL("../../../protocol/", import.meta.url));
}

function normalizeReference(reference: string): string {
  return reference.replaceAll("\\", "/").replace(/^\.\//u, "");
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function findSchemaFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    const entryPath = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await findSchemaFiles(entryPath)));
    } else if (entry.isFile() && entry.name.endsWith(".schema.json")) {
      files.push(entryPath);
    }
  }

  return files.sort((left, right) => left.localeCompare(right));
}

function copyAjvError(error: ErrorObject): SchemaValidationError {
  return {
    instancePath: error.instancePath,
    schemaPath: error.schemaPath,
    keyword: error.keyword,
    message: error.message ?? "schema validation failed",
    params: { ...error.params },
  };
}

export class SchemaRegistry {
  readonly #ajv: Ajv2020;
  readonly #aliases = new Map<string, string>();
  readonly #validators = new Map<string, ValidateFunction>();
  readonly #protocolRoot: string;

  private constructor(protocolRoot: string) {
    this.#protocolRoot = resolve(protocolRoot);
    this.#ajv = new Ajv2020({
      allErrors: true,
      strict: true,
      validateFormats: true,
    });
    addFormats(this.#ajv);
  }

  public static async load(protocolRoot = defaultProtocolRoot()): Promise<SchemaRegistry> {
    const registry = new SchemaRegistry(protocolRoot);
    await registry.#loadSchemas();
    return registry;
  }

  public get protocolRoot(): string {
    return this.#protocolRoot;
  }

  public get schemaReferences(): readonly string[] {
    return [...this.#aliases.keys()].sort((left, right) => left.localeCompare(right));
  }

  public validate(schemaReference: string, value: unknown): SchemaValidationResult {
    const validator = this.#getValidator(schemaReference);
    const valid = validator(value);

    return {
      valid,
      errors: valid ? [] : (validator.errors ?? []).map(copyAjvError),
    };
  }

  public assertValid(schemaReference: string, value: unknown): void {
    const result = this.validate(schemaReference, value);
    if (!result.valid) {
      const details = result.errors
        .map((error) => `${error.instancePath || "/"} ${error.message}`)
        .join("; ");
      throw new ProtocolSchemaError(
        `Value does not satisfy schema ${JSON.stringify(schemaReference)}: ${details}`,
      );
    }
  }

  #getValidator(schemaReference: string): ValidateFunction {
    const normalizedReference = normalizeReference(schemaReference);
    const key = this.#aliases.get(normalizedReference) ?? schemaReference;
    const cached = this.#validators.get(key);
    if (cached !== undefined) {
      return cached;
    }

    const validator = this.#ajv.getSchema(key);
    if (validator === undefined) {
      throw new ProtocolSchemaError(
        `Unknown protocol schema ${JSON.stringify(schemaReference)}. Loaded schemas: ${this.schemaReferences.join(", ")}`,
      );
    }

    this.#validators.set(key, validator);
    return validator;
  }

  async #loadSchemas(): Promise<void> {
    const schemasDirectory = resolve(this.#protocolRoot, "schemas");
    let schemaFiles: string[];

    try {
      schemaFiles = await findSchemaFiles(schemasDirectory);
    } catch (error) {
      throw new ProtocolSchemaError(`Unable to read protocol schemas at ${schemasDirectory}`, {
        cause: error,
      });
    }

    if (schemaFiles.length === 0) {
      throw new ProtocolSchemaError(`No *.schema.json files found at ${schemasDirectory}`);
    }

    for (const schemaFile of schemaFiles) {
      let schema: unknown;
      try {
        schema = JSON.parse(await readFile(schemaFile, "utf8"));
      } catch (error) {
        throw new ProtocolSchemaError(`Unable to parse protocol schema ${schemaFile}`, {
          cause: error,
        });
      }

      if (!isJsonObject(schema)) {
        throw new ProtocolSchemaError(`Protocol schema ${schemaFile} must contain a JSON object`);
      }

      const relativePath = normalizeReference(relative(this.#protocolRoot, schemaFile));
      const fileName = basename(schemaFile);
      const fileUri = pathToFileURL(schemaFile).href;
      const schemaId = typeof schema["$id"] === "string" ? schema["$id"] : undefined;

      try {
        this.#ajv.addSchema(schema as JsonSchema, fileUri);
      } catch (error) {
        throw new ProtocolSchemaError(`Unable to register protocol schema ${relativePath}`, {
          cause: error,
        });
      }

      const key = schemaId ?? fileUri;
      this.#registerAlias(relativePath, key);
      this.#registerAlias(relativePath.replace(/^schemas\//u, ""), key);
      this.#registerAlias(fileName, key);
      this.#registerAlias(fileUri, key);
      if (schemaId !== undefined) {
        this.#registerAlias(schemaId, key);
        try {
          this.#registerAlias(basename(new URL(schemaId).pathname), key);
        } catch {
          this.#registerAlias(basename(schemaId), key);
        }
      }
    }
  }

  #registerAlias(alias: string, key: string): void {
    const normalizedAlias = normalizeReference(alias);
    const existing = this.#aliases.get(normalizedAlias);
    if (existing !== undefined && existing !== key) {
      throw new ProtocolSchemaError(
        `Ambiguous protocol schema alias ${JSON.stringify(normalizedAlias)}`,
      );
    }
    this.#aliases.set(normalizedAlias, key);
  }
}

export function resolveProtocolPath(protocolRoot: string, reference: string): string {
  const root = resolve(protocolRoot);
  const candidate = resolve(root, reference);
  const relativePath = relative(root, candidate);

  if (relativePath === "" || relativePath === ".") {
    return candidate;
  }
  if (relativePath.startsWith(`..${sep}`) || relativePath === "..") {
    throw new ProtocolSchemaError(
      `Protocol path ${JSON.stringify(reference)} escapes the protocol directory`,
    );
  }

  return candidate;
}
