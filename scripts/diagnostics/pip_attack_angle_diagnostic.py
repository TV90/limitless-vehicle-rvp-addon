"""PIP 与攻角耦合的二维隔离诊断；只打印结果，不修改武器配置或资源。

按 Java 公式转录共面 nlerp/slerp、PIP、动压与攻角求解。
这是数学复现，不是 Minecraft 实体测试：关闭推力、重力、阻力、制导门限、
引信与碰撞，目标为质点；用弹目相对运动线段计算首次交会脱靶量。
"""

import math


# 游戏每 Tick 重力常量；本诊断仅用于 G 与法向速度增量换算。
G = 9.8 / 400.0
# 与当前 PL-15 / AIM-120D 常见配置相同的标称速率、参考速率与转向比例。
SPEED = 5.5
FACTOR = 0.1
# 现有武器配置统一使用的攻角上限，单位弧度。
CAP = math.radians(60.0)


def wrap(angle):
    """取共面最短旋转角。"""
    return (angle + math.pi) % (2.0 * math.pi) - math.pi


def pip_direction(x, y, heading, target_x, target_y, target_speed):
    """转录 steerPredictiveIntercept 的恒速二次求根和现有回退。"""
    rx, ry = target_x - x, target_y - y
    a = target_speed**2 - SPEED**2
    b = 2.0 * ry * target_speed
    c = rx**2 + ry**2
    roots = []
    if abs(a) < 1.0e-8:
        if b < 0.0:
            roots = [-c / b]
    else:
        discriminant = b * b - 4.0 * a * c
        if discriminant >= 0.0:
            roots = [t for t in ((-b + math.sqrt(discriminant)) / (2.0 * a),
                                  (-b - math.sqrt(discriminant)) / (2.0 * a)) if t > 0.0]
    if roots:
        time = min(roots)
    else:
        distance = math.sqrt(c)
        relative_dot = (-SPEED * math.cos(heading) * rx
                        + (target_speed - SPEED * math.sin(heading)) * ry) / max(distance, 1.0e-8)
        time = distance / max(SPEED - relative_dot, 0.1)
    return math.atan2(ry + target_speed * time, rx)


def simulate(attack_angle, density, distance, target_speed, reversal_tick=None, offset=0.0):
    """对比攻角接入前气动链与当前链；返回首次交会最小距离和时刻。"""
    x = y = heading = body = 0.0
    target_x, target_y = distance, offset
    available_dv = 2.0 * SPEED * math.sin(math.atan2(FACTOR, 1.0 - FACTOR) / 2.0)
    available_dv *= max(0.05, min(1.0, density))
    turn_limit = 2.0 * math.asin(available_dv / (2.0 * SPEED))
    best = (float("inf"), 0.0)
    for tick in range(500):
        if tick == reversal_tick:
            target_speed = -target_speed
        desired = pip_direction(x, y, heading, target_x, target_y, target_speed)
        if attack_angle:
            # 共面球面插值与 Java solve 顺序相同：机头、攻角锥、载荷、速度。
            body += FACTOR * wrap(desired - body)
            alpha = max(-CAP, min(CAP, wrap(body - heading)))
            body = heading + alpha
            load = abs(math.sin(alpha)) / math.sin(CAP)
            turn = min(abs(alpha), 2.0 * math.asin(available_dv * load / (2.0 * SPEED)))
            heading += math.copysign(turn, alpha)
        else:
            # 旧实体链先做 turning_factor nlerp，再按同一可用 G 裁决。
            error = wrap(desired - heading)
            turn = math.atan2(FACTOR * math.sin(error), 1.0 - FACTOR + FACTOR * math.cos(error))
            heading += max(-turn_limit, min(turn_limit, turn))
            body = heading
        vx, vy = SPEED * math.cos(heading), SPEED * math.sin(heading)
        rx, ry = target_x - x, target_y - y
        dx, dy = -vx, target_speed - vy
        fraction = max(0.0, min(1.0, -(rx * dx + ry * dy) / (dx * dx + dy * dy)))
        miss = math.hypot(rx + dx * fraction, ry + dy * fraction)
        if miss < best[0]:
            best = miss, tick + fraction
        elif tick > 1:
            # 首次交会后即结束，避免无限绕圈的后续重逢掩盖首次脱靶。
            break
        x, y = x + vx, y + vy
        target_y += target_speed
    return best


def main():
    """输出单步响应及连续交会结果，供方案审阅复核。"""
    design_g = 2.0 * SPEED * math.sin(math.atan2(FACTOR, 1.0 - FACTOR) / 2.0) / G
    error = math.radians(10.0)
    old_turn = math.atan2(FACTOR * math.sin(error), 1.0 - FACTOR + FACTOR * math.cos(error))
    load = math.sin(FACTOR * error) / math.sin(CAP)
    print(f"design_G={design_g:.6f}, first_alpha=1deg, first_load={load:.8f}")
    for density in (1.0, 0.5, 0.25):
        available = design_g * density * G
        baseline = min(old_turn, 2.0 * math.asin(available / (2.0 * SPEED)))
        actual = 2.0 * math.asin(available * load / (2.0 * SPEED))
        print(f"density={density}: old={math.degrees(baseline):.6f}deg, "
              f"attack_angle={math.degrees(actual):.6f}deg")
    print("density distance target_speed reversal offset | old_miss old_tick | AA_miss AA_tick")
    for case in ((1.0, 300, 2, None, 0), (0.5, 300, 2, None, 0),
                 (0.25, 600, 2, None, 0), (0.25, 1000, 2, None, 0),
                 (0.25, 600, 2, 80, 0), (0.25, 300, 0, None, 0)):
        old = simulate(False, *case)
        current = simulate(True, *case)
        print(*case, "|", *(f"{v:.4f}" for v in old), "|", *(f"{v:.4f}" for v in current))


if __name__ == "__main__":
    main()
