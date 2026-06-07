#!/usr/bin/env python3
"""Hoist top-level arm_data / tv_missile_data into guidance_data ARM/TV source params."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ARM_KEYS = {
    "scan_interval_tick",
    "memory_tick",
    "radiation_pulse_memory_tick",
    "locked_bonus",
}
ARM_ALIASES = {"allow_reacquire": "reacquire", "scan_radius": None}
TV_KEYS = {"control_range", "timeout_tick", "video_modes"}
TV_ALIASES = {"max_range": "control_range", "mode": None}


def merge_params(target: dict, source: dict) -> None:
    for key, value in source.items():
        if key in ARM_ALIASES:
            mapped = ARM_ALIASES[key]
            if mapped:
                target.setdefault(mapped, value)
            continue
        if key in TV_ALIASES:
            mapped = TV_ALIASES[key]
            if mapped:
                target.setdefault(mapped, value)
            continue
        target.setdefault(key, value)


def hoist_block(obj: dict, block_key: str, source_type: str) -> bool:
    if block_key not in obj:
        return False
    block = obj.pop(block_key)
    if not isinstance(block, dict):
        return True
    guidance = obj.setdefault("guidance_data", {})
    stages = guidance.get("stages")
    if not isinstance(stages, list):
        return True
    for stage in stages:
        if not isinstance(stage, dict):
            continue
        sources = stage.get("sources")
        if not isinstance(sources, list):
            continue
        for source in sources:
            if not isinstance(source, dict):
                continue
            if str(source.get("type", "")).upper() != source_type:
                continue
            params = source.setdefault("params", {})
            if isinstance(params, dict):
                merge_params(params, block)
    return True


def migrate_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    obj = json.loads(text)
    if not isinstance(obj, dict):
        return False
    changed = hoist_block(obj, "arm_data", "ARM")
    changed = hoist_block(obj, "tv_missile_data", "TV") or changed
    if not changed:
        return False
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path, help="weapon JSON files or directories")
    args = parser.parse_args()
    files: list[Path] = []
    for path in args.paths:
        if path.is_dir():
            files.extend(sorted(path.rglob("*.json")))
        else:
            files.append(path)
    updated = 0
    for file in files:
        if migrate_file(file):
            print(f"updated {file}")
            updated += 1
    print(f"done: {updated} file(s)")


if __name__ == "__main__":
    main()
