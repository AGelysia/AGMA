#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_INPUT="${1:-$ROOT/build/standalone-release}"

fail() {
  printf 'standalone-release-check: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -le 1 ]] || fail "usage: [new-output-directory]"
for program in cmp find git grep node realpath sort; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required release-check program is unavailable: $program"
done
if git -C "$ROOT" ls-files | grep -Eiq '(^|/)01-standalone-client-development-plan\.md$'; then
  fail "the standalone development plan must remain outside the code repository"
fi
OUTPUT="$(realpath -m -- "$OUTPUT_INPUT")"
[[ "$OUTPUT" == /* && ! -e "$OUTPUT" && ! -L "$OUTPUT" ]] \
  || fail "release output must be a new absolute directory"
OUTPUT_PARENT="$(dirname "$OUTPUT")"
if [[ ! -e "$OUTPUT_PARENT" && ! -L "$OUTPUT_PARENT" ]]; then
  [[ "$OUTPUT_PARENT" == "$ROOT/build" && -d "$ROOT" && ! -L "$ROOT" && -O "$ROOT" ]] \
    || fail "a missing release output parent is allowed only for the repository build directory"
  mkdir -m 0700 "$OUTPUT_PARENT"
fi
[[ -d "$OUTPUT_PARENT" && ! -L "$OUTPUT_PARENT" && -O "$OUTPUT_PARENT" ]] \
  || fail "release output parent must be an owner-controlled ordinary directory"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-release-check.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT
"$ROOT/scripts/package-standalone-release.sh" "$WORK/first" >/dev/null
"$ROOT/scripts/package-standalone-release.sh" "$WORK/second" >/dev/null

find "$WORK/first" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' \
  | sort >"$WORK/first.paths"
find "$WORK/second" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' \
  | sort >"$WORK/second.paths"
cmp "$WORK/first.paths" "$WORK/second.paths" \
  || fail "standalone release builds produced different file sets"
VERSION="$(node -p "require('$ROOT/standalone-client/version.json').version")"
printf '%s\n' \
  "AGMA-Client-Standalone-${VERSION}-mc1.18.2-fabric-linux-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-mc1.18.2-fabric-windows-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-mc1.18.2-forge-linux-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-mc1.18.2-forge-windows-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-mc1.21.11-fabric-linux-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-mc1.21.11-fabric-windows-x86_64.jar" \
  "AGMA-Client-Standalone-${VERSION}-SBOM.cdx.json" \
  "AGMA-Client-Standalone-${VERSION}-SHA256SUMS" \
  | sort >"$WORK/expected.paths"
cmp "$WORK/expected.paths" "$WORK/first.paths" \
  || fail "standalone release produced an unexpected eight-asset inventory"
while IFS= read -r path; do
  cmp "$WORK/first/$path" "$WORK/second/$path" \
    || fail "standalone release asset is not reproducible: $path"
done <"$WORK/first.paths"

mv "$WORK/first" "$OUTPUT"
printf 'standalone-release-check assets=8 jars=6 reproducible=yes result=%s\n' "$OUTPUT"
