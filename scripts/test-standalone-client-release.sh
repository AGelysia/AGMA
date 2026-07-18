#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_VERSION=0.3.0
RUNTIME_VERSION=0.3.0
NODE_VERSION=22.23.1
TARGETS=(1.18.2:fabric 1.18.2:forge 1.21.11:fabric)
PLATFORMS=(linux-x86_64 windows-x86_64)
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agma-standalone-client-release-test.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail() {
  printf 'test-standalone-client-release: %s\n' "$*" >&2
  exit 1
}

expect_client_rejection() {
  local label=$1
  local jar=$2
  local minecraft=$3
  local loader=$4
  if "$ROOT/scripts/verify-standalone-client-jar.sh" \
    "$jar" "$minecraft" "$loader" linux-x86_64 \
    "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null 2>&1; then
    fail "$label passed integrated standalone client verification"
  fi
}

for run in first second; do
  "$ROOT/scripts/create-standalone-verifier-fixtures.sh" \
    "$WORK/fixtures-$run" "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION" \
    >/dev/null
done
for target in "${TARGETS[@]}"; do
  minecraft="${target%%:*}"
  loader="${target##*:}"
  base="AGMA-Standalone-Client-mc${minecraft}-${loader}-${CLIENT_VERSION}.jar"
  cmp "$WORK/fixtures-first/$base" "$WORK/fixtures-second/$base"
done
for platform in "${PLATFORMS[@]}"; do
  runtime="AGMA-Standalone-Runtime-${RUNTIME_VERSION}-${platform}.zip"
  cmp "$WORK/fixtures-first/$runtime" "$WORK/fixtures-second/$runtime"
done

mkdir "$WORK/release" "$WORK/release-second"
for target in "${TARGETS[@]}"; do
  minecraft="${target%%:*}"
  loader="${target##*:}"
  base="AGMA-Standalone-Client-mc${minecraft}-${loader}-${CLIENT_VERSION}.jar"
  for platform in "${PLATFORMS[@]}"; do
    runtime="AGMA-Standalone-Runtime-${RUNTIME_VERSION}-${platform}.zip"
    name="AGMA-Client-Standalone-${CLIENT_VERSION}-mc${minecraft}-${loader}-${platform}.jar"
    for run in first second; do
      release=release
      [[ "$run" == first ]] || release=release-second
      "$ROOT/scripts/package-standalone-client-jar.sh" \
        "$WORK/fixtures-$run/$base" "$WORK/fixtures-$run/$runtime" \
        "$minecraft" "$loader" "$platform" "$CLIENT_VERSION" \
        "$RUNTIME_VERSION" "$NODE_VERSION" "$WORK/$release/$name" >/dev/null
    done
    cmp "$WORK/release/$name" "$WORK/release-second/$name"
  done
done

for release in release release-second; do
  node "$ROOT/scripts/standalone-sbom.mjs" \
    write "$WORK/$release" "$CLIENT_VERSION" \
    "$WORK/$release/AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json"
  (
    cd "$WORK/$release"
    find . -maxdepth 1 -type f \
      ! -name "AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS" \
      -printf '%P\n' \
      | sort \
      | xargs sha256sum >"AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS"
  )
done
cmp "$WORK/release/AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json" \
  "$WORK/release-second/AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json"
cmp "$WORK/release/AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS" \
  "$WORK/release-second/AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS"
"$ROOT/scripts/verify-standalone-release.sh" \
  "$WORK/release" "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION" >/dev/null

cp -a "$WORK/release" "$WORK/tampered-sbom"
node -e '
  const fs = require("node:fs");
  const path = process.argv[1];
  const bom = JSON.parse(fs.readFileSync(path, "utf8"));
  bom.metadata.component.name = "tampered";
  fs.writeFileSync(path, `${JSON.stringify(bom, null, 2)}\n`);
' "$WORK/tampered-sbom/AGMA-Client-Standalone-${CLIENT_VERSION}-SBOM.cdx.json"
(
  cd "$WORK/tampered-sbom"
  find . -maxdepth 1 -type f \
    ! -name "AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS" \
    -printf '%P\n' \
    | sort \
    | xargs sha256sum >"AGMA-Client-Standalone-${CLIENT_VERSION}-SHA256SUMS"
)
if "$ROOT/scripts/verify-standalone-release.sh" \
  "$WORK/tampered-sbom" "$CLIENT_VERSION" "$RUNTIME_VERSION" "$NODE_VERSION" \
  >/dev/null 2>&1; then
  fail "tampered standalone CycloneDX SBOM passed release verification"
fi

FABRIC_JAR="$WORK/release/AGMA-Client-Standalone-${CLIENT_VERSION}-mc1.21.11-fabric-linux-x86_64.jar"
FORGE_JAR="$WORK/release/AGMA-Client-Standalone-${CLIENT_VERSION}-mc1.18.2-forge-linux-x86_64.jar"
mkdir -p "$WORK/forbidden/docs"

plan_jar="$WORK/plan.jar"
cp "$FABRIC_JAR" "$plan_jar"
printf 'must not ship\n' >"$WORK/forbidden/docs/01-standalone-client-development-plan.md"
(
  cd "$WORK/forbidden"
  zip -q "$plan_jar" docs/01-standalone-client-development-plan.md
)
expect_client_rejection 'development plan payload' "$plan_jar" 1.21.11 fabric

server_jar="$WORK/server.jar"
cp "$FABRIC_JAR" "$server_jar"
printf 'name: AGMA\n' >"$WORK/forbidden/paper-plugin.yml"
(
  cd "$WORK/forbidden"
  zip -q "$server_jar" paper-plugin.yml
)
expect_client_rejection 'Paper payload' "$server_jar" 1.21.11 fabric

launcher_jar="$WORK/launcher.jar"
cp "$FABRIC_JAR" "$launcher_jar"
printf '#!/bin/sh\nnode app.js\n' >"$WORK/forbidden/start-runtime.sh"
(
  cd "$WORK/forbidden"
  zip -q "$launcher_jar" start-runtime.sh
)
expect_client_rejection 'system Node launch script' "$launcher_jar" 1.21.11 fabric

hash_jar="$WORK/hash.jar"
cp "$FABRIC_JAR" "$hash_jar"
mkdir -p "$WORK/hash-replacement/META-INF/agma-standalone"
printf 'not a sidecar\n' \
  >"$WORK/hash-replacement/META-INF/agma-standalone/runtime.zip"
(
  cd "$WORK/hash-replacement"
  zip -q -u "$hash_jar" META-INF/agma-standalone/runtime.zip
)
expect_client_rejection 'Runtime archive hash mismatch' "$hash_jar" 1.21.11 fabric

mixed_jar="$WORK/forge-with-fabric-metadata.jar"
cp "$FORGE_JAR" "$mixed_jar"
mkdir "$WORK/forge-mixed"
printf '{}\n' >"$WORK/forge-mixed/fabric.mod.json"
(
  cd "$WORK/forge-mixed"
  zip -q "$mixed_jar" fabric.mod.json
)
expect_client_rejection 'Fabric metadata in Forge JAR' "$mixed_jar" 1.18.2 forge

emi_jar="$WORK/forge-with-emi-reference.jar"
cp "$FORGE_JAR" "$emi_jar"
mkdir -p "$WORK/forge-emi/dev/minecraftagent/standalone/forge"
printf 'invalid dev/emi/EmiPlugin bytecode reference\n' \
  >"$WORK/forge-emi/dev/minecraftagent/standalone/forge/StandaloneForgeMod.class"
(
  cd "$WORK/forge-emi"
  zip -q -u "$emi_jar" dev/minecraftagent/standalone/forge/StandaloneForgeMod.class
)
expect_client_rejection 'EMI bytecode in Forge JAR' "$emi_jar" 1.18.2 forge

nested_fabric_jar="$WORK/forge-with-nested-fabric-reference.jar"
cp "$FORGE_JAR" "$nested_fabric_jar"
nested_common="META-INF/jars/AGMA-Standalone-Fabric-Common-${CLIENT_VERSION}.jar"
mkdir -p "$WORK/forge-nested/META-INF/jars" \
  "$WORK/forge-nested-injection/dev/minecraftagent/standalone/common"
unzip -p "$nested_fabric_jar" "$nested_common" \
  >"$WORK/forge-nested/$nested_common"
printf 'invalid net/fabricmc/api/ClientModInitializer bytecode reference\n' \
  >"$WORK/forge-nested-injection/dev/minecraftagent/standalone/common/Bad.class"
(
  cd "$WORK/forge-nested-injection"
  zip -q -u "$WORK/forge-nested/$nested_common" \
    dev/minecraftagent/standalone/common/Bad.class
)
(
  cd "$WORK/forge-nested"
  zip -q -u "$nested_fabric_jar" "$nested_common"
)
expect_client_rejection \
  'Fabric bytecode in Forge nested library' "$nested_fabric_jar" 1.18.2 forge

jarjar_jar="$WORK/forge-with-tampered-jarjar.jar"
cp "$FORGE_JAR" "$jarjar_jar"
mkdir -p "$WORK/forge-jarjar/META-INF/jarjar"
unzip -p "$jarjar_jar" META-INF/jarjar/metadata.json \
  >"$WORK/forge-jarjar/META-INF/jarjar/metadata.json"
node -e '
  const fs = require("node:fs");
  const path = process.argv[1];
  const value = JSON.parse(fs.readFileSync(path, "utf8"));
  value.jars[0].version.artifactVersion = "9.9.9";
  fs.writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
' "$WORK/forge-jarjar/META-INF/jarjar/metadata.json"
(
  cd "$WORK/forge-jarjar"
  zip -q -u "$jarjar_jar" META-INF/jarjar/metadata.json
)
expect_client_rejection 'tampered Forge JarJar metadata' "$jarjar_jar" 1.18.2 forge

toml_jar="$WORK/forge-with-server-metadata.jar"
cp "$FORGE_JAR" "$toml_jar"
mkdir -p "$WORK/forge-toml/META-INF"
unzip -p "$toml_jar" META-INF/mods.toml \
  | sed 's/clientSideOnly = true/clientSideOnly = false/' \
  >"$WORK/forge-toml/META-INF/mods.toml"
(
  cd "$WORK/forge-toml"
  zip -q -u "$toml_jar" META-INF/mods.toml
)
expect_client_rejection 'non-client-only Forge metadata' "$toml_jar" 1.18.2 forge

missing_jar="$WORK/forge-with-missing-nested-library.jar"
cp "$FORGE_JAR" "$missing_jar"
zip -q -d "$missing_jar" \
  "META-INF/jars/AGMA-Standalone-Client-Core-${CLIENT_VERSION}.jar"
expect_client_rejection 'missing Forge nested library' "$missing_jar" 1.18.2 forge

version_mismatch_jar="$WORK/forge-with-version-mismatched-library.jar"
cp "$FORGE_JAR" "$version_mismatch_jar"
expected_common="META-INF/jars/AGMA-Standalone-Fabric-Common-${CLIENT_VERSION}.jar"
mismatched_common='META-INF/jars/AGMA-Standalone-Fabric-Common-9.9.9.jar'
mkdir -p "$WORK/forge-version-mismatch/META-INF/jars"
unzip -p "$version_mismatch_jar" "$expected_common" \
  >"$WORK/forge-version-mismatch/$mismatched_common"
zip -q -d "$version_mismatch_jar" "$expected_common"
(
  cd "$WORK/forge-version-mismatch"
  zip -q "$version_mismatch_jar" "$mismatched_common"
)
expect_client_rejection \
  'version-mismatched Forge nested library' "$version_mismatch_jar" 1.18.2 forge

expect_client_rejection 'Forge JAR verified as Fabric' "$FORGE_JAR" 1.18.2 fabric

printf 'test-standalone-client-release assets=8 jars=6 reproducible=yes result=passed\n'
