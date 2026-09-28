# -*- coding: utf-8 -*-
"""
坦克烟雾弹模块化补全（2026-09-29，用户实机反馈 t90m 后追问"其它坦克是不是也没做好"——范围仅 6 台坦克）

扫描结论（权威包 limitless_vehicle/，五层链 = 结构骨→部件→系统绑定→bone_modules→倍率/别名）：
- vt4 / ztz100 / t90m：完整（t90m 为上一轮 §33.3 补齐）；
- abramsx：结构骨 l/r 在，countermeasure 整个为空 {}、无部件、无 bone_modules/倍率/别名——全空；
- m1a2sep / t84bm：结构模型是 l/r 双骨，但部件与全部配置指向**不存在的单数幽灵骨**
  turret_smoke_grenade_barrel（§31.2.3 批处理从部件 structure_bone 照抄，部件本身就错）
  ——绑定在幽灵骨上 = 模块化实际不生效（与 t90m 修复前同病）。

修复（镜像 vt4/m1a2sep 既有模板）：
- abramsx：新建 countermeasure.smoke（参数镜像 m1a2sep）+ l/r 部件（base=turret，骨在炮塔下）
  + bone_modules/factor/别名全套；
- m1a2sep / t84bm：单数幽灵部件替换为 l/r 双部件（m1a2sep 的烟骨在模型**根部**不随炮塔转
  → 部件省略 base 挂车体，先例 9k720/ah64；t84bm 骨在炮塔下 → base=turret）、绑定/配置/
  倍率/别名全部迁到 l/r 并删除幽灵条目；
- 改前备份 docs/plan/坦克烟雾弹补全备份_20260929/；幂等；只改权威包，不碰 structure.json。
"""
import glob
import io
import json
import os
import shutil

PACK = "limitless_vehicle/rvp/data/rvp"
VEH = os.path.join(PACK, "vehicles")
BACKUP = os.path.join("docs", "plan", "坦克烟雾弹补全备份_20260929")

L_BONE = "turret_smoke_grenade_l_barrel"
R_BONE = "turret_smoke_grenade_r_barrel"
L_PART = "turret_smoke_grenade_l"
R_PART = "turret_smoke_grenade_r"
GHOST_BONE = "turret_smoke_grenade_barrel"
GHOST_PART = "turret_smoke_grenade"

# 参数模板 = m1a2sep 既有值（ sisters 坦克同款）；smoke/decoy 子对象同镜像
CM_PARAMS = {
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

BONE_MODULES_ENTRY = {"modules": ["countermeasure"], "min_damage": 20}


def make_part(pid, bone, base):
    p = {
        "id": pid,
        "name": pid,
        "type": "ywzj_vehicle:weapon",
        "structure_bone": bone,
        "is_seat": False,
    }
    if base is not None:
        # base=None（挂车体）时省略键——m1a2sep 烟骨在模型根部，先例 9k720/ah64
        p["base"] = base
    p["rot_info"] = {"x_rot_speed": 0, "y_rot_speed": 0}
    return p


def detect_indent(text):
    for line in text.splitlines()[1:]:
        stripped = line.lstrip(" ")
        if stripped:
            return len(line) - len(stripped) or 2
    return 2


def fix(vid, part_base, params_needed):
    path = os.path.join(VEH, vid + ".json")
    data = json.load(io.open(path, encoding="utf-8"))
    applied = []

    # ── parts：删幽灵部件，补 l/r 双部件 ──
    parts = data.setdefault("parts", [])
    before = len(parts)
    parts[:] = [p for p in parts if not (isinstance(p, dict) and p.get("id") == GHOST_PART)]
    if len(parts) != before:
        applied.append(f"parts: -{GHOST_PART}")
    have = {p.get("id") for p in parts if isinstance(p, dict)}
    for pid, bone in ((L_PART, L_BONE), (R_PART, R_BONE)):
        if pid not in have:
            parts.append(make_part(pid, bone, part_base))
            applied.append(f"parts: +{pid}(bone={bone}, base={part_base or '省略=车体'})")

    # ── countermeasure.smoke：绑定改 l/r；无参数时补镜像参数 ──
    cm = data.setdefault("countermeasure", {})
    smoke = cm.setdefault("smoke", {})
    for key, val in CM_PARAMS.items():
        if params_needed and key not in smoke:
            smoke[key] = val
            applied.append(f"cm.smoke.{key}={val if not isinstance(val, dict) else '{...}'}")
    if smoke.get("launcher_parts") != [L_PART, R_PART]:
        smoke["launcher_parts"] = [L_PART, R_PART]
        applied.append("cm.smoke.launcher_parts=[l,r]")
    if smoke.get("bone_modules") != [L_BONE, R_BONE]:
        smoke["bone_modules"] = [L_BONE, R_BONE]
        applied.append("cm.smoke.bone_modules=[l,r]")

    # ── bone_modules：删幽灵骨，补 l/r ──
    bm = data.setdefault("bone_modules", {})
    if GHOST_BONE in bm:
        del bm[GHOST_BONE]
        applied.append(f"bone_modules: -{GHOST_BONE}(幽灵骨)")
    for bone in (L_BONE, R_BONE):
        if bone not in bm:
            bm[bone] = json.loads(json.dumps(BONE_MODULES_ENTRY))
            applied.append(f"bone_modules: +{bone}")

    # ── factor / 别名：删幽灵条目，补 l/r ──
    ghost_tables = []
    for table in ("hitbox_damage_factor", "hitbox_display_name", "hitbox_display_name_CN"):
        obj = data.setdefault(table, {})
        if GHOST_BONE in obj:
            del obj[GHOST_BONE]
            ghost_tables.append(table)
    if ghost_tables:
        applied.append(f"{'+'.join(ghost_tables)}: -{GHOST_BONE}(幽灵骨)")
    fac = data.setdefault("hitbox_damage_factor", {})
    for bone in (L_BONE, R_BONE):
        if bone not in fac:
            fac[bone] = 0.4
            applied.append(f"factor: +{bone}=0.4")
    alias_pairs = {
        "hitbox_display_name": {L_BONE: "Smoke Launcher (L)", R_BONE: "Smoke Launcher (R)"},
        "hitbox_display_name_CN": {L_BONE: "烟幕弹发射器（左）", R_BONE: "烟幕弹发射器（右）"},
    }
    for table, pairs in alias_pairs.items():
        obj = data.setdefault(table, {})
        for bone, val in pairs.items():
            if bone not in obj:
                obj[bone] = val
                applied.append(f"{table}: +{bone}={val}")

    if applied:
        os.makedirs(BACKUP, exist_ok=True)
        backup_path = os.path.join(BACKUP, os.path.basename(path))
        if not os.path.exists(backup_path):
            shutil.copyfile(path, backup_path)
        raw = io.open(path, encoding="utf-8").read()
        indent = detect_indent(raw)
        io.open(path, "w", encoding="utf-8", newline="\n").write(
            json.dumps(data, ensure_ascii=False, indent=indent) + "\n")
    return applied


def verify():
    print("── verify（6 台坦克五层链）──")
    ok = True
    for vid in ("t90m", "vt4", "ztz100", "abramsx", "m1a2sep", "t84bm"):
        data = json.load(io.open(os.path.join(VEH, vid + ".json"), encoding="utf-8"))
        smoke = (data.get("countermeasure") or {}).get("smoke") or {}
        bm = data.get("bone_modules") or {}
        fac = data.get("hitbox_damage_factor") or {}
        aen = data.get("hitbox_display_name") or {}
        acn = data.get("hitbox_display_name_CN") or {}
        part_ids = {p.get("id") for p in data.get("parts", []) if isinstance(p, dict)}
        checks = {
            "parts_l_r": L_PART in part_ids and R_PART in part_ids,
            "cm_parts": smoke.get("launcher_parts") == [L_PART, R_PART],
            "cm_bones": smoke.get("bone_modules") == [L_BONE, R_BONE],
            "bone_modules": L_BONE in bm and R_BONE in bm,
            "factor": fac.get(L_BONE) == 0.4 and fac.get(R_BONE) == 0.4,
            "alias_en": L_BONE in aen and R_BONE in aen,
            "alias_cn": L_BONE in acn and R_BONE in acn,
            "no_ghost": GHOST_BONE not in bm and GHOST_BONE not in fac
                and GHOST_PART not in part_ids,
        }
        bad = [k for k, v in checks.items() if not v]
        print(f"  {vid}: {'PASS' if not bad else 'FAIL ' + str(bad)}")
        ok = ok and not bad
    print(f"verify {'PASS' if ok else 'FAIL'}")


def main():
    plans = [
        ("abramsx", "turret", True),    # 全空：镜像参数 + 部件挂炮塔（骨在炮塔下）
        ("m1a2sep", None, False),       # 幽灵骨改正：骨在模型根部 → 省略 base 挂车体
        ("t84bm", "turret", False),     # 幽灵骨改正：骨在炮塔下
        ("t90m", None, False),          # 已齐（§33.3），只校验
        ("vt4", None, False),
        ("ztz100", None, False),
    ]
    for vid, base, params in plans:
        applied = fix(vid, base, params)
        print(f"[{vid}] {'no-change' if not applied else ''}")
        for line in applied:
            print(f"  {line}")
    verify()


if __name__ == "__main__":
    main()
