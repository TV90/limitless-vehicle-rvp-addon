# -*- coding: utf-8 -*-
"""
CM（干扰物）绑定骨命中倍率/别名回填（2026-09-29，用户实机反馈 t90m 烟雾弹命中显示实体名）

背景：§31.2.3 的 22 台 countermeasure 批处理只写了 bone_modules/countermeasure 两侧，
没有同步补 hitbox_damage_factor 与 hitbox_display_name(_CN)——t90m 烟幕骨只剩 09-02 时代的
单数旧骨条目（turret_smoke_grenade_barrel，结构模型里不存在），16 台飞机的 decoy 骨
（decoy_flare[_barrel]，flare/chaff 共用）则完全没有条目。

规则（保守，只补缺、不改已有）：
- 别名：有旧单数条目 → 抄其名，l/r 骨加 " (L)"/" (R)" / "（左）"/"（右）"后缀（对齐 vt4 模板）；
  无旧条目 → 按系统类型推导：多系统共用骨（飞机 flare+chaff 同骨）用中性名
  "Decoy Launcher"/"干扰弹发射器"，单一系统按 flare/chaff/smoke 推导；
- 倍率：仅当旧单数条目存在时抄其值（t90m → 0.4）；无旧条目不发明（飞机维持缺省 1.0，
  避免未要求的平衡性变更）。
- 只改权威载具包 limitless_vehicle/（run/** 是冒烟沙盒陈年包，禁碰，见 agents.md 2026-09-29 铁律）；
  改前备份到 docs/plan/CM命中别名补全备份_20260929/；幂等（重跑 = 无改动）。
"""
import glob
import io
import json
import os
import re
import shutil

PACK = os.path.join("limitless_vehicle", "rvp", "data", "rvp", "vehicles")
BACKUP = os.path.join("docs", "plan", "CM命中别名补全备份_20260929")

SYSTEM_FALLBACK = {
    "smoke": ("Smoke Launcher", "烟幕弹发射器"),
    "flare": ("Flare Launcher", "热焰弹发射器"),
    "chaff": ("Chaff Launcher", "箔条发射器"),
}
SHARED_FALLBACK = ("Decoy Launcher", "干扰弹发射器")


def detect_indent(text):
    """按文件既有缩进风格回写（首行嵌套缩进宽度），保持与历史脚本产物一致。"""
    for line in text.splitlines()[1:]:
        stripped = line.lstrip(" ")
        if stripped and not line.startswith(" " * len(line)):
            return len(line) - len(stripped) or 2
    return 2


def legacy_singluar(bone):
    """旧单数骨名候选：双侧 _l_barrel/_r_barrel → _barrel（09-02 迁移时代命名）。"""
    out = bone.replace("_l_barrel", "_barrel").replace("_r_barrel", "_barrel")
    if out == bone:
        out = re.sub(r"_l$", "", re.sub(r"_r$", "", bone))
    return out


def side_suffix(bone, cn=False):
    if re.search(r"_l(_|$)", bone):
        return "（左）" if cn else " (L)"
    if re.search(r"_r(_|$)", bone):
        return "（右）" if cn else " (R)"
    return ""


def main():
    files = sorted(glob.glob(os.path.join(PACK, "*.json")))
    bone_systems = {}  # 骨名 → 绑定它的系统集合（跨车统计共用骨）
    parsed = {}
    for path in files:
        vid = os.path.splitext(os.path.basename(path))[0]
        data = json.load(io.open(path, encoding="utf-8"))
        parsed[path] = (vid, data)
        for sys_name, sys_cfg in (data.get("countermeasure") or {}).items():
            if isinstance(sys_cfg, dict):
                for bone in sys_cfg.get("bone_modules") or []:
                    bone_systems.setdefault(bone, set()).add(sys_name)

    changed = []
    for path, (vid, data) in parsed.items():
        bound = []
        for sys_name, sys_cfg in (data.get("countermeasure") or {}).items():
            if isinstance(sys_cfg, dict):
                for bone in sys_cfg.get("bone_modules") or []:
                    bound.append((sys_name, bone))
        if not bound:
            continue
        factors = data.setdefault("hitbox_damage_factor", {})
        alias_en = data.setdefault("hitbox_display_name", {})
        alias_cn = data.setdefault("hitbox_display_name_CN", {})
        applied = []
        for sys_name, bone in bound:
            legacy = legacy_singluar(bone)
            if bone not in alias_en and bone not in alias_cn:
                base_en = alias_en.get(legacy)
                base_cn = alias_cn.get(legacy)
                if base_en is None or base_cn is None:
                    if len(bone_systems.get(bone, ()) ) > 1:
                        base_en, base_cn = SHARED_FALLBACK
                    else:
                        base_en, base_cn = SYSTEM_FALLBACK.get(sys_name, SHARED_FALLBACK)
                alias_en[bone] = base_en + side_suffix(bone)
                alias_cn[bone] = base_cn + side_suffix(bone, cn=True)
                applied.append(f"alias[{bone}]={alias_en[bone]}/{alias_cn[bone]}")
            if bone not in factors:
                legacy_factor = factors.get(legacy)
                if legacy_factor is not None:
                    factors[bone] = legacy_factor
                    applied.append(f"factor[{bone}]={legacy_factor}(from {legacy})")
                # 无旧条目不发明倍率（飞机 decoy 维持缺省 1.0，避免未要求的平衡变更）
        if applied:
            changed.append((vid, applied))
            os.makedirs(BACKUP, exist_ok=True)
            backup_path = os.path.join(BACKUP, os.path.basename(path))
            if not os.path.exists(backup_path):
                shutil.copyfile(path, backup_path)
            raw = io.open(path, encoding="utf-8").read()
            indent = detect_indent(raw)
            text = json.dumps(data, ensure_ascii=False, indent=indent) + "\n"
            io.open(path, "w", encoding="utf-8", newline="\n").write(text)

    print(f"scanned={len(files)} vehicles_with_cm_bindings={sum(1 for _, (v, d) in parsed.items() if (d.get('countermeasure') or {}))} changed={len(changed)}")
    for vid, applied in changed:
        print(f"[{vid}]")
        for line in applied:
            print(f"  {line}")

    # ── 写后校验：重载并断言每根绑定骨的别名齐全、有旧条目者倍率已补 ──
    print("── verify ──")
    bad = 0
    for path in files:
        vid = os.path.splitext(os.path.basename(path))[0]
        data = json.load(io.open(path, encoding="utf-8"))
        cm = data.get("countermeasure") or {}
        if not cm:
            continue
        factors = data.get("hitbox_damage_factor") or {}
        alias_en = data.get("hitbox_display_name") or {}
        alias_cn = data.get("hitbox_display_name_CN") or {}
        for sys_name, sys_cfg in cm.items():
            if not isinstance(sys_cfg, dict):
                continue
            for bone in sys_cfg.get("bone_modules") or []:
                if bone not in alias_en or bone not in alias_cn:
                    print(f"  MISSING alias {vid}:{bone}")
                    bad += 1
                legacy = legacy_singluar(bone)
                if legacy in factors and bone not in factors:
                    print(f"  MISSING factor(copy) {vid}:{bone}")
                    bad += 1
    print(f"verify {'PASS' if bad == 0 else f'FAIL({bad})'}")


if __name__ == "__main__":
    main()
