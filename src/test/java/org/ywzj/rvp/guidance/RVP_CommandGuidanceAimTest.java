package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证操作手制导优先采用同步世界瞄准点，并正确回退服务端物理炮轴。 */
class RVP_CommandGuidanceAimTest {

    /** 向量分量断言允许的浮点误差。 */
    private static final double EPSILON = 1.0E-9;

    @Test
    void actualDirectionWinsWhenBothDirectionsAreUsable() {
        Vec3 actual = new Vec3(3.0, 4.0, 0.0);
        Vec3 requested = new Vec3(0.0, 0.0, 8.0);

        Vec3 selected = RVP_CommandGuidanceAim.selectDirection(actual, requested);

        assertVectorEquals(new Vec3(0.6, 0.8, 0.0), selected);
    }

    @Test
    void nearZeroActualDirectionFallsBackToRequestedDirection() {
        Vec3 selected = RVP_CommandGuidanceAim.selectDirection(
                new Vec3(1.0E-4, 0.0, 0.0),
                new Vec3(0.0, 0.0, 5.0));

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), selected);
    }

    @Test
    void nonFiniteActualDirectionFallsBackToRequestedDirection() {
        Vec3 nanSelected = RVP_CommandGuidanceAim.selectDirection(
                new Vec3(Double.NaN, 0.0, 1.0),
                new Vec3(0.0, 2.0, 0.0));
        Vec3 infiniteSelected = RVP_CommandGuidanceAim.selectDirection(
                new Vec3(Double.POSITIVE_INFINITY, 0.0, 1.0),
                new Vec3(0.0, 2.0, 0.0));

        assertVectorEquals(new Vec3(0.0, 1.0, 0.0), nanSelected);
        assertVectorEquals(new Vec3(0.0, 1.0, 0.0), infiniteSelected);
    }

    @Test
    void returnsNullWhenBothDirectionsAreUnusable() {
        Vec3 selected = RVP_CommandGuidanceAim.selectDirection(
                Vec3.ZERO,
                new Vec3(0.0, Double.NEGATIVE_INFINITY, 0.0));

        assertNull(selected);
    }

    @Test
    void selectedDirectionIsNormalized() {
        Vec3 selected = RVP_CommandGuidanceAim.selectDirection(
                null,
                new Vec3(2.0, -3.0, 6.0));

        assertEquals(1.0, selected.length(), EPSILON);
    }

    @Test
    void synchronizedWorldPointBuildsDirectionWithoutVehicleAngles() {
        Vec3 selected = RVP_CommandGuidanceAim.directionFromPoint(
                new Vec3(10.0, 5.0, -20.0),
                new Vec3(10.0, 5.0, 80.0));

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), selected);
    }

    @Test
    void invalidSynchronizedWorldPointCannotEnterGuidance() {
        Vec3 nonFinite = RVP_CommandGuidanceAim.directionFromPoint(
                Vec3.ZERO,
                new Vec3(Double.NaN, 0.0, 100.0));
        Vec3 tooClose = RVP_CommandGuidanceAim.directionFromPoint(
                new Vec3(1.0, 2.0, 3.0),
                new Vec3(1.0, 2.0, 3.0));

        assertNull(nonFinite);
        assertNull(tooClose);
    }

    /** 逐分量比较方向，避免 Vec3 对象相等语义影响测试。 */
    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
