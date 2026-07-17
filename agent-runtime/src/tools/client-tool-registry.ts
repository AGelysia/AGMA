import type { ModelToolDefinition } from "../providers/model-provider.js";
import type { SchemaRegistry } from "../protocol/schema-registry.js";
import {
  clientToolIds,
  type ClientToolId,
  type ToolExecutionResult,
  type ToolResultSource,
  type ToolResultTrust,
} from "./tool-types.js";

export interface ClientToolDescriptor extends ModelToolDefinition {
  readonly id: ClientToolId;
  readonly argumentsSchema: string;
  readonly resultSchema: string;
  readonly source: ToolResultSource;
  readonly trust: ToolResultTrust;
  readonly execution: "connector_remote";
}

const sources = [
  {
    id: "game.resource.search",
    providerName: "game_resource_search",
    description:
      "Search the bounded client-visible Minecraft resource catalog and preserve ambiguous candidates.",
    source: "client_catalog",
    trust: "client_visible",
  },
  {
    id: "game.process.lookup",
    providerName: "game_process_lookup",
    description:
      "Look up client-visible processes producing an exact resource in a pinned generation.",
    source: "client_catalog",
    trust: "client_visible",
  },
  {
    id: "game.process.uses",
    providerName: "game_process_uses",
    description:
      "Look up client-visible processes consuming an exact resource in a pinned generation.",
    source: "client_catalog",
    trust: "client_visible",
  },
  {
    id: "game.process.plan",
    providerName: "game_process_plan",
    description:
      "Request a bounded deterministic process plan with final materials and workstation requirements.",
    source: "client_planner",
    trust: "deterministic",
  },
  {
    id: "game.inventory.snapshot",
    providerName: "game_inventory_snapshot",
    description: "Read a bounded, single-use-authorized subset of the local player's inventory.",
    source: "client_context",
    trust: "client_visible",
  },
] as const;

function closedObject(
  properties: Readonly<Record<string, unknown>>,
  required: readonly string[],
): Readonly<Record<string, unknown>> {
  return { type: "object", properties, required, additionalProperties: false };
}

function providerParameters(id: ClientToolId): Readonly<Record<string, unknown>> {
  if (id === "game.resource.search") {
    return closedObject({ query: { type: "string" }, limit: { type: "integer" } }, [
      "query",
      "limit",
    ]);
  }
  if (id === "game.process.lookup" || id === "game.process.uses") {
    return closedObject(
      {
        resourceId: { type: "string" },
        generationId: { type: "string" },
        limit: { type: "integer" },
      },
      ["resourceId", "generationId", "limit"],
    );
  }
  if (id === "game.process.plan") {
    return closedObject(
      {
        resourceId: { type: "string" },
        amount: { type: "number" },
        generationId: { type: "string" },
        maxDepth: { type: "integer" },
        maxNodes: { type: "integer" },
        topK: { type: "integer" },
      },
      ["resourceId", "amount", "generationId", "maxDepth", "maxNodes", "topK"],
    );
  }
  return closedObject(
    {
      authorizationId: { type: "string" },
      generationId: { type: "string" },
      resourceIds: { type: "array", items: { type: "string" } },
    },
    ["authorizationId", "generationId", "resourceIds"],
  );
}

function schemaReference(id: ClientToolId, kind: "arguments" | "result"): string {
  return `tools/${id.replaceAll(/[._]/gu, "-")}-${kind}.schema.json`;
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function resourceGeneration(value: unknown, generationId: string): boolean {
  return (
    isRecord(value) && isRecord(value["source"]) && value["source"]["generationId"] === generationId
  );
}

function processGeneration(value: unknown, generationId: string): boolean {
  if (
    !isRecord(value) ||
    !isRecord(value["source"]) ||
    value["source"]["generationId"] !== generationId
  ) {
    return false;
  }
  const collections = [
    value["workstations"],
    value["inputs"],
    value["catalysts"],
    value["outputs"],
  ];
  if (collections.some((collection) => !Array.isArray(collection))) return false;
  return (
    (value["workstations"] as readonly unknown[]).every((resource) =>
      resourceGeneration(resource, generationId),
    ) &&
    (value["inputs"] as readonly unknown[]).every(
      (input) =>
        isRecord(input) &&
        Array.isArray(input["alternatives"]) &&
        input["alternatives"].every((resource) => resourceGeneration(resource, generationId)),
    ) &&
    (value["catalysts"] as readonly unknown[]).every(
      (catalyst) =>
        isRecord(catalyst) &&
        resourceGeneration(catalyst["resource"], generationId) &&
        (catalyst["returnedResource"] === null ||
          resourceGeneration(catalyst["returnedResource"], generationId)),
    ) &&
    (value["outputs"] as readonly unknown[]).every(
      (output) => isRecord(output) && resourceGeneration(output["resource"], generationId),
    ) &&
    (value["energy"] === null || resourceGeneration(value["energy"], generationId))
  );
}

function semanticResult(
  descriptor: ClientToolDescriptor,
  result: Readonly<Record<string, unknown>>,
  argumentsValue?: Readonly<Record<string, unknown>>,
): boolean {
  const generationId = result["generationId"];
  if (typeof generationId !== "string") return false;
  if (descriptor.id === "game.resource.search") {
    return (
      Array.isArray(result["candidates"]) &&
      result["candidates"].every(
        (candidate) =>
          isRecord(candidate) && resourceGeneration(candidate["resource"], generationId),
      )
    );
  }
  if (descriptor.id === "game.process.lookup" || descriptor.id === "game.process.uses") {
    return (
      Array.isArray(result["processes"]) &&
      result["processes"].every((process) => processGeneration(process, generationId))
    );
  }
  if (descriptor.id === "game.inventory.snapshot") {
    const authorized = argumentsValue?.["resourceIds"];
    const entries = result["entries"];
    if (!Array.isArray(entries)) return false;
    if (argumentsValue === undefined) return entries.every(isRecord);
    if (
      result["authorizationId"] !== argumentsValue["authorizationId"] ||
      result["generationId"] !== argumentsValue["generationId"] ||
      !Array.isArray(authorized) ||
      !authorized.every((entry): entry is string => typeof entry === "string")
    ) {
      return false;
    }
    const allowed = new Set(authorized);
    return entries.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry["resourceId"] === "string" &&
        allowed.has(entry["resourceId"]),
    );
  }
  if (descriptor.id === "game.process.plan" && argumentsValue !== undefined) {
    const target = result["target"];
    return (
      result["generationId"] === argumentsValue["generationId"] &&
      isRecord(target) &&
      target["resourceId"] === argumentsValue["resourceId"] &&
      target["amount"] === argumentsValue["amount"]
    );
  }
  return true;
}

export class ClientToolRegistry {
  readonly #schemas: SchemaRegistry;
  readonly #configured: readonly ClientToolId[];
  readonly #active = new Set<ClientToolId>();
  readonly #byId = new Map<ClientToolId, ClientToolDescriptor>();
  readonly #byProviderName = new Map<string, ClientToolDescriptor>();

  public constructor(schemas: SchemaRegistry, allowed: readonly string[]) {
    const allowlist = new Set(allowed);
    if (
      allowlist.size !== allowed.length ||
      allowed.some((id) => !clientToolIds.includes(id as ClientToolId))
    ) {
      throw new TypeError("The client Tool allowlist is invalid.");
    }
    this.#schemas = schemas;
    this.#configured = clientToolIds.filter((id) => allowlist.has(id));
    for (const source of sources) {
      if (!allowlist.has(source.id)) continue;
      const descriptor: ClientToolDescriptor = Object.freeze({
        ...source,
        execution: "connector_remote",
        argumentsSchema: schemaReference(source.id, "arguments"),
        resultSchema: schemaReference(source.id, "result"),
        parameters: providerParameters(source.id),
      });
      this.#byId.set(source.id, descriptor);
      this.#byProviderName.set(source.providerName, descriptor);
    }
  }

  public configuredClientTools(): readonly ClientToolId[] {
    return this.#configured;
  }

  public activateClientCapabilities(capabilityIds: readonly string[]): void {
    this.#active.clear();
    const advertised = new Set(capabilityIds);
    for (const id of this.#configured) if (advertised.has(id)) this.#active.add(id);
  }

  public clearClientCapabilities(): void {
    this.#active.clear();
  }

  public isClientToolActive(id: ClientToolId): boolean {
    return this.#active.has(id);
  }

  public byId(id: ClientToolId): ClientToolDescriptor | undefined {
    return this.#byId.get(id);
  }

  public byProviderName(providerName: string): ClientToolDescriptor | undefined {
    const descriptor = this.#byProviderName.get(providerName);
    return descriptor !== undefined && this.#active.has(descriptor.id) ? descriptor : undefined;
  }

  public activeTools(): readonly ClientToolDescriptor[] {
    return this.#configured.flatMap((id) => {
      const descriptor = this.#byId.get(id);
      return descriptor !== undefined && this.#active.has(id) ? [descriptor] : [];
    });
  }

  public validateArguments(
    descriptor: ClientToolDescriptor,
    value: Readonly<Record<string, unknown>>,
  ): boolean {
    return this.#schemas.validate(descriptor.argumentsSchema, value).valid;
  }

  public validateResult(
    descriptor: ClientToolDescriptor,
    value: ToolExecutionResult,
    argumentsValue?: Readonly<Record<string, unknown>>,
  ): boolean {
    return (
      value.status === "succeeded" &&
      value.source === descriptor.source &&
      value.trust === descriptor.trust &&
      value.result !== null &&
      value.error === null &&
      this.#schemas.validate(descriptor.resultSchema, value.result).valid &&
      semanticResult(descriptor, value.result, argumentsValue)
    );
  }
}
