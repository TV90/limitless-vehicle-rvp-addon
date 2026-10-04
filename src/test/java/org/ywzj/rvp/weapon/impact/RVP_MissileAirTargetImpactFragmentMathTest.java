package org.ywzj.rvp.weapon.impact;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_MissileAirTargetImpactFragmentMathTest {
    /** 远侧点应位于导弹行进方向的 AABB 外侧，而不是命中近侧。 */
    @Test
    void farSideFollowsTravelDirection() {
        AABB target = new AABB(-1.0D, -2.0D, -3.0D, 1.0D, 2.0D, 3.0D);

        Vec3 farSide = RVP_MissileAirTargetImpactFragmentMath.resolveFarSide(
                target, new Vec3(1.0D, 0.0D, 0.0D), 0.25D);

        assertEquals(1.25D, farSide.x, 1.0E-12D);
        assertEquals(0.0D, farSide.y, 1.0E-12D);
        assertEquals(0.0D, farSide.z, 1.0E-12D);
    }

    /** 横向出生分散基底必须与行进方向正交且自身归一化。 */
    @Test
    void perpendicularBasisIsOrthonormal() {
        Vec3 direction = new Vec3(2.0D, -1.0D, 3.0D);
        RVP_MissileAirTargetImpactFragmentMath.OrthonormalBasis basis =
                RVP_MissileAirTargetImpactFragmentMath.perpendicularBasis(direction);

        assertEquals(1.0D, basis.direction().length(), 1.0E-12D);
        assertEquals(1.0D, basis.first().length(), 1.0E-12D);
        assertEquals(1.0D, basis.second().length(), 1.0E-12D);
        assertEquals(0.0D, basis.direction().dot(basis.first()), 1.0E-12D);
        assertEquals(0.0D, basis.direction().dot(basis.second()), 1.0E-12D);
        assertEquals(0.0D, basis.first().dot(basis.second()), 1.0E-12D);
    }

    /** 阻尼应逐 tick 降速，达到停止阈值后应结束视觉碎片。 */
    @Test
    void dampingAndStopThresholdAreMonotonic() {
        Vec3 velocity = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 damped = RVP_MissileAirTargetImpactFragmentMath.applyDamping(velocity, 0.5D);

        assertEquals(0.5D, damped.x, 1.0E-12D);
        assertTrue(RVP_MissileAirTargetImpactFragmentMath.isStopped(
                new Vec3(0.01D, 0.0D, 0.0D), 0.05D));
        assertTrue(!RVP_MissileAirTargetImpactFragmentMath.isStopped(
                new Vec3(0.1D, 0.0D, 0.0D), 0.05D));
    }

    /** 白烟补点数量应按轨迹长度增加，并受单次上限约束。 */
    @Test
    void smokePointCountFillsSegmentWithinLimit() {
        assertEquals(3, RVP_MissileAirTargetImpactFragmentMath.resolveSmokePointCount(
                1.01D, 0.35D, 8));
        assertEquals(8, RVP_MissileAirTargetImpactFragmentMath.resolveSmokePointCount(
                10.0D, 0.35D, 8));
        assertEquals(1, RVP_MissileAirTargetImpactFragmentMath.resolveSmokePointCount(
                0.0D, 0.35D, 8));
    }

    /** 碎片数量必须在 1～配置上限内随机，默认上限 4 即得到 1～4 个。 */
    @Test
    void randomFragmentCountStaysWithinConfiguredRange() {
        RandomSource random = RandomSource.create(5678L);

        for (int index = 0; index < 128; index++) {
            int count = RVP_MissileAirTargetImpactFragmentMath.randomFragmentCount(random, 4);
            assertTrue(count >= 1 && count <= 4);
        }
        assertEquals(1, RVP_MissileAirTargetImpactFragmentMath.randomFragmentCount(random, 1));
    }

    /** 碎片方向应保持在命中方向圆锥半角内，并保持单位长度。 */
    @Test
    void randomFragmentDirectionStaysInsideCone() {
        Vec3 axis = new Vec3(1.0D, 0.0D, 0.0D);
        double halfAngle = Math.toRadians(30.0D);
        RandomSource random = RandomSource.create(1234L);

        for (int index = 0; index < 128; index++) {
            Vec3 direction = RVP_MissileAirTargetImpactFragmentMath.randomDirectionInCone(
                    axis, halfAngle, random);
            assertEquals(1.0D, direction.length(), 1.0E-12D);
            assertTrue(direction.dot(axis) >= Math.cos(halfAngle) - 1.0E-12D);
        }
    }
}
