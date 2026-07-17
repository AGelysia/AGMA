#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${AGMA_RUNTIME_CONFIG:-$ROOT/runtime/config.yml}"

if (($# == 0)); then
  set -- --config "$CONFIG"
fi

if [[ ! -f "$CONFIG" && "$1" == --config && "${2:-}" == "$CONFIG" ]]; then
  printf 'Runtime configuration is missing: %s\n' "$CONFIG" >&2
  printf 'Create it from runtime/config.example.yml before starting AGMA.\n' >&2
  exit 1
fi

command -v node >/dev/null 2>&1 \
  || { printf 'Node.js 22.16.0 or newer, but lower than 23, is required.\n' >&2; exit 1; }
node -e '
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major !== 22 || minor < 16) process.exit(1);
' || { printf 'Node.js 22.16.0 or newer, but lower than 23, is required.\n' >&2; exit 1; }

exec node "$ROOT/runtime/dist/bootstrap/index.js" "$@"
