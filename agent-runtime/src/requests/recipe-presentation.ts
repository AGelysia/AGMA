import { validateRecipeViewV2 } from "../protocol/semantic-validation.js";
import type { CoreToolId, ToolExecutionResult } from "../tools/tool-types.js";

const RECIPE_VIEW_CONTENT_LIMIT_BYTES = 48 * 1024;
const FALLBACK_VARIANT_LIMIT = 4;

type RecipeToolId = Extract<CoreToolId, "server.recipe.lookup" | "server.recipe.uses">;
type JsonRecord = Readonly<Record<string, unknown>>;

export interface RecipeViewContentV2 {
  readonly schemaVersion: "2.0";
  readonly query: {
    readonly mode: "lookup" | "uses";
    readonly itemId: string;
  };
  readonly selectedRecipe: 0;
  readonly totalMatches: number;
  readonly truncated: boolean;
  readonly recipes: readonly JsonRecord[];
}

export interface RecipeViewPresentation {
  readonly title: string;
  readonly content: RecipeViewContentV2;
}

export interface AuthoritativeRecipePresentation {
  readonly fallbackText: string;
  readonly view?: RecipeViewPresentation;
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRecipeTool(tool: CoreToolId): tool is RecipeToolId {
  return tool === "server.recipe.lookup" || tool === "server.recipe.uses";
}

function contentBytes(content: RecipeViewContentV2): number {
  return Buffer.byteLength(JSON.stringify(content), "utf8");
}

function titleFor(itemId: string): string {
  return `Recipes for ${itemId}`.slice(0, 128);
}

function queryScope(mode: "lookup" | "uses", itemId: string, singular = false): string {
  if (mode === "lookup") {
    return `${singular ? "produces" : "produce"} ${itemId}`;
  }
  return `${singular ? "uses" : "use"} ${itemId}`;
}

function describeVariant(recipe: JsonRecord): string | undefined {
  const recipeId = recipe["recipeId"];
  const recipeType = recipe["recipeType"];
  if (typeof recipeId !== "string" || typeof recipeType !== "string") {
    return undefined;
  }
  const result = recipe["result"];
  if (!isRecord(result)) {
    return `${recipeId} (${recipeType}, dynamic result)`;
  }
  const itemId = result["itemId"];
  const count = result["count"];
  if (typeof itemId !== "string" || !Number.isSafeInteger(count)) {
    return undefined;
  }
  return `${recipeId} (${recipeType}, ${String(count)}x ${itemId})`;
}

function fallbackFor(
  query: RecipeViewContentV2["query"],
  totalMatches: number,
  recipes: readonly JsonRecord[],
  truncated: boolean,
): string {
  const countLabel = totalMatches === 1 ? "recipe" : "recipes";
  const prefix = "Text-only recipe summary:";
  if (totalMatches === 0) {
    if (truncated) {
      return `${prefix} the authoritative server registry found no verified recipes that ${queryScope(query.mode, query.itemId)}, but the registry scan was incomplete.`;
    }
    return `${prefix} the authoritative server registry found no recipes that ${queryScope(query.mode, query.itemId)}.`;
  }

  const shown = recipes.slice(0, FALLBACK_VARIANT_LIMIT).flatMap((recipe) => {
    const description = describeVariant(recipe);
    return description === undefined ? [] : [description];
  });
  const returned = recipes.length === totalMatches ? "" : ` ${String(recipes.length)} returned.`;
  const variants = shown.length === 0 ? "" : ` Variants: ${shown.join("; ")}.`;
  return `${prefix} the authoritative server registry found ${String(totalMatches)} ${countLabel} that ${queryScope(query.mode, query.itemId, totalMatches === 1)}.${returned}${variants}`;
}

function unsafeResultFallback(): AuthoritativeRecipePresentation {
  return {
    fallbackText:
      "The server recipe registry returned data that could not be presented safely. Try again later.",
  };
}

function contentFor(
  query: RecipeViewContentV2["query"],
  recipes: readonly JsonRecord[],
  totalMatches: number,
  truncated: boolean,
): RecipeViewContentV2 {
  return {
    schemaVersion: "2.0",
    query,
    selectedRecipe: 0,
    totalMatches,
    truncated,
    recipes,
  };
}

/**
 * Snapshots one schema-validated authoritative recipe Tool result. Model output is deliberately
 * absent from this boundary so it cannot supply or amend recipe presentation fields.
 */
export function createAuthoritativeRecipePresentation(
  tool: CoreToolId,
  execution: ToolExecutionResult,
): AuthoritativeRecipePresentation | undefined {
  if (
    !isRecipeTool(tool) ||
    execution.status !== "succeeded" ||
    execution.source !== "server_registry" ||
    execution.trust !== "authoritative" ||
    !isRecord(execution.result)
  ) {
    return undefined;
  }

  const rawQuery = execution.result["query"];
  const rawRecipes = execution.result["recipes"];
  const totalMatches = execution.result["totalMatches"];
  const truncated = execution.result["truncated"];
  if (
    !isRecord(rawQuery) ||
    (rawQuery["mode"] !== "lookup" && rawQuery["mode"] !== "uses") ||
    typeof rawQuery["itemId"] !== "string" ||
    !Array.isArray(rawRecipes) ||
    !rawRecipes.every(isRecord) ||
    !Number.isSafeInteger(totalMatches) ||
    Number(totalMatches) < rawRecipes.length ||
    typeof truncated !== "boolean" ||
    (!truncated && Number(totalMatches) !== rawRecipes.length)
  ) {
    return unsafeResultFallback();
  }

  const query = structuredClone(rawQuery) as RecipeViewContentV2["query"];
  const recipes = structuredClone(rawRecipes) as JsonRecord[];
  const matchCount = Number(totalMatches);
  const fallbackText = fallbackFor(query, matchCount, recipes, truncated);
  if (recipes.length === 0) {
    return { fallbackText };
  }

  const completeContent = contentFor(query, recipes, matchCount, truncated);
  if (validateRecipeViewV2(completeContent).length > 0) {
    return unsafeResultFallback();
  }

  const publishedRecipes: JsonRecord[] = [];
  for (const recipe of recipes) {
    const candidate = contentFor(query, [...publishedRecipes, recipe], matchCount, true);
    if (contentBytes(candidate) > RECIPE_VIEW_CONTENT_LIMIT_BYTES) {
      break;
    }
    publishedRecipes.push(recipe);
  }
  if (publishedRecipes.length === 0) {
    return { fallbackText };
  }

  const content = contentFor(
    query,
    publishedRecipes,
    matchCount,
    truncated || publishedRecipes.length < recipes.length,
  );
  if (validateRecipeViewV2(content).length > 0) {
    return unsafeResultFallback();
  }
  return {
    fallbackText,
    view: {
      title: titleFor(query.itemId),
      content,
    },
  };
}

export function unavailableRecipeFallback(): string {
  return "The server recipe registry could not provide a verified result. Try again later.";
}

export function uncheckedRecipeFallback(): string {
  return "No verified server recipe was checked. Ask again with an exact Minecraft item ID.";
}
