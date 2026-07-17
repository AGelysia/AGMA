#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${1:-}"
OUTPUT_DIRECTORY="${2:-}"
DISTRIBUTIONS="$ROOT/standalone-client/managed-runtime/node-distributions.json"
DISTRIBUTIONS_SHA256="4301d5710c7c179393c7320b82e57681faba87a98c737d8d6a55557b17933c6d"
CACHE="${AGMA_STANDALONE_MANAGED_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/agma/standalone-managed-runtime}"

fail() {
  printf 'build-standalone-managed-runtime: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 2 ]] || fail "usage: <linux-x86_64|windows-x86_64> <output-directory>"
for program in curl find realpath sha256sum sort tar touch unzip zip; do
  command -v "$program" >/dev/null 2>&1 || fail "required build program is unavailable: $program"
done
OUTPUT_DIRECTORY="$(realpath -m -- "$OUTPUT_DIRECTORY")"
[[ "$OUTPUT_DIRECTORY" == /* && ! -e "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" ]] \
  || fail "output must be a new absolute directory"
[[ ! -L "$CACHE" ]] || fail "cache must not be a symbolic link"
mkdir -p "$CACHE"
CACHE="$(realpath -- "$CACHE")"
[[ -d "$CACHE" && ! -L "$CACHE" && -O "$CACHE" ]] \
  || fail "cache must be an owner-controlled ordinary directory"
chmod 0700 "$CACHE"

printf '%s  %s\n' "$DISTRIBUTIONS_SHA256" "$DISTRIBUTIONS" \
  | sha256sum --check --strict --quiet \
  || fail "standalone Node distribution manifest differs from its reviewed bytes"

read_distribution() {
  case "$1" in
    linux-x86_64)
      printf '%s\n' \
        22.23.1 \
        node-v22.23.1-linux-x64.tar.xz \
        tar.xz \
        node-v22.23.1-linux-x64 \
        https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz \
        9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578
      ;;
    windows-x86_64)
      printf '%s\n' \
        22.23.1 \
        node-v22.23.1-win-x64.zip \
        zip \
        node-v22.23.1-win-x64 \
        https://nodejs.org/dist/v22.23.1/node-v22.23.1-win-x64.zip \
        7df0bc9375723f4a86b3aa1b7cc73342423d9677a8df4538aca31a049e309c29
      ;;
    *) fail "unsupported standalone platform: $1" ;;
  esac
}

fetch_archive() {
  local archive=$1
  local url=$2
  local expected=$3
  local cached="$CACHE/$archive"
  if [[ -e "$cached" ]] && { [[ ! -f "$cached" || -L "$cached" ]] \
    || ! printf '%s  %s\n' "$expected" "$cached" | sha256sum --check --strict --quiet; }; then
    rm -f "$cached"
  fi
  if [[ ! -f "$cached" ]]; then
    local download
    download="$(mktemp "$CACHE/.$archive.download.XXXXXXXX")"
    trap 'rm -f "${download:-}"' RETURN
    curl --fail --location --proto '=https' --retry 3 --retry-delay 1 --silent --show-error \
      --tlsv1.2 --output "$download" "$url"
    printf '%s  %s\n' "$expected" "$download" | sha256sum --check --strict --quiet \
      || fail "downloaded Node archive does not match the official SHA-256"
    chmod 0600 "$download"
    mv "$download" "$cached"
    trap - RETURN
  fi
  printf '%s\n' "$cached"
}

mapfile -t build_distribution < <(read_distribution linux-x86_64)
mapfile -t target_distribution < <(read_distribution "$PLATFORM")
[[ "${#build_distribution[@]}" -eq 6 && "${#target_distribution[@]}" -eq 6 ]] \
  || fail "Node distribution manifest returned incomplete data"
NODE_VERSION="${target_distribution[0]}"
[[ "$NODE_VERSION" == "${build_distribution[0]}" ]] || fail "build and target Node versions differ"
BUILD_ARCHIVE="$(fetch_archive "${build_distribution[1]}" "${build_distribution[4]}" "${build_distribution[5]}")"
TARGET_ARCHIVE="$(fetch_archive "${target_distribution[1]}" "${target_distribution[4]}" "${target_distribution[5]}")"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-build.XXXXXXXX")"
cleanup() {
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir "$WORK/build-node"
tar -xJf "$BUILD_ARCHIVE" -C "$WORK/build-node"
BUILD_NODE_ROOT="$WORK/build-node/${build_distribution[3]}"
BUILD_NODE="$BUILD_NODE_ROOT/bin/node"
NPM_CLI="$BUILD_NODE_ROOT/lib/node_modules/npm/bin/npm-cli.js"
[[ -f "$BUILD_NODE" && ! -L "$BUILD_NODE" && -x "$BUILD_NODE" ]] \
  || fail "pinned build Node executable is unavailable"
[[ "$($BUILD_NODE --version)" == "v$NODE_VERSION" ]] \
  || fail "pinned build Node reports the wrong version"
"$BUILD_NODE" "$ROOT/scripts/standalone-sidecar.mjs" verify-distributions "$DISTRIBUTIONS"

mkdir "$WORK/target-node"
if [[ "${target_distribution[2]}" == tar.xz ]]; then
  tar -xJf "$TARGET_ARCHIVE" -C "$WORK/target-node"
else
  unzip -q "$TARGET_ARCHIVE" -d "$WORK/target-node"
fi
TARGET_NODE_ROOT="$WORK/target-node/${target_distribution[3]}"
if [[ "$PLATFORM" == linux-x86_64 ]]; then
  TARGET_NODE="$TARGET_NODE_ROOT/bin/node"
else
  TARGET_NODE="$TARGET_NODE_ROOT/node.exe"
fi
[[ -f "$TARGET_NODE" && ! -L "$TARGET_NODE" ]] || fail "target Node executable is unavailable"
[[ -f "$TARGET_NODE_ROOT/LICENSE" && ! -L "$TARGET_NODE_ROOT/LICENSE" ]] \
  || fail "target Node license is unavailable"

RUNTIME_VERSION="$($BUILD_NODE -p "require(process.argv[1]).version" "$ROOT/standalone-client/version.json")"
BUILD_APP="$WORK/build-app"
mkdir "$BUILD_APP"
mkdir -p "$WORK/standalone-client/contracts"
cp "$ROOT/agent-runtime/package.json" "$ROOT/agent-runtime/package-lock.json" \
  "$ROOT/agent-runtime/tsconfig.json" "$BUILD_APP/"
cp -R "$ROOT/agent-runtime/src" "$BUILD_APP/src"
cp -R "$ROOT/agent-runtime/scripts" "$BUILD_APP/scripts"
cp "$ROOT/standalone-client/version.json" "$WORK/standalone-client/version.json"
cp "$ROOT/standalone-client/contracts/runtime-schema-allowlist.json" \
  "$WORK/standalone-client/contracts/runtime-schema-allowlist.json"
(
  cd "$BUILD_APP"
  PATH="$BUILD_NODE_ROOT/bin:$PATH" "$BUILD_NODE" "$NPM_CLI" ci \
    --ignore-scripts --no-audit --no-fund --cache "$CACHE/npm"
  PATH="$BUILD_NODE_ROOT/bin:$PATH" "$BUILD_NODE" "$NPM_CLI" exec -- \
    tsc -p tsconfig.json --noEmit
  PATH="$BUILD_NODE_ROOT/bin:$PATH" "$BUILD_NODE" "$NPM_CLI" run build:standalone --silent
)
[[ -f "$BUILD_APP/dist-standalone/bootstrap/index.js" ]] \
  || fail "Runtime build produced no standalone entrypoint"

PAYLOAD="$WORK/payload"
mkdir -p "$PAYLOAD/bin" "$PAYLOAD/app/dist/standalone/bootstrap" \
  "$PAYLOAD/standalone-client/contracts" "$PAYLOAD/licenses/node" "$PAYLOAD/licenses/npm"
if [[ "$PLATFORM" == linux-x86_64 ]]; then
  cp "$TARGET_NODE" "$PAYLOAD/bin/node"
else
  cp "$TARGET_NODE" "$PAYLOAD/bin/node.exe"
fi
cp "$BUILD_APP/dist-standalone/package.json" "$BUILD_APP/dist-standalone/package-lock.json" \
  "$PAYLOAD/app/"
cp "$BUILD_APP/dist-standalone/bootstrap/index.js" \
  "$PAYLOAD/app/dist/standalone/bootstrap/index.js"
(
  cd "$PAYLOAD/app"
  PATH="$BUILD_NODE_ROOT/bin:$PATH" "$BUILD_NODE" "$NPM_CLI" ci \
    --omit=dev --ignore-scripts --no-audit --no-fund --cache "$CACHE/npm"
)
rm -rf "$PAYLOAD/app/node_modules/.bin"
"$BUILD_NODE" "$ROOT/scripts/standalone-sidecar.mjs" copy-schemas \
  "$ROOT/standalone-client/contracts" "$PAYLOAD/standalone-client/contracts"
cp "$TARGET_NODE_ROOT/LICENSE" "$PAYLOAD/licenses/node/LICENSE"
"$BUILD_NODE" "$ROOT/scripts/standalone-sidecar.mjs" write-inventory \
  "$PAYLOAD/app" "$PAYLOAD/licenses"

mkdir "$OUTPUT_DIRECTORY"
ARCHIVE="$OUTPUT_DIRECTORY/AGMA-Standalone-Runtime-${RUNTIME_VERSION}-${PLATFORM}.zip"
AGMA_STANDALONE_BUILD_NODE="$BUILD_NODE" "$ROOT/scripts/package-standalone-managed-runtime.sh" \
  "$PAYLOAD" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION" "$ARCHIVE" >/dev/null
(
  cd "$OUTPUT_DIRECTORY"
  sha256sum "$(basename "$ARCHIVE")" >SHA256SUMS
)
printf 'build-standalone-managed-runtime platform=%s runtime=%s node=v%s result=passed\n' \
  "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION"
