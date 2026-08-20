package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 实体态与虚拟态共用的无状态转向数学。
 *
 * <p>本类不访问实体、世界或武器数据，只执行实体制导链原有的
 * {@code turningFactor} 速度方向插值，确保两种飞行状态使用相同算法。</p>
 */
public final class RVP_TrajectorySteeringMath {
    /** 工具类不允许实例化。 */
    private RVP_TrajectorySteeringMath() {
    }

    /**
     * 按 {@code turningFactor} 在当前方向与期望方向之间插值，并保持指定速率。
     *
     * @param current 当前速度；允许为空或近似零向量
     * @param desiredDirection 期望方向；允许传入未归一化向量
     * @param speed 输出速率，单位格/Tick
     * @param turningFactor 单 Tick 方向插值强度，运行时钳制到 0～1
     * @return 插值后的速度；期望方向无效时保持当前速度
     */
    public static Vec3 applyTurningFactor(Vec3 current, Vec3 desiredDirection,
                                           double speed, float turningFactor) {
        if (desiredDirection == null || desiredDirection.lengthSqr() <= 1.0E-8) {
            return current;
        }
        float factor = Mth.clamp(turningFactor, 0.0F, 1.0F);
        if (current == null || current.lengthSqr() <= 1.0E-8) {
            return desiredDirection.normalize().scale(speed);
        }
        Vec3 blended = current.normalize().scale(1.0 - factor)
                .add(desiredDirection.normalize().scale(factor));
        if (blended.lengthSqr() <= 1.0E-8) {
            return current.normalize().scale(speed);
        }
        return blended.normalize().scale(speed);
    }
}
