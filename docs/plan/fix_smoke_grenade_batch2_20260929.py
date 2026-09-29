# -*- coding: utf-8 -*-
"""
烟雾弹普及第二批量（2026-09-29，用户点名五台步战/防空车）：cssa5、zbl08a、lavad、lav25、ps1sm

模型（用户已更新）五台均有 turret_smoke_grenade_{l,r}_barrel 双骨：
- ps1sm 双骨挂 vehicle_body（车体，用户定版"烟雾弹在车体"）→ 部件省略 base；
- 其余四台挂 turret → 部件 base=turret；
- zbl08a/lavad/lav25 的旧 smoke_barrel 骨/部件并存，保留未动。

配置（镜像 m1a2sep 模板）：countermeasure.smoke 全套参数 + launcher_parts/bone_modules
绑定双骨 + bone_modules(countermeasure min_damage 20) + factor 0.4 + 双语别名。
改前备份 docs/plan/烟雾弹普及备份_20260929b/；幂等；只改权威载具包。
"""
import json, io, os, shutil

PACK = "limitless_vehicle/rvp/data/rvp"
VEH = os.path.join(PACK, "vehicles")
BACKUP = "docs/plan/烟雾弹普及备份_20260929b"
CM_PARAMS = {
    "total": 4, "per_round": 2, "burst_rounds": 2,
    "launch_interval_tick": 4, "reload_tick": 250,
    "smoke": {"lifetime_tick": 100, "radius": 10.0, "speed": 1.0, "gravity": 0.01,
              "explode_tick": 10, "rise_speed": 0.0, "opacity": 1.0, "drift": 0.0},
    "decoy": {"glow_color": 16777215, "halo_scale": 4.0},
}
LB, RB = "turret_smoke_grenade_l_barrel", "turret_smoke_grenade_r_barrel"
LP, RP = "turret_smoke_grenade_l", "turret_smoke_grenade_r"
BM_ENTRY = {"modules": ["countermeasure"], "min_damage": 20}

def make_part(pid, bone, base):
    p = {"id": pid, "name": pid, "type": "ywzj_vehicle:weapon",
         "structure_bone": bone, "is_seat": False}
    if base:
        p["base"] = base
    p["rot_info"] = {"x_rot_speed": 0, "y_rot_speed": 0}
    return p

def detect_indent(text):
    for line in text.splitlines()[1:]:
        st = line.lstrip(" ")
        if st:
            return len(line) - len(st) or 2
    return 2

TARGETS = {
    "cssa5": "turret", "zbl08a": "turret", "lavad": "turret", "lav25": "turret",
    "ps1sm": None,
}

def main():
    changed = []
    for vid, base in TARGETS.items():
        path = os.path.join(VEH, vid + ".json")
        d = json.load(io.open(path, encoding="utf-8"))
        applied = []
        parts = d.setdefault("parts", [])
        have = {p.get("id") for p in parts if isinstance(p, dict)}
        for pid, bone in ((LP, LB), (RP, RB)):
            if pid not in have:
                parts.append(make_part(pid, bone, base))
                applied.append(f"parts: +{pid}(base={base or '省略'})")
        smoke = d.setdefault("countermeasure", {}).setdefault("smoke", {})
        for k, v in CM_PARAMS.items():
            if k not in smoke:
                smoke[k] = json.loads(json.dumps(v))
                applied.append(f"cm.smoke.{k}")
        if smoke.get("launcher_parts") != [LP, RP]:
            smoke["launcher_parts"] = [LP, RP]; applied.append("cm.smoke.launcher_parts")
        if smoke.get("bone_modules") != [LB, RB]:
            smoke["bone_modules"] = [LB, RB]; applied.append("cm.smoke.bone_modules")
        bm = d.setdefault("bone_modules", {})
        for bone in (LB, RB):
            if bone not in bm:
                bm[bone] = json.loads(json.dumps(BM_ENTRY)); applied.append(f"bm: +{bone}")
        fac = d.setdefault("hitbox_damage_factor", {})
        for bone in (LB, RB):
            if bone not in fac:
                fac[bone] = 0.4; applied.append(f"factor: +{bone}")
        aen = d.setdefault("hitbox_display_name", {})
        acn = d.setdefault("hitbox_display_name_CN", {})
        for bone, val in {LB: "Smoke Launcher (L)", RB: "Smoke Launcher (R)"}.items():
            if bone not in aen:
                aen[bone] = val; applied.append(f"alias: +{bone}")
        for bone, val in {LB: "烟幕弹发射器（左）", RB: "烟幕弹发射器（右）"}.items():
            if bone not in acn:
                acn[bone] = val; applied.append(f"alias_CN: +{bone}")
        if applied:
            changed.append((vid, applied))
            os.makedirs(BACKUP, exist_ok=True)
            bp = os.path.join(BACKUP, os.path.basename(path))
            if not os.path.exists(bp):
                shutil.copyfile(path, bp)
            raw = io.open(path, encoding="utf-8").read()
            io.open(path, "w", encoding="utf-8", newline="\n").write(
                json.dumps(d, ensure_ascii=False, indent=detect_indent(raw)) + "\n")

    print(f"changed={len(changed)}")
    for vid, applied in changed:
        print(f"[{vid}] {applied}")

    print("── verify ──")
    ok = True
    for vid in TARGETS:
        d = json.load(io.open(os.path.join(VEH, vid + ".json"), encoding="utf-8"))
        smoke = (d.get("countermeasure") or {}).get("smoke") or {}
        bm = d.get("bone_modules") or {}
        fac = d.get("hitbox_damage_factor") or {}
        pmap = {p.get("id"): p for p in d.get("parts", []) if isinstance(p, dict)}
        checks = {
            "parts": LP in pmap and RP in pmap,
            "weapon": all(pmap.get(x, {}).get("type") == "ywzj_vehicle:weapon" for x in (LP, RP)),
            "bind": smoke.get("launcher_parts") == [LP, RP] and smoke.get("bone_modules") == [LB, RB],
            "bm": LB in bm and RB in bm,
            "factor": fac.get(LB) == 0.4 and fac.get(RB) == 0.4,
            "alias": LB in (d.get("hitbox_display_name") or {}) and LB in (d.get("hitbox_display_name_CN") or {}),
        }
        bad = [k for k, v in checks.items() if not v]
        print(f"  {vid}: {'PASS' if not bad else 'FAIL ' + str(bad)}")
        ok = ok and not bad
    print(f"verify {'PASS' if ok else 'FAIL'}")

if __name__ == "__main__":
    main()
