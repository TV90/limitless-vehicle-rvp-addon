#!/usr/bin/env python3
"""Migrate legacy terminal_ir_* weapon fields to guidance_data distance stages."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

TERMINAL_IR_KEYS = [
    "terminal_ir_enabled",
    "terminal_ir_activation_distance",
    "terminal_ir_seeker_fov",
    "terminal_ir_seek_range",
    "terminal_ir_scan_interval_tick",
    "terminal_ir_vehicle_only",
    "terminal_ir_smoke_break_lock",
    "terminal_ir_memory_tick",
    "terminal_ir_allow_reacquire",
]


def build_terminal_stage(weapon: dict[str, Any]) -> dict[str, Any] | None:
    if not weapon.get("terminal_ir_enabled"):
        return None
    distance = weapon.get("terminal_ir_activation_distance", 100)
    return {
        "name": "terminal_ir",
        "start_tick": 0,
        "max_distance": float(distance),
        "enter_once": True,
        "seeker_data": {
            "fov": weapon.get("terminal_ir_seeker_fov", 40),
            "range": weapon.get("terminal_ir_seek_range", 80),
            "scan_interval_tick": weapon.get("terminal_ir_scan_interval_tick", 2),
        },
        "sources": [
            {
                "type": "IR",
                "priority": 100,
                "params": {
                    "vehicle_only": weapon.get("terminal_ir_vehicle_only", True),
                    "reacquire": weapon.get("terminal_ir_allow_reacquire", False),
                    "memory_tick": weapon.get("terminal_ir_memory_tick", 0),
                },
                "fallback_on_jammed": weapon.get("terminal_ir_smoke_break_lock", True),
            },
            {"type": "GPS", "priority": 50},
            {"type": "IOG", "priority": 10},
        ],
    }


def normalize_guidance(guidance: Any) -> dict[str, Any]:
    if isinstance(guidance, list):
        return {"stages": guidance}
    if isinstance(guidance, dict):
        return guidance
    return {"stages": []}


def migrate_weapon(weapon: dict[str, Any]) -> bool:
    terminal = build_terminal_stage(weapon)
    if terminal is None:
        return False

    guidance = normalize_guidance(weapon.get("guidance_data"))
    stages = list(guidance.get("stages") or [])
    if not any(s.get("name") == "gps_midcourse" for s in stages):
        stages.insert(
            0,
            {
                "name": "gps_midcourse",
                "start_tick": 0,
                "sources": [
                    {"type": "GPS", "priority": 100},
                    {"type": "IOG", "priority": 10},
                ],
            },
        )
    if not any(s.get("name") == "terminal_ir" for s in stages):
        stages.append(terminal)

    guidance["stage_policy"] = guidance.get("stage_policy", "sticky")
    guidance["stages"] = stages
    weapon["guidance_data"] = guidance

    for key in TERMINAL_IR_KEYS:
        weapon.pop(key, None)
    weapon.pop("homing_mode", None)
    return True


def migrate_file(path: Path) -> bool:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        return False
    changed = migrate_weapon(data)
    if changed:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return changed


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("Usage: migrate_guidance.py <weapon.json> [...]", file=sys.stderr)
        return 1
    changed_any = False
    for arg in argv[1:]:
        path = Path(arg)
        if migrate_file(path):
            print(f"migrated: {path}")
            changed_any = True
        else:
            print(f"skipped: {path}")
    return 0 if changed_any or len(argv) > 1 else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
