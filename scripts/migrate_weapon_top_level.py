#!/usr/bin/env python3
"""Migrate RVP weapon JSON: explosion_data, arm_data, tv_missile_data, laser_data, steering_data."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"

STEERING_KEYS = (
    "rigidity_time",
    "turning_factor",
    "max_degree_of_missile",
    "predict_target_pos",
    "tick_end_homing",
)

ARM_MAP = {
    "anti_radiation_scan_interval_tick": "scan_interval_tick",
    "anti_radiation_memory_tick": "memory_tick",
    "anti_radiation_radiation_pulse_memory_tick": "radiation_pulse_memory_tick",
    "anti_radiation_allow_reacquire": "allow_reacquire",
    "anti_radiation_locked_bonus": "locked_bonus",
}

ARM_DROP = (
    "anti_radiation_seek_range",
    "anti_radiation_allow_fire_without_seeker",
    "anti_radiation_preselect_enabled",
)


def ensure_guidance_object(obj: dict) -> dict:
    g = obj.get("guidance_data")
    if g is None:
        g = obj.pop("guidance", None)
    if isinstance(g, list):
        wrapper = {"stages": g}
        obj["guidance_data"] = wrapper
        return wrapper
    if isinstance(g, dict):
        return g
    wrapper: dict = {"stages": []}
    obj["guidance_data"] = wrapper
    return wrapper


def migrate_steering(obj: dict) -> bool:
    steering = {k: obj.pop(k) for k in STEERING_KEYS if k in obj}
    if not steering:
        return False
    guidance = ensure_guidance_object(obj)
    stages = guidance.get("stages")
    if not isinstance(stages, list):
        stages = []
        guidance["stages"] = stages
    if stages and isinstance(stages[0], dict):
        first = stages[0]
        existing = first.get("steering_data")
        if isinstance(existing, dict):
            existing.update(steering)
        else:
            first["steering_data"] = steering
    else:
        stages.append({"name": "default", "steering_data": steering, "sources": []})
    guidance.pop("steering_data", None)
    return True


def migrate_arm(obj: dict) -> bool:
    changed = False
    arm: dict = {}
    for old, new in ARM_MAP.items():
        if old in obj:
            arm[new] = obj.pop(old)
            changed = True
    for key in ARM_DROP:
        if key in obj:
            if key == "anti_radiation_seek_range":
                seeker = obj.setdefault("seeker_data", {})
                if isinstance(seeker, dict) and "range" not in seeker:
                    seeker["range"] = obj.pop(key)
                else:
                    obj.pop(key)
            else:
                obj.pop(key)
            changed = True
    if arm:
        existing = obj.get("arm_data")
        if isinstance(existing, dict):
            existing.update(arm)
        else:
            obj["arm_data"] = arm
        changed = True
    return changed


def migrate_tv(obj: dict) -> bool:
    changed = False
    tv: dict = {}
    if "tv_missile_control_range" in obj:
        tv["control_range"] = obj.pop("tv_missile_control_range")
        changed = True
    if "tv_missile_timeout_tick" in obj:
        tv["timeout_tick"] = obj.pop("tv_missile_timeout_tick")
        changed = True
    if "tv_missile_video_modes" in obj:
        tv["video_modes"] = obj.pop("tv_missile_video_modes")
        changed = True
    if tv:
        existing = obj.get("tv_missile_data")
        if isinstance(existing, dict):
            existing.update(tv)
        else:
            obj["tv_missile_data"] = tv
    return changed


def migrate_laser(obj: dict) -> bool:
    changed = False
    laser: dict = {}
    if "laser_range" in obj:
        laser["range"] = obj.pop("laser_range")
        changed = True
    if "laser_visual" in obj:
        laser["visual_data"] = obj.pop("laser_visual")
        changed = True
    if laser:
        existing = obj.get("laser_data")
        if isinstance(existing, dict):
            existing.update(laser)
        else:
            obj["laser_data"] = laser
    return changed


def migrate_explosion(obj: dict) -> bool:
    if "explosion" in obj and "explosion_data" not in obj:
        obj["explosion_data"] = obj.pop("explosion")
        return True
    return False


def migrate_file(path: Path) -> bool:
    with path.open(encoding="utf-8") as f:
        obj = json.load(f)
    if not isinstance(obj, dict):
        return False
    changed = False
    changed |= migrate_explosion(obj)
    changed |= migrate_steering(obj)
    changed |= migrate_arm(obj)
    changed |= migrate_tv(obj)
    changed |= migrate_laser(obj)
    if not changed:
        return False
    with path.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return True


def main() -> None:
    count = 0
    for path in sorted(ROOT.rglob("*.json")):
        if "weapons" not in path.parts:
            continue
        if migrate_file(path):
            print("migrated:", path.relative_to(ROOT))
            count += 1
    print(f"done: {count} files")


if __name__ == "__main__":
    main()
