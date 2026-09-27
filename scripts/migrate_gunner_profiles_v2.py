#!/usr/bin/env python3
"""一次性把 gunner/ 平铺 Profile 改写为当前 schema v2；运行时 Java 不包含旧格式兼容。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


# 默认只处理用户授权的载具包源目录，不扫描或改写任何 run 副本。
DEFAULT_ROOT = Path("limitless_vehicle/rvp/data/rvp/gunner")


def behavior(instance_id: str, type_path: str, priority: int, config: dict | None = None) -> dict:
    return {
        "id": instance_id,
        "type": f"rvp:{type_path}",
        "priority": priority,
        "config": config or {},
    }


def migrate(old: dict, fallback_name: str) -> dict:
    if "schema_version" in old or "behaviors" in old:
        raise ValueError("输入必须是尚未迁移的平铺 Profile")
    name = old.get("name", fallback_name)
    faction = old.get("faction", "friendly")
    drive = bool(old.get("allow_drive", True))
    result = [
        behavior("incoming_ammo", "ciws_targeting", 850,
                 {"scan_interval_tick": 1, "target_cooldown_tick": 100}),
        behavior("main_target", "primary_targeting", 500, {
            "target_types": old.get("target_types", ["rvp:missile", "vehicle", "monster", "player"]),
            "gps_prefer_farthest": old.get("gps_prefer_farthest", True),
            "search_radius": old.get("search_radius", 96.0),
            "scan_interval_tick": old.get("scan_interval_tick", 10),
            "engagement_net_cooldown_tick": old.get("engagement_net_cooldown_tick", 100),
        }),
    ]
    if drive:
        result.append(behavior("resupply", "driver_supply", 100))
    result.extend([
        behavior("base_defense", "weapon_countermeasure", 800, {
            "range": old.get("countermeasure_range", 36.0),
            "cooldown_tick": old.get("countermeasure_cooldown_tick", 80),
        }),
        behavior("rvp_defense", "rvp_countermeasure", 850, {
            "scan_interval_tick": 5,
            "cooldown_tick": 100,
            "missile_threat_range": 256.0,
            "radar_lock_threat_range": 1024.0,
        }),
        behavior("ecm", "active_ecm", 850, {"threat_range": 400.0}),
    ])
    if drive:
        result.append(behavior("smoke_cover", "smoke_evasion", 820, {
            "scan_interval_tick": 10, "hold_tick": 260, "look_radius": 48.0,
        }))
    result.extend([
        behavior("own_radar", "ownship_radar", 500),
        behavior("external_radar", "external_radar", 510),
        behavior("guidance", "guided_weapon_support", 520),
    ])
    if drive:
        air_common = {
            "attack_phase_tick": old.get("air_attack_phase_tick", 200),
            "disengage_phase_tick": old.get("air_disengage_phase_tick", 200),
            "initial_disengage_tick_min": old.get("air_initial_disengage_tick_min", 300),
            "initial_disengage_tick_max": old.get("air_initial_disengage_tick_max", 400),
        }
        result.extend([
            behavior("sead", "sead_revenge", 950, {
                "threat_scan_interval_tick": 10, "radar_lock_range": 1024.0,
                "fly_away_tick": 100, "reversal_tick": 160, "lock_fire_tick": 40,
                "timeout_tick": 400, "cooldown_tick": 400,
            }),
            behavior("fixed_wing_flight", "fixed_wing_combat_flight", 500, {
                "cruise_altitude_min": old.get("fixedwing_cruise_altitude_min", 150.0),
                "cruise_altitude_max": old.get("fixedwing_cruise_altitude_max", 500.0),
                "combat_radius_min": old.get("fixedwing_combat_radius_min", 40.0),
                "combat_radius_max": old.get("fixedwing_combat_radius_max", 550.0),
                **air_common,
            }),
            behavior("rotary_wing_flight", "rotary_wing_combat_flight", 500, {
                "cruise_altitude_min": old.get("rotary_cruise_altitude_min", 28.0),
                "cruise_altitude_max": old.get("rotary_cruise_altitude_max", 60.0),
                **air_common,
            }),
            behavior("launcher_move", "launcher_positioning", 500, {
                "stop_distance": old.get("drive_stop_distance", 12.0),
            }),
            behavior("recover", "stuck_recovery", 800, {
                "check_interval_tick": old.get("drive_stuck_check_tick", 20),
                "stuck_distance": old.get("drive_stuck_distance", 1.0),
                "recovery_tick": old.get("drive_recovery_tick", 20),
            }),
            behavior("ground_combat_move", "ground_engagement_move", 500, {
                "stop_distance": old.get("drive_stop_distance", 12.0),
                "hold_tick": 100, "evade_tick_min": 140, "evade_tick_max": 280,
                "evade_yaw_deg": 55.0,
            }),
        ])
        if old.get("ground_wander_enabled", True):
            result.append(behavior("ground_idle_patrol", "ground_patrol", 200, {
                "big_turn_interval_tick_min": old.get("ground_big_turn_interval_tick_min", 300),
                "big_turn_interval_tick_max": old.get("ground_big_turn_interval_tick_max", 600),
                "big_turn_angle_deg_min": old.get("ground_big_turn_angle_deg_min", 120.0),
                "big_turn_angle_deg_max": old.get("ground_big_turn_angle_deg_max", 180.0),
                "big_turn_duration_tick": old.get("ground_big_turn_duration_tick", 40),
            }))
    result.append(behavior("main_combat", "weapon_engagement", 500, {
        "fire_window_deg": old.get("fire_window_deg", 6.0),
        "lead_scale": old.get("lead_scale", 1.0),
        "burst_fire_tick": old.get("burst_fire_tick", 6),
        "burst_rest_tick": old.get("burst_rest_tick", 10),
        "guided_weapon_cooldown_tick": 100,
        "ciws_target_cooldown_tick": 100,
    }))
    return {"schema_version": 2, "name": name, "faction": faction, "behaviors": result}


def sample_profiles() -> dict[str, dict]:
    static = {
        "schema_version": 2, "name": "static_gunner", "faction": "friendly", "behaviors": [
            behavior("incoming_ammo", "ciws_targeting", 850, {"scan_interval_tick": 1, "target_cooldown_tick": 100}),
            behavior("main_target", "primary_targeting", 500, {"target_types": ["vehicle", "monster", "player"], "search_radius": 192.0, "scan_interval_tick": 10, "gps_prefer_farthest": True}),
            behavior("own_radar", "ownship_radar", 500),
            behavior("external_radar", "external_radar", 510),
            behavior("guidance", "guided_weapon_support", 520),
            behavior("main_combat", "weapon_engagement", 500, {"fire_window_deg": 6.0, "lead_scale": 1.0, "burst_fire_tick": 6, "burst_rest_tick": 10}),
        ],
    }
    ciws = {
        "schema_version": 2, "name": "ciws_only", "faction": "friendly", "behaviors": [
            behavior("incoming_ammo", "ciws_targeting", 850, {"scan_interval_tick": 1, "target_cooldown_tick": 100}),
            behavior("own_radar", "ownship_radar", 500),
            behavior("main_combat", "weapon_engagement", 500, {"fire_window_deg": 6.0, "lead_scale": 1.0, "burst_fire_tick": 6, "burst_rest_tick": 10}),
        ],
    }
    sead = migrate({"name": "sead_pilot", "faction": "friendly", "target_types": ["vehicle", "player"], "search_radius": 1024.0}, "sead_pilot")
    return {"static_gunner.json": static, "ciws_only.json": ciws, "sead_pilot.json": sead}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--no-samples", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"Gunner Profile 目录不存在: {root}")
    files = sorted(root.glob("*.json"))
    migrated: dict[Path, dict] = {}
    for path in files:
        source = json.loads(path.read_text(encoding="utf-8"))
        if source.get("schema_version") == 2:
            continue
        migrated[path] = migrate(source, path.stem)
    for path, content in migrated.items():
        path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    created = 0
    if not args.no_samples:
        for name, content in sample_profiles().items():
            path = root / name
            if path.exists():
                raise SystemExit(f"示例 Profile 已存在，拒绝覆盖: {path}")
            path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            created += 1
    print(f"已迁移 {len(migrated)} 个 Profile，新增 {created} 个组合样例；目标仅为 {root}")


if __name__ == "__main__":
    main()
