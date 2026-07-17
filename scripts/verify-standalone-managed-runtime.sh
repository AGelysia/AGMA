#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE="${1:-}"
PLATFORM="${2:-}"
RUNTIME_VERSION="${3:-}"
NODE_VERSION="${4:-}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"

fail() {
  printf 'verify-standalone-managed-runtime: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 4 ]] \
  || fail "usage: <sidecar.zip> <platform> <runtime-version> <node-version>"
for program in awk realpath sha256sum unzip wc zipinfo; do
  command -v "$program" >/dev/null 2>&1 || fail "required verification program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"

ARCHIVE="$(realpath -- "$ARCHIVE")"
[[ -f "$ARCHIVE" && ! -L "$ARCHIVE" && -s "$ARCHIVE" ]] \
  || fail "sidecar archive must be an ordinary non-empty file"
ARCHIVE_BYTES="$(wc -c <"$ARCHIVE")"
[[ "$ARCHIVE_BYTES" =~ ^[0-9]+$ && "$ARCHIVE_BYTES" -le 536870912 ]] \
  || fail "sidecar archive exceeds its byte limit"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-verify.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if ! zipinfo -l "$ARCHIVE" | awk '
  NR <= 2 { next }
  $1 ~ /^[0-9]+$/ && $2 ~ /^files?,$/ { footer = 1; next }
  {
    type = substr($1, 1, 1)
    if (type != "-" && type != "d") exit 1
    if ($4 !~ /^[0-9]+$/ || $4 > 268435456) exit 1
    expanded += $4
    if (expanded > 809500672) exit 1
    entries++
  }
  END { if (entries == 0 || footer != 1) exit 1 }
'; then
  fail "sidecar archive contains a non-file entry"
fi
unzip -Z1 "$ARCHIVE" >"$WORK/entries"
"$NODE_COMMAND" "$ROOT/scripts/standalone-sidecar.mjs" verify-entry-list "$WORK/entries"
mkdir "$WORK/payload"
unzip -q "$ARCHIVE" -d "$WORK/payload"
"$NODE_COMMAND" "$ROOT/scripts/standalone-sidecar.mjs" verify-payload \
  "$WORK/payload" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION"

printf 'verify-standalone-managed-runtime platform=%s sha256=%s result=passed\n' \
  "$PLATFORM" \
  "$(sha256sum "$ARCHIVE" | cut -d ' ' -f 1)"
