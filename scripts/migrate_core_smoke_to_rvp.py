#!/usr/bin/env python3
"""把载具 JSON 里的本体烟雾弹（ywzj_vehicle:launcher_smoke_grenade）迁移为 RVP 烟雾干扰物系统。

- 删除 weapons 里的 launcher_smoke_grenade 条目（"取消原有的本体烟雾弹"）；
- 单管骨车（m1a2sep/t84bm）保留原发射件不动；双管骨车（t90m/vt4）把旧单件替换为左右两个发射件；
- 顶层写入 countermeasure.smoke，数值统一用 T90M 备份值（launcher_parts 指向各车发射件）；
- 顺带恢复 m142 被覆盖丢失的 bone_modules.ecm_bone（照 E 盘备份原文）。

方案：docs/plan/VT4重做配置清单_20260902.md。默认 dry-run，--apply 才落盘。
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

VEHICLE_DIR = Path(__file__).resolve().parents[1] / "limitless_vehicle" / "rvp" / "data" / "rvp" / "vehicles"
CORE_SMOKE_ID = "ywzj_vehicle:launcher_smoke_grenade"

# 统一使用 T90M 备份的烟雾参数（2026-09-02 确认）
SMOKE_SYSTEM = {
    "total": 4,
    "per_round": 2,
    "burst_rounds": 2,
    "launch_interval_tick": 4,
    "reload_tick": 250,
    "smoke": {
        "lifetime_tick": 100,
        "radius": 10.0,
        "speed": 1.0,
        "gravity": 0.01,
        "explode_tick": 10,
        "rise_speed": 0.0,
        "opacity": 1.0,
        "drift": 0.0,
    },
    "decoy": {"glow_color": 16777215, "halo_scale": 4.0},
}

# 每车发射件规划：(part_id, structure_bone)。single=True 表示保留车上已有发射件原样。
PLAN = {
    "m1a2sep": {"parts": [("turret_smoke_grenade", "turret_smoke_grenade_barrel")], "keep_existing": True},
    "t84bm": {"parts": [("turret_smoke_grenade", "turret_smoke_grenade_barrel")], "keep_existing": True},
    "t90m": {
        "parts": [
            ("turret_smoke_grenade_l", "turret_smoke_grenade_l_barrel"),
            ("turret_smoke_grenade_r", "turret_smoke_grenade_r_barrel"),
        ],
        "keep_existing": False,
        "remove_old_part_id": "turret_smoke_grenade",
    },
    "vt4": {
        "parts": [
            ("turret_smoke_grenade_l", "turret_smoke_grenade_l_barrel"),
            ("turret_smoke_grenade_r", "turret_smoke_grenade_r_barrel"),
        ],
        "keep_existing": False,
        "remove_old_part_id": "turret_smoke_grenade",
    },
}

# m142 被覆盖丢失的 ECM 配置（照 E 盘备份原文）
M142_ECM = {"modules": ["ecm_active"], "ecm_active": {"ammo_jam_radius": 250, "vehicle_jam_radius": 0}}


def read_raw(path: Path) -> str:
    """按原样读取（不做通用换行转换），保留 CRLF。"""
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def detect_style(text: str) -> tuple[str, bool, bool]:
    """探测缩进字符串、是否 CRLF、文件末尾是否有换行。"""
    m = re.search(r"\r?\n(\s+)\"", text)
    indent = m.group(1) if m else "  "
    if indent.startswith("\t"):
        indent = "\t"
    else:
        indent = " " * len(indent)
    crlf = "\r\n" in text
    trailing = text.endswith("\n")
    return indent, crlf, trailing


def dump_like(data, indent: str, crlf: bool, trailing: bool) -> str:
    text = json.dumps(data, ensure_ascii=False, indent=indent)
    if crlf:
        text = text.replace("\n", "\r\n")
    if trailing:
        text += "\r\n" if crlf else "\n"
    return text


def remove_core_smoke_weapons(d: dict) -> int:
    """删除所有 weapons 列表里的本体烟雾弹条目，返回删除数。"""
    removed = 0
    for part in d.get("parts", []):
        weapons = part.get("weapons")
        if not isinstance(weapons, list):
            continue
        keep = []
        for it in weapons:
            wid = it.get("id") if isinstance(it, dict) else it
            if wid == CORE_SMOKE_ID:
                removed += 1
                continue
            keep.append(it)
        part["weapons"] = keep
    return removed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="实际执行（默认 dry-run）")
    args = parser.parse_args()

    # 1) 烟雾迁移
    for vid, plan in PLAN.items():
        path = VEHICLE_DIR / f"{vid}.json"
        text = read_raw(path)
        d = json.loads(text)
        removed = remove_core_smoke_weapons(d)

        parts = d["parts"]
        part_ids = [p.get("id") for p in parts]
        if not plan["keep_existing"]:
            old_id = plan["remove_old_part_id"]
            if old_id in part_ids:
                idx = part_ids.index(old_id)
                parts.pop(idx)
                new_parts = [
                    {
                        "id": pid,
                        "name": pid,
                        "type": "ywzj_vehicle:weapon",
                        "structure_bone": bone,
                        "is_seat": False,
                        "base": "turret",
                        "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0},
                    }
                    for pid, bone in plan["parts"]
                ]
                for offset, np in enumerate(new_parts):
                    parts.insert(idx + offset, np)
                print(f"[{vid}] 旧发射件 {old_id} 替换为 {len(new_parts)} 个左右件")
            else:
                print(f"[{vid}] 未找到旧发射件 {old_id}（可能已迁移），仅补件")
                for pid, bone in plan["parts"]:
                    if pid not in part_ids:
                        parts.append({
                            "id": pid, "name": pid, "type": "ywzj_vehicle:weapon",
                            "structure_bone": bone, "is_seat": False, "base": "turret",
                            "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0},
                        })
        else:
            missing = [pid for pid, _ in plan["parts"] if pid not in part_ids]
            if missing:
                print(f"[{vid}] 警告：保留模式下缺少发射件 {missing}")

        launcher_parts = [pid for pid, _ in plan["parts"]]
        cm = d.setdefault("countermeasure", {})
        cm["smoke"] = {**SMOKE_SYSTEM, "launcher_parts": launcher_parts}
        print(f"[{vid}] 删除本体烟雾弹条目 {removed} 处；countermeasure.smoke.launcher_parts = {launcher_parts}")

        if args.apply:
            indent, crlf, trailing = detect_style(text)
            path.write_text(dump_like(d, indent, crlf, trailing), encoding="utf-8", newline="")

    # 2) m142 ECM 恢复
    m142 = VEHICLE_DIR / "m142.json"
    text = read_raw(m142)
    d = json.loads(text)
    if "ecm_bone" in d.get("bone_modules", {}):
        print("[m142] ecm_bone 已存在，跳过")
    else:
        d.setdefault("bone_modules", {})["ecm_bone"] = M142_ECM
        print("[m142] 恢复 bone_modules.ecm_bone（照备份原文）")
        if args.apply:
            indent, crlf, trailing = detect_style(text)
            m142.write_text(dump_like(d, indent, crlf, trailing), encoding="utf-8", newline="")

    mode = "APPLY" if args.apply else "DRY-RUN（加 --apply 落盘）"
    print(f"\n[{mode}] 完成")


if __name__ == "__main__":
    main()
