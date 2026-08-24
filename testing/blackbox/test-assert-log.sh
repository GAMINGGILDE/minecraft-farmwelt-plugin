#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_pass_fixture() {
  local fixture="$1"
  local findings="$TEMP_DIR/pass-findings.txt"
  local known="$TEMP_DIR/pass-known-watchdogs.txt"
  local output="$TEMP_DIR/pass-output.txt"

  "$SCRIPT_DIR/assert-log.sh" "$FIXTURE_DIR/$fixture" "$findings" "$known" > "$output" 2>&1 \
    || fail "$fixture should pass"
  [[ ! -s "$findings" ]] || fail "$fixture produced unexpected findings"
  [[ "$(grep -c '^KNOWN / ALLOWED:' "$known" || true)" == "2" ]] \
    || fail "$fixture should preserve exactly two known watchdog blocks"
  grep -Fq 'Current Thread: Global Region Tick Thread' "$known" \
    || fail "$fixture did not preserve the known global-region thread variant"
  grep -Fq 'Current Thread: Folia Region Scheduler Thread #0' "$known" \
    || fail "$fixture did not preserve the known Folia region-scheduler thread variant"
  grep -Fq 'KNOWN: Worlds create watchdog, 5.31s' "$output" \
    || fail "$fixture did not report the known create watchdog"
  grep -Fq 'KNOWN: Worlds regenerate watchdog, 5.09s' "$output" \
    || fail "$fixture did not report the known regenerate watchdog"
  echo "PASS: $fixture"
}

assert_fail_fixture() {
  local fixture="$1"
  local expected_finding="$2"
  local name="${fixture%.log}"
  local findings="$TEMP_DIR/$name-findings.txt"
  local known="$TEMP_DIR/$name-known-watchdogs.txt"
  local output="$TEMP_DIR/$name-output.txt"

  if "$SCRIPT_DIR/assert-log.sh" "$FIXTURE_DIR/$fixture" "$findings" "$known" > "$output" 2>&1; then
    fail "$fixture should fail"
  fi
  grep -Fq -- "$expected_finding" "$findings" \
    || fail "$fixture did not retain the expected error finding: $expected_finding"
  echo "PASS: $fixture fails as expected"
}

assert_pass_fixture "log-known-worlds-watchdogs.log"
assert_fail_fixture "log-foreign-watchdog.log" "ForeignPlugin.blockGlobalRegion"
assert_fail_fixture "log-foreign-watchdog-thread.log" "Current Thread: Async Command Executor"
assert_fail_fixture "log-real-exception.log" "IllegalStateException"
assert_fail_fixture "log-too-many-known-watchdogs.log" "Global region has not responded in 5.30s"
assert_fail_fixture "log-too-long-worlds-watchdog.log" "Global region has not responded in 10.01s"
assert_fail_fixture "log-worlds-watchdog-outside-phase.log" "Global region has not responded in 5.25s"

echo "PASS: all assert-log fixtures behaved as expected"
