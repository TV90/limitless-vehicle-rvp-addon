# -*- coding: utf-8 -*-
"""
fix_flare_alpha_20260920.py —— flare.png 透明通道补全（2026-09-20 用户反馈"热成像下
MCHR 爆炸火花带一层白底"）。

根因：textures/nuclear/flare.png 是加色混合专用贴图——alpha 全不透明、光斑渐变画在
RGB（中心白→边缘黑）。主画面 additive（SRC_ALPHA, ONE）下"黑色加 0"无底，平时正常；
但 RVP_ThermalParticleChannel 把它重画进本体 thermal_buffer 时，additive 的 alpha
方程把整张 quad 的缓冲 alpha 写成 1，本体 thermal.fsh 以 alpha 为白热显形因子、
判热阈值极低 → 整张 quad 显形，RGB 黑边折算 heat≈0.4 的灰底板。

修复：alpha 通道 = luma(RGB)（0.299R+0.587G+0.114B，与 thermal.fsh 的亮度折算一致），
写回 RGBA。主画面 additive 下仅光斑边缘略收紧（rgb×luma 平方衰减，火花/闪光生命
极短不可感），热成像底板消失、白热从中心自然衰减。

用法（项目根目录）：python -X utf8 scripts/fix_flare_alpha_20260920.py
行为：逐张检查列出的贴图 alpha 现状；alpha 全 1 的做备份（*.bak_20260920）并补全，
已有 mask 的仅报告不动。幂等：已补过的贴图（alpha 存在 0 与非 0 混合）自动跳过。
"""
import os
import shutil
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURES = [
    # (相对路径, 是否允许补全) —— flare 是本次修复目标；同族贴图先检查、确认全 1 才补
    ("src/main/resources/assets/ywzj_rvp/textures/nuclear/flare.png", True),
    ("src/main/resources/assets/ywzj_rvp/textures/boom/smoke.png", True),
    ("src/main/resources/assets/ywzj_rvp/textures/nuclear/particle_base.png", True),
]
BACKUP_SUFFIX = ".bak_20260920"


def alpha_status(img):
    """返回 (min_a, max_a, 全1?) —— 全 1 即无透明通道信息（additive 专用贴图特征）。"""
    alpha = img.getchannel("A")
    lo, hi = alpha.getextrema()
    return lo, hi, lo >= 255


def apply_luma_alpha(img):
    """alpha = luma(RGB)，钳 [0,255]；保留 RGB 原样（additive 亮度不因 alpha 改变）。"""
    img = img.convert("RGBA")
    r, g, b, _ = img.split()
    luma = Image.merge("RGB", (r, g, b)).convert("L")
    # convert("L") 即 ITU-R 601-2 luma（0.299/0.587/0.114），与 thermal.fsh 一致
    return Image.merge("RGBA", (r, g, b, luma))


def main():
    for rel, allow_fix in TEXTURES:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            print(f"[SKIP] 不存在: {rel}")
            continue
        img = Image.open(path)
        print(f"[CHECK] {rel}: mode={img.mode} size={img.size}")
        if img.mode not in ("RGBA", "LA"):
            print(f"        无 alpha 通道（mode={img.mode}）→ 视同全 1")
            lo, hi, all_opaque = 255, 255, True
        else:
            lo, hi, all_opaque = alpha_status(img)
            print(f"        alpha 范围 [{lo}, {hi}] {'→ 全不透明（需补全）' if all_opaque else '→ 已有 mask（不动）'}")
        if not all_opaque:
            continue
        if not allow_fix:
            print(f"        [SKIP] 未授权补全")
            continue
        # 备份放 scripts/texture_backup_20260920/（不放 resources 原处——src/resources
        # 会进 git 与 jar，processResources 无 exclude，.bak 会被打进产物）
        backup_dir = os.path.join(ROOT, "scripts", "texture_backup_20260920")
        os.makedirs(backup_dir, exist_ok=True)
        backup = os.path.join(backup_dir, os.path.basename(path))
        if not os.path.exists(backup):
            shutil.copy2(path, backup)
            print(f"        [BACKUP] scripts/texture_backup_20260920/{os.path.basename(backup)}")
        fixed = apply_luma_alpha(img)
        fixed.save(path)
        lo2, hi2, _ = alpha_status(fixed)
        print(f"        [FIXED] alpha 范围 [{lo2}, {hi2}]（alpha=luma）")
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
