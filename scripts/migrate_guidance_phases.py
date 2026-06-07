#!/usr/bin/env python3
"""Migrate weapon JSON: remove top-level seeker_data, stages->phases, flat fields->activation."""

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


def normalize_phase(phase: dict[str, Any]) -> None:
    if "seeker_data" in phase and "seeker" not in phase:
        phase["seeker"] = phase.pop("seeker_data")

    activation = dict(phase.get("activation") or {})
    for old_key, new_key in PHASE_FLAT_KEYS.items():
        if old_key in phase and new_key not in activation:
            activation[new_key] = phase.pop(old_key)
    if activation:
        phase["activation"] = activation


def normalize_guidance(guidance: Any) -> dict[str, Any] | None:
    if guidance is None:
        return None
    if isinstance(guidance, list):
        guidance = {"phases": guidance}
    if not isinstance(guidance, dict):
        return None

    if "stages" in guidance and "phases" not in guidance:
        guidance["phases"] = guidance.pop("stages")
    if "stage_policy" in guidance and "phase_resolve_policy" not in guidance:
        guidance["phase_resolve_policy"] = guidance.pop("stage_policy")

    for phase in guidance.get("phases") or []:
        if isinstance(phase, dict):
            normalize_phase(phase)
    return guidance


def migrate_weapon(obj: dict[str, Any]) -> bool:
    changed = False
    top_seeker = obj.pop("seeker_data", None)

    guidance = obj.get("guidance_data")
    normalized = normalize_guidance(guidance)
    if normalized is not None:
        if normalized is not guidance:
            obj["guidance_data"] = normalized
            changed = True
        guidance = normalized

    if top_seeker and isinstance(guidance, dict):
        phases = guidance.get("phases") or []
        target_phase = None
        for phase in phases:
            if not isinstance(phase, dict):
                continue
            sources = phase.get("sources") or []
            if any(isinstance(s, dict) and s.get("type") in SEEKER_SOURCE_TYPES for s in sources):
                target_phase = phase
                break
        if target_phase is None and phases and isinstance(phases[0], dict):
            target_phase = phases[0]
        if target_phase is not None:
            seeker = dict(target_phase.get("seeker") or {})
            for key, value in top_seeker.items():
                seeker.setdefault(key, value)
            target_phase["seeker"] = seeker
            changed = True
        else:
            changed = True

    if "seeker_data" in obj:
        del obj["seeker_data"]
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
