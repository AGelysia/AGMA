#!/usr/bin/env bash
# Thin wrapper: the actual rules live in check-versions.mjs so that bash and
# PowerShell CI enforce exactly the same version alignment.
set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v node >/dev/null 2>&1 || {
  printf 'check-versions: node is required\n' >&2
  exit 1
}
exec node "$ROOT/scripts/check-versions.mjs"
