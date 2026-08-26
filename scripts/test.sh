#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_ARGS=(--no-daemon --max-workers=1)
if [[ "${MINECRAFT_AGENT_NO_BUILD_CACHE:-0}" == 1 ]]; then
  GRADLE_ARGS+=(--no-build-cache)
fi

cd "$ROOT/agent-runtime"
npm ci --prefer-offline
npm run format:check
npm run lint
if [[ -n "${MINECRAFT_AGENT_VITEST_JUNIT:-}" ]]; then
  [[ "$MINECRAFT_AGENT_VITEST_JUNIT" == /* ]] \
    || { printf 'MINECRAFT_AGENT_VITEST_JUNIT must be an absolute path\n' >&2; exit 1; }
  mkdir -p "$(dirname "$MINECRAFT_AGENT_VITEST_JUNIT")"
  npm test -- --reporter=default --reporter=junit \
    --outputFile.junit="$MINECRAFT_AGENT_VITEST_JUNIT"
else
  npm test
fi
npm run build

cd "$ROOT"
./scripts/check-versions.sh
./scripts/test-standalone-managed-runtime.sh
./scripts/test-standalone-client-release.sh
# One Gradle invocation: multi-project configuration (Loom especially) is the
# dominant cost, so paying it once per module is what made CI slow.
./gradlew "${GRADLE_ARGS[@]}" \
  :protocol:jvm:build \
  :paper-plugin:build \
  :client-mod:build \
  :standalone-client:core:build \
  :standalone-client:runtime-supervisor-core:build \
  :standalone-client:fabric-common:build \
  :standalone-client:ui-common:build \
  :standalone-client:fabric-mc12111:build \
  :standalone-client:fabric-mc1182:build \
  :standalone-client:forge-mc1182:build
