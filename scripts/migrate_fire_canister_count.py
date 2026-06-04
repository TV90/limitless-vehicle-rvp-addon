#!/usr/bin/env python3
"""Remove projectile_count / legacy canister; use fire_data.canister_count only."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"


def migrate_fire(fire: dict) -> bool:
    changed = False
    if "projectile_count" in fire:
        pc = fire.pop("projectile_count")
        if isinstance(pc, int) and pc > 1 and fire.get("canister_count", 0) <= 0:
            fire["canister_count"] = pc
        changed = True
    if "canister" in fire:
        c = fire.pop("canister")
        if "canister_count" not in fire:
            if isinstance(c, bool) and c:
                fire["canister_count"] = 8
            elif isinstance(c, (int, float)) and c > 0:
                fire["canister_count"] = int(c)
        changed = True
    return changed


def migrate(obj: dict) -> bool:
    fire = obj.get("fire_data")
    if isinstance(fire, dict) and migrate_fire(fire):
        return True
    fire = obj.get("fire")
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
