#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/standalone-client/managed-runtime/fixtures/payload"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-sidecar-test.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT

node "$ROOT/scripts/standalone-sidecar.mjs" verify-distributions \
  "$ROOT/standalone-client/managed-runtime/node-distributions.json"

copy_fixture() {
  local target=$1
  cp -a "$FIXTURE/." "$target/"
  mkdir -p "$target/standalone-client/contracts"
  node "$ROOT/scripts/standalone-sidecar.mjs" copy-schemas \
    "$ROOT/standalone-client/contracts" "$target/standalone-client/contracts"
}

for platform in linux-x86_64 windows-x86_64; do
  for run in first second; do
    payload="$WORK/$platform-$run"
    mkdir "$payload"
    copy_fixture "$payload"
    mkdir "$payload/bin"
    if [[ "$platform" == linux-x86_64 ]]; then
      printf 'offline Linux Node fixture\n' >"$payload/bin/node"
    else
      printf 'offline Windows Node fixture\n' >"$payload/bin/node.exe"
    fi
    "$ROOT/scripts/package-standalone-managed-runtime.sh" \
      "$payload" "$platform" 0.2.0 22.23.1 "$WORK/$platform-$run.zip" >/dev/null
  done
  cmp "$WORK/$platform-first.zip" "$WORK/$platform-second.zip"
  "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
    "$WORK/$platform-first.zip" "$platform" 0.2.0 22.23.1 >/dev/null
done

malicious="$WORK/malicious"
mkdir "$malicious"
copy_fixture "$malicious"
mkdir -p "$malicious/bin" "$malicious/standalone-client/private"
printf 'offline Linux Node fixture\n' >"$malicious/bin/node"
printf 'must not ship\n' \
  >"$malicious/standalone-client/private/01-standalone-client-development-plan.md"
if "$ROOT/scripts/package-standalone-managed-runtime.sh" \
  "$malicious" linux-x86_64 0.2.0 22.23.1 "$WORK/malicious.zip" >/dev/null 2>&1; then
  printf 'forbidden development plan entered a standalone sidecar\n' >&2
  exit 1
fi

paper_payload="$WORK/paper-payload"
mkdir "$paper_payload"
copy_fixture "$paper_payload"
mkdir -p "$paper_payload/bin" "$paper_payload/app/dist/transport"
printf 'offline Linux Node fixture\n' >"$paper_payload/bin/node"
printf 'must not ship\n' >"$paper_payload/app/dist/transport/paper-handshake.js"
if "$ROOT/scripts/package-standalone-managed-runtime.sh" \
  "$paper_payload" linux-x86_64 0.2.0 22.23.1 "$WORK/paper.zip" >/dev/null 2>&1; then
  printf 'Paper Runtime payload entered a standalone sidecar\n' >&2
  exit 1
fi

paper_bundle="$WORK/paper-bundle"
mkdir "$paper_bundle"
copy_fixture "$paper_bundle"
mkdir "$paper_bundle/bin"
printf 'offline Linux Node fixture\n' >"$paper_bundle/bin/node"
printf 'const legacy = "PAPER_CONFIG_VERSION";\n' \
  >"$paper_bundle/app/dist/standalone/bootstrap/index.js"
if "$ROOT/scripts/package-standalone-managed-runtime.sh" \
  "$paper_bundle" linux-x86_64 0.2.0 22.23.1 "$WORK/paper-bundle.zip" \
  >/dev/null 2>&1; then
  printf 'Paper Runtime graph marker entered a standalone sidecar\n' >&2
  exit 1
fi

lock_drift="$WORK/lock-drift"
mkdir "$lock_drift"
copy_fixture "$lock_drift"
mkdir "$lock_drift/bin"
printf 'offline Linux Node fixture\n' >"$lock_drift/bin/node"
sed -i 's/"version": "1.0.0"/"version": "9.9.9"/' \
  "$lock_drift/app/node_modules/.package-lock.json"
if "$ROOT/scripts/package-standalone-managed-runtime.sh" \
  "$lock_drift" linux-x86_64 0.2.0 22.23.1 "$WORK/lock-drift.zip" >/dev/null 2>&1; then
  printf 'production dependency lock drift passed standalone verification\n' >&2
  exit 1
fi

cp "$WORK/linux-x86_64-first.zip" "$WORK/collision.zip"
mkdir -p "$WORK/collision/BIN"
printf 'duplicate case-folded path\n' >"$WORK/collision/BIN/NODE"
(
  cd "$WORK/collision"
  zip -q "$WORK/collision.zip" BIN/NODE
)
if "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
  "$WORK/collision.zip" linux-x86_64 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'case-colliding ZIP entry passed standalone verification\n' >&2
  exit 1
fi

cp "$WORK/linux-x86_64-first.zip" "$WORK/symlink.zip"
mkdir -p "$WORK/symlink-source/app/node_modules"
ln -s fixture-package "$WORK/symlink-source/app/node_modules/fixture-link"
(
  cd "$WORK/symlink-source"
  zip -q -y "$WORK/symlink.zip" app/node_modules/fixture-link
)
if "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
  "$WORK/symlink.zip" linux-x86_64 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'symbolic-link ZIP entry passed standalone verification\n' >&2
  exit 1
fi

printf 'test-standalone-managed-runtime platforms=2 reproducible=yes result=passed\n'
