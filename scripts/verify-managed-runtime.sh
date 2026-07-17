#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="linux-x86_64"
DISTRIBUTIONS="$ROOT/managed-runtime/node-distributions.json"
ARCHIVE="${1:-$ROOT/build/managed-runtime/$PLATFORM/sidecar.zip}"

fail() {
  printf 'verify-managed-runtime: %s\n' "$*" >&2
  exit 1
}

for program in cmp find node sha256sum unzip; do
  command -v "$program" >/dev/null 2>&1 || fail "required program is unavailable: $program"
done

[[ -f "$ARCHIVE" && ! -L "$ARCHIVE" ]] || fail "sidecar archive is missing or is a symbolic link"
[[ -f "$DISTRIBUTIONS" && ! -L "$DISTRIBUTIONS" ]] \
  || fail "node distribution manifest is missing or is a symbolic link"
unzip -tqq "$ARCHIVE" >/dev/null || fail "sidecar ZIP integrity check failed"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-managed-verify.XXXXXXXX")"
cleanup() {
  rm -rf "$WORK"
}
trap cleanup EXIT
unzip -q "$ARCHIVE" -d "$WORK/payload"
PAYLOAD="$WORK/payload"

[[ -z "$(find "$PAYLOAD" -type l -print -quit)" ]] \
  || fail "sidecar ZIP contains a symbolic link"
[[ -z "$(find "$PAYLOAD" -type f -links +1 -print -quit)" ]] \
  || fail "sidecar ZIP contains a hard-linked file"
[[ -z "$(find "$PAYLOAD" ! -type f ! -type d -print -quit)" ]] \
  || fail "sidecar ZIP contains a special file"
[[ -f "$PAYLOAD/sidecar-manifest.json" && ! -L "$PAYLOAD/sidecar-manifest.json" ]] \
  || fail "sidecar manifest is missing"

NODE_VERSION="$({
  node - "$PAYLOAD" "$DISTRIBUTIONS" "$PLATFORM" <<'NODE'
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const [root, distributionsPath, expectedPlatform] = process.argv.slice(2);
const exactKeys = (value, expected) => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
};
const safePath = (value) =>
  typeof value === "string" &&
  value.length > 0 &&
  !value.startsWith("/") &&
  !value.includes("\\") &&
  !value.split("/").some((part) => part === "" || part === "." || part === "..") &&
  ![...value].some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint === undefined || codePoint < 0x20 || codePoint > 0x7e || codePoint === 0x7f;
  });

const distributions = JSON.parse(fs.readFileSync(distributionsPath, "utf8"));
if (
  !exactKeys(distributions, ["schemaVersion", "distributions"]) ||
  distributions.schemaVersion !== 1 ||
  !Array.isArray(distributions.distributions)
) {
  throw new Error("node distribution manifest has an unsupported schema");
}
const matches = distributions.distributions.filter((entry) => entry?.platform === expectedPlatform);
if (matches.length !== 1 || matches[0].nodeVersion !== "22.23.1") {
  throw new Error("pinned Node distribution is unavailable");
}

const manifestPath = path.join(root, "sidecar-manifest.json");
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
if (
  !exactKeys(manifest, ["schemaVersion", "runtimeVersion", "platform", "files"]) ||
  manifest.schemaVersion !== 1 ||
  manifest.platform !== expectedPlatform ||
  typeof manifest.runtimeVersion !== "string" ||
  !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/u.test(manifest.runtimeVersion) ||
  !Array.isArray(manifest.files) ||
  manifest.files.length === 0 ||
  manifest.files.length > 16384
) {
  throw new Error("sidecar manifest has an unsupported schema");
}

const actualFiles = [];
function visit(directory, prefix = "") {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = prefix === "" ? entry.name : `${prefix}/${entry.name}`;
    if (!safePath(relativePath)) throw new Error(`unsafe payload path: ${JSON.stringify(relativePath)}`);
    const absolutePath = path.join(directory, entry.name);
    const metadata = fs.lstatSync(absolutePath);
    if (entry.isSymbolicLink()) throw new Error(`symbolic link in payload: ${relativePath}`);
    if (entry.isDirectory()) {
      visit(absolutePath, relativePath);
    } else if (entry.isFile() && metadata.isFile() && metadata.nlink === 1) {
      if (relativePath !== "sidecar-manifest.json") actualFiles.push(relativePath);
    } else {
      throw new Error(`non-ordinary payload entry: ${relativePath}`);
    }
  }
}
visit(root);
actualFiles.sort((left, right) => Buffer.from(left).compare(Buffer.from(right)));

const declaredPaths = [];
for (const [index, entry] of manifest.files.entries()) {
  if (
    !exactKeys(entry, ["path", "size", "sha256", "executable"]) ||
    !safePath(entry.path) ||
    Buffer.byteLength(entry.path, "utf8") > 240 ||
    entry.path === "sidecar-manifest.json" ||
    !Number.isSafeInteger(entry.size) ||
    entry.size < 0 ||
    typeof entry.sha256 !== "string" ||
    !/^[0-9a-f]{64}$/u.test(entry.sha256) ||
    typeof entry.executable !== "boolean"
  ) {
    throw new Error(`invalid sidecar manifest file entry at index ${index}`);
  }
  if (index > 0 && Buffer.from(manifest.files[index - 1].path).compare(Buffer.from(entry.path)) >= 0) {
    throw new Error("sidecar manifest file entries are not strictly sorted and unique");
  }

  const absolutePath = path.resolve(root, entry.path);
  const relativeToRoot = path.relative(root, absolutePath);
  if (relativeToRoot.startsWith("..") || path.isAbsolute(relativeToRoot)) {
    throw new Error(`sidecar manifest path escapes the payload: ${entry.path}`);
  }
  const metadata = fs.lstatSync(absolutePath);
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.nlink !== 1) {
    throw new Error(`declared sidecar file is not ordinary: ${entry.path}`);
  }
  if (metadata.size !== entry.size) throw new Error(`size mismatch for ${entry.path}`);
  const digest = crypto.createHash("sha256").update(fs.readFileSync(absolutePath)).digest("hex");
  if (digest !== entry.sha256) throw new Error(`SHA-256 mismatch for ${entry.path}`);
  if (((metadata.mode & 0o111) !== 0) !== entry.executable) {
    throw new Error(`executable mode mismatch for ${entry.path}`);
  }
  declaredPaths.push(entry.path);
}

if (
  actualFiles.length !== declaredPaths.length ||
  actualFiles.some((entry, index) => entry !== declaredPaths[index])
) {
  throw new Error("sidecar manifest does not cover the exact payload file set");
}
if (!declaredPaths.includes("bin/node") || !declaredPaths.includes("app/dist/bootstrap/index.js")) {
  throw new Error("sidecar payload is missing a required Runtime entrypoint");
}
if (!declaredPaths.some((entry) => entry.startsWith("protocol/schemas/"))) {
  throw new Error("sidecar payload is missing protocol schemas");
}
if (
  !declaredPaths.includes("licenses/node/LICENSE") ||
  !declaredPaths.includes("licenses/npm-production-packages.json")
) {
  throw new Error("sidecar payload is missing required redistribution metadata");
}
if (manifest.files.some((entry) => entry.executable !== (entry.path === "bin/node"))) {
  throw new Error("only the pinned Node binary may be executable");
}

const packageMetadata = JSON.parse(fs.readFileSync(path.join(root, "app/package.json"), "utf8"));
if (
  packageMetadata.version !== manifest.runtimeVersion ||
  packageMetadata.type !== "module" ||
  packageMetadata.name !== "agma-runtime"
) {
  throw new Error("sidecar Runtime package identity does not match its manifest");
}

const nodeLicense = fs.lstatSync(path.join(root, "licenses", "node", "LICENSE"));
if (!nodeLicense.isFile() || nodeLicense.isSymbolicLink() || nodeLicense.nlink !== 1 || nodeLicense.size === 0) {
  throw new Error("pinned Node license is not an ordinary non-empty file");
}

const byteCompare = (left, right) => Buffer.from(left).compare(Buffer.from(right));
const normalizeLicense = (value) => {
  if (typeof value === "string" && value.trim() !== "") return value.trim();
  if (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    typeof value.type === "string" &&
    value.type.trim() !== ""
  ) {
    return value.type.trim();
  }
  if (Array.isArray(value)) {
    const values = value.map(normalizeLicense).filter((entry) => entry !== "UNKNOWN");
    if (values.length > 0) return values.join(" OR ");
  }
  return "UNKNOWN";
};
const expectedPackageName = (packagePath) => {
  const segments = packagePath.split("/");
  const name = segments.at(-1);
  const scope = segments.at(-2);
  return scope?.startsWith("@") ? `${scope}/${name}` : name;
};
const licenseFileName = /^(?:licen[cs]e|notice|copying)(?:$|[._ -])/iu;
const modulesRoot = path.join(root, "app", "node_modules");
const productionLock = JSON.parse(
  fs.readFileSync(path.join(modulesRoot, ".package-lock.json"), "utf8"),
);
if (
  typeof productionLock.packages !== "object" ||
  productionLock.packages === null ||
  Array.isArray(productionLock.packages)
) {
  throw new Error("production npm lock metadata does not contain a package inventory");
}
const packageKeys = Object.keys(productionLock.packages).sort(byteCompare);
const inventory = JSON.parse(
  fs.readFileSync(path.join(root, "licenses", "npm-production-packages.json"), "utf8"),
);
if (
  !exactKeys(inventory, ["schemaVersion", "packages"]) ||
  inventory.schemaVersion !== 1 ||
  !Array.isArray(inventory.packages) ||
  inventory.packages.length !== packageKeys.length ||
  inventory.packages.length === 0
) {
  throw new Error("production npm license inventory has an unsupported schema");
}

const expectedLicenseCopies = [];
for (const [index, packageKey] of packageKeys.entries()) {
  if (!packageKey.startsWith("node_modules/")) {
    throw new Error(`unexpected production npm package path: ${packageKey}`);
  }
  const packagePath = packageKey.slice("node_modules/".length);
  const inventoryEntry = inventory.packages[index];
  if (
    !exactKeys(inventoryEntry, ["path", "name", "version", "license"]) ||
    inventoryEntry.path !== packagePath ||
    !safePath(packagePath) ||
    Buffer.byteLength(packagePath, "utf8") > 180 ||
    typeof inventoryEntry.name !== "string" ||
    inventoryEntry.name !== expectedPackageName(packagePath) ||
    typeof inventoryEntry.version !== "string" ||
    !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/u.test(inventoryEntry.version) ||
    typeof inventoryEntry.license !== "string" ||
    inventoryEntry.license.length === 0 ||
    inventoryEntry.license.length > 512
  ) {
    throw new Error(`invalid production npm license inventory entry at index ${index}`);
  }
  if (index > 0 && byteCompare(inventory.packages[index - 1].path, packagePath) >= 0) {
    throw new Error("production npm license inventory is not strictly sorted and unique");
  }

  const packageDirectory = path.resolve(path.join(root, "app"), packageKey);
  if (path.relative(modulesRoot, packageDirectory) !== packagePath) {
    throw new Error(`production npm package path escapes node_modules: ${packagePath}`);
  }
  const packageJson = JSON.parse(fs.readFileSync(path.join(packageDirectory, "package.json"), "utf8"));
  if (
    packageJson.name !== inventoryEntry.name ||
    packageJson.version !== inventoryEntry.version ||
    normalizeLicense(packageJson.license) !== inventoryEntry.license
  ) {
    throw new Error(`production npm license inventory differs from package metadata: ${packagePath}`);
  }

  const sourceLicenses = fs
    .readdirSync(packageDirectory, { withFileTypes: true })
    .filter((entry) => licenseFileName.test(entry.name))
    .sort((left, right) => byteCompare(left.name, right.name));
  for (const entry of sourceLicenses) {
    const source = path.join(packageDirectory, entry.name);
    const sourceMetadata = fs.lstatSync(source);
    if (
      !entry.isFile() ||
      !sourceMetadata.isFile() ||
      entry.isSymbolicLink() ||
      sourceMetadata.nlink !== 1
    ) {
      throw new Error(`npm license source is not an ordinary file: ${packagePath}/${entry.name}`);
    }
    const relativeLicense = `licenses/npm/${packagePath}/${entry.name}`;
    const target = path.join(root, relativeLicense);
    const targetMetadata = fs.lstatSync(target);
    if (!targetMetadata.isFile() || targetMetadata.isSymbolicLink() || targetMetadata.nlink !== 1) {
      throw new Error(`copied npm license is not an ordinary file: ${relativeLicense}`);
    }
    if (!fs.readFileSync(source).equals(fs.readFileSync(target))) {
      throw new Error(`copied npm license differs from package content: ${relativeLicense}`);
    }
    expectedLicenseCopies.push(relativeLicense);
  }
}

expectedLicenseCopies.sort(byteCompare);
const actualLicenseCopies = [];
function visitLicenseCopies(directory, prefix = "licenses/npm") {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = `${prefix}/${entry.name}`;
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      visitLicenseCopies(absolutePath, relativePath);
    } else if (entry.isFile()) {
      actualLicenseCopies.push(relativePath);
    } else {
      throw new Error(`npm license tree contains a non-ordinary entry: ${relativePath}`);
    }
  }
}
visitLicenseCopies(path.join(root, "licenses", "npm"));
actualLicenseCopies.sort(byteCompare);
if (
  actualLicenseCopies.length !== expectedLicenseCopies.length ||
  actualLicenseCopies.some((entry, index) => entry !== expectedLicenseCopies[index])
) {
  throw new Error("copied npm license tree does not match production package contents");
}
process.stdout.write(`${matches[0].nodeVersion}\n`);
NODE
})" || fail "sidecar manifest verification failed"

[[ -x "$PAYLOAD/bin/node" ]] || fail "pinned Node binary is not executable"
[[ "$($PAYLOAD/bin/node --version)" == "v$NODE_VERSION" ]] \
  || fail "pinned Node binary reports an unexpected version"

OUTPUT_DIRECTORY="$(dirname "$ARCHIVE")"
if [[ -f "$OUTPUT_DIRECTORY/sidecar-manifest.json" ]]; then
  cmp -s "$OUTPUT_DIRECTORY/sidecar-manifest.json" "$PAYLOAD/sidecar-manifest.json" \
    || fail "external sidecar manifest differs from the archived manifest"
fi
if [[ -f "$OUTPUT_DIRECTORY/SHA256SUMS" ]]; then
  (
    cd "$OUTPUT_DIRECTORY"
    sha256sum --check --strict --quiet SHA256SUMS
  ) || fail "managed Runtime output checksums do not verify"
fi

FILE_COUNT="$(node -p "require(process.argv[1]).files.length" "$PAYLOAD/sidecar-manifest.json")"
printf 'verify-managed-runtime platform=%s node=v%s files=%s result=passed\n' \
  "$PLATFORM" \
  "$NODE_VERSION" \
  "$FILE_COUNT"
