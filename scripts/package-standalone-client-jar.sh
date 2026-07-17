#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_JAR="${1:-}"
SIDECAR="${2:-}"
MINECRAFT_VERSION="${3:-}"
PLATFORM="${4:-}"
CLIENT_VERSION="${5:-}"
RUNTIME_VERSION="${6:-}"
NODE_VERSION="${7:-}"
OUTPUT="${8:-}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
SOURCE_DATE_EPOCH=315532800

fail() {
  printf 'package-standalone-client-jar: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 8 ]] \
  || fail "usage: <base.jar> <sidecar.zip> <minecraft-version> <platform> <client-version> <runtime-version> <node-version> <output.jar>"
for program in cp realpath touch unzip zip zipinfo; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required packaging program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"

BASE_JAR="$(realpath -- "$BASE_JAR")"
SIDECAR="$(realpath -- "$SIDECAR")"
OUTPUT="$(realpath -m -- "$OUTPUT")"
[[ -f "$BASE_JAR" && ! -L "$BASE_JAR" && -s "$BASE_JAR" ]] \
  || fail "base JAR must be an ordinary non-empty file"
[[ -f "$SIDECAR" && ! -L "$SIDECAR" && -s "$SIDECAR" ]] \
  || fail "sidecar must be an ordinary non-empty file"
[[ "$OUTPUT" == /* && "$OUTPUT" == *.jar && ! -e "$OUTPUT" && ! -L "$OUTPUT" ]] \
  || fail "output must be a new absolute JAR path"
OUTPUT_DIRECTORY="$(dirname "$OUTPUT")"
[[ -d "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" && -O "$OUTPUT_DIRECTORY" ]] \
  || fail "output directory must be an owner-controlled ordinary directory"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-client-package.XXXXXXXX")"
TEMP_JAR="$(mktemp "$OUTPUT_DIRECTORY/.standalone-client.XXXXXXXX.jar")"
cleanup() {
  rm -rf "$WORK"
  rm -f "$TEMP_JAR"
}
trap cleanup EXIT

unzip -tqq "$BASE_JAR" >/dev/null || fail "base JAR integrity check failed"
unzip -Z1 "$BASE_JAR" >"$WORK/base.entries"
"$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
  verify-entry-list "$WORK/base.entries" base
AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
  "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
  "$SIDECAR" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null

mkdir -p "$WORK/add/META-INF/agma-standalone"
cp "$SIDECAR" "$WORK/add/META-INF/agma-standalone/runtime.zip"
"$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" write-descriptor \
  "$WORK/add/META-INF/agma-standalone/runtime.zip" \
  "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION" \
  "$WORK/add/META-INF/agma-standalone/runtime-artifact.json"
find "$WORK/add" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +

cp "$BASE_JAR" "$TEMP_JAR"
(
  cd "$WORK/add"
  zip -X -q -0 "$TEMP_JAR" \
    META-INF/agma-standalone/runtime-artifact.json \
    META-INF/agma-standalone/runtime.zip
)
AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
  "$ROOT/scripts/verify-standalone-client-jar.sh" \
  "$TEMP_JAR" "$MINECRAFT_VERSION" "$PLATFORM" "$CLIENT_VERSION" \
  "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null
mv "$TEMP_JAR" "$OUTPUT"

printf 'package-standalone-client-jar minecraft=%s platform=%s result=%s\n' \
  "$MINECRAFT_VERSION" "$PLATFORM" "$OUTPUT"
