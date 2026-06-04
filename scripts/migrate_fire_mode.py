#!/usr/bin/env python3
"""Normalize fire_data.fire_mode to RVP_EnumFireMode enum names (uppercase)."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"

VALID = frozenset({
    "FULL_AUTO",
    "SEMI_AUTO",
    "BURST",
    "CHARGE",
    "MINIGUN",
    "RAILGUN",
})

# 一次性：旧 JSON 里曾用的非枚举写法 → 枚举名
LEGACY = {
    "single": "FULL_AUTO",
    "canister": "FULL_AUTO",
    "charge": "CHARGE",
    "semi": "SEMI_AUTO",
    "semi_auto": "SEMI_AUTO",
    "burst": "BURST",
    "full_auto": "FULL_AUTO",
    "auto": "FULL_AUTO",
    "minigun": "MINIGUN",
    "railgun": "RAILGUN",
}


def normalize(raw: str) -> str:
    key = raw.strip()
    upper = key.upper()
    if upper in VALID:
        return upper
    legacy = LEGACY.get(key.lower())
    return legacy if legacy else "FULL_AUTO"


def migrate(obj: dict) -> bool:
    fire = obj.get("fire_data")
    if not isinstance(fire, dict):
        return False
    field = "fire_mode" if "fire_mode" in fire else "mode"
    if field not in fire:
        return False
    raw = str(fire[field]).strip()
    new = normalize(raw)
    changed = field != "fire_mode" or new != raw
    fire.pop("mode", None)
    fire["fire_mode"] = new
    return changed


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
