#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/standalone-client/managed-runtime/fixtures/payload"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-client-release-test.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT

copy_fixture() {
  local target=$1
  cp -a "$FIXTURE/." "$target/"
  mkdir -p "$target/standalone-client/contracts"
  node "$ROOT/scripts/standalone-sidecar.mjs" copy-schemas \
    "$ROOT/standalone-client/contracts" "$target/standalone-client/contracts"
}

make_nested_jar() {
  local output=$1
  shift
  local root="$WORK/nested-$RANDOM"
  mkdir "$root"
  for path in "$@"; do
    mkdir -p "$root/$(dirname "$path")"
    printf 'offline class fixture\n' >"$root/$path"
  done
  find "$root" -exec touch -h -d '@315532800' {} +
  (
    cd "$root"
    find . -type f -printf '%P\n' | sort | zip -X -q "$output" -@
  )
}

make_base_jar() {
  local minecraft=$1
  local root="$WORK/base-$minecraft"
  local java_version='>=21'
  local extra_entrypoint=''
  [[ "$minecraft" == 1.21.11 ]] || {
    java_version='>=17'
    extra_entrypoint=',"emi":["dev.minecraftagent.standalone.fabric.viewer.emi.EmiCatalogPlugin"]'
  }
  mkdir -p "$root/META-INF/jars"
  cp "$WORK/core.jar" "$root/META-INF/jars/AGMA-Standalone-Client-Core-0.2.0.jar"
  cp "$WORK/common.jar" "$root/META-INF/jars/AGMA-Standalone-Fabric-Common-0.2.0.jar"
  cp "$WORK/supervisor.jar" "$root/META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-0.2.0.jar"
  printf '%s\n' \
    "{\"schemaVersion\":1,\"id\":\"agma_standalone\",\"version\":\"0.2.0\",\"name\":\"AGMA Standalone Client\",\"environment\":\"client\",\"entrypoints\":{\"client\":[\"dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint\"]${extra_entrypoint}},\"depends\":{\"fabricloader\":\">=0.19.3\",\"fabric-api\":\"fixture\",\"minecraft\":\"${minecraft}\",\"java\":\"${java_version}\"},\"jars\":[{\"file\":\"META-INF/jars/AGMA-Standalone-Client-Core-0.2.0.jar\"},{\"file\":\"META-INF/jars/AGMA-Standalone-Fabric-Common-0.2.0.jar\"},{\"file\":\"META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-0.2.0.jar\"}]}" \
    >"$root/fabric.mod.json"
  find "$root" -exec touch -h -d '@315532800' {} +
  (
    cd "$root"
    find . -type f -printf '%P\n' | sort | zip -X -q "$WORK/base-$minecraft.jar" -@
  )
}

make_nested_jar "$WORK/core.jar" \
  dev/minecraftagent/standalone/catalog/CatalogSnapshot.class
make_nested_jar "$WORK/common.jar" \
  dev/minecraftagent/standalone/common/EmbeddedRuntimeDistribution.class \
  dev/minecraftagent/standalone/common/SystemRuntimeProcessLauncher.class
make_nested_jar "$WORK/supervisor.jar" \
  dev/minecraftagent/standalone/supervisor/install/ManagedRuntimeInstaller.class
make_base_jar 1.18.2
make_base_jar 1.21.11

for platform in linux-x86_64 windows-x86_64; do
  for run in first second; do
    payload="$WORK/payload-$platform-$run"
    mkdir "$payload"
    copy_fixture "$payload"
    mkdir "$payload/bin"
    if [[ "$platform" == linux-x86_64 ]]; then
      printf 'offline Linux Node fixture\n' >"$payload/bin/node"
    else
      printf 'offline Windows Node fixture\n' >"$payload/bin/node.exe"
    fi
    "$ROOT/scripts/package-standalone-managed-runtime.sh" \
      "$payload" "$platform" 0.2.0 22.23.1 "$WORK/runtime-$platform-$run.zip" >/dev/null
  done
  cmp "$WORK/runtime-$platform-first.zip" "$WORK/runtime-$platform-second.zip"
done

mkdir "$WORK/release" "$WORK/release-second"
for minecraft in 1.18.2 1.21.11; do
  for platform in linux-x86_64 windows-x86_64; do
    name="AGMA-Client-Standalone-0.2.0-mc${minecraft}-fabric-${platform}.jar"
    for run in first second; do
      "$ROOT/scripts/package-standalone-client-jar.sh" \
        "$WORK/base-$minecraft.jar" "$WORK/runtime-$platform-first.zip" \
        "$minecraft" "$platform" 0.2.0 0.2.0 22.23.1 \
        "$WORK/$run-$name" >/dev/null
    done
    cmp "$WORK/first-$name" "$WORK/second-$name"
    cp "$WORK/first-$name" "$WORK/release/$name"
    cp "$WORK/second-$name" "$WORK/release-second/$name"
  done
done
for release in release release-second; do
  node "$ROOT/scripts/standalone-sbom.mjs" \
    write "$WORK/$release" 0.2.0 \
    "$WORK/$release/AGMA-Client-Standalone-0.2.0-SBOM.cdx.json"
  (
    cd "$WORK/$release"
    find . -maxdepth 1 -type f ! -name 'AGMA-Client-Standalone-0.2.0-SHA256SUMS' \
      -printf '%P\n' \
      | sort \
      | xargs sha256sum >AGMA-Client-Standalone-0.2.0-SHA256SUMS
  )
done
cmp "$WORK/release/AGMA-Client-Standalone-0.2.0-SBOM.cdx.json" \
  "$WORK/release-second/AGMA-Client-Standalone-0.2.0-SBOM.cdx.json"
cmp "$WORK/release/AGMA-Client-Standalone-0.2.0-SHA256SUMS" \
  "$WORK/release-second/AGMA-Client-Standalone-0.2.0-SHA256SUMS"
"$ROOT/scripts/verify-standalone-release.sh" \
  "$WORK/release" 0.2.0 0.2.0 22.23.1 >/dev/null

cp -a "$WORK/release" "$WORK/tampered-sbom"
node -e \
  'const fs=require("node:fs");const path=process.argv[1];const bom=JSON.parse(fs.readFileSync(path,"utf8"));bom.metadata.component.name="tampered";fs.writeFileSync(path,`${JSON.stringify(bom,null,2)}\n`);' \
  "$WORK/tampered-sbom/AGMA-Client-Standalone-0.2.0-SBOM.cdx.json"
(
  cd "$WORK/tampered-sbom"
  find . -maxdepth 1 -type f ! -name 'AGMA-Client-Standalone-0.2.0-SHA256SUMS' \
    -printf '%P\n' \
    | sort \
    | xargs sha256sum >AGMA-Client-Standalone-0.2.0-SHA256SUMS
)
if "$ROOT/scripts/verify-standalone-release.sh" \
  "$WORK/tampered-sbom" 0.2.0 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'tampered standalone CycloneDX SBOM passed release verification\n' >&2
  exit 1
fi

plan_jar="$WORK/plan.jar"
cp "$WORK/first-AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-linux-x86_64.jar" \
  "$plan_jar"
mkdir -p "$WORK/forbidden/docs"
printf 'must not ship\n' \
  >"$WORK/forbidden/docs/01-standalone-client-development-plan.md"
(
  cd "$WORK/forbidden"
  zip -q "$plan_jar" docs/01-standalone-client-development-plan.md
)
if "$ROOT/scripts/verify-standalone-client-jar.sh" \
  "$plan_jar" 1.21.11 linux-x86_64 0.2.0 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'development plan entered an integrated standalone client JAR\n' >&2
  exit 1
fi

server_jar="$WORK/server.jar"
cp "$WORK/first-AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-linux-x86_64.jar" \
  "$server_jar"
printf 'name: AGMA\n' >"$WORK/forbidden/paper-plugin.yml"
(
  cd "$WORK/forbidden"
  zip -q "$server_jar" paper-plugin.yml
)
if "$ROOT/scripts/verify-standalone-client-jar.sh" \
  "$server_jar" 1.21.11 linux-x86_64 0.2.0 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'Paper payload entered an integrated standalone client JAR\n' >&2
  exit 1
fi

launcher_jar="$WORK/launcher.jar"
cp "$WORK/first-AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-linux-x86_64.jar" \
  "$launcher_jar"
printf '#!/bin/sh\nnode app.js\n' >"$WORK/forbidden/start-runtime.sh"
(
  cd "$WORK/forbidden"
  zip -q "$launcher_jar" start-runtime.sh
)
if "$ROOT/scripts/verify-standalone-client-jar.sh" \
  "$launcher_jar" 1.21.11 linux-x86_64 0.2.0 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'system Node launch-script requirement entered a standalone client JAR\n' >&2
  exit 1
fi

hash_jar="$WORK/hash.jar"
cp "$WORK/first-AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-linux-x86_64.jar" \
  "$hash_jar"
mkdir -p "$WORK/hash-replacement/META-INF/agma-standalone"
printf 'not a sidecar\n' \
  >"$WORK/hash-replacement/META-INF/agma-standalone/runtime.zip"
(
  cd "$WORK/hash-replacement"
  zip -q -u "$hash_jar" META-INF/agma-standalone/runtime.zip
)
if "$ROOT/scripts/verify-standalone-client-jar.sh" \
  "$hash_jar" 1.21.11 linux-x86_64 0.2.0 0.2.0 22.23.1 >/dev/null 2>&1; then
  printf 'Runtime archive hash mismatch passed integrated JAR verification\n' >&2
  exit 1
fi

printf 'test-standalone-client-release assets=5 reproducible=yes result=passed\n'
