# -*- coding: utf-8 -*-
"""
2026-09-20 载具包物理参数适配脚本
背景：本体 ywzj_vehicle 更新 e513a11→f4e92c8，物理全面切换 N/kg 真实量纲
（mass 默认 1→1000、friction 0.005→2000，轮式/固定翼/旋翼的力参数公式改为
 F/(mass*400)。无旧值兼容层，JSON 写多少就是多少）。

换算规则（全部从本体默认包参考载具反推并精确验证）：
- 质量 mass：按各车真实战斗全重/空重填（kg）；friction 一律 = 2*mass
  （m1a2 61000/122000、ztl11 22500/45000、j10c 9750/19500、ah64d 5165/10330 全部吻合）
- 履带式：attributes 为加速度语义，本体未改公式 → 一律不动（倒车倍率自然不变）
- 轮式：forward/backward/brake_force 新值 = 旧值 × 新质量 × 400
  （ztl11: 0.02*22500*400=180000、0.025*22500*400=225000 精确吻合）
- 固定翼：thrust 新值 = 旧值 × 质量 × 456.41
  （j10c: 0.02*9750*456.41=89000 吻合，含本体上调的 14% 推重比）
  air_drag_k_min/max 新值 = 旧值 × 质量（j10c: 0.002*9750=19.5 精确吻合）
  thrust_k 与其余气动参数不动
- 旋翼：main_rotor_force 新值 = 旧值 × 质量 × 489.58
  （ah64d: 0.0392*5165*489.58=99129 吻合）
- 能量系统代码公式未变，RVP 现有 energy 数值自洽，不动
- 悬挂部件（ywzj_vehicle:suspension）需要结构文件配骨骼，本次按约定
  只改参数不动结构模型 → 全部车辆不加悬挂部件，走保留的非悬挂重力路径

运行：python scripts/adapt_vehicle_physics_20260920.py
"""
import json
import io
import os
import shutil

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
VEH_DIR = os.path.join(ROOT, 'limitless_vehicle', 'rvp', 'data', 'rvp', 'vehicles')
BACKUP_DIR = os.path.join(ROOT, 'scripts', 'vehicle_json_backup_20260920')

# 各车质量表（kg）：履带/轮式=战斗全重，固定翼=空重，旋翼=空重。
# 与本体默认包同车已对齐（j10c=9750、rafale=10000、ah64=ah64d 5165）。
# ps1sm / cssa5 / suav 为估算值，实测手感不对优先调这三台。
MASS = {
    # 履带式（参考本体 M1A2；ZTZ100 用户指定 45 吨）
    'abramsx':   55000,   # Abrams X 原型车估重
    'bmpt72':    50000,   # BMPT-72
    'bukm3':     35000,   # 山毛榉-M3 发射车
    'm1a2sep':   63000,   # M1A2 SEP
    'ps1sm':     35000,   # 估算值（待实测校准）
    't84bm':     46000,   # T-84BM 堡垒
    't90m':      48000,   # T-90M
    'vt4':       52000,   # VT-4
    'ztz100':    45000,   # 用户指定 45 吨
    # 轮式（参考本体 ZTL11）
    '96l6':          30000,  # 96L6E 雷达车（MZKT-7930 底盘）
    '9k720':         40000,  # 伊斯坎德尔 TEL 满载
    'cssa5':         20000,  # 估算值（待实测校准）
    'irist_slm_tads': 35000, # IRIS-T SLM 雷达车
    'irist_slm_tel':  35000, # IRIS-T SLM 发射车
    'lav25':     12800,      # LAV-25
    'lavad':     13400,      # LAV-AD
    'm142':      13800,      # HIMARS
    'zbl08a':    22000,      # ZBL-08
    # 固定翼（参考本体 J10C）
    'ea18g':       14550,    # EA-18G
    'f14a_iriaf':  19800,    # F-14A
    'f14d':        19830,    # F-14D
    'f14d_maodie': 19830,    # F-14D
    'f16v':        8600,     # F-16V
    'f22a':        19700,    # F-22A
    'j10c':        9750,     # 与本体默认包一致
    'j15':         17500,    # 歼-15
    'j16':         21600,    # 歼-16
    'j16d':        21600,    # 歼-16D
    'j20a':        19400,    # 歼-20（公开估计值）
    'rafale':      10000,    # 与本体默认包一致
    'su57':        18000,    # 苏-57
    'suav':        22,       # 估算值（小型手抛无人机，待实测校准）
    # 旋翼（参考本体 AH64D）
    'ah64': 5165,            # 与本体默认包 AH64D 一致
    'mi28': 8200,            # 米-28N
}

# 固定翼换算系数：thrust = 旧值 * mass * 456.41（j10c 反推）
FIXED_WING_THRUST_K = 456.41
# 旋翼换算系数：main_rotor_force = 旧值 * mass * 489.58（ah64d 反推）
ROTOR_FORCE_K = 489.58


def main():
    if not os.path.isdir(VEH_DIR):
        raise SystemExit('vehicles dir not found: %s' % VEH_DIR)

    # 1. 全量备份（同名已存在则跳过，保留最早的原件）
    os.makedirs(BACKUP_DIR, exist_ok=True)
    for name in os.listdir(VEH_DIR):
        if name.endswith('.json'):
            dst = os.path.join(BACKUP_DIR, name)
            if not os.path.exists(dst):
                shutil.copy2(os.path.join(VEH_DIR, name), dst)
    print('backup -> %s (%d files)' % (BACKUP_DIR, len(os.listdir(BACKUP_DIR))))

    # 2. 逐车适配
    changed, skipped = [], []
    for name in sorted(os.listdir(VEH_DIR)):
        if not name.endswith('.json'):
            continue
        vid = name[:-5]
        if vid not in MASS:
            skipped.append(vid)
            continue
        mass = float(MASS[vid])
        path = os.path.join(VEH_DIR, name)
        data = json.load(io.open(path, encoding='utf-8'))
        vtype = data.get('type', '').split(':')[-1]

        # physics_info：mass 排最前，friction=2*mass，其余键（can_destroy_block 等）保留
        old_phys = data.get('physics_info', {})
        new_phys = {'mass': int(mass), 'friction': int(2 * mass)}
        new_phys.update(old_phys)
        data['physics_info'] = new_phys

        attrs = data.get('attributes', {})
        if vtype == 'wheeled_vehicle':
            # 轮式力参数换算，三个力同乘同因子，倒车/前进倍率不变
            for key in ('brake_force', 'forward_force', 'backward_force'):
                if key in attrs:
                    attrs[key] = round(attrs[key] * mass * 400, 2)
        elif vtype == 'fixed_wing_vehicle':
            if 'thrust' in attrs:
                attrs['thrust'] = round(attrs['thrust'] * mass * FIXED_WING_THRUST_K, 1)
            for key in ('air_drag_k_min', 'air_drag_k_max'):
                if key in attrs:
                    attrs[key] = round(attrs[key] * mass, 4)
        elif vtype == 'rotary_wing_vehicle':
            if 'main_rotor_force' in attrs:
                attrs['main_rotor_force'] = round(attrs['main_rotor_force'] * mass * ROTOR_FORCE_K, 1)
        # 履带式 attributes 不动；旋翼其余属性不动

        with io.open(path, 'w', encoding='utf-8', newline='\n') as fp:
            json.dump(data, fp, ensure_ascii=False, indent=2)
            fp.write('\n')
        changed.append('%s(%s, %dkg)' % (vid, vtype, mass))

    print('changed %d: %s' % (len(changed), ', '.join(changed)))
    if skipped:
        print('skipped(no mass entry): %s' % ', '.join(skipped))


if __name__ == '__main__':
    main()
