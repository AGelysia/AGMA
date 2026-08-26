#!/usr/bin/env bash
# Removes regenerable local build output. The release-check scripts refuse to
# overwrite an existing output directory, so repeated local release checks
# accumulate suffixed copies under build/ — this script reclaims that space.
# It never touches tracked files; verify with `git status` afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v git >/dev/null 2>&1 || { printf 'clean-local: git is required\n' >&2; exit 1; }

targets=(
  "$ROOT/build"
  "$ROOT/dist"
  "$ROOT/release"
  "$ROOT/.gradle"
  "$ROOT/logs"
)
for module in paper-plugin client-mod; do
  targets+=("$ROOT/$module/build")
done
for module in core runtime-supervisor-core fabric-common fabric-mc12111 fabric-mc1182 forge-mc1182; do
  targets+=("$ROOT/standalone-client/$module/build")
done

printf 'The following regenerable directories will be deleted:\n'
for target in "${targets[@]}"; do
  if [[ -e "$target" ]]; then
    du -sh "$target"
  fi
done
printf '\nContinue? [y/N] '
read -r answer
[[ "$answer" == y || "$answer" == Y ]] || { printf 'clean-local: aborted\n' >&2; exit 1; }

for target in "${targets[@]}"; do
  if [[ -e "$target" ]]; then
    rm -rf --one-file-system "$target"
    printf 'removed %s\n' "$target"
  fi
done
printf 'clean-local: done. Run ./scripts/test.sh or a release check to regenerate what you need.\n'
