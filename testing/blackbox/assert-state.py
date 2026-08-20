#!/usr/bin/env python3
"""Prüft den bestehenden reset-state.yml-Vertrag ohne zusätzliche YAML-Abhängigkeit."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass(frozen=True)
class ResetState:
    version: int
    last_reset: datetime | None
    next_reset: datetime


def fail(message: str) -> "NoReturn":
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_timestamp(raw: str, path: Path, key: str) -> datetime:
    value = raw.strip().strip("'\"")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        fail(f"{path}: {key} is not a valid ISO-8601 timestamp: {value}")
    if parsed.tzinfo is None:
        fail(f"{path}: {key} has no timezone: {value}")
    return parsed


def read_state(path: Path, world: str) -> ResetState:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"could not read {path}: {error}")

    version_match = re.search(r"(?m)^version:\s*(\d+)\s*$", text)
    if version_match is None:
        fail(f"{path}: version is missing or invalid")

    section_match = re.search(
        rf"(?ms)^  {re.escape(world)}:\s*$\n((?:^    .*(?:\n|$))*)",
        text,
    )
    if section_match is None:
        fail(f"{path}: worlds.{world} is missing")

    values: dict[str, str] = {}
    for line in section_match.group(1).splitlines():
        match = re.match(r"^    ([a-z-]+):\s*(.*?)\s*$", line)
        if match:
            values[match.group(1)] = match.group(2)

    next_raw = values.get("next-reset")
    if not next_raw:
        fail(f"{path}: worlds.{world}.next-reset is missing")
    last_raw = values.get("last-reset")

    return ResetState(
        version=int(version_match.group(1)),
        last_reset=parse_timestamp(last_raw, path, "last-reset") if last_raw else None,
        next_reset=parse_timestamp(next_raw, path, "next-reset"),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("world")
    args = parser.parse_args()

    before = read_state(args.before, args.world)
    after = read_state(args.after, args.world)

    if before.version != 1 or after.version != 1:
        fail(f"expected reset-state version 1, got {before.version} and {after.version}")
    if after.last_reset is None:
        fail("last-reset was not set after the successful reset")
    if before.last_reset == after.last_reset:
        fail("last-reset was not changed by the reset")
    if before.next_reset == after.next_reset:
        fail("next-reset was not advanced by the reset")
    if after.next_reset <= after.last_reset:
        fail("next-reset is not later than last-reset")

    print(f"State before: last-reset={before.last_reset}, next-reset={before.next_reset}")
    print(f"State after:  last-reset={after.last_reset}, next-reset={after.next_reset}")
    print("PASS: reset-state.yml was updated and remains internally consistent")


if __name__ == "__main__":
    main()
