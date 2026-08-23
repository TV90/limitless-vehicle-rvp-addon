package org.ywzj.rvp.weapon.physics;

import com.google.gson.Gson;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import org.ywzj.rvp.weapon.data.RVP_WindData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_WindDriftUtilTest {

    private static final Gson GSON = new Gson();

    @Test
    void velocityConvergesTowardCapturedWindDirection() {
        RVP_ProjectileData projectile = GSON.fromJson("""
                {
                  "wind_data": {
                    "enabled": true,
                    "direction_mode": "parent_facing_reverse",
                    "speed": 0.2,
                    "response": 0.1,
                    "turbulence": 0.0
                  }
                }
                """, RVP_ProjectileData.class);
        RVP_WindData wind = projectile.getWindData();
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 100; tick++) {
            velocity = RVP_WindDriftUtil.apply(velocity, new Vec3(0.0, 0.0, -1.0), wind, 7L, tick);
        }

        assertEquals(0.0, velocity.x, 1.0E-9);
        assertEquals(0.0, velocity.y, 1.0E-9);
        assertEquals(-0.2, velocity.z, 1.0E-4);
    }

    @Test
    void turbulenceIsDeterministicForSameSeedAndTick() {
        RVP_ProjectileData projectile = GSON.fromJson("""
                {"wind_data":{"enabled":true,"speed":0.1,"response":0.03,"turbulence":0.006}}
                """, RVP_ProjectileData.class);
        Vec3 first = RVP_WindDriftUtil.apply(Vec3.ZERO, new Vec3(1, 0, 0),
                projectile.getWindData(), 1234L, 17);
        Vec3 second = RVP_WindDriftUtil.apply(Vec3.ZERO, new Vec3(1, 0, 0),
                projectile.getWindData(), 1234L, 17);

        assertEquals(first, second);
        assertTrue(Double.isFinite(first.x) && Double.isFinite(first.z));
    }

    @Test
    void horizontalWindDoesNotDampFallingVelocity() {
        RVP_ProjectileData projectile = GSON.fromJson("""
                {"wind_data":{"enabled":true,"speed":0.1,"response":0.5,"vertical_factor":0.0}}
                """, RVP_ProjectileData.class);
        Vec3 next = RVP_WindDriftUtil.apply(new Vec3(0.0, -0.7, 0.0),
                new Vec3(1.0, 0.0, 0.0), projectile.getWindData(), 1L, 1);

        assertEquals(-0.7, next.y, 1.0E-9);
        assertEquals(0.05, next.x, 1.0E-9);
    }
}
