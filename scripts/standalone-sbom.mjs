import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  closeSync,
  lstatSync,
  mkdtempSync,
  openSync,
  readFileSync,
  readSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";

const TARGETS = [
  { minecraft: "1.18.2", loader: "fabric" },
  { minecraft: "1.18.2", loader: "forge" },
  { minecraft: "1.21.11", loader: "fabric" },
];
const PLATFORMS = ["linux-x86_64", "windows-x86_64"];
const DESCRIPTOR_ENTRY = "META-INF/agma-standalone/runtime-artifact.json";
const RUNTIME_ENTRY = "META-INF/agma-standalone/runtime.zip";
const FORGE_DESCRIPTOR_ENTRY = "META-INF/mods.toml";
const FORGE_JARJAR_ENTRY = "META-INF/jarjar/metadata.json";
const MANIFEST_ENTRY = "sidecar-manifest.json";
const INVENTORY_ENTRY = "licenses/npm-production-packages.json";
const VERSION = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/u;
const PACKAGE_VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}$/u;
const SHA256 = /^[0-9a-f]{64}$/u;
const MAXIMUM_JAR_BYTES = 768 * 1024 * 1024;
const MAXIMUM_RUNTIME_BYTES = 512 * 1024 * 1024;
const MAXIMUM_MANIFEST_BYTES = 16 * 1024 * 1024;
const MAXIMUM_INVENTORY_BYTES = 4 * 1024 * 1024;
const decoder = new TextDecoder("utf-8", { fatal: true });

function fail(message) {
  throw new Error(`standalone-sbom: ${message}`);
}

function byteCompare(left, right) {
  return Buffer.from(left, "utf8").compare(Buffer.from(right, "utf8"));
}

function exactKeys(value, keys) {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const actual = Object.keys(value).sort(byteCompare);
  const expected = [...keys].sort(byteCompare);
  return (
    actual.length === expected.length &&
    actual.every((key, index) => key === expected[index])
  );
}

function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function parseCanonicalJson(bytes, label) {
  let parsed;
  try {
    parsed = JSON.parse(decoder.decode(bytes));
  } catch {
    fail(`${label} is not valid UTF-8 JSON`);
  }
  if (!bytes.equals(Buffer.from(canonicalJson(parsed), "utf8"))) {
    fail(`${label} is not canonical JSON`);
  }
  return parsed;
}

function parseForgeJarJarJson(bytes, label) {
  let parsed;
  try {
    parsed = JSON.parse(decoder.decode(bytes));
  } catch {
    fail(`${label} is not valid UTF-8 JSON`);
  }
  const canonical = Buffer.from(canonicalJson(parsed), "utf8");
  if (
    !bytes.equals(canonical) &&
    !bytes.equals(canonical.subarray(0, canonical.length - 1))
  ) {
    fail(`${label} is not stable Forge JSON`);
  }
  return parsed;
}

function parseForgeModsToml(bytes, label) {
  let text;
  try {
    text = decoder.decode(bytes);
  } catch {
    fail(`${label} is not valid UTF-8 TOML`);
  }
  const document = { root: {}, mods: [], dependencies: [] };
  let current = document.root;
  const lines = text.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line === "") continue;
    if (line === "[[mods]]") {
      current = {};
      document.mods.push(current);
      continue;
    }
    if (line === "[[dependencies.agma_standalone]]") {
      current = {};
      document.dependencies.push(current);
      continue;
    }
    const assignment = /^([A-Za-z][A-Za-z0-9]*) = (.+)$/u.exec(line);
    if (assignment === null || Object.hasOwn(current, assignment[1])) {
      fail(
        `${label} contains unsupported or duplicate TOML at line ${index + 1}`,
      );
    }
    const [, key, raw] = assignment;
    let value;
    if (raw === "true" || raw === "false") {
      value = raw === "true";
    } else if (raw === "'''") {
      const content = [];
      for (
        index += 1;
        index < lines.length && lines[index] !== "'''";
        index += 1
      ) {
        content.push(lines[index]);
      }
      if (index >= lines.length)
        fail(`${label} contains an unterminated TOML string`);
      value = content.join("\n");
    } else {
      try {
        value = JSON.parse(raw);
      } catch {
        fail(
          `${label} contains an unsupported TOML value at line ${index + 1}`,
        );
      }
      if (typeof value !== "string") {
        fail(`${label} contains a non-string TOML value at line ${index + 1}`);
      }
    }
    current[key] = value;
  }
  return document;
}

function sha256Bytes(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function sha256File(path) {
  const hash = createHash("sha256");
  const descriptor = openSync(path, "r");
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  try {
    for (;;) {
      const count = readSync(descriptor, buffer, 0, buffer.length, null);
      if (count === 0) break;
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    closeSync(descriptor);
  }
  return hash.digest("hex");
}

function ordinaryFile(pathValue, maximumBytes) {
  const path = resolve(pathValue);
  const metadata = lstatSync(path);
  if (
    !metadata.isFile() ||
    metadata.isSymbolicLink() ||
    metadata.size < 1 ||
    metadata.size > maximumBytes
  ) {
    fail(`expected an ordinary bounded file: ${pathValue}`);
  }
  return { path, size: metadata.size };
}

function archiveEntries(archive) {
  let output;
  try {
    output = execFileSync("unzip", ["-Z1", archive], {
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch {
    fail(`could not inspect ZIP entries: ${basename(archive)}`);
  }
  const entries = output.split(/\r?\n/u).filter(Boolean);
  if (entries.length === 0 || entries.length > 65536) {
    fail(`ZIP entry count is outside its limit: ${basename(archive)}`);
  }
  return entries;
}

function archiveEntry(archive, entries, entry, maximumBytes) {
  if (entries.filter((candidate) => candidate === entry).length !== 1) {
    fail(`${basename(archive)} does not contain exactly one ${entry}`);
  }
  let bytes;
  try {
    bytes = execFileSync("unzip", ["-p", archive, entry], {
      encoding: "buffer",
      maxBuffer: maximumBytes + 1,
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch {
    fail(`could not read ${entry} from ${basename(archive)}`);
  }
  if (bytes.length < 1 || bytes.length > maximumBytes) {
    fail(`${entry} is outside its byte limit`);
  }
  return bytes;
}

function safeManifestPath(value) {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 512 &&
    !value.startsWith("/") &&
    !value.includes("\\") &&
    value
      .split("/")
      .every(
        (segment) => segment.length > 0 && segment !== "." && segment !== "..",
      )
  );
}

function validPackageName(value) {
  if (typeof value !== "string" || value.length < 1 || value.length > 214)
    return false;
  return value.startsWith("@")
    ? /^@[^\s/@]+\/[^\s/@]+$/u.test(value)
    : /^[^\s/@]+$/u.test(value);
}

function validateDescriptor(descriptor, platform, version, runtimeBytes) {
  if (
    !exactKeys(descriptor, [
      "schemaVersion",
      "product",
      "platform",
      "runtimeVersion",
      "nodeVersion",
      "byteSize",
      "sha256",
      "archive",
    ]) ||
    descriptor.schemaVersion !== 1 ||
    descriptor.product !== "agma-standalone-runtime-artifact" ||
    descriptor.platform !== platform ||
    descriptor.runtimeVersion !== version ||
    typeof descriptor.nodeVersion !== "string" ||
    !VERSION.test(descriptor.nodeVersion) ||
    descriptor.byteSize !== runtimeBytes.length ||
    descriptor.sha256 !== sha256Bytes(runtimeBytes) ||
    descriptor.archive !== RUNTIME_ENTRY
  ) {
    fail(`embedded Runtime descriptor is invalid for ${platform}`);
  }
}

function validateManifest(manifest, platform, version, nodeVersion) {
  if (
    !exactKeys(manifest, [
      "schemaVersion",
      "product",
      "runtimeVersion",
      "nodeVersion",
      "platform",
      "nodeExecutable",
      "entrypoint",
      "files",
    ]) ||
    manifest.schemaVersion !== 1 ||
    manifest.product !== "agma-standalone-runtime" ||
    manifest.runtimeVersion !== version ||
    manifest.nodeVersion !== nodeVersion ||
    manifest.platform !== platform ||
    manifest.nodeExecutable !==
      (platform === "linux-x86_64" ? "bin/node" : "bin/node.exe") ||
    manifest.entrypoint !== "app/dist/standalone/bootstrap/index.js" ||
    !Array.isArray(manifest.files) ||
    manifest.files.length < 1 ||
    manifest.files.length > 16384
  ) {
    fail(`embedded Runtime manifest is invalid for ${platform}`);
  }
  const files = new Map();
  for (const [index, entry] of manifest.files.entries()) {
    if (
      !exactKeys(entry, ["path", "size", "sha256", "executable"]) ||
      !safeManifestPath(entry.path) ||
      !Number.isSafeInteger(entry.size) ||
      entry.size < 0 ||
      typeof entry.sha256 !== "string" ||
      !SHA256.test(entry.sha256) ||
      typeof entry.executable !== "boolean" ||
      entry.executable !== (entry.path === manifest.nodeExecutable) ||
      files.has(entry.path) ||
      (index > 0 &&
        byteCompare(manifest.files[index - 1].path, entry.path) >= 0)
    ) {
      fail(`embedded Runtime manifest file ${String(index)} is invalid`);
    }
    files.set(entry.path, entry);
  }
  return files;
}

function validateInventory(inventory) {
  if (
    !exactKeys(inventory, ["schemaVersion", "packages"]) ||
    inventory.schemaVersion !== 1 ||
    !Array.isArray(inventory.packages) ||
    inventory.packages.length < 1 ||
    inventory.packages.length > 4096
  ) {
    fail("embedded production npm inventory is invalid");
  }
  const paths = new Set();
  for (const [index, entry] of inventory.packages.entries()) {
    if (
      !exactKeys(entry, ["path", "name", "version", "license"]) ||
      !safeManifestPath(entry.path) ||
      !validPackageName(entry.name) ||
      typeof entry.version !== "string" ||
      !PACKAGE_VERSION.test(entry.version) ||
      typeof entry.license !== "string" ||
      entry.license.length < 1 ||
      entry.license.length > 512 ||
      paths.has(entry.path) ||
      (index > 0 &&
        byteCompare(inventory.packages[index - 1].path, entry.path) >= 0)
    ) {
      fail(
        `embedded production npm inventory entry ${String(index)} is invalid`,
      );
    }
    paths.add(entry.path);
  }
}

function validateFabricMetadata(jar, entries, name, version, minecraft) {
  if (
    entries.includes(FORGE_DESCRIPTOR_ENTRY) ||
    entries.includes(FORGE_JARJAR_ENTRY)
  ) {
    fail(`Fabric JAR unexpectedly contains Forge metadata: ${name}`);
  }
  const fabric = JSON.parse(
    decoder.decode(archiveEntry(jar, entries, "fabric.mod.json", 1024 * 1024)),
  );
  if (
    fabric?.id !== "agma_standalone" ||
    fabric?.version !== version ||
    fabric?.depends?.minecraft !== minecraft
  ) {
    fail(`Fabric identity does not match final JAR name: ${name}`);
  }
}

function validateForgeMetadata(jar, entries, name, version, minecraft) {
  if (minecraft !== "1.18.2" || entries.includes("fabric.mod.json")) {
    fail(`Forge target identity does not match final JAR name: ${name}`);
  }
  const forge = parseForgeModsToml(
    archiveEntry(jar, entries, FORGE_DESCRIPTOR_ENTRY, 1024 * 1024),
    `${name}:${FORGE_DESCRIPTOR_ENTRY}`,
  );
  const mod = forge.mods[0];
  if (
    !exactKeys(forge.root, [
      "modLoader",
      "loaderVersion",
      "license",
      "clientSideOnly",
      "showAsResourcePack",
    ]) ||
    forge.root.modLoader !== "javafml" ||
    forge.root.loaderVersion !== "[40,)" ||
    forge.root.license !== "Apache-2.0" ||
    forge.root.clientSideOnly !== true ||
    forge.root.showAsResourcePack !== true ||
    forge.mods.length !== 1 ||
    !exactKeys(mod, [
      "modId",
      "version",
      "displayName",
      "displayTest",
      "authors",
      "description",
    ]) ||
    mod.modId !== "agma_standalone" ||
    mod.version !== version ||
    mod.displayName !== "AGMA Standalone Client" ||
    mod.displayTest !== "IGNORE_ALL_VERSION" ||
    mod.authors !== "AGMA contributors" ||
    typeof mod.description !== "string" ||
    mod.description.length < 1 ||
    forge.dependencies.length !== 2
  ) {
    fail(
      `Forge descriptor identity or client-only boundary is invalid: ${name}`,
    );
  }
  const dependencies = new Map();
  for (const dependency of forge.dependencies) {
    if (
      !exactKeys(dependency, [
        "modId",
        "mandatory",
        "versionRange",
        "ordering",
        "side",
      ]) ||
      typeof dependency.modId !== "string" ||
      dependencies.has(dependency.modId)
    ) {
      fail(`Forge dependency metadata is invalid: ${name}`);
    }
    dependencies.set(dependency.modId, dependency);
  }
  const forgeDependency = dependencies.get("forge");
  const minecraftDependency = dependencies.get("minecraft");
  if (
    forgeDependency?.mandatory !== true ||
    forgeDependency?.versionRange !== "[40.2.21,41)" ||
    forgeDependency?.ordering !== "NONE" ||
    forgeDependency?.side !== "CLIENT" ||
    minecraftDependency?.mandatory !== true ||
    minecraftDependency?.versionRange !== "[1.18.2,1.18.3)" ||
    minecraftDependency?.ordering !== "NONE" ||
    minecraftDependency?.side !== "CLIENT"
  ) {
    fail(`Forge dependency versions or sides are invalid: ${name}`);
  }

  const jarJar = parseForgeJarJarJson(
    archiveEntry(jar, entries, FORGE_JARJAR_ENTRY, 1024 * 1024),
    `${name}:${FORGE_JARJAR_ENTRY}`,
  );
  const expectedNested = new Map([
    ["core", `META-INF/jars/AGMA-Standalone-Client-Core-${version}.jar`],
    [
      "fabric-common",
      `META-INF/jars/AGMA-Standalone-Fabric-Common-${version}.jar`,
    ],
    [
      "runtime-supervisor-core",
      `META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${version}.jar`,
    ],
  ]);
  if (
    !exactKeys(jarJar, ["jars"]) ||
    !Array.isArray(jarJar.jars) ||
    jarJar.jars.length !== 3
  ) {
    fail(
      `Forge JarJar metadata does not contain exactly three libraries: ${name}`,
    );
  }
  const seen = new Set();
  for (const nested of jarJar.jars) {
    const artifact = nested?.identifier?.artifact;
    const expectedPath = expectedNested.get(artifact);
    if (
      !exactKeys(nested, ["identifier", "version", "path"]) ||
      !exactKeys(nested.identifier, ["group", "artifact"]) ||
      !exactKeys(nested.version, ["range", "artifactVersion"]) ||
      nested.identifier.group !== "dev.minecraftagent" ||
      expectedPath === undefined ||
      seen.has(artifact) ||
      nested.version.range !== `[${version},)` ||
      nested.version.artifactVersion !== version ||
      nested.path !== expectedPath ||
      entries.filter((entry) => entry === expectedPath).length !== 1
    ) {
      fail(`Forge JarJar library metadata is invalid: ${name}`);
    }
    seen.add(artifact);
  }
}

function inspectJar(release, version, target, platform) {
  const { minecraft, loader } = target;
  const name = `AGMA-Client-Standalone-${version}-mc${minecraft}-${loader}-${platform}.jar`;
  const jar = ordinaryFile(join(release, name), MAXIMUM_JAR_BYTES);
  const jarEntries = archiveEntries(jar.path);
  if (loader === "fabric")
    validateFabricMetadata(jar.path, jarEntries, name, version, minecraft);
  else validateForgeMetadata(jar.path, jarEntries, name, version, minecraft);
  const descriptorBytes = archiveEntry(
    jar.path,
    jarEntries,
    DESCRIPTOR_ENTRY,
    4096,
  );
  const descriptor = parseCanonicalJson(
    descriptorBytes,
    `${name}:${DESCRIPTOR_ENTRY}`,
  );
  const runtimeBytes = archiveEntry(
    jar.path,
    jarEntries,
    RUNTIME_ENTRY,
    MAXIMUM_RUNTIME_BYTES,
  );
  validateDescriptor(descriptor, platform, version, runtimeBytes);

  const temporary = mkdtempSync(join(tmpdir(), "agma-standalone-sbom."));
  const runtimePath = join(temporary, "runtime.zip");
  try {
    writeFileSync(runtimePath, runtimeBytes, { flag: "wx", mode: 0o600 });
    const runtimeEntries = archiveEntries(runtimePath);
    const manifestBytes = archiveEntry(
      runtimePath,
      runtimeEntries,
      MANIFEST_ENTRY,
      MAXIMUM_MANIFEST_BYTES,
    );
    const inventoryBytes = archiveEntry(
      runtimePath,
      runtimeEntries,
      INVENTORY_ENTRY,
      MAXIMUM_INVENTORY_BYTES,
    );
    const manifest = parseCanonicalJson(
      manifestBytes,
      `${name}:${MANIFEST_ENTRY}`,
    );
    const inventory = parseCanonicalJson(
      inventoryBytes,
      `${name}:${INVENTORY_ENTRY}`,
    );
    const files = validateManifest(
      manifest,
      platform,
      version,
      descriptor.nodeVersion,
    );
    validateInventory(inventory);
    const inventoryFile = files.get(INVENTORY_ENTRY);
    const nodeFile = files.get(manifest.nodeExecutable);
    if (
      inventoryFile === undefined ||
      inventoryFile.size !== inventoryBytes.length ||
      inventoryFile.sha256 !== sha256Bytes(inventoryBytes) ||
      nodeFile === undefined ||
      nodeFile.size < 1
    ) {
      fail(
        `embedded Runtime inventory or Node does not match its manifest: ${name}`,
      );
    }
    return {
      name,
      minecraft,
      loader,
      platform,
      jarSha256: sha256File(jar.path),
      runtimeSha256: descriptor.sha256,
      runtimeVersion: descriptor.runtimeVersion,
      nodeVersion: descriptor.nodeVersion,
      nodeSha256: nodeFile.sha256,
      nodeExecutable: manifest.nodeExecutable,
      entrypoint: manifest.entrypoint,
      runtimeFileCount: manifest.files.length,
      manifestSha256: sha256Bytes(manifestBytes),
      inventorySha256: sha256Bytes(inventoryBytes),
      manifestCanonical: canonicalJson(manifest),
      inventoryCanonical: canonicalJson(inventory),
      packages: inventory.packages,
    };
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}

function npmPurl(name, version) {
  const encodedName = name.startsWith("@")
    ? `%40${encodeURIComponent(name.slice(1).split("/")[0])}/${encodeURIComponent(name.split("/")[1])}`
    : encodeURIComponent(name);
  return `pkg:npm/${encodedName}@${encodeURIComponent(version)}`;
}

function properties(values) {
  return Object.entries(values)
    .sort(([left], [right]) => byteCompare(left, right))
    .map(([name, value]) => ({
      name: `dev.minecraftagent.agma:${name}`,
      value: String(value),
    }));
}

function buildBom(releaseValue, version) {
  if (!VERSION.test(version))
    fail(`invalid final standalone version: ${version}`);
  const release = resolve(releaseValue);
  const metadata = lstatSync(release);
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
    fail("release directory is missing or unsafe");
  }
  const records = [];
  for (const target of TARGETS) {
    for (const platform of PLATFORMS)
      records.push(inspectJar(release, version, target, platform));
  }

  const runtimes = new Map();
  for (const record of records) {
    const existing = runtimes.get(record.platform);
    if (existing === undefined) {
      runtimes.set(record.platform, record);
      continue;
    }
    if (
      existing.runtimeSha256 !== record.runtimeSha256 ||
      existing.manifestCanonical !== record.manifestCanonical ||
      existing.inventoryCanonical !== record.inventoryCanonical
    ) {
      fail(
        `Release JARs do not embed one identical ${record.platform} Runtime inventory`,
      );
    }
  }

  const rootReference = `urn:agma:standalone:${version}`;
  const components = [];
  const dependencyMap = new Map([[rootReference, new Set()]]);
  for (const record of records) {
    const reference = `urn:agma:standalone:jar:${record.minecraft}:${record.loader}:${record.platform}:${record.jarSha256}`;
    const runtimeReference = `urn:agma:standalone:runtime:${record.platform}:${record.runtimeSha256}`;
    dependencyMap.get(rootReference).add(reference);
    dependencyMap.set(reference, new Set([runtimeReference]));
    components.push({
      type: "application",
      "bom-ref": reference,
      group: "dev.minecraftagent",
      name: `AGMA Client Standalone mc${record.minecraft} ${record.loader} ${record.platform}`,
      version,
      hashes: [{ alg: "SHA-256", content: record.jarSha256 }],
      purl: `pkg:generic/AGMA-Client-Standalone@${version}?loader=${encodeURIComponent(record.loader)}&minecraft=${encodeURIComponent(record.minecraft)}&platform=${encodeURIComponent(record.platform)}`,
      properties: properties({
        loader: record.loader,
        minecraftVersion: record.minecraft,
        platform: record.platform,
        releaseAsset: record.name,
        runtimeSha256: record.runtimeSha256,
      }),
    });
  }

  const npmComponents = new Map();
  for (const [platform, runtime] of [...runtimes.entries()].sort(
    ([left], [right]) => byteCompare(left, right),
  )) {
    const runtimeReference = `urn:agma:standalone:runtime:${platform}:${runtime.runtimeSha256}`;
    const nodeReference = `urn:agma:standalone:node:${platform}:${runtime.nodeSha256}`;
    const runtimeDependencies = new Set([nodeReference]);
    components.push({
      type: "application",
      "bom-ref": runtimeReference,
      group: "dev.minecraftagent",
      name: `AGMA Standalone Runtime (${platform})`,
      version: runtime.runtimeVersion,
      hashes: [{ alg: "SHA-256", content: runtime.runtimeSha256 }],
      purl: `pkg:generic/agma-standalone-runtime@${runtime.runtimeVersion}?platform=${encodeURIComponent(platform)}`,
      properties: properties({
        entrypoint: runtime.entrypoint,
        inventorySha256: runtime.inventorySha256,
        manifestSha256: runtime.manifestSha256,
        nodeExecutable: runtime.nodeExecutable,
        platform,
        runtimeFileCount: runtime.runtimeFileCount,
      }),
    });
    components.push({
      type: "application",
      "bom-ref": nodeReference,
      name: `Node.js (${platform})`,
      version: runtime.nodeVersion,
      hashes: [{ alg: "SHA-256", content: runtime.nodeSha256 }],
      purl: `pkg:generic/node@${runtime.nodeVersion}?platform=${encodeURIComponent(platform)}`,
      properties: properties({ platform, source: "embedded-runtime-manifest" }),
    });
    dependencyMap.set(nodeReference, new Set());
    for (const entry of runtime.packages) {
      const reference = npmPurl(entry.name, entry.version);
      runtimeDependencies.add(reference);
      const existing = npmComponents.get(reference);
      if (existing !== undefined && existing.license !== entry.license) {
        fail(
          `npm inventory license differs for ${entry.name}@${entry.version}`,
        );
      }
      npmComponents.set(reference, entry);
    }
    dependencyMap.set(runtimeReference, runtimeDependencies);
  }

  for (const [reference, entry] of [...npmComponents.entries()].sort(
    ([left], [right]) => byteCompare(left, right),
  )) {
    components.push({
      type: "library",
      "bom-ref": reference,
      name: entry.name,
      version: entry.version,
      purl: reference,
      licenses: [{ license: { name: entry.license } }],
      properties: properties({ source: "embedded-production-npm-inventory" }),
    });
    dependencyMap.set(reference, new Set());
  }

  components.sort((left, right) =>
    byteCompare(left["bom-ref"], right["bom-ref"]),
  );
  const dependencies = [...dependencyMap.entries()]
    .sort(([left], [right]) => byteCompare(left, right))
    .map(([reference, dependsOn]) => ({
      ref: reference,
      dependsOn: [...dependsOn].sort(byteCompare),
    }));
  return {
    $schema: "https://cyclonedx.org/schema/bom-1.7.schema.json",
    bomFormat: "CycloneDX",
    specVersion: "1.7",
    version: 1,
    metadata: {
      component: {
        type: "application",
        "bom-ref": rootReference,
        group: "dev.minecraftagent",
        name: "AGMA Client Standalone",
        version,
      },
      properties: properties({
        releaseArtifactCount: records.length,
        runtimeInventorySource: "final-jar-embedded-manifest",
      }),
    },
    components,
    dependencies,
  };
}

function expectedName(version) {
  return `AGMA-Client-Standalone-${version}-SBOM.cdx.json`;
}

function writeBom(release, version, outputValue) {
  const output = resolve(outputValue);
  if (basename(output) !== expectedName(version))
    fail("SBOM output name is not canonical");
  const parent = lstatSync(dirname(output));
  if (!parent.isDirectory() || parent.isSymbolicLink())
    fail("SBOM output directory is unsafe");
  writeFileSync(output, canonicalJson(buildBom(release, version)), {
    flag: "wx",
    mode: 0o600,
  });
}

function verifyBom(release, version, inputValue) {
  const input = ordinaryFile(inputValue, 32 * 1024 * 1024);
  if (basename(input.path) !== expectedName(version))
    fail("SBOM input name is not canonical");
  const expected = Buffer.from(
    canonicalJson(buildBom(release, version)),
    "utf8",
  );
  if (!readFileSync(input.path).equals(expected)) {
    fail("CycloneDX SBOM differs from the six final JAR inventories");
  }
}

try {
  const [command, ...arguments_] = process.argv.slice(2);
  if (command === "write" && arguments_.length === 3) writeBom(...arguments_);
  else if (command === "verify" && arguments_.length === 3)
    verifyBom(...arguments_);
  else
    fail(
      "usage: standalone-sbom.mjs <write|verify> <release-directory> <version> <sbom-path>",
    );
} catch (error) {
  console.error(
    error instanceof Error
      ? error.message
      : "standalone-sbom: unexpected failure",
  );
  process.exitCode = 1;
}
