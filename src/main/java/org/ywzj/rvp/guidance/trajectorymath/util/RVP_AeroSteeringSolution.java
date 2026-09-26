package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * 单 Tick 气动转向求解结果。
 *
 * <p>{@link #velocity()} 只包含方向转向并保持输入速率；{@link #inducedDragLoss()}
 * 是供后续运动积分阶段结算的独立速度损失，求解器不会提前把它扣入速度。</p>
 *
 * @param velocity 气动裁决后的速度向量，单位格/Tick，长度与有效输入速度相同
 * @param turnAngleRadians 本 Tick 实际施加的速度方向转角，单位弧度
 * @param availableTurnAngleRadians 本 Tick 可用的最大速度方向转角，单位弧度
 * @param loadFactor 实际转角占可用转角的比例 λ，范围 0～1
 * @param availableGs 当前动压下的可用法向过载，单位 G；无过载限制时为正无穷
 * @param inducedDragLoss 按 λ² 计算、留待运动阶段结算的速率损失，单位格/Tick
 * @param limitedBy 实际转角未满足指令时的主限制来源
 */
public record RVP_AeroSteeringSolution(
        Vec3 velocity,
        double turnAngleRadians,
        double availableTurnAngleRadians,
        double loadFactor,
        double availableGs,
        double inducedDragLoss,
        LimitReason limitedBy
) {
    /** 气动转向对本 Tick 指令的限制来源。 */
    public enum LimitReason {
        /** 指令转角未触及任何限制。 */
        NONE,
        /** 指令转角受当前动压下的可用 G 值限制。 */
        AERO_G,
        /** 指令转角受绝对转角上限限制。 */
        TURN_RATE,
        /** 气动模型关闭，结果由旧版转向算法产生。 */
        DISABLED
    }
}
