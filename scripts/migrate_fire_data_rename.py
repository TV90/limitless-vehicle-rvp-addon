#!/usr/bin/env python3
"""Rename fire_data: bomblet_* / burst_count -> canister_* (non-BURST burst_count)."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"

RENAMES = {
    "bomblet_diff": "canister_diff",
    "BombletDiff": "canister_diff",
    "bomblet_s_time": "canister_burst_delay_time",
    "BombletSTime": "canister_burst_delay_time",
    "bombletstime": "canister_burst_delay_time",
}


def migrate_fire(fire: dict) -> bool:
    changed = False
    for old, new in RENAMES.items():
        if old in fire and new not in fire:
            fire[new] = fire.pop(old)
            changed = True
        elif old in fire:
            fire.pop(old)
            changed = True

    burst_mode = str(fire.get("fire_mode", "")).upper() == "BURST"
    if "burst_count" in fire and not burst_mode:
        if "canister_burst_count" not in fire:
            fire["canister_burst_count"] = fire.pop("burst_count")
        else:
            fire.pop("burst_count")
        changed = True
    return changed


def migrate(obj: dict) -> bool:
    fire = obj.get("fire_data")
    if isinstance(fire, dict) and migrate_fire(fire):
        return True
    return False


def main() -> None:
    n = 0
    for path in sorted(ROOT.rglob("*.json")):
        if "weapons" not in path.parts:
            continue
        with path.open(encoding="utf-8") as f:
            obj = json.load(f)
        if not isinstance(obj, dict) or not migrate(obj):
            continue
        with path.open("w", encoding="utf-8", newline="\n") as f:
            json.dump(obj, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(path.relative_to(ROOT))
        n += 1
    print("done", n)


if __name__ == "__main__":
    main()
