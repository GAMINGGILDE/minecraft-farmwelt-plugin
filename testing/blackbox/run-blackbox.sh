#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

DOWNLOAD_TIMEOUT_SECONDS="${BLACKBOX_DOWNLOAD_TIMEOUT_SECONDS:-180}"
STARTUP_TIMEOUT_SECONDS="${BLACKBOX_STARTUP_TIMEOUT_SECONDS:-240}"
WORLD_TIMEOUT_SECONDS="${BLACKBOX_WORLD_TIMEOUT_SECONDS:-180}"
RESET_TIMEOUT_SECONDS="${BLACKBOX_RESET_TIMEOUT_SECONDS:-300}"
SHUTDOWN_TIMEOUT_SECONDS="${BLACKBOX_SHUTDOWN_TIMEOUT_SECONDS:-60}"
JAVA_XMS="${BLACKBOX_JAVA_XMS:-1G}"
JAVA_XMX="${BLACKBOX_JAVA_XMX:-2G}"
PYTHON_BIN="${BLACKBOX_PYTHON:-python3}"

OUTPUT_ROOT="${BLACKBOX_OUTPUT_DIR:-$REPO_ROOT/build/blackbox}"
mkdir -p -- "$OUTPUT_ROOT"
RUN_DIR="$(mktemp -d "$OUTPUT_ROOT/run-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
SERVER_DIR="$RUN_DIR/server"
ARTIFACT_DIR="$RUN_DIR/artifacts"
DOWNLOAD_DIR="$RUN_DIR/downloads"
HARNESS_LOG="$ARTIFACT_DIR/harness.log"
CONSOLE_LOG="$SERVER_DIR/console.log"
LATEST_LOG="$SERVER_DIR/logs/latest.log"
STATE_FILE="$SERVER_DIR/plugins/Farmwelt/reset-state.yml"
STATE_BEFORE="$ARTIFACT_DIR/reset-state-before.yml"
STATE_AFTER="$ARTIFACT_DIR/reset-state-after.yml"
WORLD_DIR="$SERVER_DIR/$MAIN_WORLD_NAME/dimensions/worlds/test_farmwelt"
WORLD_MARKER="$WORLD_DIR/region/.farmwelt-blackbox-marker"

mkdir -p -- "$SERVER_DIR/plugins/Farmwelt" "$ARTIFACT_DIR" "$DOWNLOAD_DIR"
exec > >(tee -a "$HARNESS_LOG") 2>&1

SERVER_PID=""
SERVER_STDIN_OPEN=0
SERVER_OUTPUT_OPEN=0
CONSOLE_READER_PID=""
SHUTDOWN_COMPLETE=0

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

server_is_running() {
  [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null
}

send_console() {
  local command="$1"
  [[ "$SERVER_STDIN_OPEN" == "1" ]] || fail "server console is not available for '$command'"
  server_is_running || fail "server exited before console command '$command'"
  echo "CONSOLE> $command"
  printf '%s\n' "$command" >&3 || fail "could not send console command '$command'"
}

wait_for_log_regex() {
  local regex="$1"
  local timeout_seconds="$2"
  local description="$3"
  local first_line="${4:-1}"
  local deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    if [[ -f "$LATEST_LOG" ]] && tail -n "+$first_line" "$LATEST_LOG" | grep -E -- "$regex" >/dev/null; then
      return 0
    fi
    server_is_running || fail "server exited while waiting for $description"
    sleep 1
  done
  fail "$description did not complete within ${timeout_seconds} seconds"
}

wait_for_path() {
  local path="$1"
  local timeout_seconds="$2"
  local description="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    [[ -d "$path" ]] && return 0
    server_is_running || fail "server exited while waiting for $description"
    sleep 1
  done
  fail "$description did not complete within ${timeout_seconds} seconds"
}

line_after_current_log() {
  if [[ -f "$LATEST_LOG" ]]; then
    echo $(( $(wc -l < "$LATEST_LOG") + 1 ))
  else
    echo 1
  fi
}

stop_server() {
  if [[ -z "$SERVER_PID" ]]; then
    SHUTDOWN_COMPLETE=1
    return 0
  fi

  if server_is_running; then
    echo "Stopping Folia through its console..."
    if [[ "$SERVER_STDIN_OPEN" == "1" ]]; then
      printf 'stop\n' >&3 || true
    fi
  fi

  local deadline=$((SECONDS + SHUTDOWN_TIMEOUT_SECONDS))
  while server_is_running && (( SECONDS < deadline )); do
    sleep 1
  done

  if server_is_running; then
    echo "FAIL: Folia did not stop within ${SHUTDOWN_TIMEOUT_SECONDS} seconds; terminating it for cleanup" >&2
    kill "$SERVER_PID" 2>/dev/null || true
    local kill_deadline=$((SECONDS + 10))
    while server_is_running && (( SECONDS < kill_deadline )); do
      sleep 1
    done
    if server_is_running; then
      kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
    if [[ "$SERVER_STDIN_OPEN" == "1" ]]; then
      exec 3>&- || true
      SERVER_STDIN_OPEN=0
    fi
    if [[ -n "$CONSOLE_READER_PID" ]]; then
      wait "$CONSOLE_READER_PID" 2>/dev/null || true
      CONSOLE_READER_PID=""
    fi
    if [[ "$SERVER_OUTPUT_OPEN" == "1" ]]; then
      exec 4<&- || true
      SERVER_OUTPUT_OPEN=0
    fi
    return 1
  fi

  set +e
  wait "$SERVER_PID"
  local exit_code=$?
  set -e
  SERVER_PID=""
  if [[ "$SERVER_STDIN_OPEN" == "1" ]]; then
    exec 3>&-
    SERVER_STDIN_OPEN=0
  fi
  if [[ -n "$CONSOLE_READER_PID" ]]; then
    set +e
    wait "$CONSOLE_READER_PID"
    local reader_exit_code=$?
    set -e
    CONSOLE_READER_PID=""
    [[ "$reader_exit_code" == "0" ]] || {
      echo "FAIL: console log reader exited with code $reader_exit_code" >&2
      return 1
    }
  fi
  if [[ "$SERVER_OUTPUT_OPEN" == "1" ]]; then
    exec 4<&-
    SERVER_OUTPUT_OPEN=0
  fi
  [[ "$exit_code" == "0" ]] || {
    echo "FAIL: Folia exited with code $exit_code" >&2
    return 1
  }
  SHUTDOWN_COMPLETE=1
}

copy_if_present() {
  local source="$1"
  local destination="$2"
  if [[ -e "$source" ]]; then
    cp -a -- "$source" "$destination"
  fi
}

collect_artifacts() {
  mkdir -p -- "$ARTIFACT_DIR"
  copy_if_present "$CONSOLE_LOG" "$ARTIFACT_DIR/server-console.log"
  copy_if_present "$LATEST_LOG" "$ARTIFACT_DIR/latest.log"
  copy_if_present "$SERVER_DIR/plugins/Farmwelt/config.yml" "$ARTIFACT_DIR/farmwelt-config.yml"
  if [[ -f "$STATE_FILE" && ! -f "$STATE_AFTER" ]]; then
    cp -- "$STATE_FILE" "$STATE_AFTER"
  fi
  if [[ -d "$SERVER_DIR/plugins/Worlds" ]]; then
    mkdir -p -- "$ARTIFACT_DIR/worlds-data"
    cp -a -- "$SERVER_DIR/plugins/Worlds/." "$ARTIFACT_DIR/worlds-data/"
  fi
  copy_if_present "$SCRIPT_DIR/versions.env" "$ARTIFACT_DIR/versions.env"
}

on_exit() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "$SERVER_PID" ]]; then
    stop_server || exit_code=1
  fi
  collect_artifacts
  echo "BLACKBOX_ARTIFACT_DIR=$ARTIFACT_DIR"
  if [[ "$exit_code" == "0" && "$SHUTDOWN_COMPLETE" == "1" ]]; then
    echo "PASS: Folia/Worlds black-box smoke test completed"
  else
    echo "FAIL: Folia/Worlds black-box smoke test failed; artifacts were preserved" >&2
  fi
  exit "$exit_code"
}

trap on_exit EXIT
trap 'exit 130' INT TERM

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command '$1' is not installed"
}

download_verified() {
  local url="$1"
  local destination="$2"
  local algorithm="$3"
  local expected="$4"

  echo "Downloading $(basename -- "$destination")..."
  if ! curl --fail --location --silent --show-error \
    --connect-timeout 15 \
    --max-time "$DOWNLOAD_TIMEOUT_SECONDS" \
    --retry 3 \
    --retry-max-time "$DOWNLOAD_TIMEOUT_SECONDS" \
    --retry-delay 2 \
    --retry-all-errors \
    --user-agent 'minecraft-farmwelt-plugin-blackbox/1.0 (https://github.com/GAMINGGILDE/minecraft-farmwelt-plugin)' \
    --output "$destination" \
    "$url"; then
    fail "download failed or timed out: $url"
  fi

  if ! printf '%s  %s\n' "$expected" "$destination" | "${algorithm}sum" --check --status; then
    fail "${algorithm} checksum mismatch for $(basename -- "$destination")"
  fi
}

for command in curl find grep java jar readlink sed sha256sum sha512sum tail tee "$PYTHON_BIN"; do
  require_command "$command"
done

java_line="$(java -version 2>&1 | head -n 1)"
java_major="$(printf '%s\n' "$java_line" | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "$java_major" == "$JAVA_VERSION" ]] \
  || fail "Java $JAVA_VERSION is required, found: $java_line"
grep -Fq -- "JavaLanguageVersion.of($JAVA_VERSION)" "$REPO_ROOT/build.gradle.kts" \
  || fail "versions.env Java pin no longer matches build.gradle.kts"
grep -Fq -- "paper-api:$FOLIA_VERSION.build." "$REPO_ROOT/build.gradle.kts" \
  || fail "versions.env Folia/Minecraft pin no longer matches build.gradle.kts"
grep -Fq -- "worlds:$WORLDS_VERSION" "$REPO_ROOT/build.gradle.kts" \
  || fail "versions.env Worlds pin no longer matches build.gradle.kts"

if [[ -n "${BLACKBOX_PLUGIN_JAR:-}" ]]; then
  PLUGIN_JAR="$(readlink -f -- "$BLACKBOX_PLUGIN_JAR")"
else
  mapfile -t plugin_jars < <(find "$REPO_ROOT/build/libs" -maxdepth 1 -type f \
    -name 'Farmwelt-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print)
  [[ "${#plugin_jars[@]}" == "1" ]] \
    || fail "expected exactly one current Farmwelt JAR under build/libs; run './gradlew clean test' and './gradlew build' first"
  PLUGIN_JAR="$(readlink -f -- "${plugin_jars[0]}")"
fi

[[ -f "$PLUGIN_JAR" ]] || fail "Farmwelt JAR does not exist: $PLUGIN_JAR"
jar tf "$PLUGIN_JAR" | grep -Fx 'paper-plugin.yml' >/dev/null \
  || fail "selected Farmwelt JAR has no paper-plugin.yml: $PLUGIN_JAR"

FOLIA_JAR="$DOWNLOAD_DIR/folia-$FOLIA_VERSION-$FOLIA_BUILD.jar"
WORLDS_JAR="$DOWNLOAD_DIR/worlds-$WORLDS_VERSION-all.jar"
download_verified "$FOLIA_URL" "$FOLIA_JAR" sha256 "$FOLIA_SHA256"
download_verified "$WORLDS_URL" "$WORLDS_JAR" sha512 "$WORLDS_SHA512"

cp -- "$FOLIA_JAR" "$SERVER_DIR/folia.jar"
cp -- "$WORLDS_JAR" "$SERVER_DIR/plugins/Worlds.jar"
cp -- "$PLUGIN_JAR" "$SERVER_DIR/plugins/Farmwelt.jar"
cp -- "$SCRIPT_DIR/fixtures/config.yml" "$SERVER_DIR/plugins/Farmwelt/config.yml"

printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
cat > "$SERVER_DIR/server.properties" <<EOF
allow-flight=true
enable-command-block=false
enable-query=false
enable-rcon=false
enforce-secure-profile=false
level-name=$MAIN_WORLD_NAME
max-players=1
motd=Farmwelt Black-Box Smoke Test
online-mode=false
server-ip=127.0.0.1
server-port=25565
simulation-distance=2
spawn-protection=0
sync-chunk-writes=false
view-distance=2
EOF

{
  echo "started-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git-commit=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "java=$java_line"
  echo "folia=$FOLIA_VERSION build $FOLIA_BUILD"
  echo "worlds=$WORLDS_VERSION"
  echo "farmwelt-jar=$(basename -- "$PLUGIN_JAR")"
  echo "farmwelt-sha256=$(sha256sum "$PLUGIN_JAR" | awk '{print $1}')"
} > "$ARTIFACT_DIR/run-metadata.txt"

echo "Starting isolated Folia $FOLIA_VERSION build $FOLIA_BUILD with Worlds $WORLDS_VERSION..."
coproc FOLIA_PROCESS {
  cd -- "$SERVER_DIR"
  exec java -Xms"$JAVA_XMS" -Xmx"$JAVA_XMX" -jar folia.jar --nogui --nojline 2>&1
}
SERVER_PID="$FOLIA_PROCESS_PID"
exec 3>&${FOLIA_PROCESS[1]}
SERVER_STDIN_OPEN=1
exec 4<&${FOLIA_PROCESS[0]}
SERVER_OUTPUT_OPEN=1
tee "$CONSOLE_LOG" <&4 >/dev/null &
CONSOLE_READER_PID=$!

wait_for_log_regex 'Done \(' "$STARTUP_TIMEOUT_SECONDS" "Folia startup"

grep -Fq -- "Worlds $WORLDS_VERSION erkannt." "$LATEST_LOG" \
  || fail "Worlds plugin did not enable or Farmwelt did not detect version $WORLDS_VERSION"
grep -Fq -- "Worlds-Integration initialisiert. Reset-Lifecycle wird über Worlds ausgeführt." "$LATEST_LOG" \
  || fail "Farmwelt did not initialize the real Worlds integration"
grep -Fq -- "Farmwelt wurde gestartet." "$LATEST_LOG" \
  || fail "Farmwelt did not finish startup"
if grep -Fq -- "Farmwelt wird deaktiviert" "$LATEST_LOG"; then
  fail "Farmwelt disabled itself during startup"
fi

world_log_line="$(line_after_current_log)"
send_console "world create test_farmwelt"
wait_for_log_regex \
  "Successfully created the world.*$TEST_WORLD_KEY|$TEST_WORLD_KEY.*wurde erfolgreich erstellt" \
  "$WORLD_TIMEOUT_SECONDS" \
  "Worlds test-world creation" \
  "$world_log_line"
wait_for_path "$WORLD_DIR/region" "$WORLD_TIMEOUT_SECONDS" "Worlds test-world readiness"

printf 'created-before-reset=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$WORLD_MARKER"
[[ -f "$WORLD_MARKER" ]] || fail "could not place the regeneration marker in the test-world directory"

status_log_line="$(line_after_current_log)"
send_console "farmwelt status overworld"
wait_for_log_regex 'Reset-Status:.*overworld' 30 "Farmwelt status response" "$status_log_line"

[[ -s "$STATE_FILE" ]] || fail "Farmwelt did not initialize reset-state.yml"
cp -- "$STATE_FILE" "$STATE_BEFORE"

reset_log_line="$(line_after_current_log)"
send_console "farmwelt reset force overworld"
wait_for_log_regex \
  "Reset für Farmwelt 'overworld' erfolgreich abgeschlossen" \
  "$RESET_TIMEOUT_SECONDS" \
  "Farmwelt reset" \
  "$reset_log_line"

[[ -s "$STATE_FILE" ]] || fail "reset-state.yml is missing after reset success"
cp -- "$STATE_FILE" "$STATE_AFTER"
"$PYTHON_BIN" "$SCRIPT_DIR/assert-state.py" "$STATE_BEFORE" "$STATE_AFTER" overworld

if [[ -e "$WORLD_MARKER" ]]; then
  fail "the pre-reset world marker survived; Worlds did not replace the test-world directory"
fi
echo "PASS: the marker disappeared when Worlds recreated the region data"

old_seed="$(tail -n "+$reset_log_line" "$LATEST_LOG" | sed -n 's/.*Alter Seed: \([-0-9][0-9]*\).*/\1/p' | tail -n 1)"
new_seed="$(tail -n "+$reset_log_line" "$LATEST_LOG" | sed -n 's/.*Neuer Seed: \([-0-9][0-9]*\).*/\1/p' | tail -n 1)"
[[ -n "$old_seed" && -n "$new_seed" ]] || fail "old and new world seeds were not logged"
if [[ "$old_seed" == "$new_seed" ]]; then
  echo "NOTE: random seeds are identical; marker removal still proves directory replacement"
else
  echo "PASS: world seed changed from $old_seed to $new_seed"
fi

stop_server || fail "server required forced cleanup instead of a clean shutdown"

"$SCRIPT_DIR/assert-log.sh" "$LATEST_LOG" "$ARTIFACT_DIR/log-gate-findings.txt"
collect_artifacts
