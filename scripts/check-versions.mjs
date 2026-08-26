#!/usr/bin/env node
// Asserts that version numbers pinned across the repository stay aligned.
// Single implementation shared by scripts/check-versions.sh (bash) and
// scripts/test.ps1 (PowerShell) so Linux and Windows CI enforce the same rules.
//
// Version axes and their single sources of truth:
//   server product version  -> agent-runtime/package.json (.version)
//   standalone client       -> standalone-client/version.json (.version)
//   Node runtime            -> standalone-client/managed-runtime/node-distributions.json
//                              (root managed-runtime/node-distributions.json mirrors it)
//   Paper / Minecraft pin   -> gradle/libs.versions.toml (minecraft) + PAPER_BUILD in the smoke scripts
//
// Deliberately excluded: standalone-client/managed-runtime/fixtures/payload/app/package.json
// is a verifier fixture with its own frozen version and must NOT track the product version.
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

const fail = (message) => failures.push(message);
const requireEqual = (what, expected, actual) => {
  if (expected !== actual) {
    fail(`${what}: expected '${expected}', found '${actual}'`);
  }
};
const read = (path) => readFileSync(join(root, path), "utf8");
const requireContains = (file, needle) => {
  if (!read(file).includes(needle)) {
    fail(`${file} no longer contains the pinned value '${needle}'`);
  }
};
const isSemver = (value) => /^\d+\.\d+\.\d+$/.test(value);

// ---------------------------------------------------------------------------
// Server product version (root Gradle build mirrors agent-runtime/package.json)
// ---------------------------------------------------------------------------
const serverVersion = JSON.parse(read("agent-runtime/package.json")).version;
if (!isSemver(serverVersion)) {
  fail(`agent-runtime/package.json version is not semver: '${serverVersion}'`);
}
if (/version\s*=\s*"\d+\.\d+\.\d+"/.test(read("build.gradle.kts"))) {
  fail("root build.gradle.kts hardcodes a version literal; it must read agent-runtime/package.json");
}
requireContains("build.gradle.kts", "agent-runtime/package.json");

// ---------------------------------------------------------------------------
// Standalone client version (module builds read standalone-client/version.json)
// ---------------------------------------------------------------------------
const standaloneVersion = JSON.parse(read("standalone-client/version.json")).version;
if (!isSemver(standaloneVersion)) {
  fail(`standalone-client/version.json version is not semver: '${standaloneVersion}'`);
}
for (const module of [
  "core",
  "runtime-supervisor-core",
  "fabric-common",
  "fabric-mc12111",
  "fabric-mc1182",
  "forge-mc1182",
  "ui-common",
]) {
  const file = `standalone-client/${module}/build.gradle.kts`;
  if (/orElse\("\d+\.\d+\.\d+"\)/.test(read(file))) {
    fail(`${file} hardcodes a standalone version default; it must read standalone-client/version.json`);
  }
  requireContains(file, "version.json");
}

// ---------------------------------------------------------------------------
// Node runtime pin (two manifests + the scripts and workflows that repeat it)
// ---------------------------------------------------------------------------
const serverManifest = JSON.parse(read("managed-runtime/node-distributions.json"));
const standaloneManifest = JSON.parse(read("standalone-client/managed-runtime/node-distributions.json"));

const serverLinux = serverManifest.distributions.filter((entry) => entry.platform === "linux-x86_64");
if (serverManifest.distributions.length !== 1 || serverLinux.length !== 1) {
  fail("server Node manifest must contain exactly one linux-x86_64 distribution");
}
const standaloneLinux = standaloneManifest.distributions.filter((entry) => entry.platform === "linux-x86_64");
const standaloneWindows = standaloneManifest.distributions.filter((entry) => entry.platform === "windows-x86_64");
if (standaloneLinux.length !== 1 || standaloneWindows.length !== 1) {
  fail("standalone Node manifest must contain exactly one linux and one windows distribution");
}

const nodePin = serverLinux[0]?.nodeVersion;
requireEqual("standalone manifest top-level nodeVersion", nodePin, standaloneManifest.nodeVersion);
for (const field of ["archive", "url", "sha256"]) {
  requireEqual(`manifests disagree on linux ${field}`, serverLinux[0][field], standaloneLinux[0][field]);
}

// The standalone build script pins the manifest bytes themselves.
const manifestSha = createHash("sha256")
  .update(readFileSync(join(root, "standalone-client/managed-runtime/node-distributions.json")))
  .digest("hex");
if (!read("scripts/build-standalone-managed-runtime.sh").includes(manifestSha)) {
  fail("scripts/build-standalone-managed-runtime.sh DISTRIBUTIONS_SHA256 does not match the manifest bytes");
}

// The standalone build script repeats the full distribution table inline.
for (const value of [
  nodePin,
  standaloneLinux[0].archive,
  standaloneLinux[0].url,
  standaloneLinux[0].sha256,
  standaloneWindows[0].archive,
  standaloneWindows[0].url,
  standaloneWindows[0].sha256,
]) {
  requireContains("scripts/build-standalone-managed-runtime.sh", value);
}

// The server build script pins the linux distribution inline.
for (const value of [nodePin, serverLinux[0].archive, serverLinux[0].url, serverLinux[0].sha256]) {
  requireContains("scripts/build-managed-runtime.sh", value);
}
requireContains("scripts/verify-managed-runtime.sh", nodePin);

// Release test scripts repeat the pins; they act as drift detectors.
const releaseTestConstants = read("scripts/test-standalone-client-release.sh");
for (const [name, expected] of [
  ["CLIENT_VERSION", standaloneVersion],
  ["RUNTIME_VERSION", standaloneVersion],
  ["NODE_VERSION", nodePin],
]) {
  const match = releaseTestConstants.match(new RegExp(`^${name}=(.*)$`, "m"));
  requireEqual(`scripts/test-standalone-client-release.sh ${name}`, expected, match?.[1]);
}
requireContains(".github/workflows/standalone-release.yml", `-NodeVersion "${nodePin}"`);

// CI must test on the same Node line the products embed.
for (const workflow of ["verify.yml", "release.yml", "standalone-release.yml"]) {
  for (const match of read(`.github/workflows/${workflow}`).matchAll(/node-version: "([^"]+)"/g)) {
    requireEqual(`${workflow} setup-node version`, nodePin, match[1]);
  }
}

// ---------------------------------------------------------------------------
// Paper / Minecraft pin
// ---------------------------------------------------------------------------
const mcVersion = read("gradle/libs.versions.toml").match(/^minecraft = "([^"]+)"/m)?.[1];
const paperBuilds = [
  ...read("scripts/paper-smoke.sh").matchAll(/^PAPER_BUILD=(.*)$/gm),
  ...read("scripts/managed-paper-smoke.sh").matchAll(/^PAPER_BUILD=(.*)$/gm),
].map((match) => match[1]);
const distinctPaperBuilds = [...new Set(paperBuilds)];
if (distinctPaperBuilds.length !== 1) {
  fail("PAPER_BUILD differs between scripts/paper-smoke.sh and scripts/managed-paper-smoke.sh");
}
const paperBuild = distinctPaperBuilds[0];
requireContains("deploy/systemd/agma-paper.service.example", `paper-${mcVersion}-${paperBuild}.jar`);
if (/(MINECRAFT_VERSION|minecraft)\s*=\s*"\d+\.\d+/.test(read("scripts/package.sh"))) {
  fail("scripts/package.sh hardcodes a Minecraft version; it must derive it from gradle/libs.versions.toml");
}
requireContains("scripts/package.sh", "libs.versions.toml");

// ---------------------------------------------------------------------------
if (failures.length > 0) {
  for (const failure of failures) {
    process.stderr.write(`check-versions: ${failure}\n`);
  }
  process.stderr.write(`check-versions: ${failures.length} mismatch(es) found\n`);
  process.exit(1);
}
process.stdout.write(
  `check-versions: OK (server=${serverVersion} standalone=${standaloneVersion} node=${nodePin} paper=${mcVersion}-${paperBuild})\n`,
);
