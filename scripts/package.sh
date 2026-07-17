#!/usr/bin/env bash
set -euo pipefail
umask 022
export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
RELEASE="$ROOT/release"
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
MINECRAFT_VERSION="1.21.11"
PLATFORM="linux-x86_64"
SKIP_TESTS="${AGMA_PACKAGE_SKIP_TESTS:-0}"
PACKAGE_NAME="AGMA-Server-Separated-${VERSION}-mc${MINECRAFT_VERSION}"
PACKAGE_ROOT="$DIST/$PACKAGE_NAME"

fail() {
  printf 'package: %s\n' "$*" >&2
  exit 1
}

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "Runtime package version is not a final release version: $VERSION"
[[ "$SKIP_TESTS" == 0 || "$SKIP_TESTS" == 1 ]] \
  || fail "AGMA_PACKAGE_SKIP_TESTS must be 0 or 1"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] \
  || fail "the integrated release can only be packaged on Linux x86_64"
grep -Fqx 'minecraft = "1.21.11"' "$ROOT/gradle/libs.versions.toml" \
  || fail "the locked Minecraft version is not $MINECRAFT_VERSION"

for program in find java node npm sha256sum sort touch unzip zip; do
  command -v "$program" >/dev/null 2>&1 \
    || fail "required program was not found: $program"
done

cd "$ROOT"
rm -rf "$ROOT/agent-runtime/dist" "$DIST" "$RELEASE"
"$ROOT/gradlew" --no-daemon --max-workers=1 --no-build-cache clean

if [[ "$SKIP_TESTS" == 0 ]]; then
  MINECRAFT_AGENT_NO_BUILD_CACHE=1 "$ROOT/scripts/test.sh"
else
  (
    cd "$ROOT/agent-runtime"
    npm ci --prefer-offline
    npm run build
  )
fi

"$ROOT/gradlew" \
  --no-daemon \
  --max-workers=1 \
  --no-build-cache \
  :paper-plugin:managedOfflineJar \
  :client-mod:remapJar

THIN_SOURCE="$ROOT/paper-plugin/build/libs/AGMA-Server-Plugin-${VERSION}.jar"
INTEGRATED_SOURCE="$ROOT/paper-plugin/build/libs/AGMA-Server-Integrated-${VERSION}-mc${MINECRAFT_VERSION}-${PLATFORM}.jar"
CLIENT_SOURCE="$ROOT/client-mod/build/libs/AGMA-Client-${VERSION}.jar"
for artifact in "$THIN_SOURCE" "$INTEGRATED_SOURCE" "$CLIENT_SOURCE"; do
  [[ -f "$artifact" && ! -L "$artifact" && -s "$artifact" ]] \
    || fail "expected build artifact is missing: $artifact"
done
[[ -f "$ROOT/agent-runtime/dist/bootstrap/index.js" ]] \
  || fail "Runtime build output is missing"

mkdir -p \
  "$PACKAGE_ROOT/runtime" \
  "$PACKAGE_ROOT/protocol" \
  "$PACKAGE_ROOT/capability-packs" \
  "$PACKAGE_ROOT/deploy" \
  "$RELEASE"

cp "$THIN_SOURCE" "$PACKAGE_ROOT/AGMA-Server-Plugin.jar"
cp -R "$ROOT/agent-runtime/dist" "$PACKAGE_ROOT/runtime/dist"
cp \
  "$ROOT/agent-runtime/package.json" \
  "$ROOT/agent-runtime/package-lock.json" \
  "$PACKAGE_ROOT/runtime/"
cp "$ROOT/agent-runtime/config.example.yml" "$PACKAGE_ROOT/runtime/config.example.yml"
cp -R "$ROOT/protocol/schemas" "$PACKAGE_ROOT/protocol/schemas"
cp -R "$ROOT/capability-packs/." "$PACKAGE_ROOT/capability-packs/"
cp -R "$ROOT/deploy/." "$PACKAGE_ROOT/deploy/"
cp "$ROOT/.env.example" "$PACKAGE_ROOT/runtime.env.example"
cp "$ROOT/packaging/server-separated/README.md" "$PACKAGE_ROOT/README.md"
cp "$ROOT/packaging/server-separated/start-runtime.sh" "$PACKAGE_ROOT/start-runtime.sh"
cp "$ROOT/packaging/server-separated/start-runtime.ps1" "$PACKAGE_ROOT/start-runtime.ps1"
cp "$ROOT/docs/operations.md" "$PACKAGE_ROOT/OPERATIONS.md"
cp \
  "$ROOT/LICENSE" \
  "$ROOT/SECURITY.md" \
  "$ROOT/CLIENT-COMPATIBILITY.md" \
  "$PACKAGE_ROOT/"

(
  cd "$PACKAGE_ROOT/runtime"
  npm ci \
    --omit=dev \
    --ignore-scripts \
    --no-audit \
    --no-fund \
    --prefer-offline
)
rm -rf "$PACKAGE_ROOT/runtime/node_modules/.bin"

link_path="$(find "$PACKAGE_ROOT" -type l -print -quit)"
[[ -z "$link_path" ]] || fail "symbolic links cannot be packaged: ${link_path#"$PACKAGE_ROOT"/}"
special_path="$(find "$PACKAGE_ROOT" ! -type d ! -type f -print -quit)"
[[ -z "$special_path" ]] || fail "special filesystem entries cannot be packaged"

find "$PACKAGE_ROOT" -type d -exec chmod 0755 {} +
find "$PACKAGE_ROOT" -type f -exec chmod 0644 {} +
chmod 0755 "$PACKAGE_ROOT/start-runtime.sh"
(
  cd "$PACKAGE_ROOT"
  find . -type f ! -name SHA256SUMS -print0 \
    | sort -z \
    | xargs -0 sha256sum >SHA256SUMS
)
find "$PACKAGE_ROOT" -exec touch -h -d '@315532800' {} +

INTEGRATED_RELEASE="$RELEASE/AGMA-Server-Integrated-${VERSION}-mc${MINECRAFT_VERSION}-${PLATFORM}.jar"
SEPARATED_RELEASE="$RELEASE/${PACKAGE_NAME}.zip"
CLIENT_RELEASE="$RELEASE/AGMA-Client-${VERSION}-mc${MINECRAFT_VERSION}-fabric.jar"
cp "$INTEGRATED_SOURCE" "$INTEGRATED_RELEASE"
cp "$CLIENT_SOURCE" "$CLIENT_RELEASE"
(
  cd "$DIST"
  find "$PACKAGE_NAME" -print \
    | sort \
    | zip -X -q "$SEPARATED_RELEASE" -@
)

(
  cd "$RELEASE"
  sha256sum \
    "$(basename "$CLIENT_RELEASE")" \
    "$(basename "$INTEGRATED_RELEASE")" \
    "$(basename "$SEPARATED_RELEASE")" >SHA256SUMS
)

"$ROOT/scripts/verify-release.sh" "$RELEASE"
printf 'package: version=%s assets=3 result=passed\n' "$VERSION"
