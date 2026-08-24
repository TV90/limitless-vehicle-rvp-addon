package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;

/**
 * 子弹药部署速度的纯数学工具；不访问世界或实体状态。
 */
public final class RVP_DeploymentMotionUtil {

    /** 小于该长度平方的部署水平速度直接归零，避免无限保留浮点残量。 */
    private static final double ZERO_HORIZONTAL_LENGTH_SQR = 1.0E-12D;

    private RVP_DeploymentMotionUtil() {}

    /**
     * 按半衰期衰减一次部署水平速度；只消费 X/Z，返回值的 Y 恒为 0。
     *
     * @param horizontalVelocity 当前部署水平速度，单位格/Tick
     * @param halfLifeTicks 半衰期，单位 Tick；非正或非有限值表示不衰减
     * @return 下一 Tick 的部署水平速度
     */
    public static Vec3 decayHorizontal(Vec3 horizontalVelocity, float halfLifeTicks) {
        if (horizontalVelocity == null) {
            return Vec3.ZERO;
        }
        Vec3 horizontal = new Vec3(horizontalVelocity.x, 0.0D, horizontalVelocity.z);
        if (!Float.isFinite(halfLifeTicks) || halfLifeTicks <= 0f) {
            return horizontal;
        }
        double multiplier = Math.pow(0.5D, 1.0D / halfLifeTicks);
        Vec3 next = horizontal.scale(multiplier);
        return next.lengthSqr() <= ZERO_HORIZONTAL_LENGTH_SQR ? Vec3.ZERO : next;
    }

    /**
     * 把碰撞、穿透等系统对总速度施加的差值并入基础弹道，避免下一次分量重组覆盖外部改速。
     *
     * @param baseVelocity 上一 Tick 的基础弹道分量
     * @param currentTotalVelocity 外部系统处理后的当前总速度
     * @param previousComposedVelocity 上一 Tick 三分量合成的总速度
     * @return 吸收外部速度差后的基础弹道分量
     */
    public static Vec3 absorbExternalDelta(Vec3 baseVelocity, Vec3 currentTotalVelocity,
                                           Vec3 previousComposedVelocity) {
        Vec3 base = baseVelocity == null ? Vec3.ZERO : baseVelocity;
        Vec3 current = currentTotalVelocity == null ? Vec3.ZERO : currentTotalVelocity;
        Vec3 previous = previousComposedVelocity == null ? Vec3.ZERO : previousComposedVelocity;
        return base.add(current.subtract(previous));
    }
}
