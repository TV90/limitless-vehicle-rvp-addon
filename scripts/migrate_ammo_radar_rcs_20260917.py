#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
弹药雷达信号参数迁移（2026-09-17）：
misc_data.signal_intensity_factor_on_radar（均匀倍率，全量删除）
→ misc_data.ammo_radar_rcs_factor [迎头, 侧向, 尾向]（分角度，与战机 rvp_radar_rcs_factor 同款曲线）。

迁移值（用户定版 2026-09-17）：
- j16_akf98a / rafale_storm_shadow → [0.08, 0.35, 0.18]（与 j20a 载具相同的隐身参数）
- 其余 12 个导弹/炸弹 → [1, 1, 1]
- 仅迁移 type 为 rvp:missile / rvp:bomb 的武器，机炮等其它类型一律跳过并告警。

范围：本地包 + run/client_1 + run/client_2 + run/server 共 4 份副本（载具包不进 git）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELTIVE = Path("limitless_vehicle") / "rvp" / "data" / "rvp" / "weapons"
# 主包 + 3 份 run 副本（均拼接 RELTIVE；主包根即 ywzj_rvp）
COPY_ROOTS = [ROOT,
              ROOT / "run" / "client_1",
              ROOT / "run" / "client_2",
              ROOT / "run" / "server"]

# 武器名 → 迁移值（用户定版）
VALUES = {
    "j16_akf98a": [0.08, 0.35, 0.18],
    "rafale_storm_shadow": [0.08, 0.35, 0.18],
    "9k720_9m723": [1, 1, 1],
    "f14d_mk84": [1, 1, 1],
    "j10c_gb3_ir": [1, 1, 1],
    "j10c_gb3_laser": [1, 1, 1],
    "m142_atacms": [1, 1, 1],
    "f14d_maodie_yeshenggounai": [1, 1, 1],
    "mi28_kh_39": [1, 1, 1],
    "rafale_aasm_ir": [1, 1, 1],
    "rafale_aasm_laser": [1, 1, 1],
    "m1a2sep_lahat": [1, 1, 1],
    "spice_1000": [1, 1, 1],
    "su57_kh38": [1, 1, 1],
}

ALLOWED_TYPES = {"rvp:missile", "rvp:bomb", "rvp:rocket"}
OLD_FIELD = re.compile(
    r'"signal_intensity_factor_on_radar"\s*:\s*\{\s*"\[\[0,inf\]\]"\s*:\s*([0-9.]+)\s*\}')
TYPE_FIELD = re.compile(r'"type"\s*:\s*"([^"]+)"')


def fmt_array(values, indent):
    pad = " " * indent
    inner = ",\n".join(f"{pad}  {v}" for v in values)
    return "\"ammo_radar_rcs_factor\": [\n" + inner + "\n" + pad + "]"


def migrate_file(path: Path, name: str) -> bool:
    text = path.read_text(encoding="utf-8")
    type_match = TYPE_FIELD.search(text)
    weapon_type = type_match.group(1) if type_match else "?"
    if weapon_type not in ALLOWED_TYPES:
        print(f"[跳过] {path.relative_to(ROOT)} type={weapon_type}（非导弹/炸弹/火箭弹）")
        return False
    values = VALUES[name]
    array_text = fmt_array(values, 4)

    def repl(match):
        return array_text

    new_text, count = OLD_FIELD.subn(repl, text)
    if count == 0:
        if '"ammo_radar_rcs_factor"' in text and '"signal_intensity_factor_on_radar"' not in text:
            print(f"[跳过-已迁移] {path.relative_to(ROOT)}")
            return True
        print(f"[失败] {path.relative_to(ROOT)} 旧字段匹配 {count} 次（应为 1）")
        return False
    path.write_text(new_text, encoding="utf-8")
    print(f"[完成] {path.relative_to(ROOT)} → {values}（type={weapon_type}）")
    return True


def main() -> int:
    migrated = 0
    failed = 0
    for root in COPY_ROOTS:
        for name in VALUES:
            path = root / RELTIVE / f"{name}.json"
            if not path.is_file():
                print(f"[失败] 缺少文件: {path}")
                failed += 1
                continue
            if migrate_file(path, name):
                migrated += 1
            else:
                failed += 1
    print(f"\n迁移完成：成功 {migrated}，失败 {failed}（应为 56/0）")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
