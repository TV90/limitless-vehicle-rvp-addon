#!/usr/bin/env python3
"""Normalize RVP weapon JSON: move damage/inaccuracy/velocity into *_data groups."""
from __future__ import annotations

import json
from pathlib import Path

WEAPONS_DIR = Path(__file__).resolve().parents[1] / "run/client_1/limitless_vehicle/rvp/data/rvp/weapons"
META = Path(__file__).resolve().parents[1] / "run/client_1/limitless_vehicle/rvp/vehicle_pack.meta.json"

TOP_LEVEL_MOVE = ("damage", "inaccuracy", "velocity")


def normalize(data: dict) -> list[str]:
    changes: list[str] = []

    if "damage" in data:
        dm = data.setdefault("damage_model_data", {})
        if dm.get("direct") is None:
            dm["direct"] = data["damage"]
            changes.append(f"direct={data['damage']}")
        del data["damage"]
        changes.append("removed top damage")

    if "inaccuracy" in data:
        fd = data.setdefault("fire_data", {})
        if fd.get("spread") is None:
            fd["spread"] = data["inaccuracy"]
            changes.append(f"spread={data['inaccuracy']}")
        del data["inaccuracy"]
        changes.append("removed top inaccuracy")

    if "velocity" in data:
        pd = data.setdefault("projectile_data", {})
        if pd.get("velocity") is None:
            pd["velocity"] = data["velocity"]
            changes.append(f"projectile velocity={data['velocity']}")
        del data["velocity"]
        changes.append("removed top velocity")

    return changes


def main() -> None:
    for path in sorted(WEAPONS_DIR.glob("*.json")):
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
        changes = normalize(data)
        if changes:
            path.write_text(
                json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            print(f"{path.name}: {', '.join(changes)}")

    if META.exists():
        meta = json.loads(META.read_text(encoding="utf-8"))
        ver = meta.get("version", "0.0.0")
        parts = ver.split(".")
        if len(parts) == 3 and parts[0] == "0" and parts[1] == "5":
            parts[2] = str(int(parts[2]) + 1)
            meta["version"] = ".".join(parts)
            META.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"vehicle_pack.meta.json -> {meta['version']}")


if __name__ == "__main__":
    main()
