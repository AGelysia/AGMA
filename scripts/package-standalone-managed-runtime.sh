#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAYLOAD="${1:-}"
PLATFORM="${2:-}"
RUNTIME_VERSION="${3:-}"
NODE_VERSION="${4:-}"
ARCHIVE="${5:-}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
SOURCE_DATE_EPOCH=315532800

fail() {
  printf 'package-standalone-managed-runtime: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 5 ]] \
  || fail "usage: <payload-dir> <platform> <runtime-version> <node-version> <output.zip>"
for program in find realpath sha256sum sort touch unzip zip; do
  command -v "$program" >/dev/null 2>&1 || fail "required build program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"

PAYLOAD="$(realpath -- "$PAYLOAD")"
ARCHIVE="$(realpath -m -- "$ARCHIVE")"
[[ -d "$PAYLOAD" && ! -L "$PAYLOAD" ]] || fail "payload must be an ordinary directory"
[[ "$ARCHIVE" == /* && "$ARCHIVE" == *.zip && ! -e "$ARCHIVE" && ! -L "$ARCHIVE" ]] \
  || fail "output must be a new absolute ZIP path"
OUTPUT_DIRECTORY="$(dirname "$ARCHIVE")"
[[ -d "$OUTPUT_DIRECTORY" && ! -L "$OUTPUT_DIRECTORY" ]] \
  || fail "output directory must already exist and must not be a link"
[[ -O "$OUTPUT_DIRECTORY" ]] || fail "output directory must be owned by the build user"

"$NODE_COMMAND" "$ROOT/scripts/standalone-sidecar.mjs" verify-distributions \
  "$ROOT/standalone-client/managed-runtime/node-distributions.json"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-package.XXXXXXXX")"
TEMP_ARCHIVE="$(mktemp "$OUTPUT_DIRECTORY/.standalone-sidecar.XXXXXXXX.zip")"
cleanup() {
  rm -rf "$WORK"
  rm -f "$TEMP_ARCHIVE"
}
trap cleanup EXIT

mkdir "$WORK/payload"
cp -a "$PAYLOAD/." "$WORK/payload/"
"$NODE_COMMAND" "$ROOT/scripts/standalone-sidecar.mjs" write-manifest \
  "$WORK/payload" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION"

find "$WORK/payload" -type d -exec chmod 0700 {} +
find "$WORK/payload" -type f -exec chmod 0600 {} +
case "$PLATFORM" in
  linux-x86_64) chmod 0700 "$WORK/payload/bin/node" ;;
  windows-x86_64) chmod 0700 "$WORK/payload/bin/node.exe" ;;
  *) fail "unsupported standalone platform: $PLATFORM" ;;
esac
find "$WORK/payload" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +

(
  cd "$WORK/payload"
  find . -mindepth 1 -print \
    | sed 's#^\./##' \
    | sort \
    | zip -X -q -@ - >"$TEMP_ARCHIVE"
)
AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
  "$TEMP_ARCHIVE" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null
mv "$TEMP_ARCHIVE" "$ARCHIVE"
sha256sum "$ARCHIVE"
