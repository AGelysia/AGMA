import { describe, expect, it } from "vitest";

import { parseRuntimeCli } from "../src/bootstrap/index.js";

describe("runtime CLI", () => {
  it("keeps the existing external forms and accepts one fixed managed form", () => {
    expect(parseRuntimeCli([])).toEqual({ managed: false });
    expect(parseRuntimeCli(["--config", "/private/runtime.yml"])).toEqual({
      configPath: "/private/runtime.yml",
      managed: false,
    });
    expect(parseRuntimeCli(["--config", "/private/runtime.yml", "--managed"])).toEqual({
      configPath: "/private/runtime.yml",
      managed: true,
    });
  });

  it.each([
    ["--managed"],
    ["--config"],
    ["--managed", "--config", "/private/runtime.yml"],
    ["--config", "/private/runtime.yml", "--managed", "secret"],
  ])("rejects unsupported or ambiguous arguments: %j", (...arguments_) => {
    expect(() => parseRuntimeCli(arguments_)).toThrowError(
      expect.objectContaining({ code: "CONFIG_PATH_INVALID" }),
    );
  });
});
