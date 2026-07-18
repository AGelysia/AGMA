#!/usr/bin/env bash
set -euo pipefail
umask 022
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-release-check.XXXXXXXX")"
TEST_RESULTS="$WORK/test-results"
DIRTY_OVERRIDE="${AGMA_ALLOW_DIRTY_RELEASE_CHECK:-}"
EVIDENCE_DIR="${AGMA_RELEASE_EVIDENCE_DIR:-}"
STARTED_CLEAN=1
EVIDENCE_ACTIVE=0

cleanup() {
  local status=$?
  if [[ "$EVIDENCE_ACTIVE" == 1 && "$status" != 0 && -d "$EVIDENCE_DIR" ]]; then
    printf 'release-check result=failed exit_code=%s\n' "$status" \
      >"$EVIDENCE_DIR/release-check.failed"
  fi
  rm -rf "$WORK"
  return "$status"
}
trap cleanup EXIT

fail() {
  printf 'release-check: %s\n' "$*" >&2
  exit 1
}

cd "$ROOT"
if [[ -n "$EVIDENCE_DIR" ]]; then
  [[ "$EVIDENCE_DIR" == /* && "$EVIDENCE_DIR" != / && "$EVIDENCE_DIR" != *$'\n'* ]] \
    || fail "evidence path must be a non-root absolute path"
  [[ "$(realpath -m -- "$EVIDENCE_DIR")" == "$EVIDENCE_DIR" \
    && "$EVIDENCE_DIR" != "$ROOT" \
    && "$EVIDENCE_DIR" != "$ROOT"/* ]] \
    || fail "evidence path must be canonical and outside the repository"
  [[ ! -e "$EVIDENCE_DIR" && ! -L "$EVIDENCE_DIR" ]] \
    || fail "evidence path must not already exist"
  mkdir -m 0700 "$EVIDENCE_DIR"
  EVIDENCE_ACTIVE=1
  TEST_RESULTS="$EVIDENCE_DIR/test-results"
fi

for program in cmp curl find git java node npm realpath rg sha256sum ss unzip xargs; do
  command -v "$program" >/dev/null 2>&1 || fail "required program was not found: $program"
done
JAVA_SPECIFICATION="$(java -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java\.specification\.version = //p' \
  | tail -n 1)"
[[ "$JAVA_SPECIFICATION" == 21 ]] || fail "Java 21 is required"
node -e '
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major !== 22 || minor < 16) process.exit(1);
' || fail "Node.js 22.16.0 or newer, but lower than 23, is required"
node - "$(npm --version)" <<'NODE' || fail "npm 10 or newer is required"
const major = Number(process.argv[2].split(".")[0]);
if (!Number.isSafeInteger(major) || major < 10) process.exit(1);
NODE

INITIAL_STATUS="$(git status --porcelain)" || fail "Git worktree status could not be read"
if [[ -n "$INITIAL_STATUS" ]]; then
  STARTED_CLEAN=0
  if [[ "$DIRTY_OVERRIDE" != I_UNDERSTAND_THIS_IS_NOT_A_RELEASE ]]; then
    fail "a clean Git worktree is required"
  fi
  printf 'release-check warning=dirty-development-only\n' >&2
fi
if [[ "$STARTED_CLEAN" == 0 && "$EVIDENCE_ACTIVE" == 1 ]]; then
  fail "dirty development checks cannot write release evidence"
fi

while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find "$ROOT/scripts" "$ROOT/packaging" -type f -name '*.sh' -print0 | sort -z)
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoLogo -NoProfile -File "$ROOT/scripts/check-powershell.ps1"
fi

mapfile -d '' JSON_FILES < <(
  rg --files -0 \
    -g '*.json' \
    -g '!**/build/**' \
    -g '!**/node_modules/**' \
    -g '!dist/**' \
    -g '!release/**' \
    | sort -z
)
node -e '
  const fs = require("node:fs");
  for (const file of process.argv.slice(1)) JSON.parse(fs.readFileSync(file, "utf8"));
' "${JSON_FILES[@]/#/$ROOT/}"

mkdir -p "$TEST_RESULTS"
MINECRAFT_AGENT_VITEST_JUNIT="$TEST_RESULTS/runtime.xml" \
  "$ROOT/scripts/package.sh"
cp -R "$ROOT/paper-plugin/build/test-results/test" "$TEST_RESULTS/paper"
cp -R "$ROOT/client-mod/build/test-results/test" "$TEST_RESULTS/client"
cp -R "$ROOT/standalone-client/core/build/test-results/test" "$TEST_RESULTS/standalone-core"
cp -R "$ROOT/standalone-client/runtime-supervisor-core/build/test-results/test" \
  "$TEST_RESULTS/standalone-supervisor"
cp -R "$ROOT/standalone-client/fabric-common/build/test-results/test" \
  "$TEST_RESULTS/standalone-fabric-common"
cp -R "$ROOT/standalone-client/fabric-mc12111/build/test-results/test" \
  "$TEST_RESULTS/standalone-fabric-mc12111"
cp -R "$ROOT/standalone-client/fabric-mc1182/build/test-results/test" \
  "$TEST_RESULTS/standalone-fabric-mc1182"
cp -R "$ROOT/standalone-client/forge-mc1182/build/test-results/test" \
  "$TEST_RESULTS/standalone-forge-mc1182"
java "$ROOT/scripts/VerifyTestResults.java" \
  "$TEST_RESULTS/runtime.xml" \
  "$TEST_RESULTS/paper" \
  "$TEST_RESULTS/client" \
  "$TEST_RESULTS/standalone-core" \
  "$TEST_RESULTS/standalone-supervisor" \
  "$TEST_RESULTS/standalone-fabric-common" \
  "$TEST_RESULTS/standalone-fabric-mc12111" \
  "$TEST_RESULTS/standalone-fabric-mc1182" \
  "$TEST_RESULTS/standalone-forge-mc1182" \
  | tee "$TEST_RESULTS/inventory.txt"
cp "$ROOT/release/SHA256SUMS" "$WORK/release.SHA256SUMS"
find "$ROOT/release" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
  | sort -z \
  | xargs -0 sha256sum >"$WORK/release.absolute.SHA256SUMS"

VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
SEPARATED_NAME="AGMA-Server-Separated-${VERSION}-mc1.21.11"
mkdir "$WORK/separated"
unzip -q "$ROOT/release/${SEPARATED_NAME}.zip" -d "$WORK/separated"
PACKAGED_RUNTIME="$WORK/separated/$SEPARATED_NAME/runtime"
BOOTSTRAP_OUTPUT="$WORK/bootstrap.output"
if node "$PACKAGED_RUNTIME/dist/bootstrap/index.js" --invalid \
  >"$BOOTSTRAP_OUTPUT" 2>&1; then
  fail "packaged Runtime bootstrap accepted invalid CLI arguments"
fi
grep -Fq '"code":"CONFIG_PATH_INVALID"' "$BOOTSTRAP_OUTPUT" \
  || fail "packaged Runtime bootstrap did not execute"
PROVIDER_OUTPUT="$WORK/provider.output"
if node "$PACKAGED_RUNTIME/dist/validation/live-provider-check.js" \
  >"$PROVIDER_OUTPUT" 2>&1; then
  fail "packaged provider validator accepted missing billing confirmation"
fi
printf '%s\n' \
  'confirmation=FAIL code=BILLING_CONFIRMATION_REQUIRED' \
  'result=FAIL code=BILLING_CONFIRMATION_REQUIRED' >"$WORK/provider.expected"
cmp "$WORK/provider.expected" "$PROVIDER_OUTPUT" \
  || fail "packaged provider validator returned an unexpected failure"

"$ROOT/scripts/paper-smoke.sh"
"$ROOT/scripts/managed-paper-smoke.sh"
(
  cd "$ROOT/agent-runtime"
  npm audit --audit-level=high
)

AGMA_PACKAGE_SKIP_TESTS=1 "$ROOT/scripts/package.sh"
cmp "$WORK/release.SHA256SUMS" "$ROOT/release/SHA256SUMS" \
  || fail "release checksum manifest is not reproducible"
find "$ROOT/release" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
  | sort -z \
  | xargs -0 sha256sum >"$WORK/release-second.absolute.SHA256SUMS"
sed "s#${ROOT}/release/#RELEASE/#" "$WORK/release.absolute.SHA256SUMS" \
  >"$WORK/release-first.normalized"
sed "s#${ROOT}/release/#RELEASE/#" "$WORK/release-second.absolute.SHA256SUMS" \
  >"$WORK/release-second.normalized"
cmp "$WORK/release-first.normalized" "$WORK/release-second.normalized" \
  || fail "release asset bytes are not reproducible"

git diff --check
FINAL_STATUS="$(git status --porcelain)" || fail "final Git worktree status could not be read"
if [[ "$STARTED_CLEAN" == 1 && -n "$FINAL_STATUS" ]]; then
  fail "release verification changed the Git worktree"
fi

if [[ "$EVIDENCE_ACTIVE" == 1 ]]; then
  {
    printf 'candidate.commit=%s\n' "$(git rev-parse HEAD)"
    printf 'candidate.version=%s\n' "$VERSION"
    cat "$ROOT/release/SHA256SUMS"
    printf 'release-check result=passed\n'
  } >"$EVIDENCE_DIR/release-check.passed"
fi

printf 'release-check result=passed\n'
cat "$ROOT/release/SHA256SUMS"
