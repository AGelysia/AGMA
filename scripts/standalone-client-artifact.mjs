import { createHash } from "node:crypto";
import { lstatSync, readFileSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";

const PRODUCT = "agma-standalone-runtime-artifact";
const ARCHIVE_RESOURCE = "META-INF/agma-standalone/runtime.zip";
const DESCRIPTOR_RESOURCE = "META-INF/agma-standalone/runtime-artifact.json";
const VERSION = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$/u;
const SHA256 = /^[0-9a-f]{64}$/u;
const PLATFORMS = new Set(["linux-x86_64", "windows-x86_64"]);
const MAXIMUM_ARCHIVE_BYTES = 512 * 1024 * 1024;
const WINDOWS_RESERVED = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu;

function fail(message) {
  throw new Error(message);
}

function exactKeys(value, expected) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function ordinaryFile(value, maximum = Number.MAX_SAFE_INTEGER) {
  const path = resolve(value);
  const metadata = lstatSync(path);
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size < 1 || metadata.size > maximum) {
    fail(`expected an ordinary bounded file: ${value}`);
  }
  return { path, metadata };
}

function metadata(archiveValue, platform, runtimeVersion, nodeVersion) {
  const archive = ordinaryFile(archiveValue, MAXIMUM_ARCHIVE_BYTES);
  if (!PLATFORMS.has(platform)) fail(`unsupported standalone platform: ${platform}`);
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

function writeDescriptor(archive, platform, runtimeVersion, nodeVersion, outputValue) {
  const output = resolve(outputValue);
  writeFileSync(output, canonical(metadata(archive, platform, runtimeVersion, nodeVersion)), {
    flag: "wx",
    mode: 0o600,
  });
}

function verifyDescriptor(descriptorValue, archive, platform, runtimeVersion, nodeVersion) {
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
  return path.split("/").every(
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
    /(?:^|\/)(?:start-runtime|run-runtime)(?:\.(?:sh|ps1|bat|cmd))?$/u.test(folded) ||
    /(?:^|\/)(?:config(?:\.example)?|runtime\.env)\.(?:yml|yaml)$/u.test(folded)
  );
}

function verifyEntryList(pathValue, kind) {
  if (kind !== "base" && kind !== "integrated" && kind !== "nested") {
    fail(`unsupported JAR entry-list kind: ${kind}`);
  }
  const entries = readFileSync(resolve(pathValue), "utf8").split("\n").filter(Boolean);
  if (entries.length === 0 || entries.length > 65536) fail("JAR entry count is outside its limit");
  const folded = new Set();
  for (const raw of entries) {
    if (!safeArchivePath(raw)) fail(`unsafe JAR entry: ${raw}`);
    const path = raw.endsWith("/") ? raw.slice(0, -1) : raw;
    const key = path.toLowerCase();
    if (folded.has(key)) fail(`duplicate or case-colliding JAR entry: ${raw}`);
    if (forbiddenJarPath(path)) fail(`server, launch-script, or development payload entered JAR: ${raw}`);
    folded.add(key);
  }
  const hasDescriptor = folded.has(DESCRIPTOR_RESOURCE.toLowerCase());
  const hasArchive = folded.has(ARCHIVE_RESOURCE.toLowerCase());
  if (kind === "integrated" && (!hasDescriptor || !hasArchive)) {
    fail("integrated standalone JAR is missing its Runtime descriptor or archive");
  }
  if (kind !== "integrated" && (hasDescriptor || hasArchive)) {
    fail("base or nested JAR unexpectedly contains an embedded Runtime");
  }
  if (kind !== "nested" && !folded.has("fabric.mod.json")) fail("Fabric descriptor is missing");
}

function requireArray(value, field) {
  if (!Array.isArray(value) || value.length === 0) fail(`Fabric descriptor ${field} is invalid`);
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
    !exactKeys(root.entrypoints, minecraftVersion === "1.18.2" ? ["client", "emi"] : ["client"]) ||
    !exactKeys(root.depends, ["fabricloader", "fabric-api", "minecraft", "java"]) ||
    root.depends.minecraft !== minecraftVersion ||
    root.depends.java !== (minecraftVersion === "1.18.2" ? ">=17" : ">=21")
  ) {
    fail("Fabric descriptor identity, environment, or compatibility is invalid");
  }
  const clientEntrypoints = requireArray(root.entrypoints.client, "client entrypoint");
  if (
    clientEntrypoints.length !== 1 ||
    clientEntrypoints[0] !== "dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint"
  ) {
    fail("Fabric client entrypoint is invalid");
  }
  if (minecraftVersion === "1.18.2") {
    const emiEntrypoints = requireArray(root.entrypoints.emi, "EMI entrypoint");
    if (
      emiEntrypoints.length !== 1 ||
      emiEntrypoints[0] !== "dev.minecraftagent.standalone.fabric.viewer.emi.EmiCatalogPlugin"
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

const [command, ...arguments_] = process.argv.slice(2);
if (command === "write-descriptor" && arguments_.length === 5) {
  writeDescriptor(...arguments_);
} else if (command === "verify-descriptor" && arguments_.length === 5) {
  verifyDescriptor(...arguments_);
} else if (command === "verify-entry-list" && arguments_.length === 2) {
  verifyEntryList(...arguments_);
} else if (command === "verify-fabric" && arguments_.length === 3) {
  verifyFabric(...arguments_);
} else {
  fail(
    "usage: standalone-client-artifact.mjs " +
      "<write-descriptor|verify-descriptor|verify-entry-list|verify-fabric> ...",
  );
}
