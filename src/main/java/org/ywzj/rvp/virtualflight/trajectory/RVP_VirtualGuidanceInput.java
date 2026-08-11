package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.world.phys.Vec3;

/**
 * 阶段 B 的纯制导输入。
 *
 * <p>{@code fixedTargetPosition} 为固定 GPS 快照目标；{@code preset} 非空时表示启用
 * 弹道导弹（PRESET 三段式），积分器按上升→巡航→俯冲生成期望方向，否则走 GPS 巡航闭环。</p>
 */
public record RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition,
                                       RVP_VirtualPresetGuidance preset) {
    public RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition) {
        this(fixedTargetPosition, null);
    }
}
