package org.ywzj.rvp.entity.projectile;

/**
 * 弹体当前速率基准与历史峰值的纯数学更新规则。
 *
 * <p>当前速率供下一 Tick 制导与恒速运动消费，必须允许随阻力、穿透和转向损失下降；
 * 历史峰值仅用于统计与虚拟中段快照，禁止反向写回当前速率。</p>
 */
public final class RVP_ProjectileSpeedMath {
    /** 速度接近零时保留的最小制导数值基准，单位格/Tick。 */
    public static final double MIN_REFERENCE_SPEED = 0.01D;

    private RVP_ProjectileSpeedMath() {}

    /**
     * 由权威速度生成下一 Tick 的当前速率基准，不保留更早 Tick 的高速值。
     *
     * @param actualSpeed 当前权威速度模长，单位格/Tick
     * @return 当前速率基准，单位格/Tick
     */
    public static double resolveCurrentSpeed(double actualSpeed) {
        return Math.max(actualSpeed, MIN_REFERENCE_SPEED);
    }

    /**
     * 只向上更新历史峰值；该返回值不得用于制导或运动速度回填。
     *
     * @param previousPeak 已记录的历史峰值，单位格/Tick
     * @param actualSpeed 当前权威速度模长，单位格/Tick
     * @return 更新后的历史峰值，单位格/Tick
     */
    public static double resolvePeakSpeed(double previousPeak, double actualSpeed) {
        return Math.max(Math.max(previousPeak, actualSpeed), 0.0D);
    }
}
