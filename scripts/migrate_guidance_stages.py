#!/usr/bin/env python3
"""Migrate guidance_data: phases->stages, hoist top-level steering_data into stages."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

PHASE_FLAT_KEYS = {
    "start_tick": "start_tick",
    "end_tick": "end_tick",
    "enter_once": "enter_once",
    "require_target": "require_target",
    "require_entity_target": "require_entity_target",
    "require_illumination": "require_illumination",
    "min_distance": "min_target_distance",
    "max_distance": "max_target_distance",
    "min_entity_distance": "min_entity_distance",
    "max_entity_distance": "max_entity_distance",
    "min_altitude": "min_altitude_agl",
    "max_altitude": "max_altitude_agl",
}

SEEKER_SOURCE_TYPES = {"ARH", "SARH", "IR", "ARM", "SACLOS", "TV"}


def normalize_stage(stage: dict[str, Any]) -> None:
    if "seeker_data" in stage and "seeker" not in stage:
        stage["seeker"] = stage.pop("seeker_data")

    activation = dict(stage.get("activation") or {})
    for old_key, new_key in PHASE_FLAT_KEYS.items():
        if old_key in stage and new_key not in activation:
            activation[new_key] = stage.pop(old_key)
    if activation:
        stage["activation"] = activation


def migrate_guidance(guidance: Any) -> tuple[dict[str, Any] | None, bool]:
    if guidance is None:
        return None, False
    if isinstance(guidance, list):
        guidance = {"stages": guidance}
    if not isinstance(guidance, dict):
        return None, False

    changed = False
    if "phases" in guidance and "stages" not in guidance:
        guidance["stages"] = guidance.pop("phases")
        changed = True
    if "stage_policy" in guidance and "phase_resolve_policy" not in guidance:
        guidance["phase_resolve_policy"] = guidance.pop("stage_policy")
        changed = True

    top_steering = guidance.pop("steering_data", None)
    if top_steering is not None:
        changed = True

    stages = guidance.get("stages")
    if not isinstance(stages, list):
        stages = []
        guidance["stages"] = stages

    for stage in stages:
        if isinstance(stage, dict):
            before = json.dumps(stage, sort_keys=True)
            normalize_stage(stage)
            if top_steering and "steering_data" not in stage:
                stage["steering_data"] = dict(top_steering)
            if json.dumps(stage, sort_keys=True) != before or top_steering:
                changed = True

    return guidance, changed


def migrate_weapon(obj: dict[str, Any]) -> bool:
    changed = False
    top_seeker = obj.pop("seeker_data", None)

    guidance = obj.get("guidance_data")
    normalized, guidance_changed = migrate_guidance(guidance)
    if normalized is not None:
        obj["guidance_data"] = normalized
        changed |= guidance_changed
        guidance = normalized

    if top_seeker and isinstance(guidance, dict):
        stages = guidance.get("stages") or []
        target_stage = None
        for stage in stages:
            if not isinstance(stage, dict):
                continue
            sources = stage.get("sources") or []
            if any(isinstance(s, dict) and s.get("type") in SEEKER_SOURCE_TYPES for s in sources):
                target_stage = stage
                break
        if target_stage is None and stages and isinstance(stages[0], dict):
            target_stage = stages[0]
        if target_stage is not None:
            seeker = dict(target_stage.get("seeker") or {})
            for key, value in top_seeker.items():
                seeker.setdefault(key, value)
            target_stage["seeker"] = seeker
            changed = True
        else:
            changed = True

    return changed


def process_file(path: Path) -> bool:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        return False
    if not migrate_weapon(data):
        return False
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def main(argv: list[str]) -> int:
    roots = [Path(p) for p in argv[1:]] if len(argv) > 1 else [
        Path(__file__).resolve().parents[1] / "docs" / "examples",
    ]
    changed = 0
    for root in roots:
        for path in root.rglob("*.json"):
            if process_file(path):
                print(f"migrated: {path}")
                changed += 1
    print(f"done, {changed} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
