import { describe, expect, it } from "vitest";

import { createAuthoritativeRecipePresentation } from "../src/requests/recipe-presentation.js";
import type { ToolExecutionResult } from "../src/tools/tool-types.js";

function recipe(index: number, large = false): Record<string, unknown> {
  const text = "x".repeat(500);
  const components = large ? { customName: text, lore: [text, text, text] } : {};
  return {
    recipeId: `test:stone_variant_${String(index)}`,
    recipeType: "stonecutting",
    source: { kind: "server_registry", providerId: null },
    result: { itemId: "minecraft:stone_bricks", count: 1, components },
    layout: {
      kind: "single_input",
      ingredient: {
        choiceType: "exact",
        alternatives: [{ itemId: "minecraft:stone", count: 1, components }],
      },
    },
    remainingItems: [],
  };
}

function execution(recipes: readonly Record<string, unknown>[]): ToolExecutionResult {
  return {
    status: "succeeded",
    source: "server_registry",
    trust: "authoritative",
    result: {
      query: { mode: "lookup", itemId: "minecraft:stone_bricks" },
      recipes,
      totalMatches: recipes.length,
      truncated: false,
    },
    error: null,
  };
}

describe("Authoritative recipe presentation", () => {
  it("rejects a result without the exact authoritative server-registry boundary", () => {
    expect(
      createAuthoritativeRecipePresentation("server.recipe.lookup", {
        ...execution([recipe(1)]),
        trust: "verified",
      }),
    ).toBeUndefined();
    expect(
      createAuthoritativeRecipePresentation("server.info.read", execution([recipe(1)])),
    ).toBeUndefined();
  });

  it("refuses a schema-valid but semantically ambiguous recipe set", () => {
    const duplicated = recipe(1);
    const presentation = createAuthoritativeRecipePresentation(
      "server.recipe.lookup",
      execution([duplicated, structuredClone(duplicated)]),
    );

    expect(presentation).toEqual({
      fallbackText:
        "The server recipe registry returned data that could not be presented safely. Try again later.",
    });
  });

  it("does not describe an incomplete uses scan as a definitive zero result", () => {
    const result: ToolExecutionResult = {
      ...execution([]),
      result: {
        query: { mode: "uses", itemId: "minecraft:stone" },
        recipes: [],
        totalMatches: 0,
        truncated: true,
      },
    };

    const presentation = createAuthoritativeRecipePresentation("server.recipe.uses", result);

    expect(presentation?.fallbackText).toContain("no verified recipes");
    expect(presentation?.fallbackText).toContain("scan was incomplete");
    expect(presentation?.view).toBeUndefined();
  });

  it("snapshots the result and truncates only at a complete recipe boundary", () => {
    const recipes = Array.from({ length: 16 }, (_, index) => recipe(index, true));
    const result = execution(recipes);
    const presentation = createAuthoritativeRecipePresentation("server.recipe.lookup", result);
    const view = presentation?.view;

    expect(view).toBeDefined();
    expect(view?.content.recipes.length).toBeGreaterThan(0);
    expect(view?.content.recipes.length).toBeLessThan(recipes.length);
    expect(view?.content).toMatchObject({ totalMatches: 16, truncated: true });
    expect(Buffer.byteLength(JSON.stringify(view?.content), "utf8")).toBeLessThanOrEqual(48 * 1024);

    recipes[0]!["recipeId"] = "spoof:mutated_after_snapshot";
    expect(view?.content.recipes[0]?.["recipeId"]).toBe("test:stone_variant_0");
  });
});
