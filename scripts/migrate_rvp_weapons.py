#!/usr/bin/env python3
"""Migrate rvp_vehicle weapons into src/main/resources/rvp with MCHeli-style names."""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_WEAPONS = ROOT / "src/main/resources/rvp_vehicle/data/ywzj_vehicle/weapons"
OUT_PACK = ROOT / "src/main/resources/rvp"
OUT_WEAPONS = OUT_PACK / "data/rvp/weapons"
OUT_DISPLAY = OUT_PACK / "assets/rvp/display/weapon"

WEAPON_MAP = {
    "mclos_missile_test.json": ("gp125_atgm_mclos.json", "GP125 线导反坦克导弹", "missile_launch"),
    "tv_missile_test.json": ("gp125_atgm_tv.json", "GP125 电视制导反坦克导弹", "missile_launch"),
    "saclos_missile_test.json": ("tow_2n.json", "TOW-2N 反坦克导弹", "missile_launch"),
    "ir_missile_test.json": ("tow_2ir.json", "TOW-2IR 红外制导反坦克导弹", "missile_launch"),
    "sarh_missile_test.json": ("tor_9m331er.json", "9M331-ER 半主动雷达制导导弹", "missile_launch"),
    "anti_radiation_missile.json": ("yj91_arm.json", "鹰击91 反辐射导弹", "missile_launch"),
    "canister_shot_test.json": ("sh33_canister.json", "3SH33 榴霰弹", "auto_cannon_fire"),
    "cluster_bomb_test.json": ("agm154_jsow.json", "AGM-154 JSOW 集束弹药布撒器", "missile_launch"),
    "rvp_missile_example.json": ("pl15_arh.json", "PL-15 主动雷达空空导弹", "missile_launch"),
    "rvp_bomb_example.json": ("yj20_gps_bomb.json", "鹰击-20 卫星制导炸弹", "missile_launch"),
    "rvp_rocket_example.json": ("s13of_rocket.json", "S-13OF 122毫米火箭弹", "missile_launch"),
    "rvp_machinegun_example.json": ("shipovn_2a42.json", "2A42 30毫米机炮", "auto_cannon_fire"),
    "rvp_laser_example.json": ("liaoyuan3_laser.json", "燎原-3 轻型激光器", "auto_cannon_fire"),
    "rvp_dispenser_example.json": ("disp_general.json", "通用快速布雷器", "missile_launch"),
    "rvp_targetingpod_example.json": ("generic_targeting_pod.json", "通用目标指示吊舱", "auto_cannon_fire"),
}


def main() -> None:
    OUT_WEAPONS.mkdir(parents=True, exist_ok=True)
    OUT_DISPLAY.mkdir(parents=True, exist_ok=True)

    for src_name, (out_name, display_name, fire_sound) in WEAPON_MAP.items():
        src_path = SRC_WEAPONS / src_name
        data = json.loads(src_path.read_text(encoding="utf-8"))
        data["name"] = display_name
        out_path = OUT_WEAPONS / out_name
        out_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        weapon_id = out_path.stem
        display = {
            "type": "ywzj_vehicle:weapon",
            "sounds": {
                "fire": f"ywzj_vehicle:{fire_sound}",
                "reload": "ywzj_vehicle:common_reload",
            },
        }
        (OUT_DISPLAY / f"{weapon_id}.json").write_text(
            json.dumps(display, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    meta_src = ROOT / "run/client_1/limitless_vehicle/rvp/vehicle_pack.meta.json"
    if meta_src.is_file():
        shutil.copy2(meta_src, OUT_PACK / "vehicle_pack.meta.json")
    else:
        (OUT_PACK / "vehicle_pack.meta.json").write_text(
            """{
  "namespace": "rvp",
  "title": "RealVehicleProject",
  "description": "YWZJ RVP addon vehicle pack",
  "version": "0.5.6",
  "date": "2026-06-03",
  "license": "All Rights Reserved",
  "authors": ["YWZJ"]
}
""",
            encoding="utf-8",
        )

    # Remove legacy misplaced pack
    legacy = ROOT / "src/main/resources/rvp_vehicle"
    if legacy.is_dir():
        shutil.rmtree(legacy)

    # Sync to dev run folder
    run_pack = ROOT / "run/client_1/limitless_vehicle/rvp"
    if run_pack.parent.is_dir():
        if run_pack.is_dir():
            shutil.rmtree(run_pack)
        shutil.copytree(OUT_PACK, run_pack)

    # Remove duplicate test weapons from default_vehicle runtime pack
    dv_weapons = ROOT / "run/client_1/limitless_vehicle/default_vehicle/data/ywzj_vehicle/weapons"
    for old in list(WEAPON_MAP.keys()):
        p = dv_weapons / old
        if p.is_file():
            p.unlink()

    print(f"Migrated {len(WEAPON_MAP)} weapons to {OUT_PACK}")


if __name__ == "__main__":
    main()
