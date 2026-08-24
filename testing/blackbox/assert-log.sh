#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

LOG_FILE="${1:?usage: assert-log.sh <latest.log> <findings-file> [known-watchdogs-file]}"
FINDINGS_FILE="${2:?usage: assert-log.sh <latest.log> <findings-file> [known-watchdogs-file]}"
KNOWN_WATCHDOGS_FILE="${3:-$(dirname -- "$FINDINGS_FILE")/known-watchdogs.txt}"

WATCHDOG_MAX_SECONDS=10
WATCHDOG_MAX_BLOCKS=2
WATCHDOG_MAX_BLOCK_LINES=200
WATCHDOG_HEADER_REGEX='^\[[^]]+\] \[Folia Watchdog Thread/ERROR\]: Global region has not responded in ([0-9]+([.][0-9]+)?)s:$'
WATCHDOG_LINE_REGEX='^\[[^]]+\] \[Folia Watchdog Thread/ERROR\]: '
WATCHDOG_CURRENT_THREAD_REGEX='^\[[^]]+\] \[Folia Watchdog Thread/ERROR\]: Current Thread: (Global Region Tick Thread|Folia Region Scheduler Thread #[0-9]+)$'

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

require_fixed() {
  local pattern="$1"
  local description="$2"
  grep -Fq -- "$pattern" "$LOG_FILE" || fail "$description"
}

count_fixed() {
  local pattern="$1"
  local count
  count="$(grep -F -c -- "$pattern" "$LOG_FILE" || true)"
  echo "$count"
}

first_line_fixed() {
  local pattern="$1"
  grep -F -n -m 1 -- "$pattern" "$LOG_FILE" | cut -d: -f1
}

[[ -s "$LOG_FILE" ]] || fail "server latest.log is missing or empty"

: > "$FINDINGS_FILE"
{
  echo "# Bekannte, im Black-Box-Harness tolerierte Worlds-Watchdog-Blöcke"
  echo "# Grenze: höchstens ein Create- und ein Regenerate-Block, jeweils maximal ${WATCHDOG_MAX_SECONDS}s"
} > "$KNOWN_WATCHDOGS_FILE"

require_fixed "Done (" "Folia did not reach its normal ready state"
require_fixed "Worlds $WORLDS_VERSION erkannt." "Farmwelt did not detect Worlds $WORLDS_VERSION"
require_fixed "Worlds-Integration initialisiert. Reset-Lifecycle wird über Worlds ausgeführt." \
  "Farmwelt did not initialize the real Worlds lifecycle"
require_fixed "Farmwelt wurde gestartet." "Farmwelt did not finish startup"
require_fixed "Geladene Bukkit-Welt '$TEST_WORLD_NAME' gefunden." \
  "the prepared farmworld was not loaded when reset validation ran"
require_fixed "Reset für Farmwelt 'overworld' erfolgreich abgeschlossen." \
  "the reset did not finish with SUCCESS"
require_fixed "Farmwelt wurde gestoppt." "Farmwelt did not receive a clean shutdown"

regeneration_starts="$(count_fixed "Regeneration von '$TEST_WORLD_NAME' über Worlds mit zufälligem Seed gestartet.")"
[[ "$regeneration_starts" == "1" ]] \
  || fail "expected exactly one Worlds regeneration start but found $regeneration_starts"

regeneration_successes="$(count_fixed "Worlds hat Farmwelt '$TEST_WORLD_NAME' erfolgreich regeneriert.")"
[[ "$regeneration_successes" == "1" ]] \
  || fail "expected exactly one successful Worlds regeneration but found $regeneration_successes"

manual_requests="$(count_fixed "hat einen manuellen Reset für Farmwelt 'overworld' angefordert.")"
[[ "$manual_requests" == "1" ]] \
  || fail "expected exactly one manual reset request but found $manual_requests"

folia_done_line="$(first_line_fixed "Done (")"
manual_request_line="$(first_line_fixed "hat einen manuellen Reset für Farmwelt 'overworld' angefordert.")"
regeneration_start_line="$(first_line_fixed "Regeneration von '$TEST_WORLD_NAME' über Worlds mit zufälligem Seed gestartet.")"
regeneration_success_line="$(first_line_fixed "Worlds hat Farmwelt '$TEST_WORLD_NAME' erfolgreich regeneriert.")"

# Folia protokolliert den Header und den anschließenden Thread-Dump zeilenweise als ERROR.
# Deshalb wird nur der vollständig bekannte Worlds-initWorld-Dump als Block entfernt. Jede
# unvollständige, zu lange, wiederholte oder mit echten Exceptions vermischte Variante bleibt
# für das nachfolgende allgemeine Fehler-Gate sichtbar.
filtered_log="$(mktemp)"
allowlist_patterns="$(mktemp)"
trap 'rm -f -- "$filtered_log" "$allowlist_patterns"' EXIT

watchdog_allowed_count=0
watchdog_create_count=0
watchdog_regenerate_count=0
watchdog_start_line=0
watchdog_duration=""
watchdog_block=()

is_watchdog_header() {
  local line="$1"
  if [[ "$line" =~ $WATCHDOG_HEADER_REGEX ]]; then
    detected_watchdog_duration="${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

is_watchdog_error_line() {
  local line="$1"
  [[ "$line" =~ $WATCHDOG_LINE_REGEX ]]
}

classify_watchdog_block() {
  local block_text="$1"
  watchdog_phase=""

  (( ${#watchdog_block[@]} <= WATCHDOG_MAX_BLOCK_LINES )) || return 1
  awk -v duration="$watchdog_duration" -v maximum="$WATCHDOG_MAX_SECONDS" \
    'BEGIN { exit !(duration <= maximum) }' || return 1

  [[ "$block_text" == *"Global region has not responded in ${watchdog_duration}s:"* ]] || return 1
  [[ "$block_text" == *"------------------------------"* ]] || return 1
  grep -Eq -- "$WATCHDOG_CURRENT_THREAD_REGEX" <<< "$block_text" || return 1
  [[ "$block_text" == *"PID:"* ]] || return 1
  [[ "$block_text" == *"Stack:"* ]] || return 1
  [[ "$block_text" == *"net.thenextlvl.worlds"* ]] || return 1
  [[ "$block_text" == *"SimpleVersionHandler.createAsync"* ]] || return 1
  [[ "$block_text" == *"MinecraftServer.initWorld"* ]] || return 1
  [[ "$block_text" == *"PlayerSpawnFinder"* ]] || return 1
  [[ "$block_text" == *"ChunkTaskScheduler.syncLoadNonFull"* ]] || return 1

  if grep -Eiq -- 'IllegalStateException|CompletionException|NullPointerException|Caused by:|Suppressed:|uncaught exception|Unhandled exception|Could not pass event|scheduler.*(failed|exception|rejected)|(Thread|Region).*(violation|unsafe|wrong|not owned|not on|access error)' \
      <<< "$block_text"; then
    return 1
  fi

  if [[ "$block_text" == *"WorldsPlugin.regenerate("* \
      && "$block_text" == *"WorldsPlugin.regenerateNow("* \
      && "$block_text" == *"WorldsPlugin.create("* ]]; then
    (( watchdog_start_line > regeneration_start_line \
      && watchdog_start_line < regeneration_success_line )) || return 1
    watchdog_phase="regenerate"
    return 0
  fi

  if [[ "$block_text" == *"WorldCreateCommand"* \
      && "$block_text" == *"WorldsPlugin.create("* ]]; then
    (( watchdog_start_line > folia_done_line \
      && watchdog_start_line < manual_request_line )) || return 1
    watchdog_phase="create"
    return 0
  fi

  return 1
}

write_watchdog_block_to_filtered_log() {
  printf '%s\n' "${watchdog_block[@]}" >> "$filtered_log"
}

write_watchdog_block_as_blank_lines() {
  local ignored
  for ignored in "${watchdog_block[@]}"; do
    echo >> "$filtered_log"
  done
}

finish_watchdog_block() {
  local block_text
  local end_line
  local phase_count

  (( ${#watchdog_block[@]} > 0 )) || return 0
  printf -v block_text '%s\n' "${watchdog_block[@]}"
  end_line=$((watchdog_start_line + ${#watchdog_block[@]} - 1))

  if ! classify_watchdog_block "$block_text"; then
    write_watchdog_block_to_filtered_log
    watchdog_block=()
    return 0
  fi

  if [[ "$watchdog_phase" == "create" ]]; then
    phase_count="$watchdog_create_count"
  else
    phase_count="$watchdog_regenerate_count"
  fi

  if (( watchdog_allowed_count >= WATCHDOG_MAX_BLOCKS || phase_count >= 1 )); then
    write_watchdog_block_to_filtered_log
    watchdog_block=()
    return 0
  fi

  ((watchdog_allowed_count += 1))
  if [[ "$watchdog_phase" == "create" ]]; then
    ((watchdog_create_count += 1))
  else
    ((watchdog_regenerate_count += 1))
  fi

  {
    printf 'KNOWN / ALLOWED: Worlds %s watchdog, %ss, lines %d-%d\n' \
      "$watchdog_phase" "$watchdog_duration" "$watchdog_start_line" "$end_line"
    printf '%s\n' "${watchdog_block[@]}"
    echo
  } >> "$KNOWN_WATCHDOGS_FILE"
  printf 'KNOWN: Worlds %s watchdog, %ss\n' "$watchdog_phase" "$watchdog_duration"
  write_watchdog_block_as_blank_lines
  watchdog_block=()
}

line_number=0
while IFS= read -r line || [[ -n "$line" ]]; do
  ((line_number += 1))
  line="${line%$'\r'}"

  if is_watchdog_header "$line"; then
    finish_watchdog_block
    watchdog_start_line="$line_number"
    watchdog_duration="$detected_watchdog_duration"
    watchdog_block=("$line")
  elif (( ${#watchdog_block[@]} > 0 )) && is_watchdog_error_line "$line"; then
    watchdog_block+=("$line")
  else
    finish_watchdog_block
    printf '%s\n' "$line" >> "$filtered_log"
  fi
done < "$LOG_FILE"
finish_watchdog_block

# Das Gate bewertet Fehlerkontext, nicht bloß die normalen Wörter "Thread" oder "Region".
# Der eng geprüfte Worlds-Watchdog wurde bereits blockweise behandelt. Explizite erwartete
# Einzelzeilen werden anschließend weiterhin über die kleine Allowlist entfernt.
error_pattern='(SEVERE|ERROR)(\]|:)|IllegalStateException|CompletionException|NullPointerException|(^|[[:space:]])Caused by:|(^|[[:space:]])Suppressed:|(^|[[:space:]])at [[:alnum:]_$]+\.|uncaught exception|Unhandled exception|Could not pass event|Failed to (load|enable|disable|unload|create|delete|regenerate|schedule)|Worlds-Integration konnte nicht initialisiert|Reset für Farmwelt .* abgebrochen|already running|läuft bereits ein Reset|scheduler.*(failed|exception|rejected)|(Thread|thread|Region|region).*(violation|unsafe|wrong|not owned|not on|access error)|(violation|unsafe|wrong|not owned|not on|access error).*(Thread|thread|Region|region)'

grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/log-allowlist.txt" > "$allowlist_patterns" || true

grep -Ein -- "$error_pattern" "$filtered_log" \
  | grep -Ev -f "$allowlist_patterns" \
  > "$FINDINGS_FILE" || true

if [[ -s "$FINDINGS_FILE" ]]; then
  echo "Detected unexpected error lines:" >&2
  cat "$FINDINGS_FILE" >&2
  fail "detected relevant Folia, scheduler, Worlds lifecycle, or plugin errors"
fi

echo "PASS: startup, single regeneration, reset success, log gate, and shutdown markers are valid"
