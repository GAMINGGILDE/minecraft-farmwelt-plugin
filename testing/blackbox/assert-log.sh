#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

LOG_FILE="${1:?usage: assert-log.sh <latest.log> <findings-file>}"
FINDINGS_FILE="${2:?usage: assert-log.sh <latest.log> <findings-file>}"

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

[[ -s "$LOG_FILE" ]] || fail "server latest.log is missing or empty"

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

# Das Gate bewertet Fehlerkontext, nicht bloß die normalen Wörter "Thread" oder "Region".
# Explizite erwartete Zeilen werden anschließend über die kleine Allowlist entfernt.
error_pattern='(SEVERE|ERROR)(\]|:)|IllegalStateException|CompletionException|NullPointerException|(^|[[:space:]])Caused by:|(^|[[:space:]])Suppressed:|(^|[[:space:]])at [[:alnum:]_$]+\.|uncaught exception|Unhandled exception|Could not pass event|Failed to (load|enable|disable|unload|create|delete|regenerate|schedule)|Worlds-Integration konnte nicht initialisiert|Reset für Farmwelt .* abgebrochen|already running|läuft bereits ein Reset|scheduler.*(failed|exception|rejected)|(Thread|thread|Region|region).*(violation|unsafe|wrong|not owned|not on|access error)|(violation|unsafe|wrong|not owned|not on|access error).*(Thread|thread|Region|region)'

allowlist_patterns="$(mktemp)"
trap 'rm -f -- "$allowlist_patterns"' EXIT
grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/log-allowlist.txt" > "$allowlist_patterns" || true

grep -Ein -- "$error_pattern" "$LOG_FILE" \
  | grep -Ev -f "$allowlist_patterns" \
  > "$FINDINGS_FILE" || true

if [[ -s "$FINDINGS_FILE" ]]; then
  echo "Detected unexpected error lines:" >&2
  cat "$FINDINGS_FILE" >&2
  fail "detected relevant Folia, scheduler, Worlds lifecycle, or plugin errors"
fi

echo "PASS: startup, single regeneration, reset success, log gate, and shutdown markers are valid"
