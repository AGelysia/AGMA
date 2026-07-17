import { pathToFileURL } from "node:url";

import { describe, expect, it, vi } from "vitest";

import { isMainModule } from "../src/bootstrap/main-module.js";

const RELEASE_ENTRY = "/opt/agma/releases/0.1.0/runtime/dist/bootstrap/index.js";
const CURRENT_ENTRY = "/opt/agma/current/runtime/dist/bootstrap/index.js";
const MODULE_URL = pathToFileURL(RELEASE_ENTRY).href;

describe("main module detection", () => {
  it("does not start for a missing or unresolvable entry", () => {
    expect(isMainModule(undefined, MODULE_URL)).toBe(false);
    expect(
      isMainModule(CURRENT_ENTRY, MODULE_URL, () => {
        throw new Error("unresolvable");
      }),
    ).toBe(false);
  });

  it("matches a symlinked entry to the physical module path", () => {
    const resolveRealPath = vi.fn((path: string) =>
      path === CURRENT_ENTRY ? RELEASE_ENTRY : path,
    );

    expect(isMainModule(CURRENT_ENTRY, MODULE_URL, resolveRealPath)).toBe(true);
    expect(resolveRealPath).toHaveBeenCalledTimes(2);
  });

  it("rejects a different physical entry", () => {
    expect(isMainModule("/opt/agma/other.js", MODULE_URL, (path) => path)).toBe(false);
  });
});
