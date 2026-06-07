#!/usr/bin/env python3
"""One-off: migrate legacy ywzj_vehicle weapon types in rvp pack to rvp:* JSON."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "run/client_1/limitless_vehicle/rvp"
WEAPONS = ROOT / "data/rvp/weapons"
DISPLAY = ROOT / "assets/rvp/display/weapon"


def write_weapon(name: str, data: dict) -> None:
    path = WEAPONS / f"{name}.json"
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def ir_missile(
    name: str,
    damage: int = 80,
    capacity: int = 4,
    require_lock: bool = True,
    shoot_interval: int = 500,
) -> dict:
    return {
        "type": "rvp:missile",
        "name": name,
        "damage": damage,
        "shoot_interval": shoot_interval,
        "max_capacity": capacity,
        "reload": {"time": 80, "ammo": "ywzj_vehicle:ammo_missile"},
        "require_lock": require_lock,
        "fire": {"mode": "single", "projectile_count": 1, "spread": 0},
        "projectile": {
            "kind": "missile",
            "velocity": 2.4,
            "acceleration": 2.4,
            "constant_speed": True,
            "rotate_to_motion": True,
            "max_speed": 3.4,
        },
        "fuse": {
            "delay_tick": 0,
            "time_tick": 0,
            "proximity_radius": 3,
            "detonate_on_life_end": False,
        },
        "seeker": {
            "range": 384,
            "fov": 30,
            "scan_interval_tick": 3,
            "ignore_flares": False,
        },
        "guidance": [
            {
                "name": "ir_terminal",
                "start_tick": 0,
                "sources": [
                    {"type": "IR", "priority": 100, "fallback_on_jammed": True},
                    {"type": "IOG", "priority": 10},
                ],
            }
        ],
        "effects": {
            "trajectory_particle": "minecraft:smoke",
            "impact_particle": "minecraft:block",
            "explosion_particle": "minecraft:explosion",
        },
        "explosion": {
            "explode": True,
            "damage": 180,
            "radius": 6,
            "proximity_fuze": True,
            "proximity_radius": 3,
            "destroy_block": True,
        },
    }


def arh_missile(name: str, damage: int = 80, capacity: int = 4) -> dict:
    d = ir_missile(name, damage, capacity, require_lock=False)
    d["guidance"] = [
        {
            "name": "boost_iog",
            "start_tick": 0,
            "end_tick": 9,
            "sources": [{"type": "IOG", "priority": 100}],
        },
        {
            "name": "terminal_arh",
            "start_tick": 10,
            "sources": [
                {"type": "ARH", "priority": 100},
                {"type": "IOG", "priority": 10},
            ],
        },
    ]
    d["seeker"] = {
        "range": 256,
        "scan_interval_tick": 20,
        "fov": 30,
        "ignore_chaff": False,
        "jam_resistance": 0.2,
    }
    d["explosion"]["damage"] = 200
    return d


def machinegun(name: str, damage: int, interval: int, capacity: int) -> dict:
    return {
        "type": "rvp:machinegun",
        "name": name,
        "damage": damage,
        "inaccuracy": 0.6,
        "shoot_interval": interval,
        "max_capacity": capacity,
        "reload": {"time": 100, "ammo": "ywzj_vehicle:ammo_auto_cannon"},
        "fire": {"mode": "single", "projectile_count": 1, "spread": 0.35},
        "projectile": {
            "kind": "machinegun",
            "velocity": 4,
            "acceleration": 4,
            "gravity": -0.005,
            "drag": 0.001,
            "rotate_to_motion": True,
            "max_speed": 4.5,
            "min_speed": 0.5,
        },
        "damage_model": {
            "direct": damage,
            "decay": [
                {
                    "type": "linear",
                    "start_distance": 0,
                    "end_distance": 256,
                    "min_factor": 0.25,
                }
            ],
            "living_penetration": 0,
            "wall_penetration": 0,
            "bounce": 0,
        },
        "effects": {
            "trajectory_particle": "none",
            "impact_particle": "minecraft:block",
            "explosion_particle": "minecraft:explosion",
        },
        "explosion": {
            "explode": False,
            "damage": 0,
            "radius": 0,
            "destroy_block": False,
        },
    }


def rocket(name: str, capacity: int) -> dict:
    return {
        "type": "rvp:rocket",
        "name": name,
        "damage": 50,
        "shoot_interval": 200,
        "max_capacity": capacity,
        "reload": {"time": 60, "ammo": "ywzj_vehicle:ammo_rocket"},
        "fire": {"mode": "single", "projectile_count": 1, "spread": 0.2},
        "projectile": {
            "kind": "rocket",
            "velocity": 4,
            "acceleration": 4,
            "gravity": 0,
            "drag": 0,
            "constant_speed": True,
            "rotate_to_motion": True,
            "max_speed": 4.5,
        },
        "fuse": {"time_tick": 0, "airburst": False, "detonate_on_life_end": False},
        "guidance": [
            {
                "name": "unguided",
                "start_tick": 0,
                "sources": [{"type": "NONE", "priority": 100}],
            }
        ],
        "damage_model": {
            "direct": 50,
            "decay": [
                {
                    "type": "linear",
                    "start_distance": 0,
                    "end_distance": 256,
                    "min_factor": 0.25,
                }
            ],
            "living_penetration": 0,
            "wall_penetration": 0,
            "bounce": 0,
        },
        "effects": {
            "trajectory_particle": "minecraft:smoke",
            "impact_particle": "minecraft:block",
            "explosion_particle": "minecraft:explosion",
        },
        "explosion": {
            "explode": True,
            "damage": 120,
            "radius": 4,
            "destroy_block": True,
        },
    }


def tv_missile(name: str, capacity: int = 8) -> dict:
    return {
        "type": "rvp:missile",
        "name": name,
        "damage": 90,
        "shoot_interval": 800,
        "max_capacity": capacity,
        "reload": {"time": 100, "ammo": "ywzj_vehicle:ammo_missile"},
        "require_lock": False,
        "fire": {"mode": "single", "projectile_count": 1, "spread": 0},
        "projectile": {
            "kind": "missile",
            "velocity": 1.8,
            "acceleration": 1.8,
            "constant_speed": True,
            "rotate_to_motion": True,
            "max_speed": 2.4,
        },
        "fuse": {
            "delay_tick": 0,
            "time_tick": 0,
            "proximity_radius": 2.5,
            "detonate_on_life_end": False,
        },
        "tv_missile_control_range": 2000,
        "tv_missile_timeout_tick": 400,
        "tv_missile_video_modes": ["COLOR", "BW", "THERMAL"],
        "guidance": [
            {
                "name": "tv_command",
                "start_tick": 0,
                "sources": [
                    {"type": "TV", "priority": 100, "take_over_motion": True}
                ],
            }
        ],
        "effects": {
            "trajectory_particle": "minecraft:smoke",
            "impact_particle": "minecraft:block",
            "explosion_particle": "minecraft:explosion",
        },
        "explosion": {
            "explode": True,
            "damage": 160,
            "radius": 5,
            "proximity_fuze": True,
            "proximity_radius": 2.5,
            "destroy_block": True,
        },
    }


def main() -> None:
    conversions = {
        "agm_114": tv_missile("AGM-114 Hellfire", 8),
        "pl_10": ir_missile("PL-10", 80, 2, True, 500),
        "pl_8": ir_missile("PL-8", 80, 6, True, 500),
        "pl_12": arh_missile("PL-12", 80, 6),
        "pl_12_2": arh_missile("PL-12C", 80, 2),
        "aim_120": arh_missile("AIM-120D3", 80, 6),
        "yj_15": ir_missile("YJ-15", 100, 1, False, 1000),
        "mi28_kh_39t": tv_missile("KH-39T", 2),
        "mi28_2a42": machinegun("2A42 30MM", 8, 50, 120),
        "mi28_s13": rocket("S-13", 10),
        "gsh_30_1": machinegun("30mm GSh-30-1", 30, 33, 150),
        "ah64_m230": machinegun("M230 30mm", 25, 50, 1200),
    }
    for wid, data in conversions.items():
        write_weapon(wid, data)

    # display stubs for new ids
    stubs = {
        "pl_10": ("rvp:entity/missile_pl_12", "rvp:textures/entity/j16d.png"),
        "pl_8": ("rvp:entity/missile_pl_12", "rvp:textures/entity/j16d.png"),
        "ah64_m230": (None, None),
    }
    for wid, (model, tex) in stubs.items():
        p = DISPLAY / f"{wid}.json"
        if p.exists():
            continue
        body = {
            "type": "ywzj_vehicle:weapon",
            "sounds": {
                "fire": "rvp:gsh_30_1_shot" if "m230" in wid or wid == "gsh_30_1" else "ywzj_vehicle:missile_launch",
                "reload": "ywzj_vehicle:gun_reload" if "m230" in wid else "ywzj_vehicle:common_reload",
            },
        }
        if model:
            body["model"] = model
            body["texture"] = tex
        p.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    ah64_m230_display = DISPLAY / "ah64_m230.json"
    ah64_m230_display.write_text(
        json.dumps(
            {
                "type": "ywzj_vehicle:weapon",
                "sounds": {
                    "fire": "rvp:gsh_30_1_shot",
                    "reload": "ywzj_vehicle:gun_reload",
                },
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    meta = ROOT / "vehicle_pack.meta.json"
    meta_data = json.loads(meta.read_text(encoding="utf-8"))
    meta_data["version"] = "0.5.8"
    meta.write_text(json.dumps(meta_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Migrated", len(conversions), "weapons; pack version -> 0.5.8")


if __name__ == "__main__":
    main()
