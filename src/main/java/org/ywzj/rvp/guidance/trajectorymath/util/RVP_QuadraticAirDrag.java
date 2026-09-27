package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * RVP 推进弹体的速度平方空气阻力计算工具。
 *
 * <p>每 Tick 按 {@code Δv = -方向 × (dragCoefficient × |v|²) / mass × altitudeFactor}
 * 扣除速度；子步积分调用方可通过 {@code tickFraction} 按子步比例缩放阻力。</p>
 */
public final class RVP_QuadraticAirDrag {

    /** 速度与质量非法或接近零时用于跳过阻力计算的安全阈值。 */
    private static final double MIN_VALID_MAGNITUDE = 1.0E-12D;

    /** 工具类不允许实例化。 */
    private RVP_QuadraticAirDrag() {
    }

    /**
     * 按速度平方、弹体质量与高度倍率计算单个完整 Tick 的空气阻力。
     *
     * @param velocity Tick 开始时的速度，单位格/Tick
     * @param dragCoefficient 武器配置中的速度平方阻力系数
     * @param mass 当前弹体质量；燃烧期传入变质量，燃尽后传入干质量
     * @param altitudeFactor 当前高度对应的阻力倍率
     * @return 扣除阻力后的速度；阻力不会令弹体沿原方向反向
     */
    public static Vec3 apply(Vec3 velocity, double dragCoefficient, double mass, double altitudeFactor) {
        return apply(velocity, dragCoefficient, mass, altitudeFactor, 1.0D);
    }

    /**
     * 按速度平方、弹体质量、高度倍率及 Tick 子步比例计算空气阻力。
     *
     * @param velocity 当前子步的速度，单位格/Tick
     * @param dragCoefficient 武器配置中的速度平方阻力系数
     * @param mass 当前弹体质量；燃烧期传入变质量，燃尽后传入干质量
     * @param altitudeFactor 当前高度对应的阻力倍率
     * @param tickFraction 当前子步占完整 Tick 的比例
     * @return 扣除阻力后的速度；阻力不会令弹体沿原方向反向
     */
    public static Vec3 apply(Vec3 velocity, double dragCoefficient, double mass,
                             double altitudeFactor, double tickFraction) {
        double speed = velocity.length();
        if (!Double.isFinite(speed) || speed <= MIN_VALID_MAGNITUDE
                || !Double.isFinite(dragCoefficient) || dragCoefficient <= 0.0D
                || !Double.isFinite(mass) || mass <= MIN_VALID_MAGNITUDE
                || !Double.isFinite(altitudeFactor) || altitudeFactor <= 0.0D
                || !Double.isFinite(tickFraction) || tickFraction <= 0.0D) {
            return velocity;
        }

        double speedLoss = dragCoefficient * speed * speed / mass * altitudeFactor * tickFraction;
        if (!Double.isFinite(speedLoss) || speedLoss <= 0.0D) {
            return velocity;
        }
        double remainingSpeed = Math.max(speed - speedLoss, 0.0D);
        return remainingSpeed <= MIN_VALID_MAGNITUDE
                ? Vec3.ZERO
                : velocity.scale(remainingSpeed / speed);
    }
}
