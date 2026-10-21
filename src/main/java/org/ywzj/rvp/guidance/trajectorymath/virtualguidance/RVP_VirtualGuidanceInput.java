package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_TargetMotionState;

/**
 * 阶段 B 的纯制导输入。
 *
 * <p>{@code fixedTargetPosition} 为固定 GPS 快照目标；{@code preset} 非空时表示启用
 * 弹道导弹（PRESET 三段式），积分器按上升→巡航→俯冲生成期望方向，否则走 GPS 巡航闭环。</p>
 *
 * @param fixedTargetPosition 固定目标世界坐标快照
 * @param preset 可选 PRESET 弹道制导参数；{@code null} 表示使用 GPS 巡航闭环
 * @param targetMotion 当前目标运动快照；包含速度和有限时域加速度，固定点输入时为静止快照
 */
public record RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition,
                                       RVP_VirtualPresetGuidance preset,
                                       RVP_TargetMotionState targetMotion) {
    /**
     * 创建不启用 PRESET 的固定目标制导输入。
     *
     * @param fixedTargetPosition 固定目标世界坐标快照
     */
    public RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition) {
        this(fixedTargetPosition, null, RVP_TargetMotionState.stationary(
                fixedTargetPosition == null ? Vec3.ZERO : fixedTargetPosition));
    }

    /** 保留现有 PRESET 调用点，并为虚拟段补充固定目标的静止运动快照。 */
    public RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition, RVP_VirtualPresetGuidance preset) {
        this(fixedTargetPosition, preset, RVP_TargetMotionState.stationary(
                fixedTargetPosition == null ? Vec3.ZERO : fixedTargetPosition));
    }
}
