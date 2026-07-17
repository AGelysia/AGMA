import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { promisify } from "node:util";

import { beforeAll, describe, expect, it } from "vitest";

const execute = promisify(execFile);
const root = new URL("../", import.meta.url).pathname;

interface Metafile {
  readonly inputs: Readonly<Record<string, unknown>>;
  readonly outputs: Readonly<
    Record<
      string,
      { readonly imports: readonly { readonly path: string; readonly external?: boolean }[] }
    >
  >;
}

interface RuntimePackageLock {
  readonly lockfileVersion: number;
  readonly packages: Readonly<Record<string, { readonly dev?: boolean }>>;
}

function packageRoot(specifier: string): string | undefined {
  if (specifier.startsWith("node:") || specifier.startsWith(".")) return undefined;
  const parts = specifier.split("/");
  return specifier.startsWith("@") ? parts.slice(0, 2).join("/") : parts[0];
}

describe("standalone Runtime build graph", () => {
  beforeAll(async () => {
    await execute(process.execPath, ["scripts/build-standalone.mjs"], { cwd: root });
  });

  it("contains only the client bootstrap and excludes Paper/server transport sources", async () => {
    const metafile = JSON.parse(
      await readFile(new URL("../dist-standalone/metafile.json", import.meta.url), "utf8"),
    ) as Metafile;
    const inputs = Object.keys(metafile.inputs);

    expect(inputs).toContain("src/standalone/bootstrap/index.ts");
    expect(inputs).toContain("src/config/standalone-client-config.ts");
    expect(inputs).toContain("src/requests/client-agent-request-service.ts");
    expect(inputs).toContain("src/tools/client-tool-registry.ts");
    expect(inputs).not.toContain("src/bootstrap/index.ts");
    expect(inputs).not.toContain("src/config/runtime-config.ts");
    expect(inputs).not.toContain("src/transport/paper-handshake.ts");
    expect(inputs).not.toContain("src/requests/agent-request-service.ts");
    expect(inputs).not.toContain("src/tools/tool-registry.ts");
    expect(inputs.some((input) => input.startsWith("src/knowledge/"))).toBe(false);
  });

  it("contains no Paper route, handshake, execution target, or server Tool payload", async () => {
    const bundle = await readFile(
      new URL("../dist-standalone/bootstrap/index.js", import.meta.url),
      "utf8",
    );

    expect(bundle).not.toContain("paper.hello");
    expect(bundle).not.toContain("paper.command");
    expect(bundle).not.toContain("paper.permission");
    expect(bundle).not.toContain('"/agent"');
    expect(bundle).not.toContain("paper_remote");
    expect(bundle).not.toContain("server.recipe.lookup");
    expect(bundle).not.toContain("server.payload");
    expect(bundle).not.toContain("player.context.read");
    expect(bundle).not.toContain("agent-request.schema.json");
    expect(bundle).toContain("connector-request.schema.json");
    expect(bundle).toContain("evidence-claim.schema.json");
  });

  it("publishes a complete sorted external root-package list from the esbuild graph", async () => {
    const metafile = JSON.parse(
      await readFile(new URL("../dist-standalone/metafile.json", import.meta.url), "utf8"),
    ) as Metafile;
    const manifest = JSON.parse(
      await readFile(new URL("../dist-standalone/external-packages.json", import.meta.url), "utf8"),
    ) as { readonly schemaVersion: string; readonly packages: readonly string[] };
    const runtimePackage = JSON.parse(
      await readFile(new URL("../dist-standalone/package.json", import.meta.url), "utf8"),
    ) as { readonly dependencies: Readonly<Record<string, string>> };
    const runtimeLock = JSON.parse(
      await readFile(new URL("../dist-standalone/package-lock.json", import.meta.url), "utf8"),
    ) as RuntimePackageLock;
    const external = new Set<string>();
    for (const output of Object.values(metafile.outputs)) {
      for (const imported of output.imports) {
        if (imported.external !== true) continue;
        const rootPackage = packageRoot(imported.path);
        if (rootPackage !== undefined) external.add(rootPackage);
      }
    }

    expect(manifest).toEqual({ schemaVersion: "1.0", packages: [...external].sort() });
    expect(Object.keys(runtimePackage.dependencies).sort()).toEqual(manifest.packages);
    expect(runtimeLock.lockfileVersion).toBe(3);
    expect(runtimeLock.packages[""]).toMatchObject({ dependencies: runtimePackage.dependencies });
    expect(Object.values(runtimeLock.packages).some((entry) => entry.dev === true)).toBe(false);
    expect(manifest.packages).toContain("ajv-formats");
    expect(manifest.packages).not.toContain("mdast-util-from-markdown");
  });
});
