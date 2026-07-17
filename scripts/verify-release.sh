#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_INPUT="${1:-$ROOT/release}"
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
MINECRAFT_VERSION="1.21.11"
PLATFORM="linux-x86_64"
INTEGRATED_NAME="AGMA-Server-Integrated-${VERSION}-mc${MINECRAFT_VERSION}-${PLATFORM}.jar"
SEPARATED_NAME="AGMA-Server-Separated-${VERSION}-mc${MINECRAFT_VERSION}.zip"
CLIENT_NAME="AGMA-Client-${VERSION}-mc${MINECRAFT_VERSION}-fabric.jar"
PACKAGE_NAME="${SEPARATED_NAME%.zip}"

fail() {
  printf 'verify-release: %s\n' "$*" >&2
  exit 1
}

[[ -d "$RELEASE_INPUT" && ! -L "$RELEASE_INPUT" ]] \
  || fail "release directory is missing or unsafe: $RELEASE_INPUT"
RELEASE="$(cd "$RELEASE_INPUT" && pwd -P)"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi
[[ -n "$JAVA_BIN" ]] || fail "Java 21 was not found"
for program in awk cmp diff du find grep node npm sed sha256sum sort stat uniq unzip wc xargs zipinfo; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required program was not found: $program"
done

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/agma-release-verify.XXXXXXXX")"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

require_file() {
  local root=$1
  local path=$2
  [[ -f "$root/$path" && ! -L "$root/$path" && -s "$root/$path" ]] \
    || fail "required file is missing or empty: $path"
}

require_directory() {
  local root=$1
  local path=$2
  [[ -d "$root/$path" && ! -L "$root/$path" ]] \
    || fail "required directory is missing: $path"
}

require_exact_children() {
  local root=$1
  local directory=$2
  shift 2
  local actual="$TMP_ROOT/children.$RANDOM.actual"
  local expected="$TMP_ROOT/children.$RANDOM.expected"
  find "$root${directory:+/$directory}" -mindepth 1 -maxdepth 1 -printf '%f\n' \
    | sort >"$actual"
  printf '%s\n' "$@" | sort >"$expected"
  if ! cmp -s "$expected" "$actual"; then
    diff -u "$expected" "$actual" >&2 || true
    fail "unexpected or missing entry under ${directory:-release root}"
  fi
}

validate_zip_paths() {
  local entries=$1
  local required_root=${2:-}
  node - "$entries" "$required_root" <<'NODE'
const fs = require("node:fs");
const [entriesPath, requiredRoot] = process.argv.slice(2);
const entries = fs.readFileSync(entriesPath, "utf8").split("\n").filter(Boolean);
if (entries.length === 0 || entries.length > 32768) {
  throw new Error("ZIP entry count is outside the release limit");
}
const names = new Set();
for (const name of entries) {
  if (names.has(name)) throw new Error(`duplicate ZIP entry: ${name}`);
  names.add(name);
  if (
    name.startsWith("/") ||
    name.includes("\\") ||
    [...name].some((character) => {
      const codePoint = character.codePointAt(0);
      return codePoint === undefined || codePoint < 0x20 || codePoint > 0x7e || codePoint === 0x7f;
    })
  ) {
    throw new Error(`unsafe ZIP entry: ${JSON.stringify(name)}`);
  }
  const path = name.endsWith("/") ? name.slice(0, -1) : name;
  const parts = path.split("/");
  if (parts.some((part) => part === "" || part === "." || part === "..")) {
    throw new Error(`non-canonical ZIP entry: ${JSON.stringify(name)}`);
  }
  if (requiredRoot !== "" && path !== requiredRoot && !path.startsWith(`${requiredRoot}/`)) {
    throw new Error(`ZIP entry is outside ${requiredRoot}: ${name}`);
  }
}
if (requiredRoot !== "" && !names.has(`${requiredRoot}/`)) {
  throw new Error(`ZIP root directory is missing: ${requiredRoot}/`);
}
NODE
}

verify_checksum_manifest() {
  local directory=$1
  local manifest_paths="$TMP_ROOT/manifest.$RANDOM.paths"
  : >"$manifest_paths"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^([0-9a-f]{64})\ \ (\./[^[:cntrl:]\\]+)$ ]] \
      || fail "SHA256SUMS contains a malformed line"
    local manifest_path=${BASH_REMATCH[2]}
    local path=${manifest_path#./}
    [[ -n "$path" && "$path" != SHA256SUMS && "$path" != ./* \
      && "$path" != ../* && "$path" != *'/../'* && "$path" != *'/./'* ]] \
      || fail "SHA256SUMS contains an unsafe path: $manifest_path"
    [[ -f "$directory/$path" && ! -L "$directory/$path" ]] \
      || fail "SHA256SUMS references a missing file: $path"
    printf '%s\n' "$path" >>"$manifest_paths"
  done <"$directory/SHA256SUMS"

  [[ -z "$(sort "$manifest_paths" | uniq -d | sed -n '1p')" ]] \
    || fail "SHA256SUMS contains duplicate paths"
  find "$directory" -type f ! -path "$directory/SHA256SUMS" -printf '%P\n' \
    | sort >"$TMP_ROOT/manifest.actual"
  sort "$manifest_paths" >"$TMP_ROOT/manifest.expected"
  cmp "$TMP_ROOT/manifest.expected" "$TMP_ROOT/manifest.actual" \
    || fail "SHA256SUMS does not cover the exact file set"
  (cd "$directory" && sha256sum --check --strict --quiet SHA256SUMS) \
    || fail "SHA256SUMS verification failed"
}

require_exact_children "$RELEASE" "" \
  "$CLIENT_NAME" "$INTEGRATED_NAME" "$SEPARATED_NAME" SHA256SUMS
for path in "$CLIENT_NAME" "$INTEGRATED_NAME" "$SEPARATED_NAME" SHA256SUMS; do
  require_file "$RELEASE" "$path"
done
[[ -z "$(find "$RELEASE" -type l -print -quit)" ]] || fail "release contains a symbolic link"
[[ -z "$(find "$RELEASE" ! -type d ! -type f -print -quit)" ]] \
  || fail "release contains a special filesystem entry"

printf '%s\n' "$CLIENT_NAME" "$INTEGRATED_NAME" "$SEPARATED_NAME" \
  | sort >"$TMP_ROOT/release.expected"
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^[0-9a-f]{64}\ \ ([^/[:cntrl:]\\]+)$ ]] \
    || fail "release SHA256SUMS contains a malformed line"
  printf '%s\n' "${BASH_REMATCH[1]}"
done <"$RELEASE/SHA256SUMS" | sort >"$TMP_ROOT/release.actual"
cmp "$TMP_ROOT/release.expected" "$TMP_ROOT/release.actual" \
  || fail "release SHA256SUMS does not name the exact asset set"
(cd "$RELEASE" && sha256sum --check --strict --quiet SHA256SUMS) \
  || fail "release asset checksum verification failed"

SEPARATED="$RELEASE/$SEPARATED_NAME"
unzip -tqq "$SEPARATED" >/dev/null || fail "separated server ZIP integrity check failed"
unzip -Z1 "$SEPARATED" >"$TMP_ROOT/separated.entries"
validate_zip_paths "$TMP_ROOT/separated.entries" "$PACKAGE_NAME" \
  || fail "separated server ZIP contains an unsafe path"
if zipinfo -l "$SEPARATED" | grep -Eq '^[lbcps]'; then
  fail "separated server ZIP contains a link or special entry"
fi
unzip -q "$SEPARATED" -d "$TMP_ROOT/separated"
PACKAGE_ROOT="$TMP_ROOT/separated/$PACKAGE_NAME"
require_directory "$TMP_ROOT/separated" "$PACKAGE_NAME"
[[ -z "$(find "$PACKAGE_ROOT" -type l -print -quit)" ]] \
  || fail "separated server package contains a symbolic link"
[[ -z "$(find "$PACKAGE_ROOT" ! -type d ! -type f -print -quit)" ]] \
  || fail "separated server package contains a special filesystem entry"
[[ "$(find "$PACKAGE_ROOT" -type f | wc -l)" -le 32768 ]] \
  || fail "separated server package contains too many files"
[[ "$(du -sb "$PACKAGE_ROOT" | awk '{print $1}')" -le 268435456 ]] \
  || fail "separated server package exceeds the expansion limit"

require_exact_children "$PACKAGE_ROOT" "" \
  AGMA-Server-Plugin.jar \
  CLIENT-COMPATIBILITY.md \
  LICENSE \
  OPERATIONS.md \
  README.md \
  SECURITY.md \
  SHA256SUMS \
  capability-packs \
  deploy \
  protocol \
  runtime \
  runtime.env.example \
  start-runtime.ps1 \
  start-runtime.sh
require_exact_children "$PACKAGE_ROOT" runtime \
  config.example.yml dist node_modules package-lock.json package.json
require_exact_children "$PACKAGE_ROOT" protocol schemas
for path in \
  AGMA-Server-Plugin.jar \
  CLIENT-COMPATIBILITY.md \
  LICENSE \
  OPERATIONS.md \
  README.md \
  SECURITY.md \
  SHA256SUMS \
  runtime.env.example \
  runtime/config.example.yml \
  runtime/dist/bootstrap/index.js \
  runtime/dist/validation/live-provider-check.js \
  runtime/node_modules/.package-lock.json \
  runtime/package-lock.json \
  runtime/package.json \
  start-runtime.ps1 \
  start-runtime.sh; do
  require_file "$PACKAGE_ROOT" "$path"
done
for path in capability-packs deploy protocol/schemas runtime/dist runtime/node_modules; do
  require_directory "$PACKAGE_ROOT" "$path"
done

while IFS= read -r -d '' path; do
  relative=${path#"$PACKAGE_ROOT"/}
  mode="$(stat -c '%a' "$path")"
  if [[ -d "$path" ]]; then
    [[ "$mode" == 755 ]] || fail "directory mode must be 0755: $relative"
  elif [[ "$relative" == start-runtime.sh ]]; then
    [[ "$mode" == 755 ]] || fail "start-runtime.sh mode must be 0755"
  else
    [[ "$mode" == 644 ]] || fail "ordinary file mode must be 0644: $relative"
  fi
done < <(find "$PACKAGE_ROOT" -print0)

verify_checksum_manifest "$PACKAGE_ROOT"
cmp "$ROOT/agent-runtime/package.json" "$PACKAGE_ROOT/runtime/package.json" \
  || fail "packaged Runtime package.json differs from source"
cmp "$ROOT/agent-runtime/package-lock.json" "$PACKAGE_ROOT/runtime/package-lock.json" \
  || fail "packaged Runtime lockfile differs from source"
cmp "$ROOT/agent-runtime/config.example.yml" "$PACKAGE_ROOT/runtime/config.example.yml" \
  || fail "packaged Runtime configuration template differs from source"
diff -qr "$ROOT/agent-runtime/dist" "$PACKAGE_ROOT/runtime/dist" >/dev/null \
  || fail "packaged Runtime build differs from source build output"
diff -qr "$ROOT/protocol/schemas" "$PACKAGE_ROOT/protocol/schemas" >/dev/null \
  || fail "packaged protocol schemas differ from source"
diff -qr "$ROOT/capability-packs" "$PACKAGE_ROOT/capability-packs" >/dev/null \
  || fail "packaged capability packs differ from source"
diff -qr "$ROOT/deploy" "$PACKAGE_ROOT/deploy" >/dev/null \
  || fail "packaged deployment templates differ from source"
cmp "$ROOT/packaging/server-separated/README.md" "$PACKAGE_ROOT/README.md" \
  || fail "packaged README differs from source"
cmp "$ROOT/docs/operations.md" "$PACKAGE_ROOT/OPERATIONS.md" \
  || fail "packaged operations guide differs from source"

node - \
  "$PACKAGE_ROOT/runtime/package.json" \
  "$PACKAGE_ROOT/runtime/package-lock.json" \
  "$PACKAGE_ROOT/runtime/node_modules/.package-lock.json" \
  "$VERSION" <<'NODE'
const fs = require("node:fs");
const [packagePath, lockPath, installedLockPath, version] = process.argv.slice(2);
const metadata = JSON.parse(fs.readFileSync(packagePath, "utf8"));
const lock = JSON.parse(fs.readFileSync(lockPath, "utf8"));
const installed = JSON.parse(fs.readFileSync(installedLockPath, "utf8"));
if (
  metadata.name !== "agma-runtime" ||
  metadata.version !== version ||
  metadata.private !== true ||
  lock.name !== metadata.name ||
  lock.version !== version ||
  lock.packages?.[""]?.name !== metadata.name ||
  lock.packages?.[""]?.version !== version ||
  typeof installed.packages !== "object" ||
  installed.packages === null ||
  Object.keys(installed.packages).length === 0
) {
  throw new Error("packaged Runtime identity or production lock metadata is invalid");
}
for (const [path, entry] of Object.entries(installed.packages)) {
  if (!path.startsWith("node_modules/") || entry?.dev === true) {
    throw new Error(`non-production package was installed: ${path}`);
  }
}
NODE
[[ ! -e "$PACKAGE_ROOT/runtime/node_modules/.bin" ]] \
  || fail "npm executable links must not be packaged"
npm --prefix "$PACKAGE_ROOT/runtime" ls --omit=dev --all --offline >/dev/null \
  || fail "packaged production npm dependency graph is incomplete"

secret_matches="$TMP_ROOT/secret.matches"
if find "$PACKAGE_ROOT" -type f \
  ! -path '*/node_modules/*' \
  ! -name '*.jar' \
  -print0 \
  | xargs -0 grep -a -l -E \
      -- '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|(^|[^[:alnum:]_])sk-(proj-)?[[:alnum:]_-]{20,}|(^|[^[:alnum:]_])gh[pousr]_[[:alnum:]]{20,}|(^|[^[:alnum:]_])AKIA[0-9A-Z]{16}' \
      >"$secret_matches"; then
  fail "separated server package contains a credential pattern"
fi

"$JAVA_BIN" "$ROOT/scripts/VerifyReleaseJar.java" \
  "$PACKAGE_ROOT/AGMA-Server-Plugin.jar" \
  Paper \
  "$TMP_ROOT/thin" \
  "$TMP_ROOT/thin.entries"
"$JAVA_BIN" "$ROOT/scripts/VerifyReleaseJar.java" \
  "$RELEASE/$CLIENT_NAME" \
  Fabric \
  "$TMP_ROOT/client" \
  "$TMP_ROOT/client.entries"

grep -Eq '^name:[[:space:]]+AGMA[[:space:]]*$' "$TMP_ROOT/thin/paper-plugin.yml" \
  || fail "Paper descriptor has the wrong public name"
grep -Eq '^main:[[:space:]]+dev\.minecraftagent\.paper\.MinecraftAgentPlugin[[:space:]]*$' \
  "$TMP_ROOT/thin/paper-plugin.yml" || fail "Paper descriptor has the wrong entrypoint"
grep -Eq "^version:[[:space:]]+['\"]?${VERSION//./\\.}['\"]?[[:space:]]*$" \
  "$TMP_ROOT/thin/paper-plugin.yml" || fail "Paper descriptor version is wrong"
grep -Eq "^Implementation-Version:[[:space:]]+${VERSION//./\\.}[[:space:]]*\r?$" \
  "$TMP_ROOT/thin/META-INF/MANIFEST.MF" || fail "Paper manifest version is wrong"

node - "$TMP_ROOT/client/fabric.mod.json" "$VERSION" <<'NODE'
const fs = require("node:fs");
const [descriptorPath, version] = process.argv.slice(2);
const descriptor = JSON.parse(fs.readFileSync(descriptorPath, "utf8"));
if (
  descriptor.id !== "minecraftagent" ||
  descriptor.name !== "AGMA Client" ||
  descriptor.version !== version ||
  descriptor.environment !== "client" ||
  descriptor.depends?.fabricloader !== ">=0.19.3" ||
  descriptor.depends?.["fabric-api"] !== "0.141.4+1.21.11" ||
  descriptor.depends?.minecraft !== "1.21.11" ||
  descriptor.depends?.java !== ">=21"
) {
  throw new Error("Fabric descriptor does not match the locked release tuple");
}
NODE

INTEGRATED="$RELEASE/$INTEGRATED_NAME"
unzip -tqq "$INTEGRATED" >/dev/null || fail "integrated server JAR integrity check failed"
unzip -Z1 "$INTEGRATED" >"$TMP_ROOT/integrated.entries"
validate_zip_paths "$TMP_ROOT/integrated.entries" \
  || fail "integrated server JAR contains an unsafe path"
if zipinfo -l "$INTEGRATED" | grep -Eq '^[lbcps]'; then
  fail "integrated server JAR contains a link or special entry"
fi
[[ "$(grep -Fxc 'managed-runtime/artifact.properties' "$TMP_ROOT/integrated.entries")" == 1 ]] \
  || fail "integrated server JAR has no unique Runtime descriptor"
[[ "$(grep -Fxc 'managed-runtime/sidecar.zip' "$TMP_ROOT/integrated.entries")" == 1 ]] \
  || fail "integrated server JAR has no unique Runtime sidecar"
grep -Fvx \
  -e 'managed-runtime/artifact.properties' \
  -e 'managed-runtime/sidecar.zip' \
  "$TMP_ROOT/integrated.entries" | sort >"$TMP_ROOT/integrated.base.entries"
sort "$TMP_ROOT/thin.entries" >"$TMP_ROOT/thin.sorted.entries"
cmp "$TMP_ROOT/thin.sorted.entries" "$TMP_ROOT/integrated.base.entries" \
  || fail "integrated server JAR does not contain the exact thin plugin surface"

mkdir "$TMP_ROOT/integrated"
unzip -q "$INTEGRATED" -d "$TMP_ROOT/integrated"
require_file "$TMP_ROOT/integrated" managed-runtime/artifact.properties
require_file "$TMP_ROOT/integrated" managed-runtime/sidecar.zip
cp "$TMP_ROOT/integrated/managed-runtime/sidecar.zip" "$TMP_ROOT/sidecar.zip"
rm \
  "$TMP_ROOT/integrated/managed-runtime/artifact.properties" \
  "$TMP_ROOT/integrated/managed-runtime/sidecar.zip"
diff -qr "$TMP_ROOT/thin" "$TMP_ROOT/integrated" >/dev/null \
  || fail "integrated server JAR changed thin plugin bytes"

unzip -p "$INTEGRATED" managed-runtime/artifact.properties >"$TMP_ROOT/artifact.properties"
mapfile -t sidecar_descriptor < <(
  node - "$TMP_ROOT/artifact.properties" <<'NODE'
const fs = require("node:fs");
const lines = fs.readFileSync(process.argv[2], "utf8").trimEnd().split("\n");
const values = new Map();
for (const line of lines) {
  const separator = line.indexOf("=");
  if (separator < 1) throw new Error("malformed managed Runtime descriptor");
  const key = line.slice(0, separator);
  const value = line.slice(separator + 1);
  if (values.has(key)) throw new Error(`duplicate managed Runtime descriptor key: ${key}`);
  values.set(key, value);
}
if (
  values.size !== 4 ||
  values.get("schemaVersion") !== "1" ||
  values.get("resourceName") !== "managed-runtime/sidecar.zip" ||
  !/^[1-9][0-9]*$/u.test(values.get("byteSize") ?? "") ||
  !/^[0-9a-f]{64}$/u.test(values.get("sha256") ?? "")
) {
  throw new Error("managed Runtime descriptor is invalid");
}
process.stdout.write(`${values.get("byteSize")}\n${values.get("sha256")}\n`);
NODE
)
[[ "${#sidecar_descriptor[@]}" == 2 ]] || fail "managed Runtime descriptor is incomplete"
[[ "$(stat -c '%s' "$TMP_ROOT/sidecar.zip")" == "${sidecar_descriptor[0]}" ]] \
  || fail "embedded Runtime size does not match its descriptor"
[[ "$(sha256sum "$TMP_ROOT/sidecar.zip" | awk '{print $1}')" == "${sidecar_descriptor[1]}" ]] \
  || fail "embedded Runtime hash does not match its descriptor"
"$ROOT/scripts/verify-managed-runtime.sh" "$TMP_ROOT/sidecar.zip" >/dev/null
unzip -p "$TMP_ROOT/sidecar.zip" sidecar-manifest.json >"$TMP_ROOT/sidecar-manifest.json"
node - "$TMP_ROOT/sidecar-manifest.json" "$VERSION" <<'NODE'
const fs = require("node:fs");
const [manifestPath, version] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
if (manifest.runtimeVersion !== version || manifest.platform !== "linux-x86_64") {
  throw new Error("embedded Runtime identity does not match the release");
}
NODE

printf 'verify-release: version=%s assets=3 result=passed\n' "$VERSION"
