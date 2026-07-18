import { createHash } from "node:crypto";
import { lstatSync, readFileSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";

const PRODUCT = "agma-standalone-runtime-artifact";
const ARCHIVE_RESOURCE = "META-INF/agma-standalone/runtime.zip";
const DESCRIPTOR_RESOURCE = "META-INF/agma-standalone/runtime-artifact.json";
const FABRIC_DESCRIPTOR = "fabric.mod.json";
const FORGE_DESCRIPTOR = "META-INF/mods.toml";
const FORGE_JARJAR_METADATA = "META-INF/jarjar/metadata.json";
const VERSION =
  /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$/u;
const SHA256 = /^[0-9a-f]{64}$/u;
const PLATFORMS = new Set(["linux-x86_64", "windows-x86_64"]);
const LOADERS = new Set(["fabric", "forge"]);
const MAXIMUM_ARCHIVE_BYTES = 512 * 1024 * 1024;
const WINDOWS_RESERVED = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu;

function fail(message) {
  throw new Error(message);
}

function exactKeys(value, expected) {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return (
    actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index])
  );
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function ordinaryFile(value, maximum = Number.MAX_SAFE_INTEGER) {
  const path = resolve(value);
  const metadata = lstatSync(path);
  if (
    !metadata.isFile() ||
    metadata.isSymbolicLink() ||
    metadata.size < 1 ||
    metadata.size > maximum
  ) {
    fail(`expected an ordinary bounded file: ${value}`);
  }
  return { path, metadata };
}

function metadata(archiveValue, platform, runtimeVersion, nodeVersion) {
  const archive = ordinaryFile(archiveValue, MAXIMUM_ARCHIVE_BYTES);
  if (!PLATFORMS.has(platform))
    fail(`unsupported standalone platform: ${platform}`);
  if (!VERSION.test(runtimeVersion) || !VERSION.test(nodeVersion)) {
    fail("Runtime and Node versions must be bounded semantic versions");
  }
  return {
    schemaVersion: 1,
    product: PRODUCT,
    platform,
    runtimeVersion,
    nodeVersion,
    byteSize: archive.metadata.size,
    sha256: sha256(archive.path),
    archive: ARCHIVE_RESOURCE,
  };
}

function canonical(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function writeDescriptor(
  archive,
  platform,
  runtimeVersion,
  nodeVersion,
  outputValue,
) {
  const output = resolve(outputValue);
  writeFileSync(
    output,
    canonical(metadata(archive, platform, runtimeVersion, nodeVersion)),
    {
      flag: "wx",
      mode: 0o600,
    },
  );
}

function verifyDescriptor(
  descriptorValue,
  archive,
  platform,
  runtimeVersion,
  nodeVersion,
) {
  const descriptor = ordinaryFile(descriptorValue, 4096);
  const bytes = readFileSync(descriptor.path);
  const parsed = JSON.parse(bytes.toString("utf8"));
  const wanted = metadata(archive, platform, runtimeVersion, nodeVersion);
  if (
    !exactKeys(parsed, [
      "schemaVersion",
      "product",
      "platform",
      "runtimeVersion",
      "nodeVersion",
      "byteSize",
      "sha256",
      "archive",
    ]) ||
    !bytes.equals(Buffer.from(canonical(parsed), "utf8")) ||
    !Number.isSafeInteger(parsed.byteSize) ||
    typeof parsed.sha256 !== "string" ||
    !SHA256.test(parsed.sha256) ||
    canonical(parsed) !== canonical(wanted)
  ) {
    fail("embedded Runtime descriptor is invalid or differs from the sidecar");
  }
}

function safeArchivePath(value) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    Buffer.byteLength(value, "utf8") > 512 ||
    value.startsWith("/") ||
    value.includes("\\") ||
    !/^[\x20-\x7e]+$/u.test(value) ||
    /[\u0000-\u001f\u007f<>:"|?*]/u.test(value)
  ) {
    return false;
  }
  const path = value.endsWith("/") ? value.slice(0, -1) : value;
  if (path.length === 0) return false;
  return path
    .split("/")
    .every(
      (segment) =>
        segment.length > 0 &&
        segment !== "." &&
        segment !== ".." &&
        !segment.endsWith(".") &&
        !segment.endsWith(" ") &&
        !WINDOWS_RESERVED.test(segment),
    );
}

function forbiddenJarPath(value) {
  const folded = value.toLowerCase();
  const file = basename(folded);
  return (
    file === "01-standalone-client-development-plan.md" ||
    file === "paper-plugin.yml" ||
    file === "plugin.yml" ||
    file === ".env" ||
    folded.startsWith("paper-plugin/") ||
    folded.startsWith("client-mod/") ||
    folded.startsWith("capability-packs/") ||
    folded.startsWith("protocol/") ||
    folded.startsWith("managed-runtime/") ||
    folded.startsWith("dev/minecraftagent/paper/") ||
    folded.startsWith("dev/minecraftagent/client/") ||
    folded.includes("agma-server-") ||
    /(?:^|\/)(?:start-runtime|run-runtime)(?:\.(?:sh|ps1|bat|cmd))?$/u.test(
      folded,
    ) ||
    /(?:^|\/)(?:config(?:\.example)?|runtime\.env)\.(?:yml|yaml)$/u.test(folded)
  );
}

function rejectForgeLoaderPaths(entries) {
  for (const entry of entries) {
    if (
      entry === FABRIC_DESCRIPTOR ||
      entry.startsWith("net/fabricmc/") ||
      entry.includes("/fabric/") ||
      entry.startsWith("dev/emi/") ||
      entry.includes("/emi/")
    ) {
      fail(`Fabric or EMI payload entered Forge JAR: ${entry}`);
    }
  }
}

function verifyEntryList(pathValue, kind, loader) {
  if (kind !== "base" && kind !== "integrated" && kind !== "nested") {
    fail(`unsupported JAR entry-list kind: ${kind}`);
  }
  if (!LOADERS.has(loader)) fail(`unsupported standalone loader: ${loader}`);
  const entries = readFileSync(resolve(pathValue), "utf8")
    .split("\n")
    .filter(Boolean);
  if (entries.length === 0 || entries.length > 65536)
    fail("JAR entry count is outside its limit");
  const folded = new Set();
  for (const raw of entries) {
    if (!safeArchivePath(raw)) fail(`unsafe JAR entry: ${raw}`);
    const path = raw.endsWith("/") ? raw.slice(0, -1) : raw;
    const key = path.toLowerCase();
    if (folded.has(key)) fail(`duplicate or case-colliding JAR entry: ${raw}`);
    if (forbiddenJarPath(path))
      fail(`server, launch-script, or development payload entered JAR: ${raw}`);
    folded.add(key);
  }
  const hasDescriptor = folded.has(DESCRIPTOR_RESOURCE.toLowerCase());
  const hasArchive = folded.has(ARCHIVE_RESOURCE.toLowerCase());
  if (kind === "integrated" && (!hasDescriptor || !hasArchive)) {
    fail(
      "integrated standalone JAR is missing its Runtime descriptor or archive",
    );
  }
  if (kind !== "integrated" && (hasDescriptor || hasArchive)) {
    fail("base or nested JAR unexpectedly contains an embedded Runtime");
  }
  if (kind === "nested") {
    if (loader === "forge") rejectForgeLoaderPaths(folded);
    return;
  }

  const hasFabricDescriptor = folded.has(FABRIC_DESCRIPTOR.toLowerCase());
  const hasForgeDescriptor = folded.has(FORGE_DESCRIPTOR.toLowerCase());
  const hasForgeJarJar = folded.has(FORGE_JARJAR_METADATA.toLowerCase());
  if (
    loader === "fabric" &&
    (!hasFabricDescriptor || hasForgeDescriptor || hasForgeJarJar)
  ) {
    fail("Fabric JAR metadata is missing or mixed with Forge metadata");
  }
  if (
    loader === "forge" &&
    (hasFabricDescriptor || !hasForgeDescriptor || !hasForgeJarJar)
  ) {
    fail("Forge JAR metadata is missing or mixed with Fabric metadata");
  }
  const requiredEntrypoint =
    loader === "fabric"
      ? "dev/minecraftagent/standalone/fabric/standalonecliententrypoint.class"
      : "dev/minecraftagent/standalone/forge/standaloneforgemod.class";
  if (!folded.has(requiredEntrypoint))
    fail(`${loader} client entrypoint class is missing`);

  const nested = [...folded].filter(
    (entry) => entry.startsWith("meta-inf/jars/") && entry.endsWith(".jar"),
  );
  const reviewed = [
    /^meta-inf\/jars\/agma-standalone-client-core-(.+)\.jar$/u,
    /^meta-inf\/jars\/agma-standalone-fabric-common-(.+)\.jar$/u,
    /^meta-inf\/jars\/agma-standalone-runtime-supervisor-core-(.+)\.jar$/u,
  ];
  if (
    nested.length !== reviewed.length ||
    reviewed.some(
      (pattern) => nested.filter((entry) => pattern.test(entry)).length !== 1,
    )
  ) {
    fail("nested JAR inventory is not the reviewed standalone set");
  }

  if (loader === "forge") rejectForgeLoaderPaths(folded);
}

function requireArray(value, field) {
  if (!Array.isArray(value) || value.length === 0)
    fail(`Fabric descriptor ${field} is invalid`);
  return value;
}

function verifyFabric(pathValue, clientVersion, minecraftVersion) {
  if (!VERSION.test(clientVersion)) fail("client version is invalid");
  if (minecraftVersion !== "1.21.11" && minecraftVersion !== "1.18.2") {
    fail("unsupported Minecraft version");
  }
  const root = JSON.parse(readFileSync(resolve(pathValue), "utf8"));
  if (
    typeof root !== "object" ||
    root === null ||
    Array.isArray(root) ||
    root.schemaVersion !== 1 ||
    root.id !== "agma_standalone" ||
    root.version !== clientVersion ||
    root.name !== "AGMA Standalone Client" ||
    root.environment !== "client" ||
    typeof root.entrypoints !== "object" ||
    root.entrypoints === null ||
    Array.isArray(root.entrypoints) ||
    !exactKeys(
      root.entrypoints,
      minecraftVersion === "1.18.2" ? ["client", "emi"] : ["client"],
    ) ||
    !exactKeys(root.depends, [
      "fabricloader",
      "fabric-api",
      "minecraft",
      "java",
    ]) ||
    root.depends.minecraft !== minecraftVersion ||
    root.depends.java !== (minecraftVersion === "1.18.2" ? ">=17" : ">=21")
  ) {
    fail(
      "Fabric descriptor identity, environment, or compatibility is invalid",
    );
  }
  const clientEntrypoints = requireArray(
    root.entrypoints.client,
    "client entrypoint",
  );
  if (
    clientEntrypoints.length !== 1 ||
    clientEntrypoints[0] !==
      "dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint"
  ) {
    fail("Fabric client entrypoint is invalid");
  }
  if (minecraftVersion === "1.18.2") {
    const emiEntrypoints = requireArray(root.entrypoints.emi, "EMI entrypoint");
    if (
      emiEntrypoints.length !== 1 ||
      emiEntrypoints[0] !==
        "dev.minecraftagent.standalone.fabric.viewer.emi.EmiCatalogPlugin"
    ) {
      fail("Fabric EMI entrypoint is invalid");
    }
  }
  const nested = requireArray(root.jars, "nested JARs");
  const paths = nested.map((entry) => entry?.file);
  if (
    paths.length !== 3 ||
    paths.some(
      (path) =>
        typeof path !== "string" ||
        !path.startsWith("META-INF/jars/AGMA-Standalone-") ||
        !path.endsWith(".jar"),
    ) ||
    !paths.some((path) => path.includes("Client-Core")) ||
    !paths.some((path) => path.includes("Fabric-Common")) ||
    !paths.some((path) => path.includes("Runtime-Supervisor-Core"))
  ) {
    fail("Fabric nested JAR inventory is not the reviewed standalone set");
  }
}

function forgeModsToml(clientVersion) {
  return `modLoader = "javafml"
loaderVersion = "[40,)"
license = "Apache-2.0"
clientSideOnly = true
showAsResourcePack = true

[[mods]]
modId = "agma_standalone"
version = "${clientVersion}"
displayName = "AGMA Standalone Client"
authors = "AGMA contributors"
description = '''
Pure client AGMA shell with an authenticated local Runtime boundary.
'''

[[dependencies.agma_standalone]]
modId = "forge"
mandatory = true
versionRange = "[40.3.12,41)"
ordering = "NONE"
side = "CLIENT"

[[dependencies.agma_standalone]]
modId = "minecraft"
mandatory = true
versionRange = "[1.18.2,1.18.3)"
ordering = "NONE"
side = "CLIENT"
`;
}

function verifyForge(modsValue, jarJarValue, clientVersion, minecraftVersion) {
  if (!VERSION.test(clientVersion)) fail("client version is invalid");
  if (minecraftVersion !== "1.18.2")
    fail("Forge target must be Minecraft 1.18.2");

  const mods = ordinaryFile(modsValue, 16384);
  const wantedMods = Buffer.from(forgeModsToml(clientVersion), "utf8");
  if (!readFileSync(mods.path).equals(wantedMods)) {
    fail("Forge mods.toml is not the reviewed client-only descriptor");
  }

  const jarJar = ordinaryFile(jarJarValue, 16384);
  const root = JSON.parse(readFileSync(jarJar.path, "utf8"));
  if (
    !exactKeys(root, ["jars"]) ||
    !Array.isArray(root.jars) ||
    root.jars.length !== 3
  ) {
    fail("Forge JarJar metadata root or inventory is invalid");
  }
  const expected = new Map([
    ["core", `META-INF/jars/AGMA-Standalone-Client-Core-${clientVersion}.jar`],
    [
      "fabric-common",
      `META-INF/jars/AGMA-Standalone-Fabric-Common-${clientVersion}.jar`,
    ],
    [
      "runtime-supervisor-core",
      `META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${clientVersion}.jar`,
    ],
  ]);
  const seen = new Set();
  for (const jar of root.jars) {
    if (
      !exactKeys(jar, ["identifier", "version", "path"]) ||
      !exactKeys(jar.identifier, ["group", "artifact"]) ||
      !exactKeys(jar.version, ["range", "artifactVersion"]) ||
      jar.identifier.group !== "dev.minecraftagent" ||
      typeof jar.identifier.artifact !== "string" ||
      !expected.has(jar.identifier.artifact) ||
      seen.has(jar.identifier.artifact) ||
      jar.version.range !== `[${clientVersion},)` ||
      jar.version.artifactVersion !== clientVersion ||
      jar.path !== expected.get(jar.identifier.artifact)
    ) {
      fail("Forge JarJar metadata is not the reviewed standalone library set");
    }
    seen.add(jar.identifier.artifact);
  }
  if (seen.size !== expected.size) fail("Forge JarJar metadata is incomplete");
}

const [command, ...arguments_] = process.argv.slice(2);
if (command === "write-descriptor" && arguments_.length === 5) {
  writeDescriptor(...arguments_);
} else if (command === "verify-descriptor" && arguments_.length === 5) {
  verifyDescriptor(...arguments_);
} else if (command === "verify-entry-list" && arguments_.length === 3) {
  verifyEntryList(...arguments_);
} else if (command === "verify-fabric" && arguments_.length === 3) {
  verifyFabric(...arguments_);
} else if (command === "verify-forge" && arguments_.length === 4) {
  verifyForge(...arguments_);
} else {
  fail(
    "usage: standalone-client-artifact.mjs " +
      "<write-descriptor|verify-descriptor|verify-entry-list|verify-fabric|verify-forge> ...",
  );
}
