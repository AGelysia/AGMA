#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_INPUT="${1:-}"
CLIENT_VERSION="${2:-}"
RUNTIME_VERSION="${3:-}"
NODE_VERSION="${4:-}"
PAYLOAD_FIXTURE="$ROOT/standalone-client/managed-runtime/fixtures/payload"
SOURCE_DATE_EPOCH=315532800

fail() {
  printf 'create-standalone-verifier-fixtures: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 4 ]] \
  || fail "usage: <new-output-directory> <client-version> <runtime-version> <node-version>"
[[ "$CLIENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ \
  && "$RUNTIME_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ \
  && "$NODE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "fixture versions must be final semantic versions"
for program in cp find mkdir realpath sort touch zip; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required fixture program is unavailable: $program"
done
OUTPUT="$(realpath -m -- "$OUTPUT_INPUT")"
[[ "$OUTPUT" == /* && ! -e "$OUTPUT" && ! -L "$OUTPUT" ]] \
  || fail "fixture output must be a new absolute directory"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-verifier-fixtures.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir "$OUTPUT"

make_jar() {
  local root=$1
  local output=$2
  find "$root" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +
  (
    cd "$root"
    find . -type f -printf '%P\n' | sort | zip -X -q "$output" -@
  )
}

make_nested_jar() {
  local name=$1
  shift
  local root="$WORK/nested-$name"
  mkdir "$root"
  for path in "$@"; do
    mkdir -p "$root/$(dirname "$path")"
    printf 'offline class fixture\n' >"$root/$path"
  done
  make_jar "$root" "$WORK/$name.jar"
}

add_nested_jars() {
  local root=$1
  mkdir -p "$root/META-INF/jars"
  cp "$WORK/core.jar" \
    "$root/META-INF/jars/AGMA-Standalone-Client-Core-${CLIENT_VERSION}.jar"
  cp "$WORK/common.jar" \
    "$root/META-INF/jars/AGMA-Standalone-Fabric-Common-${CLIENT_VERSION}.jar"
  cp "$WORK/supervisor.jar" \
    "$root/META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${CLIENT_VERSION}.jar"
}

make_fabric_base() {
  local minecraft=$1
  local root="$WORK/base-${minecraft}-fabric"
  local java_version='>=21'
  local extra_entrypoint=''
  [[ "$minecraft" == 1.21.11 ]] || {
    java_version='>=17'
    extra_entrypoint=',"emi":["dev.minecraftagent.standalone.fabric.viewer.emi.EmiCatalogPlugin"]'
  }
  add_nested_jars "$root"
  mkdir -p "$root/dev/minecraftagent/standalone/fabric"
  printf 'offline Fabric entrypoint fixture\n' \
    >"$root/dev/minecraftagent/standalone/fabric/StandaloneClientEntrypoint.class"
  printf '%s\n' \
    "{\"schemaVersion\":1,\"id\":\"agma_standalone\",\"version\":\"${CLIENT_VERSION}\",\"name\":\"AGMA Standalone Client\",\"environment\":\"client\",\"entrypoints\":{\"client\":[\"dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint\"]${extra_entrypoint}},\"depends\":{\"fabricloader\":\">=0.19.3\",\"fabric-api\":\"fixture\",\"minecraft\":\"${minecraft}\",\"java\":\"${java_version}\"},\"jars\":[{\"file\":\"META-INF/jars/AGMA-Standalone-Client-Core-${CLIENT_VERSION}.jar\"},{\"file\":\"META-INF/jars/AGMA-Standalone-Fabric-Common-${CLIENT_VERSION}.jar\"},{\"file\":\"META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${CLIENT_VERSION}.jar\"}]}" \
    >"$root/fabric.mod.json"
  make_jar "$root" \
    "$OUTPUT/AGMA-Standalone-Client-mc${minecraft}-fabric-${CLIENT_VERSION}.jar"
}

make_forge_base() {
  local root="$WORK/base-1.18.2-forge"
  add_nested_jars "$root"
  mkdir -p "$root/META-INF/jarjar" "$root/dev/minecraftagent/standalone/forge"
  printf 'offline Forge entrypoint fixture\n' \
    >"$root/dev/minecraftagent/standalone/forge/StandaloneForgeMod.class"
  printf '%s\n' \
    'modLoader = "javafml"' \
    'loaderVersion = "[40,)"' \
    'license = "Apache-2.0"' \
    'clientSideOnly = true' \
    'showAsResourcePack = false' \
    '' \
    '[[mods]]' \
    'modId = "agma_standalone"' \
    "version = \"${CLIENT_VERSION}\"" \
    'displayName = "AGMA Standalone Client"' \
    'displayTest = "IGNORE_ALL_VERSION"' \
    'authors = "AGMA contributors"' \
    "description = '''" \
    'Pure client AGMA shell with an authenticated local Runtime boundary.' \
    "'''" \
    '' \
    '[[dependencies.agma_standalone]]' \
    'modId = "forge"' \
    'mandatory = true' \
    'versionRange = "[40.2.21,41)"' \
    'ordering = "NONE"' \
    'side = "CLIENT"' \
    '' \
    '[[dependencies.agma_standalone]]' \
    'modId = "minecraft"' \
    'mandatory = true' \
    'versionRange = "[1.18.2,1.18.3)"' \
    'ordering = "NONE"' \
    'side = "CLIENT"' \
    >"$root/META-INF/mods.toml"
  "$NODE_COMMAND" -e '
    const fs = require("node:fs");
    const version = process.argv[1];
    const output = process.argv[2];
    const library = (artifact, path) => ({
      identifier: { group: "dev.minecraftagent", artifact },
      version: { range: `[${version},)`, artifactVersion: version },
      path,
    });
    const value = { jars: [
      library("core", `META-INF/jars/AGMA-Standalone-Client-Core-${version}.jar`),
      library("fabric-common", `META-INF/jars/AGMA-Standalone-Fabric-Common-${version}.jar`),
      library("runtime-supervisor-core", `META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-${version}.jar`),
    ] };
    fs.writeFileSync(output, `${JSON.stringify(value, null, 2)}\n`);
  ' "$CLIENT_VERSION" "$root/META-INF/jarjar/metadata.json"
  make_jar "$root" \
    "$OUTPUT/AGMA-Standalone-Client-mc1.18.2-forge-${CLIENT_VERSION}.jar"
}

make_runtime() {
  local platform=$1
  local payload="$WORK/payload-$platform"
  mkdir "$payload"
  cp -a "$PAYLOAD_FIXTURE/." "$payload/"
  mkdir -p "$payload/bin" "$payload/standalone-client/contracts"
  printf '%s\n' \
    '{' \
    '  "name": "agma-runtime",' \
    "  \"version\": \"${RUNTIME_VERSION}\"," \
    '  "private": true,' \
    '  "type": "module"' \
    '}' \
    >"$payload/app/package.json"
  "$NODE_COMMAND" "$ROOT/scripts/standalone-sidecar.mjs" copy-schemas \
    "$ROOT/standalone-client/contracts" "$payload/standalone-client/contracts"
  if [[ "$platform" == linux-x86_64 ]]; then
    printf 'offline Linux Node fixture\n' >"$payload/bin/node"
  else
    printf 'offline Windows Node fixture\n' >"$payload/bin/node.exe"
  fi
  "$ROOT/scripts/package-standalone-managed-runtime.sh" \
    "$payload" "$platform" "$RUNTIME_VERSION" "$NODE_VERSION" \
    "$OUTPUT/AGMA-Standalone-Runtime-${RUNTIME_VERSION}-${platform}.zip" >/dev/null
}

NODE_COMMAND="${AGMA_STANDALONE_BUILD_NODE:-node}"
command -v "$NODE_COMMAND" >/dev/null 2>&1 \
  || fail "the selected standalone build Node is unavailable"
make_nested_jar core dev/minecraftagent/standalone/catalog/CatalogSnapshot.class
make_nested_jar common \
  dev/minecraftagent/standalone/common/EmbeddedRuntimeDistribution.class \
  dev/minecraftagent/standalone/common/SystemRuntimeProcessLauncher.class
make_nested_jar supervisor \
  dev/minecraftagent/standalone/supervisor/install/ManagedRuntimeInstaller.class
make_fabric_base 1.18.2
make_forge_base
make_fabric_base 1.21.11
make_runtime linux-x86_64
make_runtime windows-x86_64

printf 'create-standalone-verifier-fixtures targets=3 platforms=2 result=%s\n' "$OUTPUT"
