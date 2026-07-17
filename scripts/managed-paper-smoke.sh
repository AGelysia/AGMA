#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(node -p "require('$ROOT/agent-runtime/package.json').version")"
PAPER_BUILD=132
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-1.21.11-${PAPER_BUILD}.jar"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/agma-paper-smoke"
PAPER_JAR="${CACHE_ROOT}/paper-1.21.11-${PAPER_BUILD}.jar"
DEFAULT_PLUGIN_JAR="${ROOT}/paper-plugin/build/libs/AGMA-Server-Integrated-${VERSION}-mc1.21.11-linux-x86_64.jar"
PLUGIN_JAR="${MANAGED_PAPER_SMOKE_PLUGIN_JAR:-$DEFAULT_PLUGIN_JAR}"
JAVA_BIN="$(command -v "${JAVA_HOME:+${JAVA_HOME}/bin/}java")"
SYNTHETIC_API_KEY=agma-managed-smoke-api-key-0123456789abcdef
SYNTHETIC_MODEL=agma-managed-smoke-model
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/agma-managed-paper-smoke.XXXXXXXX")"
SERVER_ROOT="${WORK_ROOT}/server"
SERVER_LOG="${WORK_ROOT}/paper.log"
PROVIDER_LOG="${WORK_ROOT}/provider.log"
INPUT_FIFO="${WORK_ROOT}/console.input"
PAPER_PID=""
PROVIDER_PID=""
MANAGED_PID=""
MANAGED_SERVER_TOKEN=""
LAST_COMMAND_OUTPUT=""

fail() {
  printf 'managed-paper-smoke: %s\n' "$*" >&2
  exit 1
}

wait_with_timeout() {
  local process_id=$1
  local timeout_seconds=$2
  (
    sleep "$timeout_seconds"
    kill -TERM "$process_id" 2>/dev/null || exit 0
    sleep 5
    kill -KILL "$process_id" 2>/dev/null || true
  ) &
  local watchdog=$!
  local status=0
  wait "$process_id" || status=$?
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  return "$status"
}

process_matches_managed_runtime() {
  local process_id=$1
  local executable=$2
  local entrypoint=$3
  local arguments=()
  [[ -r "/proc/${process_id}/cmdline" ]] || return 1
  mapfile -d '' -t arguments <"/proc/${process_id}/cmdline" || true
  [[ "${arguments[0]:-}" == "$executable" && "${arguments[1]:-}" == "$entrypoint" ]]
}

terminate_if_running() {
  local process_id=$1
  if [[ -n "$process_id" ]] && kill -0 "$process_id" 2>/dev/null; then
    kill -TERM "$process_id" 2>/dev/null || true
    wait_with_timeout "$process_id" 10 2>/dev/null || true
  fi
}

terminate_managed_if_running() {
  local process_id=$1
  local executable=$2
  local entrypoint=$3
  process_matches_managed_runtime "$process_id" "$executable" "$entrypoint" || return 0
  kill -TERM "$process_id" 2>/dev/null || true
  local deadline=$((SECONDS + 5))
  while ((SECONDS < deadline)) \
    && process_matches_managed_runtime "$process_id" "$executable" "$entrypoint"; do
    sleep 1
  done
  if process_matches_managed_runtime "$process_id" "$executable" "$entrypoint"; then
    kill -KILL "$process_id" 2>/dev/null || true
  fi
}

cleanup() {
  terminate_if_running "$PAPER_PID"
  if [[ -n "$MANAGED_PID" ]]; then
    local managed_node="${SERVER_ROOT}/plugins/AGMA/managed/runtime/current/${VERSION}/bin/node"
    local managed_entrypoint="${SERVER_ROOT}/plugins/AGMA/managed/runtime/current/${VERSION}/app/dist/bootstrap/index.js"
    terminate_managed_if_running "$MANAGED_PID" "$managed_node" "$managed_entrypoint"
  fi
  terminate_if_running "$PROVIDER_PID"
  exec 9>&- 2>/dev/null || true
  exec 9<&- 2>/dev/null || true
  if [[ "${MANAGED_PAPER_SMOKE_KEEP_WORK:-0}" == 1 ]]; then
    printf 'managed-paper-smoke work-root=%s\n' "$WORK_ROOT" >&2
  else
    rm -rf -- "$WORK_ROOT"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_log() {
  local log_file=$1
  local pattern=$2
  local timeout_seconds=$3
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if [[ -f "$log_file" ]] && rg -q "$pattern" "$log_file"; then
      return 0
    fi
    sleep 1
  done
  printf 'Timed out waiting for %s in %s\n' "$pattern" "$log_file" >&2
  [[ -f "$log_file" ]] && tail -n 160 "$log_file" >&2
  return 1
}

run_console_command() {
  local command=$1
  local expected_pattern=$2
  local timeout_seconds=$3
  local previous_size
  previous_size=$(wc -c <"$SERVER_LOG")
  printf '%s\n' "$command" >&9

  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    LAST_COMMAND_OUTPUT=$(tail -c "+$((previous_size + 1))" "$SERVER_LOG")
    if rg -q "$expected_pattern" <<<"$LAST_COMMAND_OUTPUT"; then
      sleep 1
      LAST_COMMAND_OUTPUT=$(tail -c "+$((previous_size + 1))" "$SERVER_LOG")
      return 0
    fi
    sleep 1
  done
  printf 'Timed out waiting for command %s to produce %s\n' \
    "$command" "$expected_pattern" >&2
  tail -n 160 "$SERVER_LOG" >&2
  return 1
}

allocate_loopback_port() {
  node -e '
    const server = require("node:net").createServer();
    server.unref();
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (typeof address !== "object" || address === null) process.exit(1);
      process.stdout.write(String(address.port));
      server.close();
    });
  '
}

require_port() {
  local port=$1
  [[ "$port" =~ ^[0-9]{1,5}$ ]] \
    && ((10#$port >= 1024 && 10#$port <= 65535)) \
    || fail "could not allocate a loopback port"
}

write_server_properties() {
  local server_port=$1
  printf '%s\n' \
    'server-ip=127.0.0.1' \
    "server-port=$server_port" \
    'online-mode=false' \
    'enable-rcon=false' \
    'enable-query=false' \
    'broadcast-rcon-to-ops=false' \
    'max-players=1' \
    'view-distance=2' \
    'simulation-distance=2' \
    'spawn-protection=0' \
    'level-type=minecraft:flat' \
    'generate-structures=false' \
    'sync-chunk-writes=false' >"${SERVER_ROOT}/server.properties"
}

assert_loopback_listener() {
  local port=$1
  local description=$2
  local listeners
  listeners="$(ss -H -ltnp "sport = :$port")"
  if [[ "$(wc -l <<<"$listeners")" != 1 ]] \
    || ! rg -q "[[:space:]](127\\.0\\.0\\.1|\\[::ffff:127\\.0\\.0\\.1\\]):${port}[[:space:]]" \
      <<<"$listeners"; then
    printf '%s listener was not confined to 127.0.0.1:%s\n' "$description" "$port" >&2
    return 1
  fi
}

start_fake_provider() {
  local provider_port=$1
  PROVIDER_PORT="$provider_port" \
    PROVIDER_API_KEY="$SYNTHETIC_API_KEY" \
    PROVIDER_MODEL="$SYNTHETIC_MODEL" \
    node -e '
      const http = require("node:http");
      const port = Number(process.env.PROVIDER_PORT);
      const key = process.env.PROVIDER_API_KEY;
      const model = process.env.PROVIDER_MODEL;
      if (!Number.isInteger(port) || typeof key !== "string" || typeof model !== "string") {
        process.exit(2);
      }
      const server = http.createServer((request, response) => {
        if (
          request.method !== "GET" ||
          request.url !== "/v1/models" ||
          request.headers.authorization !== `Bearer ${key}`
        ) {
          response.writeHead(404, { "content-type": "application/json" });
          response.end("{}\n");
          return;
        }
        const body = JSON.stringify({ object: "list", data: [{ id: model, object: "model" }] });
        response.writeHead(200, {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        });
        response.end(body);
        process.stdout.write("FAKE_PROVIDER_MODELS_OK\n");
      });
      const stop = () => server.close(() => process.exit(0));
      process.once("SIGTERM", stop);
      process.once("SIGINT", stop);
      server.listen(port, "127.0.0.1", () => {
        process.stdout.write("FAKE_PROVIDER_READY\n");
      });
    ' >"$PROVIDER_LOG" 2>&1 &
  PROVIDER_PID=$!
  wait_for_log "$PROVIDER_LOG" '^FAKE_PROVIDER_READY$' 20
  assert_loopback_listener "$provider_port" "fake provider"
}

verify_private_regular_file() {
  local file=$1
  [[ -f "$file" && ! -L "$file" ]] || fail "private file is missing or unsafe: $file"
  [[ "$(stat -c '%a' "$file")" == 600 ]] || fail "private file is not mode 0600: $file"
  [[ "$(stat -c '%h' "$file")" == 1 ]] || fail "private file is hard linked: $file"
}

replace_private_line() {
  local file=$1
  local old_line=$2
  local new_line=$3
  verify_private_regular_file "$file"
  local directory
  directory="$(dirname "$file")"
  local temporary
  temporary="$(mktemp "${directory}/.smoke-config.XXXXXXXX")"
  chmod 600 "$temporary"
  awk -v old="$old_line" -v new="$new_line" '
    $0 == old { count += 1; print new; next }
    { print }
    END { if (count != 1) exit 42 }
  ' "$file" >"$temporary" || fail "expected exactly one managed endpoint in $file"
  mv -- "$temporary" "$file"
  verify_private_regular_file "$file"
}

configure_managed_provider() {
  local file=$1
  local provider_port=$2
  verify_private_regular_file "$file"
  local directory
  directory="$(dirname "$file")"
  local temporary
  temporary="$(mktemp "${directory}/.smoke-runtime-config.XXXXXXXX")"
  chmod 600 "$temporary"
  awk \
    -v base_url="http://127.0.0.1:${provider_port}/v1" \
    -v api_key="$SYNTHETIC_API_KEY" \
    -v model_name="$SYNTHETIC_MODEL" '
      $0 == "model:" {
        if (model_count != 0) exit 42
        model_count += 1
        in_model = 1
        print "model:"
        print "  provider: openai-compatible"
        print "  baseUrl: " base_url
        print "  apiKey: " api_key
        print "  model: " model_name
        print "  timeoutSeconds: 5"
        print "  inputMicroUsdPerMillionTokens: 1000000"
        print "  outputMicroUsdPerMillionTokens: 4000000"
        next
      }
      in_model && $0 == "storage:" {
        in_model = 0
        print
        next
      }
      !in_model { print }
      END { if (model_count != 1 || in_model) exit 42 }
    ' "$file" >"$temporary" || fail "managed provider block could not be replaced safely"
  mv -- "$temporary" "$file"
  verify_private_regular_file "$file"
  rg -q '^  id: \$\{AGMA_MANAGED_SERVER_ID\}$' "$file" \
    || fail "managed server id environment reference was not preserved"
  rg -q '^  port: \$\{AGMA_MANAGED_RUNTIME_PORT\}$' "$file" \
    || fail "managed Runtime port environment reference was not preserved"
}

find_managed_process() {
  local executable=$1
  local entrypoint=$2
  local timeout_seconds=$3
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    local matches=()
    local process_path
    for process_path in /proc/[0-9]*; do
      local process_id=${process_path##*/}
      if process_matches_managed_runtime "$process_id" "$executable" "$entrypoint"; then
        matches+=("$process_id")
      fi
    done
    if ((${#matches[@]} == 1)); then
      printf '%s\n' "${matches[0]}"
      return 0
    fi
    if ((${#matches[@]} > 1)); then
      fail "more than one managed Runtime process was found"
    fi
    sleep 1
  done
  fail "managed Runtime process was not found"
}

capture_managed_server_token() {
  local process_id=$1
  local entry
  while IFS= read -r -d '' entry; do
    if [[ "$entry" == AGMA_MANAGED_SERVER_TOKEN=* ]]; then
      MANAGED_SERVER_TOKEN=${entry#AGMA_MANAGED_SERVER_TOKEN=}
      break
    fi
  done <"/proc/${process_id}/environ"
  [[ "$MANAGED_SERVER_TOKEN" =~ ^[0-9a-f]{64}$ ]] \
    || fail "managed Runtime did not receive one generated server token"
}

assert_private_install_tree() {
  local install_root=$1
  [[ -d "$install_root" && ! -L "$install_root" ]] \
    || fail "managed Runtime install root is missing or unsafe"
  [[ -f "${install_root}/.installed.json" && ! -L "${install_root}/.installed.json" ]] \
    || fail "managed Runtime artifact marker is missing"
  [[ -f "${install_root}/sidecar-manifest.json" ]] \
    || fail "managed Runtime sidecar manifest is missing"

  local path
  for path in \
    "${SERVER_ROOT}/plugins/AGMA/managed" \
    "${SERVER_ROOT}/plugins/AGMA/managed/runtime" \
    "${SERVER_ROOT}/plugins/AGMA/managed/runtime/current"; do
    [[ -d "$path" && ! -L "$path" && "$(stat -c '%a' "$path")" == 700 ]] \
      || fail "managed Runtime parent directory is not mode 0700: $path"
  done
  while IFS= read -r -d '' path; do
    [[ "$(stat -c '%a' "$path")" == 700 ]] \
      || fail "managed Runtime directory is not mode 0700: $path"
  done < <(find "$install_root" -type d -print0)
  while IFS= read -r -d '' path; do
    local expected_mode=600
    [[ "$path" == "${install_root}/bin/node" ]] && expected_mode=700
    [[ "$(stat -c '%a' "$path")" == "$expected_mode" ]] \
      || fail "managed Runtime file has an unsafe mode: $path"
    [[ "$(stat -c '%h' "$path")" == 1 ]] \
      || fail "managed Runtime file is hard linked: $path"
  done < <(find "$install_root" -type f -print0)
  [[ -z "$(find "$install_root" ! -type d ! -type f -print -quit)" ]] \
    || fail "managed Runtime install tree contains a link or special file"
}

secret_in_file() {
  local secret=$1
  local file=$2
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *"$secret"* ]] && return 0
  done <"$file"
  return 1
}

assert_logs_redacted() {
  local log_file
  while IFS= read -r -d '' log_file; do
    if secret_in_file "$SYNTHETIC_API_KEY" "$log_file"; then
      fail "synthetic provider API key leaked into a log"
    fi
    if secret_in_file "$MANAGED_SERVER_TOKEN" "$log_file"; then
      fail "managed server token leaked into a log"
    fi
  done < <(find "$WORK_ROOT" -type f -name '*.log' -print0)
}

for program in awk curl find mv node rg sha256sum ss stat tail unzip wc; do
  command -v "$program" >/dev/null 2>&1 || fail "required program is unavailable: $program"
done
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "Java executable not found: $JAVA_BIN"

if [[ -z "${MANAGED_PAPER_SMOKE_PLUGIN_JAR:-}" ]]; then
  (
    cd "$ROOT"
    ./gradlew --no-daemon --max-workers=1 :paper-plugin:managedOfflineJar >/dev/null
  )
fi
[[ -f "$PLUGIN_JAR" && ! -L "$PLUGIN_JAR" ]] \
  || fail "managed offline plugin JAR is missing: $PLUGIN_JAR"
unzip -tqq "$PLUGIN_JAR" >/dev/null || fail "managed offline plugin JAR is corrupt"
unzip -l "$PLUGIN_JAR" | rg -q 'managed-runtime/sidecar\.zip$' \
  || fail "plugin JAR does not contain the managed Runtime sidecar"
unzip -l "$PLUGIN_JAR" | rg -q 'managed-runtime/artifact\.properties$' \
  || fail "plugin JAR does not contain managed Runtime integrity metadata"

[[ ! -e "$CACHE_ROOT" || -d "$CACHE_ROOT" && ! -L "$CACHE_ROOT" ]] \
  || fail "Paper smoke cache path must be a real directory"
mkdir -p "$CACHE_ROOT"
[[ ! -L "$PAPER_JAR" && ! -L "${PAPER_JAR}.tmp" ]] \
  || fail "Paper smoke cache files must not be symbolic links"
if [[ -f "$PAPER_JAR" ]] \
  && ! printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR" | sha256sum --check --status; then
  rm -f -- "$PAPER_JAR"
fi
if [[ ! -f "$PAPER_JAR" ]]; then
  curl --fail --location --retry 3 --output "${PAPER_JAR}.tmp" "$PAPER_URL"
  printf '%s  %s\n' "$PAPER_SHA256" "${PAPER_JAR}.tmp" | sha256sum --check --status
  mv -- "${PAPER_JAR}.tmp" "$PAPER_JAR"
fi
printf '%s  %s\n' "$PAPER_SHA256" "$PAPER_JAR" | sha256sum --check --status

PAPER_PORT="$(allocate_loopback_port)"
RUNTIME_PORT="$(allocate_loopback_port)"
PROVIDER_PORT="$(allocate_loopback_port)"
require_port "$PAPER_PORT"
require_port "$RUNTIME_PORT"
require_port "$PROVIDER_PORT"
[[ "$PAPER_PORT" != "$RUNTIME_PORT" \
  && "$PAPER_PORT" != "$PROVIDER_PORT" \
  && "$RUNTIME_PORT" != "$PROVIDER_PORT" ]] \
  || fail "loopback port allocation collided"

mkdir -p "${SERVER_ROOT}/plugins" "${SERVER_ROOT}/home"
cp -- "$PLUGIN_JAR" "${SERVER_ROOT}/plugins/"
printf 'eula=true\n' >"${SERVER_ROOT}/eula.txt"
write_server_properties "$PAPER_PORT"
start_fake_provider "$PROVIDER_PORT"

mkfifo "$INPUT_FIFO"
exec 9<>"$INPUT_FIFO"
(
  cd "$SERVER_ROOT"
  env -i \
    HOME="${SERVER_ROOT}/home" \
    LANG=C.UTF-8 \
    PATH="${SERVER_ROOT}/no-system-node" \
    "$JAVA_BIN" -Xms256M -Xmx512M -Dpaper.disableStartupVersionCheck=true \
    -jar "$PAPER_JAR" --nogui <"$INPUT_FIFO"
) >"$SERVER_LOG" 2>&1 &
PAPER_PID=$!

wait_for_log "$SERVER_LOG" 'Done \(' 180
wait_for_log "$SERVER_LOG" 'Starting Minecraft server on 127\.0\.0\.1:' 20
assert_loopback_listener "$PAPER_PORT" "Paper"
wait_for_log \
  "$SERVER_LOG" \
  'event=startup_failed stage=CONFIG code=MANAGED_RUNTIME_SETUP_REQUIRED' \
  30

run_console_command 'agma doctor' 'AGMA setup state: SETUP_REQUIRED$' 15
run_console_command 'agent' 'Unknown or incomplete command' 15
if rg -q 'AGMA status: ONLINE|event=startup_ready' <<<"$LAST_COMMAND_OUTPUT"; then
  fail "/agent was registered before managed Runtime setup completed"
fi

PAPER_CONFIG="${SERVER_ROOT}/plugins/AGMA/config.yml"
MANAGED_CONFIG="${SERVER_ROOT}/plugins/AGMA/managed/config.yml"
replace_private_line \
  "$PAPER_CONFIG" \
  '  url: ws://127.0.0.1:38127/agent' \
  "  url: ws://127.0.0.1:${RUNTIME_PORT}/agent"
configure_managed_provider "$MANAGED_CONFIG" "$PROVIDER_PORT"

run_console_command 'agma retry' 'AGMA retry started\.$' 15
wait_for_log "$PROVIDER_LOG" '^FAKE_PROVIDER_MODELS_OK$' 120
wait_for_log "$SERVER_LOG" 'event=startup_ready health=' 150
run_console_command 'agent' 'AGMA status: ONLINE$' 20

INSTALL_ROOT="${SERVER_ROOT}/plugins/AGMA/managed/runtime/current/${VERSION}"
MANAGED_NODE="${INSTALL_ROOT}/bin/node"
MANAGED_ENTRYPOINT="${INSTALL_ROOT}/app/dist/bootstrap/index.js"
assert_private_install_tree "$INSTALL_ROOT"
MANAGED_PID="$(find_managed_process "$MANAGED_NODE" "$MANAGED_ENTRYPOINT" 30)"
capture_managed_server_token "$MANAGED_PID"

printf 'stop\n' >&9
if ! wait_with_timeout "$PAPER_PID" 45; then
  fail "Paper did not stop cleanly"
fi
PAPER_PID=""
exec 9>&-
exec 9<&-

deadline=$((SECONDS + 15))
while ((SECONDS < deadline)) \
  && process_matches_managed_runtime "$MANAGED_PID" "$MANAGED_NODE" "$MANAGED_ENTRYPOINT"; do
  sleep 1
done
if process_matches_managed_runtime "$MANAGED_PID" "$MANAGED_NODE" "$MANAGED_ENTRYPOINT"; then
  fail "managed Runtime process survived Paper shutdown"
fi

if rg -q 'Error occurred while disabling AGMA|Exception.*AGMA' "$SERVER_LOG"; then
  fail "AGMA did not disable cleanly"
fi
assert_logs_redacted

printf 'managed-paper-smoke paper-build=%s paper-sha256=%s runtime=%s result=passed\n' \
  "$PAPER_BUILD" "$PAPER_SHA256" "$VERSION"
