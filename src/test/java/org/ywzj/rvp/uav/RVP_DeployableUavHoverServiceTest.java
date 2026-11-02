package org.ywzj.rvp.uav;

import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.util.PhysicsHelper;

import static org.junit.jupiter.api.Assertions.*;

/** 部署总距必须匹配本体力→阻力→重力的单位与顺序，避免无人接管前先坠落。 */
class RVP_DeployableUavHoverServiceTest {
    @Test
    void deploymentCollectiveBalancesNativeVerticalPhysicsAtDifferentAltitudes() {
        for (double altitude : new double[]{20, 64, 100, 200}) {
            // 现有三种小型无人旋翼机共享的质量/旋翼力/阻力；独立结算一次本体运动公式。
            double mass = 8.0;
            double force = 216.0;
            double friction = 16.0;
            float collective = RVP_DeployableUavHoverService.initialCollectivePitch(
                    mass, force, friction, altitude, 256);
            double air = altitude < 64 ? 1 : Math.pow(256 - altitude, 0.2) / Math.pow(256 - 64, 0.2);
            double speed = force * (collective / 100.0) * air / mass / 400.0;
            speed -= speed * friction / mass / 400.0;
            speed -= PhysicsHelper.GRAVITY / 400.0;
            assertTrue(collective > 0 && collective < 100);
            assertEquals(0.0, speed, 1.0E-8, "部署首 tick 不应自由落体或被满总距猛推上升");
        }
    }

    @Test
    void insufficientLiftAndInvalidPhysicsNeverProduceNonFiniteOrExcessiveCollective() {
        for (double altitude : new double[]{255.99, 256, 300, Double.NaN}) {
            float collective = RVP_DeployableUavHoverService.initialCollectivePitch(8, 216, 16, altitude, 256);
            assertTrue(Float.isFinite(collective));
            assertTrue(collective >= 0 && collective <= 100);
        }
        assertEquals(100f, RVP_DeployableUavHoverService.initialCollectivePitch(8, 0, 16, 64, 256));
        assertEquals(100f, RVP_DeployableUavHoverService.initialCollectivePitch(8, 216, 4000, 64, 256));
        assertTrue(RVP_DeployableUavHoverService.initialCollectivePitch(8, 216, 16, 200, 256)
                > RVP_DeployableUavHoverService.initialCollectivePitch(8, 216, 16, 64, 256));
    }
}
