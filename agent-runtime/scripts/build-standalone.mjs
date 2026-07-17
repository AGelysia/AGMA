import { rm, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputRoot = resolve(root, "dist-standalone");
const outputFile = resolve(outputRoot, "bootstrap/index.js");
const forbiddenInputs = [
  "src/bootstrap/index.ts",
  "src/config/runtime-config.ts",
  "src/transport/paper-handshake.ts",
  "src/tools/tool-registry.ts",
  "src/tools/local-tool-executor.ts",
  "src/requests/agent-request-service.ts",
  "src/modules/module-manifest.ts",
  "src/knowledge/",
];
const forbiddenOutput = [
  "paper.hello",
  "paper.command",
  "paper.permission",
  '"/agent"',
  "paper_remote",
  "server.payload",
  "player.context.read",
  "server.recipe.lookup",
  "management.costs",
  "agent-request.schema.json",
  "management-costs-request.schema.json",
  "proposal.schema.json",
];

await rm(outputRoot, { recursive: true, force: true });
await mkdir(dirname(outputFile), { recursive: true });
const result = await build({
  absWorkingDir: root,
  entryPoints: ["src/standalone/bootstrap/index.ts"],
  outfile: outputFile,
  bundle: true,
  packages: "external",
  platform: "node",
  target: "node22",
  format: "esm",
  treeShaking: true,
  sourcemap: false,
  metafile: true,
  logLevel: "warning",
});

const inputs = Object.keys(result.metafile.inputs).sort();
for (const forbidden of forbiddenInputs) {
  if (inputs.some((input) => input === forbidden || input.startsWith(forbidden))) {
    throw new Error(`Standalone Runtime graph contains forbidden input: ${forbidden}`);
  }
}
const output = await readFile(outputFile, "utf8");
for (const forbidden of forbiddenOutput) {
  if (output.includes(forbidden)) {
    throw new Error(`Standalone Runtime output contains forbidden capability: ${forbidden}`);
  }
}

function packageRoot(specifier) {
  if (specifier.startsWith("node:") || specifier.startsWith(".")) return undefined;
  const parts = specifier.split("/");
  return specifier.startsWith("@") ? parts.slice(0, 2).join("/") : parts[0];
}

function resolveDependency(lock, parentPath, dependency) {
  let current = parentPath;
  while (true) {
    const candidate =
      current === "" ? `node_modules/${dependency}` : `${current}/node_modules/${dependency}`;
    if (lock.packages[candidate] !== undefined) return candidate;
    const boundary = current.lastIndexOf("/node_modules/");
    if (boundary < 0) {
      if (current === "") break;
      current = "";
    } else {
      current = current.slice(0, boundary);
    }
  }
  throw new Error(
    `Standalone dependency is unresolved from ${parentPath || "root"}: ${dependency}`,
  );
}

function standalonePackageFiles(sourcePackage, sourceLock, roots) {
  if (sourceLock.lockfileVersion !== 3 || sourceLock.packages?.[""] === undefined) {
    throw new Error("Standalone Runtime requires an npm lockfileVersion 3 package inventory.");
  }
  const dependencies = {};
  for (const name of roots) {
    const version = sourcePackage.dependencies?.[name];
    if (typeof version !== "string") {
      throw new Error(`Standalone external package is not a pinned production dependency: ${name}`);
    }
    dependencies[name] = version;
  }

  const selected = new Set();
  const pending = roots.map((name) => resolveDependency(sourceLock, "", name));
  while (pending.length > 0) {
    const packagePath = pending.pop();
    if (packagePath === undefined || selected.has(packagePath)) continue;
    const entry = sourceLock.packages[packagePath];
    if (entry === undefined || entry.dev === true) {
      throw new Error(
        `Standalone dependency closure contains a missing or development package: ${packagePath}`,
      );
    }
    selected.add(packagePath);
    const required = {
      ...(entry.dependencies ?? {}),
      ...(entry.optionalDependencies ?? {}),
      ...(entry.peerDependencies ?? {}),
    };
    const optionalPeers = entry.peerDependenciesMeta ?? {};
    for (const name of Object.keys(required).sort()) {
      if (optionalPeers[name]?.optional === true) continue;
      pending.push(resolveDependency(sourceLock, packagePath, name));
    }
  }

  const runtimePackage = {
    name: sourcePackage.name,
    version: sourcePackage.version,
    private: true,
    type: "module",
    engines: sourcePackage.engines,
    dependencies,
  };
  const packages = { "": { ...runtimePackage } };
  delete packages[""].private;
  delete packages[""].type;
  for (const packagePath of [...selected].sort()) {
    packages[packagePath] = sourceLock.packages[packagePath];
  }
  return {
    packageJson: runtimePackage,
    packageLock: {
      name: sourcePackage.name,
      version: sourcePackage.version,
      lockfileVersion: 3,
      requires: true,
      packages,
    },
  };
}

const externalPackages = new Set();
for (const outputDetails of Object.values(result.metafile.outputs)) {
  for (const imported of outputDetails.imports) {
    if (!imported.external) continue;
    const rootPackage = packageRoot(imported.path);
    if (rootPackage !== undefined) externalPackages.add(rootPackage);
  }
}
const sourcePackage = JSON.parse(await readFile(resolve(root, "package.json"), "utf8"));
const sourceLock = JSON.parse(await readFile(resolve(root, "package-lock.json"), "utf8"));
const standaloneRelease = JSON.parse(
  await readFile(resolve(root, "../standalone-client/version.json"), "utf8"),
);
const runtimePackage = standalonePackageFiles(
  { ...sourcePackage, version: standaloneRelease.version },
  sourceLock,
  [...externalPackages].sort(),
);
await writeFile(
  resolve(outputRoot, "metafile.json"),
  `${JSON.stringify(result.metafile, null, 2)}\n`,
  "utf8",
);
await writeFile(
  resolve(outputRoot, "package.json"),
  `${JSON.stringify(runtimePackage.packageJson, null, 2)}\n`,
  "utf8",
);
await writeFile(
  resolve(outputRoot, "package-lock.json"),
  `${JSON.stringify(runtimePackage.packageLock, null, 2)}\n`,
  "utf8",
);
await writeFile(
  resolve(outputRoot, "external-packages.json"),
  `${JSON.stringify({ schemaVersion: "1.0", packages: [...externalPackages].sort() }, null, 2)}\n`,
  "utf8",
);
