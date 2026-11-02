package org.ywzj.rvp.flight;

/** 飞行公共数学：只计算数值，不依赖世界、实体或某个业务。 */
public final class RVP_FlightMath {
    /** 正常服务器每秒逻辑刻数，不随实际帧率变化。 */
    public static final double TICKS_PER_SECOND = 20.0;
    /** 本体重力换算为格/秒平方。 */
    public static final double GRAVITY = 9.8;

    private RVP_FlightMath() {}

    /** 拒绝非有限参数，避免坏值传播进实体同步字段。 */
    public static double finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " 必须为有限数值");
        return value;
    }

    /** 校验参数范围，包含端点。 */
    public static double range(double value, double min, double max, String name) {
        // 调用公共有限值校验，避免 NaN 绕过大小比较。
        finite(value, name);
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出范围");
        return value;
    }

    /** 数值限幅，调用前参数必须已通过有限值检查。 */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 尽量修正业务参数：缺失数值用默认值，有限越界值取最近边界，不要求调用者重试。 */
    public static double bounded(double value, double fallback, double min, double max) {
        // 调用公共限幅，保证修正结果可直接参加后续计算。
        return clamp(Double.isFinite(value) ? value : fallback, min, max);
    }

    /** 最短有符号角差；正后方固定返回负180，调用者可保留此前转弯侧。 */
    public static double wrap(double degrees) {
        return ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /** Minecraft航向：0朝南，90朝西。 */
    public static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 限制单刻变化，防止任务切换时突然打满杆。 */
    public static double approach(double current, double target, double maximumChange) {
        // 调用公共限幅，把变化约束保持在当前值两侧。
        return current + clamp(target - current, -maximumChange, maximumChange);
    }

    /** 按本体升力公式估计温和迎角下的最低速度；包含调用方指定的安全余量。 */
    public static double minimumSpeed(double mass, double dragMin, double dragMax,
                                      double liftToDrag, double airScale, double aoaMax,
                                      double margin) {
        double alpha = Math.max(0.1, Math.min(5.0, aoaMax));
        double drag = dragMin + (dragMax - dragMin) * Math.abs(Math.sin(Math.toRadians(alpha)));
        double lift = airScale * (liftToDrag + 2.0 * alpha / aoaMax);
        return Math.sqrt(GRAVITY * mass / Math.max(1.0E-6, drag * lift)) * margin;
    }
}
