#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_INPUT="${1:-$ROOT/build/standalone-release}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
RUNTIME_VERSION="$("$NODE_COMMAND" -p "require('$ROOT/standalone-client/version.json').version")"
CLIENT_VERSION="${AGMA_STANDALONE_VERSION:-$RUNTIME_VERSION}"
NODE_VERSION="$("$NODE_COMMAND" -p "require('$ROOT/standalone-client/managed-runtime/node-distributions.json').nodeVersion")"
TARGETS=(
  '1.18.2:fabric:fabric-mc1182'
  '1.18.2:forge:forge-mc1182'
  '1.21.11:fabric:fabric-mc12111'
)
PLATFORMS=(linux-x86_64 windows-x86_64)
CHECKSUM_NAME="AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS"
SBOM_NAME="AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json"

fail() {
  printf 'package-standalone-release: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -le 1 ]] || fail "usage: [new-output-directory]"
[[ "$CLIENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "standalone client version is not a final semantic version: $CLIENT_VERSION"
[[ "$RUNTIME_VERSION" == "$CLIENT_VERSION" ]] \
  || fail "standalone client and Runtime versions must match for release"
for program in find realpath sha256sum sort unzip xargs; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required release program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"
OUTPUT="$(realpath -m -- "$OUTPUT_INPUT")"
[[ "$OUTPUT" == /* && ! -e "$OUTPUT" && ! -L "$OUTPUT" ]] \
  || fail "release output must be a new absolute directory"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-release-package.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/runtime"

for platform in "${PLATFORMS[@]}"; do
  "$ROOT/scripts/build-standalone-managed-runtime.sh" \
    "$platform" "$WORK/runtime/$platform"
done

"$ROOT/gradlew" --no-daemon --max-workers=1 --no-build-cache --rerun-tasks \
  -PstandaloneVersion="$CLIENT_VERSION" \
  :standalone-client:fabric-mc1182:remapJar \
  :standalone-client:forge-mc1182:remapJar \
  :standalone-client:fabric-mc12111:remapJar

declare -A BASE_JARS
for target in "${TARGETS[@]}"; do
  IFS=: read -r minecraft loader module <<<"$target"
  mapfile -t candidates < <(find "$ROOT/standalone-client/$module/build/libs" \
    -maxdepth 1 -type f \
    -name "AGMA-Standalone-Client-mc${minecraft}-${loader}-${CLIENT_VERSION}.jar" -print)
  [[ "${#candidates[@]}" -eq 1 ]] \
    || fail "expected one remapped base JAR for Minecraft $minecraft $loader"
  BASE_JARS["$minecraft:$loader"]="${candidates[0]}"
done

mkdir "$OUTPUT"
for platform in "${PLATFORMS[@]}"; do
  mapfile -t sidecars < <(find "$WORK/runtime/$platform" -maxdepth 1 -type f \
    -name "AGMA-Standalone-Runtime-${RUNTIME_VERSION}-${platform}.zip" -print)
  [[ "${#sidecars[@]}" -eq 1 ]] || fail "expected one managed Runtime for $platform"
  for target in "${TARGETS[@]}"; do
    IFS=: read -r minecraft loader _ <<<"$target"
    name="AGMA-Client-Standalone-${CLIENT_VERSION}-mc${minecraft}-${loader}-${platform}.jar"
    AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
      "$ROOT/scripts/package-standalone-client-jar.sh" \
      "${BASE_JARS["$minecraft:$loader"]}" "${sidecars[0]}" \
      "$minecraft" "$loader" "$platform" \
      "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION" "$OUTPUT/$name" >/dev/null
  done
done
"$NODE_COMMAND" "$ROOT/scripts/standalone-sbom.mjs" \
  write "$OUTPUT" "$CLIENT_VERSION" "$OUTPUT/$SBOM_NAME"
(
  cd "$OUTPUT"
  find . -maxdepth 1 -type f ! -name "$CHECKSUM_NAME" -printf '%P\n' \
    | sort \
    | xargs sha256sum >"$CHECKSUM_NAME"
)
AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
  AGMA_STANDALONE_EXECUTE_RUNTIME=1 \
  "$ROOT/scripts/verify-standalone-release.sh" \
  "$OUTPUT" "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION"
printf 'package-standalone-release version=%s assets=8 result=%s\n' \
  "$CLIENT_VERSION" "$OUTPUT"
