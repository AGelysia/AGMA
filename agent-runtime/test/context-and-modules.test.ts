import { describe, expect, it } from "vitest";

import { ModuleRegistry, moduleIds } from "../src/modules/module-manifest.js";
import { buildContextWindow } from "../src/sessions/context-window.js";
import type { ConversationMessage } from "../src/storage/conversation-repository.js";

function message(
  requestId: string,
  role: "user" | "assistant",
  content: string,
): ConversationMessage {
  return {
    id: `${requestId}-${role}`,
    requestId,
    role,
    module: "general",
    content,
    createdAt: "2026-07-13T00:00:00.000Z",
  };
}

describe("context windows and module manifests", () => {
  it("drops complete oldest exchanges to satisfy both message and character budgets", () => {
    const history = [
      message("one", "user", "1111"),
      message("one", "assistant", "aaaa"),
      message("two", "user", "22"),
      message("two", "assistant", "bb"),
    ];

    expect(
      buildContextWindow(history, "now", { maximumMessages: 4, maximumCharacters: 9 }),
    ).toEqual([
      { role: "user", content: "22" },
      { role: "assistant", content: "bb" },
      { role: "user", content: "now" },
    ]);
    expect(
      buildContextWindow(history, "current-is-always-kept", {
        maximumMessages: 1,
        maximumCharacters: 4,
      }),
    ).toEqual([{ role: "user", content: "current-is-always-kept" }]);
  });

  it("defines six one-shot module prompts with fixed read-only Tool allowlists", () => {
    const registry = new ModuleRegistry();
    expect(registry.list().map((manifest) => manifest.id)).toEqual(moduleIds);
    expect(registry.get("recipe").toolAllowlist).toEqual([
      "player.held_item.read",
      "server.recipe.lookup",
      "server.recipe.uses",
    ]);
    expect(registry.get("locate").toolAllowlist).toEqual([
      "player.context.read",
      "server.info.read",
      "landmark.search",
    ]);
    expect(registry.get("guide").toolAllowlist).toContain("server.docs.search");
    expect(registry.get("build").toolAllowlist).toContain("build.preview.create");
    expect(registry.get("build").toolAllowlist).toContain("project.read");
    expect(registry.get("build").instructions).toContain("call project.read in this request");
    expect(registry.get("build").instructions).toContain("Never claim to have changed the world");
    expect(registry.get("project").toolAllowlist).toEqual(
      expect.arrayContaining(["project.list", "project.read", "project.create", "project.update"]),
    );
    expect(new Set(registry.list().map((manifest) => manifest.instructions)).size).toBe(
      moduleIds.length,
    );
    expect(registry.get("recipe").instructions).toContain("recipe");
    expect(registry.get("general").instructions).not.toContain("recipe");
  });
});
