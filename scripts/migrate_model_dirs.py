#!/usr/bin/env python3
"""整理载具包 Bedrock 模型目录与贴图目录：
弹体模型 → entity/ammo/，挂架模型 → entity/weapon_mount/，弹体贴图 → textures/entity/ammo/，
并同步改写 display / vehicle data 中对这些资源的 "model" / "texture" 引用。

方案文档：docs/plan/RVP模型目录整理方案_20260901.md
默认 dry-run（只打印计划，不落盘）；加 --apply 才真正移动文件与改写引用。
孤儿模型/贴图（全包零引用）只输出报告，绝不移动或删除（处置需用户确认）。
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

PACK_ROOT = Path(__file__).resolve().parents[1] / "limitless_vehicle" / "rvp"
MODEL_DIR = PACK_ROOT / "assets" / "rvp" / "models" / "bedrock" / "entity"
TEXTURE_DIR = PACK_ROOT / "assets" / "rvp" / "textures" / "entity"
SCAN_DIRS = (
    PACK_ROOT / "assets" / "rvp",
    PACK_ROOT / "data" / "rvp",
)

# A/B 组移动映射：模型文件名（不含 .json）→ 目标子目录（相对 entity/）
MOVE_MAP = {
    # ── A 组：弹体/弹药模型 → ammo/ ──
    "bomb_spice_1000": "ammo",
    "gb3": "ammo",
    "missile_9m317": "ammo",
    "missile_9m723": "ammo",
    "missile_aasm": "ammo",
    "missile_agm_114": "ammo",
    "missile_kh_38": "ammo",
    "missile_kh_39": "ammo",
    "missile_kh_58": "ammo",
    "missile_mica": "ammo",
    "missile_pl_12": "ammo",
    "missile_stormshadow": "ammo",
    "missile_yj_15": "ammo",
    "ucavlunner": "ammo",
    # ── B 组：挂架模型 → weapon_mount/ ──
    "weapon_mount_aasm": "weapon_mount",
    "weapon_mount_aim120_f22": "weapon_mount",
    "weapon_mount_aim260_f22": "weapon_mount",
    "weapon_mount_gb3": "weapon_mount",
    "weapon_mount_kd88a": "weapon_mount",
    "weapon_mount_pl12": "weapon_mount",
    "weapon_mount_pl15": "weapon_mount",
    "weapon_mount_stormshadow": "weapon_mount",
    "weapon_mount_twin_pl12": "weapon_mount",
    "weapon_mount_twin_pl15": "weapon_mount",
    "weapon_mount_yj91": "weapon_mount",
}

# 贴图移动映射：贴图文件名（不含 .png）→ 目标子目录（相对 textures/entity/）。
# 仅收"只被武器 display 引用"的弹体贴图；被弹药 display 直接引用的载具贴图
# （ah64/j15/j15t/UCAVLauncher）按方案约定保留原地不动。
TEXTURE_MOVE_MAP = {
    "bomb_gb3": "ammo",
    "bomb_spice_1000": "ammo",
    "missile_9m317": "ammo",
    "missile_aasm": "ammo",
    "missile_kh_38": "ammo",
    "missile_kh_39": "ammo",
    "missile_kh_58": "ammo",
    "missile_mica": "ammo",
    "missile_stormshadow": "ammo",
}


def find_reference_files(token: str) -> list[Path]:
    """在 display 与 data 下查找包含整词引用 token 的 JSON 文件。"""
    hits = []
    for root in SCAN_DIRS:
        if not root.is_dir():
            continue
        for path in root.rglob("*.json"):
            if token in path.read_text(encoding="utf-8"):
                hits.append(path)
    return sorted(set(hits))


def rewrite_file(path: Path, pairs: list[tuple[str, str]], apply: bool) -> int:
    text = path.read_text(encoding="utf-8")
    total = 0
    for old, new in pairs:
        count = text.count(old)
        if count:
            text = text.replace(old, new)
            total += count
    if apply and total:
        path.write_text(text, encoding="utf-8", newline="\n")
    return total


def orphan_report(scan_dir: Path, ref_base: Path, ref_fmt: str, strip_suffix: bool) -> list[Path]:
    """全包零引用的资源文件。引用 token 由 ref_fmt 生成：{rel} 为相对 ref_base 的路径。

    模型引用无后缀（"rvp:entity/tow"），贴图引用保留 .png（"rvp:textures/entity/ammo/tow.png"）。
    """
    orphans = []
    for path in sorted(scan_dir.rglob("*.json")) + sorted(scan_dir.rglob("*.png")):
        rel = path.relative_to(ref_base)
        if strip_suffix:
            rel = rel.with_suffix("")
        ref = ref_fmt.format(rel=rel.as_posix())
        referenced = any(
            ref in p.read_text(encoding="utf-8", errors="ignore")
            for root in SCAN_DIRS if root.is_dir()
            for p in root.rglob("*.json")
        )
        if not referenced:
            orphans.append(path)
    return orphans


def migrate_group(title: str, base_dir: Path, move_map: dict[str, str],
                  file_fmt: str, old_ref_fmt: str, new_ref_fmt: str, apply: bool) -> tuple[int, int, int]:
    """执行一组移动 + 引用改写，返回 (移动数, 改写文件数, 改写处数)。

    file_fmt     文件名模式，如 "{name}.json"
    old_ref_fmt  旧引用 token（带引号整词匹配），如 '"rvp:entity/{name}"'
    new_ref_fmt  新引用 token，如 '"rvp:entity/{sub}/{name}"'
    """
    moved = 0
    skipped = set()
    for stem, subdir in move_map.items():
        src = base_dir / file_fmt.format(name=stem)
        if not src.is_file():
            print(f"[缺失] {src.name} 不存在，跳过")
            skipped.add(stem)
            continue
        dst = base_dir / subdir / src.name
        if dst.exists():
            print(f"[冲突] 目标已存在：{dst.relative_to(PACK_ROOT)}，跳过（引用改写一并跳过，"
                  f"须先处置目标同名文件后再单独移动该条）")
            skipped.add(stem)
            continue
        print(f"[移动] {src.relative_to(PACK_ROOT)} → {dst.relative_to(PACK_ROOT)}")
        moved += 1
        if apply:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))

    rewritten_files = 0
    replaced = 0
    for stem, subdir in move_map.items():
        if stem in skipped:
            # 移动被跳过的条目不改写引用：引用仍指向原地现役文件，保持状态一致
            continue
        old = old_ref_fmt.format(name=stem)
        new = new_ref_fmt.format(sub=subdir, name=stem)
        for path in find_reference_files(old):
            count = rewrite_file(path, [(old, new)], apply)
            if count:
                print(f"[改写] {path.relative_to(PACK_ROOT)}：{count} 处 {old} → {new}")
                rewritten_files += 1
                replaced += count
    print(f"[{title}] 移动 {moved}，改写 {rewritten_files} 个文件共 {replaced} 处")
    return moved, rewritten_files, replaced


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="实际执行（默认 dry-run）")
    args = parser.parse_args()

    if not MODEL_DIR.is_dir():
        raise SystemExit(f"模型目录不存在：{MODEL_DIR}")

    # 1) 模型："rvp:entity/<名>" → "rvp:entity/<子目录>/<名>"
    m1 = migrate_group("模型", MODEL_DIR, MOVE_MAP,
                       "{name}.json",
                       '"rvp:entity/{name}"', '"rvp:entity/{sub}/{name}"', args.apply)
    # 2) 贴图："rvp:textures/entity/<名>.png" → "rvp:textures/entity/<子目录>/<名>.png"
    m2 = migrate_group("贴图", TEXTURE_DIR, TEXTURE_MOVE_MAP,
                       "{name}.png",
                       '"rvp:textures/entity/{name}.png"', '"rvp:textures/entity/{sub}/{name}.png"',
                       args.apply)

    # 3) 孤儿报告（只报告，不处置；ywzj_vehicle: 前缀引用指向本体自带资源，不会被 rvp: 匹配命中）
    print("\n── 孤儿资源报告（全包零引用，处置需用户确认，脚本不移动/删除）──")
    # 模型：引用形如 "rvp:entity/..."，相对 models/bedrock、去 .json 后缀
    for path in orphan_report(MODEL_DIR, MODEL_DIR.parent, '"rvp:{rel}"', True):
        print(f"[孤儿-模型] {path.relative_to(PACK_ROOT)}")
    # 贴图：引用形如 "rvp:textures/..."，相对 textures/、保留 .png 后缀
    for path in orphan_report(TEXTURE_DIR, TEXTURE_DIR.parent, '"rvp:textures/{rel}"', False):
        print(f"[孤儿-贴图] {path.relative_to(PACK_ROOT)}")

    mode = "APPLY" if args.apply else "DRY-RUN（加 --apply 落盘）"
    print(f"\n[{mode}] 模型：移动 {m1[0]}，改写 {m1[1]} 文件 {m1[2]} 处；"
          f"贴图：移动 {m2[0]}，改写 {m2[1]} 文件 {m2[2]} 处")


if __name__ == "__main__":
    main()
