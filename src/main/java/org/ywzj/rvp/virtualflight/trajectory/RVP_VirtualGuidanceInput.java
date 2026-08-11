package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.world.phys.Vec3;

/** 阶段 A 的固定 GPS 快照输入。 */
public record RVP_VirtualGuidanceInput(Vec3 fixedTargetPosition) {
}
