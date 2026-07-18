#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_INPUT="${1:-}"
CLIENT_VERSION="${2:-}"
RUNTIME_VERSION="${3:-}"
NODE_VERSION="${4:-}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
EXECUTE_RUNTIME="${AGMA_STANDALONE_EXECUTE_RUNTIME:-0}"
TARGETS=(1.18.2:fabric 1.18.2:forge 1.21.11:fabric)
PLATFORMS=(linux-x86_64 windows-x86_64)
CHECKSUM_NAME="AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS"
SBOM_NAME="AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json"

fail() {
  printf 'verify-standalone-release: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 4 ]] \
  || fail "usage: <release-directory> <client-version> <runtime-version> <node-version>"
[[ "$EXECUTE_RUNTIME" == 0 || "$EXECUTE_RUNTIME" == 1 ]] \
  || fail "AGMA_STANDALONE_EXECUTE_RUNTIME must be 0 or 1"
for program in cmp find realpath sha256sum sort unzip; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required verification program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"
[[ -d "$RELEASE_INPUT" && ! -L "$RELEASE_INPUT" ]] \
  || fail "release directory is missing or unsafe"
RELEASE="$(realpath -- "$RELEASE_INPUT")"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-release-verify.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT
: >"$WORK/expected-assets"
for target in "${TARGETS[@]}"; do
  minecraft="${target%%:*}"
  loader="${target##*:}"
  for platform in "${PLATFORMS[@]}"; do
    name="AGMA-Client-Standalone-${CLIENT_VERSION}-mc${minecraft}-${loader}-${platform}.jar"
    printf '%s\n' "$name" >>"$WORK/expected-assets"
    [[ -f "$RELEASE/$name" && ! -L "$RELEASE/$name" && -s "$RELEASE/$name" ]] \
      || fail "release asset is missing or unsafe: $name"
    AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
      AGMA_STANDALONE_EXECUTE_RUNTIME="$EXECUTE_RUNTIME" \
      "$ROOT/scripts/verify-standalone-client-jar.sh" \
      "$RELEASE/$name" "$minecraft" "$loader" "$platform" "$CLIENT_VERSION" \
      "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null
    unzip -p "$RELEASE/$name" META-INF/agma-standalone/runtime.zip \
      >"$WORK/${minecraft}-${loader}-${platform}.runtime.zip"
  done
done
printf '%s\n' "$SBOM_NAME" >>"$WORK/expected-assets"
printf '%s\n' "$CHECKSUM_NAME" >>"$WORK/expected-assets"
sort -o "$WORK/expected-assets" "$WORK/expected-assets"
find "$RELEASE" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort >"$WORK/actual-assets"
cmp "$WORK/expected-assets" "$WORK/actual-assets" \
  || fail "standalone release does not contain the exact reviewed asset set"
[[ -z "$(find "$RELEASE" -type l -print -quit)" ]] \
  || fail "standalone release contains a symbolic link"
[[ -z "$(find "$RELEASE" ! -type d ! -type f -print -quit)" ]] \
  || fail "standalone release contains a special filesystem entry"
"$NODE_COMMAND" "$ROOT/scripts/standalone-sbom.mjs" \
  verify "$RELEASE" "$CLIENT_VERSION" "$RELEASE/$SBOM_NAME" \
  || fail "standalone CycloneDX SBOM verification failed"

: >"$WORK/manifest-paths"
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^([0-9a-f]{64})\ \ ([^/[:cntrl:]\\]+)$ ]] \
    || fail "standalone SHA256SUMS contains a malformed line"
  path="${BASH_REMATCH[2]}"
  [[ "$path" != "$CHECKSUM_NAME" && -f "$RELEASE/$path" && ! -L "$RELEASE/$path" ]] \
    || fail "standalone SHA256SUMS references an unsafe asset"
  printf '%s\n' "$path" >>"$WORK/manifest-paths"
done <"$RELEASE/$CHECKSUM_NAME"
sort -o "$WORK/manifest-paths" "$WORK/manifest-paths"
find "$RELEASE" -mindepth 1 -maxdepth 1 -type f ! -name "$CHECKSUM_NAME" -printf '%f\n' \
  | sort >"$WORK/actual-manifest-paths"
cmp "$WORK/manifest-paths" "$WORK/actual-manifest-paths" \
  || fail "standalone SHA256SUMS does not cover the exact release asset set"
(cd "$RELEASE" && sha256sum --check --strict --quiet "$CHECKSUM_NAME") \
  || fail "standalone release checksum verification failed"

for platform in "${PLATFORMS[@]}"; do
  cmp "$WORK/1.18.2-fabric-${platform}.runtime.zip" \
    "$WORK/1.18.2-forge-${platform}.runtime.zip" \
    || fail "the Fabric and Forge 1.18.2 builds do not embed one identical $platform sidecar"
  cmp "$WORK/1.18.2-fabric-${platform}.runtime.zip" \
    "$WORK/1.21.11-fabric-${platform}.runtime.zip" \
    || fail "the three release targets do not embed one identical $platform sidecar"
done
if cmp -s "$WORK/1.21.11-fabric-linux-x86_64.runtime.zip" \
  "$WORK/1.21.11-fabric-windows-x86_64.runtime.zip"; then
  fail "Linux and Windows releases unexpectedly embed the same sidecar"
fi

printf 'verify-standalone-release version=%s assets=8 result=passed\n' "$CLIENT_VERSION"
