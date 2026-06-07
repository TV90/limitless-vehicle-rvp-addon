#!/usr/bin/env python3
"""Move damage_model_data into collision_data + fuse_data; merge decay and impact_decay."""
from __future__ import annotations

import json
from pathlib import Path

WEAPONS = Path(__file__).resolve().parents[1] / "run/client_1/limitless_vehicle/rvp/data/rvp/weapons"
FUSE_KEYS = (
    "airburst_explosion_damage",
    "airburst_explosion_radius",
    "proximity_fuse_explosion_damage",
    "proximity_fuse_explosion_radius",
    "proximity_fuse_damage",
)


def ensure_angle_domain(rule: dict) -> dict:
    if "domain" not in rule:
        rule = dict(rule)
        rule["domain"] = "angle"
    return rule


def main() -> None:
    for path in sorted(WEAPONS.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        dmg = data.pop("damage_model_data", None)
        if not dmg:
            continue
        col = data.setdefault("collision_data", {})
        if dmg.get("direct") is not None:
            col["direct_damage"] = dmg["direct"]
        if dmg.get("damage_factor"):
            col["direct_damage_factor"] = dmg["damage_factor"]
        decay: list = []
        decay.extend(dmg.get("decay") or [])
        for rule in col.pop("impact_decay", []) or []:
            decay.append(ensure_angle_domain(rule))
        if decay:
            col["damage_decay"] = decay
        fuse = data.setdefault("fuse_data", {})
        for key in FUSE_KEYS:
            if key in dmg:
                fuse[key] = dmg[key]
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("migrated", path.name)


if __name__ == "__main__":
    main()
