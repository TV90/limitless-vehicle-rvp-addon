package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.initialize;

/** 无动力炸弹接入导弹同款速度平方阻力后，仅显式配置的弹药改变弹道。 */
class RVP_BallisticDragTest {

    @BeforeAll
    static void bootstrap() {
        initialize();
    }

    private static RVP_WeaponData bomb(String projectileData) {
        return GsonUtil.GSON.fromJson(
                "{\"type\":\"rvp:bomb\",\"projectile_data\":" + projectileData + "}",
                RVP_WeaponData.class);
    }

    @Test
    void unconfiguredBombKeepsPreviousBallisticStep() {
        RVP_WeaponData data = bomb("{\"velocity\":0.1,\"gravity\":-0.03,\"max_speed\":3}");
        Vec3 velocity = new Vec3(1.5D, 0.0D, 0.0D);

        RVP_UnguidedBallisticMath.Step step =
                RVP_UnguidedBallisticMath.stepProjectile(new Vec3(0.0D, 100.0D, 0.0D), velocity, data);

        assertEquals(1.5D, step.velocity().x, 1.0E-9D);
        assertEquals(-0.03D, step.velocity().y, 1.0E-9D);
        assertTrue(step.position().x > 0.0D);
    }

    @Test
    void configuredBombLosesSpeedAndHighAltitudeLosesLess() {
        RVP_WeaponData data = bomb("{\"velocity\":0.1,\"gravity\":-0.03,\"mass\":925,"
                + "\"drag_coefficient\":0.5,\"max_speed\":3}");
        Vec3 velocity = new Vec3(1.5D, 0.0D, 0.0D);

        double lowAltitudeHorizontal = RVP_UnguidedBallisticMath
                .stepProjectile(new Vec3(0.0D, 64.0D, 0.0D), velocity, data)
                .velocity().horizontalDistance();
        double highAltitudeHorizontal = RVP_UnguidedBallisticMath
                .stepProjectile(new Vec3(0.0D, 550.0D, 0.0D), velocity, data)
                .velocity().horizontalDistance();

        assertTrue(lowAltitudeHorizontal < 1.5D, "炸弹应因速度平方阻力损失水平速度");
        assertTrue(lowAltitudeHorizontal < highAltitudeHorizontal, "低空空气更稠密，阻力应更大");
    }
}
