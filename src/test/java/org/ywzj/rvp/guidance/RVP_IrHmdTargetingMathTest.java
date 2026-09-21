package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_IrHmdTargetingMathTest {

    @Test
    void reticleInsideTargetOutlineHasZeroMissAngle() {
        Vec3 origin = Vec3.ZERO;
        Vec3 scanDirection = new Vec3(0.5, 0.0, 10.0).normalize();
        AABB target = new AABB(-1.0, -1.0, 9.0, 1.0, 1.0, 11.0);

        assertEquals(0.0,
                RVP_IrHmdTargetingMath.effectiveAngularMissDeg(origin, scanDirection, target),
                1.0E-6);
    }

    @Test
    void targetOutlineReducesCenterPointMissAngle() {
        Vec3 origin = Vec3.ZERO;
        Vec3 scanDirection = new Vec3(0.0, 0.0, 1.0);
        AABB target = new AABB(1.0, -1.0, 9.0, 3.0, 1.0, 11.0);

        double centerAngle = Math.toDegrees(Math.atan2(2.0, 10.0));
        double effectiveAngle = RVP_IrHmdTargetingMath.effectiveAngularMissDeg(
                origin, scanDirection, target);

        assertTrue(effectiveAngle >= 0.0);
        assertTrue(effectiveAngle < centerAngle);
        // 目标中心明显位于默认 2.5° 半锥外，但目标可见轮廓已经进入半锥，应允许方案 C 捕获。
        assertTrue(centerAngle > 2.5);
        assertTrue(effectiveAngle <= 2.5);
    }

    @Test
    void distantOutlineCannotPullClearlyOffAxisTargetIntoCone() {
        Vec3 origin = Vec3.ZERO;
        Vec3 scanDirection = new Vec3(0.0, 0.0, 1.0);
        AABB target = new AABB(4.0, -0.5, 9.5, 6.0, 0.5, 10.5);

        double effectiveAngle = RVP_IrHmdTargetingMath.effectiveAngularMissDeg(
                origin, scanDirection, target);

        // 即使再加实现中的 0.75° 轮廓外容差，也不能把明显偏离准线的目标吸入 2.5° 半锥。
        assertTrue(effectiveAngle > 3.25);
    }

    @Test
    void invalidScanDirectionCannotCapture() {
        double angle = RVP_IrHmdTargetingMath.effectiveAngularMissDeg(
                Vec3.ZERO, Vec3.ZERO, new AABB(-1.0, -1.0, 9.0, 1.0, 1.0, 11.0));

        assertEquals(Double.POSITIVE_INFINITY, angle);
    }
}
