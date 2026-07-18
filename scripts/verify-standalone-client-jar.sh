#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:-}"
MINECRAFT_VERSION="${2:-}"
LOADER="${3:-}"
PLATFORM="${4:-}"
CLIENT_VERSION="${5:-}"
RUNTIME_VERSION="${6:-}"
NODE_VERSION="${7:-}"
NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
EXECUTE_RUNTIME="${AGMA_STANDALONE_EXECUTE_RUNTIME:-0}"
DESCRIPTOR='META-INF/agma-standalone/runtime-artifact.json'
ARCHIVE='META-INF/agma-standalone/runtime.zip'

fail() {
  printf 'verify-standalone-client-jar: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 7 ]] \
  || fail "usage: <client.jar> <minecraft-version> <loader> <platform> <client-version> <runtime-version> <node-version>"
[[ "$EXECUTE_RUNTIME" == 0 || "$EXECUTE_RUNTIME" == 1 ]] \
  || fail "AGMA_STANDALONE_EXECUTE_RUNTIME must be 0 or 1"
for program in awk find grep realpath sha256sum unzip wc zipinfo; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required verification program is unavailable: $program"
done
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"

JAR="$(realpath -- "$JAR")"
[[ -f "$JAR" && ! -L "$JAR" && -s "$JAR" ]] \
  || fail "client JAR must be an ordinary non-empty file"
JAR_BYTES="$(wc -c <"$JAR")"
[[ "$JAR_BYTES" =~ ^[0-9]+$ && "$JAR_BYTES" -le 603979776 ]] \
  || fail "client JAR exceeds its byte limit"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-client-verify.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if ! zipinfo -l "$JAR" | awk '
  NR <= 2 { next }
  $1 ~ /^[0-9]+$/ && $2 ~ /^files?,$/ { footer = 1; next }
  {
    type = substr($1, 1, 1)
    if (type != "-" && type != "d") exit 1
    if ($4 !~ /^[0-9]+$/ || $4 > 536870912) exit 1
    expanded += $4
    if (expanded > 1073741824) exit 1
    entries++
  }
  END { if (entries == 0 || footer != 1) exit 1 }
'; then
  fail "client JAR contains a link, special entry, or excessive expansion"
fi

unzip -tqq "$JAR" >/dev/null || fail "client JAR integrity check failed"
unzip -Z1 "$JAR" >"$WORK/entries"
"$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
  verify-entry-list "$WORK/entries" integrated "$LOADER"

unzip -p "$JAR" "$DESCRIPTOR" >"$WORK/runtime-artifact.json"
unzip -p "$JAR" "$ARCHIVE" >"$WORK/runtime.zip"
"$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
  verify-descriptor "$WORK/runtime-artifact.json" "$WORK/runtime.zip" \
  "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION"
if [[ "$LOADER" == fabric ]]; then
  unzip -p "$JAR" fabric.mod.json >"$WORK/fabric.mod.json"
  "$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
    verify-fabric "$WORK/fabric.mod.json" "$CLIENT_VERSION" "$MINECRAFT_VERSION"
elif [[ "$LOADER" == forge ]]; then
  unzip -p "$JAR" META-INF/mods.toml >"$WORK/mods.toml"
  unzip -p "$JAR" META-INF/jarjar/metadata.json >"$WORK/jarjar-metadata.json"
  "$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
    verify-forge "$WORK/mods.toml" "$WORK/jarjar-metadata.json" \
    "$CLIENT_VERSION" "$MINECRAFT_VERSION"
  for nested in \
    "META-INF/jars/AGMA-Standalone-Client-Core-${CLIENT_VERSION}.jar" \
    "META-INF/jars/AGMA-Standalone-Fabric-Common-${CLIENT_VERSION}.jar" \
    "META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${CLIENT_VERSION}.jar"; do
    [[ "$(grep -Fxc "$nested" "$WORK/entries")" == 1 ]] \
      || fail "Forge JarJar path is missing or version-mismatched: $nested"
  done
  if unzip -p "$JAR" 'dev/minecraftagent/standalone/forge/*.class' \
    | grep -aEq 'net[/.]fabricmc|dev[/.]minecraftagent[/.]standalone[/.]fabric|dev[/.]emi|EmiPlugin'; then
    fail "Forge outer classes contain a Fabric or EMI reference"
  fi
else
  fail "unsupported standalone loader: $LOADER"
fi
AGMA_STANDALONE_BUILD_NODE="$NODE_COMMAND" \
  "$ROOT/scripts/verify-standalone-managed-runtime.sh" \
  "$WORK/runtime.zip" "$PLATFORM" "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null

if [[ "$EXECUTE_RUNTIME" == 1 && "$PLATFORM" == linux-x86_64 ]]; then
  mkdir "$WORK/runtime"
  unzip -q "$WORK/runtime.zip" -d "$WORK/runtime"
  [[ "$("$WORK/runtime/bin/node" --version)" == "v$NODE_VERSION" ]] \
    || fail "embedded Linux Node did not execute with the pinned version"
  if "$WORK/runtime/bin/node" \
    "$WORK/runtime/app/dist/standalone/bootstrap/index.js" --invalid \
    >"$WORK/runtime-smoke.output" 2>&1; then
    fail "embedded standalone Runtime accepted invalid launch arguments"
  fi
  grep -q 'CONFIG_PATH_INVALID' "$WORK/runtime-smoke.output" \
    || fail "embedded standalone Runtime bundle did not execute fail-closed"
fi

mkdir "$WORK/nested"
while IFS= read -r nested; do
  [[ "$nested" == META-INF/jars/*.jar ]] || continue
  file="${nested##*/}"
  unzip -p "$JAR" "$nested" >"$WORK/nested/$file"
  unzip -tqq "$WORK/nested/$file" >/dev/null \
    || fail "nested standalone JAR is corrupt: $file"
  unzip -Z1 "$WORK/nested/$file" >"$WORK/nested/$file.entries"
  "$NODE_COMMAND" "$ROOT/scripts/standalone-client-artifact.mjs" \
    verify-entry-list "$WORK/nested/$file.entries" nested "$LOADER"
  if [[ "$LOADER" == forge ]] \
    && unzip -p "$WORK/nested/$file" '*.class' \
      | grep -aEq 'net[/.]fabricmc|dev[/.]minecraftagent[/.]standalone[/.]fabric|dev[/.]emi|EmiPlugin'; then
    fail "Forge nested library contains a Fabric or EMI reference: $file"
  fi
done <"$WORK/entries"

mapfile -t core_jars < <(find "$WORK/nested" -maxdepth 1 -type f \
  -name 'AGMA-Standalone-Client-Core-*.jar' -print)
mapfile -t common_jars < <(find "$WORK/nested" -maxdepth 1 -type f \
  -name 'AGMA-Standalone-Fabric-Common-*.jar' -print)
mapfile -t supervisor_jars < <(find "$WORK/nested" -maxdepth 1 -type f \
  -name 'AGMA-Standalone-Runtime-Supervisor-Core-*.jar' -print)
[[ "${#core_jars[@]}" -eq 1 && "${#common_jars[@]}" -eq 1 \
  && "${#supervisor_jars[@]}" -eq 1 ]] \
  || fail "standalone nested libraries are missing or duplicated"
unzip -Z1 "${common_jars[0]}" >"$WORK/common.entries"
unzip -Z1 "${supervisor_jars[0]}" >"$WORK/supervisor.entries"
for required in \
  dev/minecraftagent/standalone/common/EmbeddedRuntimeDistribution.class \
  dev/minecraftagent/standalone/common/SystemRuntimeProcessLauncher.class; do
  [[ "$(grep -Fxc "$required" "$WORK/common.entries")" == 1 ]] \
    || fail "standalone client is missing $required"
done
[[ "$(grep -Fxc \
  'dev/minecraftagent/standalone/supervisor/install/ManagedRuntimeInstaller.class' \
  "$WORK/supervisor.entries")" == 1 ]] \
  || fail "standalone client is missing its verified Runtime installer"

printf 'verify-standalone-client-jar minecraft=%s loader=%s platform=%s sha256=%s result=passed\n' \
  "$MINECRAFT_VERSION" \
  "$LOADER" \
  "$PLATFORM" \
  "$(sha256sum "$JAR" | cut -d ' ' -f 1)"
