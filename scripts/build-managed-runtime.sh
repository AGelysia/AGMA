#!/usr/bin/env bash
set -euo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="linux-x86_64"
DISTRIBUTIONS="$ROOT/managed-runtime/node-distributions.json"
RUNTIME="$ROOT/agent-runtime"
PROTOCOL="$ROOT/protocol"
OUTPUT="${MINECRAFT_AGENT_MANAGED_OUTPUT:-$ROOT/build/managed-runtime/$PLATFORM}"
CACHE="${MINECRAFT_AGENT_MANAGED_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/agma/managed-runtime}"
SOURCE_DATE_EPOCH=315532800

fail() {
  printf 'build-managed-runtime: %s\n' "$*" >&2
  exit 1
}

for program in curl find node sha256sum sort tar touch unzip zip; do
  command -v "$program" >/dev/null 2>&1 || fail "required program is unavailable: $program"
done

[[ "$(uname -s)" == Linux ]] || fail "the $PLATFORM sidecar must be built on Linux"
case "$(uname -m)" in
  x86_64 | amd64) ;;
  *) fail "the $PLATFORM sidecar must be built on x86_64" ;;
esac

[[ -f "$DISTRIBUTIONS" && ! -L "$DISTRIBUTIONS" ]] \
  || fail "node distribution manifest is missing or is a symbolic link"
[[ -f "$RUNTIME/package.json" && -f "$RUNTIME/package-lock.json" ]] \
  || fail "Runtime package metadata is incomplete"
[[ -d "$RUNTIME/src" && ! -L "$RUNTIME/src" && -f "$RUNTIME/tsconfig.json" ]] \
  || fail "Runtime TypeScript sources are incomplete"
[[ -d "$PROTOCOL/schemas" && ! -L "$PROTOCOL/schemas" ]] \
  || fail "protocol schemas are missing or are a symbolic link"

mapfile -t distribution < <(
  node - "$DISTRIBUTIONS" "$PLATFORM" <<'NODE'
const fs = require("node:fs");

const [manifestPath, platform] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const exactKeys = (value, expected) => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
};

if (
  !exactKeys(manifest, ["schemaVersion", "distributions"]) ||
  manifest.schemaVersion !== 1 ||
  !Array.isArray(manifest.distributions)
) {
  throw new Error("node distribution manifest has an unsupported schema");
}
const matches = manifest.distributions.filter((entry) => entry?.platform === platform);
if (matches.length !== 1) {
  throw new Error(`expected exactly one Node distribution for ${platform}`);
}
const entry = matches[0];
if (
  !exactKeys(entry, ["platform", "nodeVersion", "archive", "url", "sha256"]) ||
  entry.nodeVersion !== "22.23.1" ||
  entry.archive !== "node-v22.23.1-linux-x64.tar.xz" ||
  entry.url !== "https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz" ||
  entry.sha256 !== "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578"
) {
  throw new Error(`Node distribution for ${platform} does not match the pinned release`);
}
for (const value of [entry.nodeVersion, entry.archive, entry.url, entry.sha256]) {
  if (typeof value !== "string" || /[\r\n\0]/u.test(value)) {
    throw new Error("node distribution manifest contains an invalid string");
  }
  process.stdout.write(`${value}\n`);
}
NODE
) || fail "node distribution manifest could not be parsed"

[[ "${#distribution[@]}" -eq 4 ]] || fail "node distribution manifest returned incomplete data"
NODE_VERSION="${distribution[0]}"
NODE_ARCHIVE="${distribution[1]}"
NODE_URL="${distribution[2]}"
NODE_SHA256="${distribution[3]}"
RUNTIME_VERSION="$(node -p "require(process.argv[1]).version" "$RUNTIME/package.json")"
[[ "$RUNTIME_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] \
  || fail "Runtime version is not package-safe: $RUNTIME_VERSION"

mkdir -p "$CACHE" "$OUTPUT"
[[ -d "$CACHE" && ! -L "$CACHE" ]] || fail "managed Runtime cache is not a regular directory"
[[ -d "$OUTPUT" && ! -L "$OUTPUT" ]] || fail "managed Runtime output is not a regular directory"

CACHED_ARCHIVE="$CACHE/$NODE_ARCHIVE"
if [[ -e "$CACHED_ARCHIVE" ]]; then
  if [[ ! -f "$CACHED_ARCHIVE" || -L "$CACHED_ARCHIVE" ]] \
    || ! printf '%s  %s\n' "$NODE_SHA256" "$CACHED_ARCHIVE" \
      | sha256sum --check --strict --quiet; then
    rm -f "$CACHED_ARCHIVE"
  fi
fi

if [[ ! -f "$CACHED_ARCHIVE" ]]; then
  DOWNLOAD="$CACHE/.${NODE_ARCHIVE}.download.$$"
  rm -f "$DOWNLOAD"
  trap 'rm -f "${DOWNLOAD:-}"' EXIT
  curl \
    --fail \
    --location \
    --proto '=https' \
    --retry 3 \
    --retry-delay 1 \
    --silent \
    --show-error \
    --tlsv1.2 \
    --output "$DOWNLOAD" \
    "$NODE_URL"
  printf '%s  %s\n' "$NODE_SHA256" "$DOWNLOAD" \
    | sha256sum --check --strict --quiet \
    || fail "downloaded Node archive does not match the pinned SHA-256"
  chmod 0644 "$DOWNLOAD"
  mv -f "$DOWNLOAD" "$CACHED_ARCHIVE"
  trap - EXIT
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-managed-runtime.XXXXXXXX")"
cleanup() {
  rm -rf "$WORK"
}
trap cleanup EXIT

NODE_ROOT_NAME="node-v${NODE_VERSION}-linux-x64"
NODE_ARCHIVE_PATHS="$WORK/node-archive-paths"
tar -tf "$CACHED_ARCHIVE" >"$NODE_ARCHIVE_PATHS"
node - "$NODE_ROOT_NAME" "$NODE_ARCHIVE_PATHS" <<'NODE'
const fs = require("node:fs");
const [root, archivePaths] = process.argv.slice(2);
const entries = fs.readFileSync(archivePaths, "utf8").split("\n").filter(Boolean);
if (entries.length === 0) throw new Error("Node archive is empty");
for (const entry of entries) {
  if (
    entry.includes("\\") ||
    entry.startsWith("/") ||
    entry.includes("\0") ||
    entry.split("/").includes("..") ||
    (entry !== root && !entry.startsWith(`${root}/`))
  ) {
    throw new Error(`unsafe Node archive path: ${JSON.stringify(entry)}`);
  }
}
NODE

mkdir "$WORK/node"
tar -xJf "$CACHED_ARCHIVE" -C "$WORK/node"
NODE_ROOT="$WORK/node/$NODE_ROOT_NAME"
PINNED_NODE="$NODE_ROOT/bin/node"
NPM_CLI="$NODE_ROOT/lib/node_modules/npm/bin/npm-cli.js"
[[ -f "$PINNED_NODE" && ! -L "$PINNED_NODE" && -x "$PINNED_NODE" ]] \
  || fail "pinned Node executable is unavailable after extraction"
[[ -f "$NPM_CLI" && ! -L "$NPM_CLI" ]] \
  || fail "pinned npm CLI is unavailable after extraction"
[[ "$($PINNED_NODE --version)" == "v$NODE_VERSION" ]] \
  || fail "pinned Node executable reports an unexpected version"

BUILD_APP="$WORK/build-app"
mkdir "$BUILD_APP"
cp "$RUNTIME/package.json" "$RUNTIME/package-lock.json" "$RUNTIME/tsconfig.json" "$BUILD_APP/"
cp -R "$RUNTIME/src" "$BUILD_APP/src"
(
  cd "$BUILD_APP"
  PATH="$NODE_ROOT/bin:$PATH" "$PINNED_NODE" "$NPM_CLI" ci \
    --ignore-scripts \
    --no-audit \
    --no-fund \
    --cache "$CACHE/npm"
  PATH="$NODE_ROOT/bin:$PATH" "$PINNED_NODE" "$NPM_CLI" run build --silent
)
[[ -f "$BUILD_APP/dist/bootstrap/index.js" ]] || fail "Runtime build did not produce its entrypoint"

PAYLOAD="$WORK/payload"
mkdir -p "$PAYLOAD/bin" "$PAYLOAD/app" "$PAYLOAD/protocol"
cp "$PINNED_NODE" "$PAYLOAD/bin/node"
cp "$RUNTIME/package.json" "$RUNTIME/package-lock.json" "$PAYLOAD/app/"
cp -R "$BUILD_APP/dist" "$PAYLOAD/app/dist"
cp -R "$PROTOCOL/schemas" "$PAYLOAD/protocol/schemas"
(
  cd "$PAYLOAD/app"
  PATH="$NODE_ROOT/bin:$PATH" "$PINNED_NODE" "$NPM_CLI" ci \
    --omit=dev \
    --ignore-scripts \
    --no-audit \
    --no-fund \
    --cache "$CACHE/npm"
)
rm -rf "$PAYLOAD/app/node_modules/.bin"

[[ -f "$NODE_ROOT/LICENSE" && ! -L "$NODE_ROOT/LICENSE" ]] \
  || fail "pinned Node distribution does not contain an ordinary LICENSE file"
mkdir -p "$PAYLOAD/licenses/node" "$PAYLOAD/licenses/npm"
cp "$NODE_ROOT/LICENSE" "$PAYLOAD/licenses/node/LICENSE"

"$PINNED_NODE" - "$PAYLOAD" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");

const root = process.argv[2];
const appRoot = path.join(root, "app");
const modulesRoot = path.join(appRoot, "node_modules");
const lockPath = path.join(modulesRoot, ".package-lock.json");
const inventoryPath = path.join(root, "licenses", "npm-production-packages.json");
const licensesRoot = path.join(root, "licenses", "npm");
const licenseFileName = /^(?:licen[cs]e|notice|copying)(?:$|[._ -])/iu;

const byteCompare = (left, right) => Buffer.from(left).compare(Buffer.from(right));
const safeRelativePath = (value) =>
  typeof value === "string" &&
  value.length > 0 &&
  !value.startsWith("/") &&
  !value.includes("\\") &&
  !value.split("/").some((segment) => segment === "" || segment === "." || segment === "..") &&
  ![...value].some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint === undefined || codePoint < 0x20 || codePoint > 0x7e || codePoint === 0x7f;
  });
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

const lockMetadata = fs.lstatSync(lockPath);
if (!lockMetadata.isFile() || lockMetadata.isSymbolicLink() || lockMetadata.nlink !== 1) {
  throw new Error("production npm lock metadata is not an ordinary file");
}
const lock = JSON.parse(fs.readFileSync(lockPath, "utf8"));
if (typeof lock.packages !== "object" || lock.packages === null || Array.isArray(lock.packages)) {
  throw new Error("production npm lock metadata does not contain a package inventory");
}

const packageKeys = Object.keys(lock.packages).sort(byteCompare);
if (packageKeys.length === 0) throw new Error("production npm package inventory is empty");
const packages = [];
for (const packageKey of packageKeys) {
  if (!packageKey.startsWith("node_modules/")) {
    throw new Error(`unexpected production npm package path: ${packageKey}`);
  }
  const packagePath = packageKey.slice("node_modules/".length);
  if (!safeRelativePath(packagePath) || Buffer.byteLength(packagePath, "utf8") > 180) {
    throw new Error(`unsafe production npm package path: ${packagePath}`);
  }

  const packageDirectory = path.resolve(appRoot, packageKey);
  const relativeToModules = path.relative(modulesRoot, packageDirectory);
  if (relativeToModules !== packagePath || relativeToModules.startsWith("..")) {
    throw new Error(`production npm package path escapes node_modules: ${packagePath}`);
  }
  const directoryMetadata = fs.lstatSync(packageDirectory);
  if (!directoryMetadata.isDirectory() || directoryMetadata.isSymbolicLink()) {
    throw new Error(`production npm package directory is not ordinary: ${packagePath}`);
  }

  const packageJsonPath = path.join(packageDirectory, "package.json");
  const packageJsonMetadata = fs.lstatSync(packageJsonPath);
  if (
    !packageJsonMetadata.isFile() ||
    packageJsonMetadata.isSymbolicLink() ||
    packageJsonMetadata.nlink !== 1
  ) {
    throw new Error(`production npm package metadata is not ordinary: ${packagePath}`);
  }
  const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
  if (
    typeof packageJson.name !== "string" ||
    packageJson.name !== expectedPackageName(packagePath) ||
    typeof packageJson.version !== "string" ||
    !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/u.test(packageJson.version)
  ) {
    throw new Error(`production npm package identity is invalid: ${packagePath}`);
  }

  const license = normalizeLicense(packageJson.license);
  packages.push({ path: packagePath, name: packageJson.name, version: packageJson.version, license });

  const licenseFiles = fs
    .readdirSync(packageDirectory, { withFileTypes: true })
    .filter((entry) => licenseFileName.test(entry.name))
    .sort((left, right) => byteCompare(left.name, right.name));
  if (licenseFiles.length === 0) continue;

  const targetDirectory = path.join(licensesRoot, packagePath);
  fs.mkdirSync(targetDirectory, { recursive: true, mode: 0o755 });
  for (const entry of licenseFiles) {
    const source = path.join(packageDirectory, entry.name);
    const metadata = fs.lstatSync(source);
    if (!entry.isFile() || !metadata.isFile() || entry.isSymbolicLink() || metadata.nlink !== 1) {
      throw new Error(`npm license entry is not an ordinary file: ${packagePath}/${entry.name}`);
    }
    fs.copyFileSync(source, path.join(targetDirectory, entry.name), fs.constants.COPYFILE_EXCL);
  }
}

fs.writeFileSync(
  inventoryPath,
  `${JSON.stringify({ schemaVersion: 1, packages }, null, 2)}\n`,
  { encoding: "utf8", mode: 0o644, flag: "wx" },
);
NODE

[[ -z "$(find "$PAYLOAD" -type l -print -quit)" ]] \
  || fail "managed Runtime payload contains a symbolic link"
[[ -z "$(find "$PAYLOAD" -type f -links +1 -print -quit)" ]] \
  || fail "managed Runtime payload contains a hard-linked file"
[[ -z "$(find "$PAYLOAD" ! -type f ! -type d -print -quit)" ]] \
  || fail "managed Runtime payload contains a special file"

find "$PAYLOAD" -type d -exec chmod 0755 {} +
find "$PAYLOAD" -type f -exec chmod 0644 {} +
chmod 0755 "$PAYLOAD/bin/node"

"$PINNED_NODE" - "$PAYLOAD" "$RUNTIME_VERSION" "$PLATFORM" <<'NODE'
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const [root, runtimeVersion, platform] = process.argv.slice(2);
const files = [];

function visit(directory, prefix = "") {
  const entries = fs.readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    Buffer.from(left.name).compare(Buffer.from(right.name)),
  );
  for (const entry of entries) {
    const relativePath = prefix === "" ? entry.name : `${prefix}/${entry.name}`;
    if (
      entry.name === "" ||
      entry.name === "." ||
      entry.name === ".." ||
      entry.name.includes("/") ||
      entry.name.includes("\\") ||
      [...entry.name].some((character) => {
        const codePoint = character.codePointAt(0);
        return codePoint === undefined || codePoint < 0x20 || codePoint > 0x7e || codePoint === 0x7f;
      })
    ) {
      throw new Error(`payload contains an unsafe path: ${JSON.stringify(relativePath)}`);
    }
    if (Buffer.byteLength(relativePath, "utf8") > 240) {
      throw new Error(`payload path exceeds the installer limit: ${relativePath}`);
    }
    const absolutePath = path.join(directory, entry.name);
    const metadata = fs.lstatSync(absolutePath);
    if (entry.isSymbolicLink()) throw new Error(`payload contains a symbolic link: ${relativePath}`);
    if (entry.isDirectory()) {
      visit(absolutePath, relativePath);
      continue;
    }
    if (!entry.isFile() || !metadata.isFile() || metadata.nlink !== 1) {
      throw new Error(`payload contains a non-ordinary file: ${relativePath}`);
    }
    if (relativePath === "sidecar-manifest.json") {
      throw new Error("payload already contains its reserved manifest path");
    }
    files.push({
      path: relativePath,
      size: metadata.size,
      sha256: crypto.createHash("sha256").update(fs.readFileSync(absolutePath)).digest("hex"),
      executable: (metadata.mode & 0o111) !== 0,
    });
  }
}

visit(root);
files.sort((left, right) => Buffer.from(left.path).compare(Buffer.from(right.path)));
const manifest = { schemaVersion: 1, runtimeVersion, platform, files };
fs.writeFileSync(path.join(root, "sidecar-manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, {
  encoding: "utf8",
  mode: 0o644,
  flag: "wx",
});
NODE

find "$PAYLOAD" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +
FILE_LIST="$WORK/zip-files"
(
  cd "$PAYLOAD"
  find . -type f -printf '%P\n' | LC_ALL=C sort >"$FILE_LIST"
)

ZIP_TEMP="$OUTPUT/.sidecar.zip.$$"
rm -f "$ZIP_TEMP"
(
  cd "$PAYLOAD"
  TZ=UTC zip -X -q "$ZIP_TEMP" -@ <"$FILE_LIST"
)
chmod 0644 "$ZIP_TEMP"
mv -f "$ZIP_TEMP" "$OUTPUT/sidecar.zip"
cp "$PAYLOAD/sidecar-manifest.json" "$OUTPUT/sidecar-manifest.json"
chmod 0644 "$OUTPUT/sidecar-manifest.json"
(
  cd "$OUTPUT"
  LC_ALL=C sha256sum sidecar-manifest.json sidecar.zip >SHA256SUMS
)
chmod 0644 "$OUTPUT/SHA256SUMS"

"$ROOT/scripts/verify-managed-runtime.sh" "$OUTPUT/sidecar.zip"
printf 'managed-runtime platform=%s runtime=%s node=%s sha256=%s\n' \
  "$PLATFORM" \
  "$RUNTIME_VERSION" \
  "$NODE_VERSION" \
  "$(sha256sum "$OUTPUT/sidecar.zip" | cut -d ' ' -f 1)"
