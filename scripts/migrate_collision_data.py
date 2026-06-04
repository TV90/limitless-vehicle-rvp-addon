#!/usr/bin/env python3
"""Move piercing/bounce fields from damage_model_data to collision_data."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"
KEYS = ("piercing", "wall_penetration", "bounce", "bounce_strength", "bounce_fuse_tick")


def migrate(obj: dict) -> bool:
    dmg = obj.get("damage_model_data")
    if not isinstance(dmg, dict):
        return False
    moved = {k: dmg.pop(k) for k in KEYS if k in dmg}
    if not moved:
        return False
    col = obj.get("collision_data")
    if not isinstance(col, dict):
        col = {}
        obj["collision_data"] = col
    for k, v in moved.items():
        if k not in col:
            col[k] = v
    return True


def main() -> None:
    count = 0
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
        print("migrated:", path.relative_to(ROOT))
        count += 1
    print(f"done: {count} files")


if __name__ == "__main__":
    main()
