#!/usr/bin/env python3
"""Move weapon root explosion_data into detonate_data.explosion_data."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"


def migrate(obj: dict) -> bool:
    explosion = obj.pop("explosion_data", None)
    if explosion is None and "explosion" in obj:
        explosion = obj.pop("explosion")
    if explosion is None:
        return False

    detonate = obj.get("detonate_data")
    if not isinstance(detonate, dict):
        detonate = {}
        obj["detonate_data"] = detonate
    if "explosion_data" not in detonate and "explosion" not in detonate:
        detonate["explosion_data"] = explosion
    return True


def main() -> None:
    count = 0
    for path in sorted(ROOT.rglob("*.json")):
        if "weapons" not in path.parts:
            continue
        with path.open(encoding="utf-8") as f:
            obj = json.load(f)
        if not isinstance(obj, dict):
            continue
        if migrate(obj):
            with path.open("w", encoding="utf-8", newline="\n") as f:
                json.dump(obj, f, indent=2, ensure_ascii=False)
                f.write("\n")
            print("migrated:", path.relative_to(ROOT))
            count += 1
    print(f"done: {count} files")


if __name__ == "__main__":
    main()
