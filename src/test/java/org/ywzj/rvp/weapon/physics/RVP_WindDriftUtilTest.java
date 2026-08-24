package org.ywzj.rvp.weapon.physics;

import com.google.gson.Gson;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import org.ywzj.rvp.weapon.data.RVP_WindData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_WindDriftUtilTest {

    /** 测试 JSON 解析使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void turbulenceFrequencyUsesDocumentedDefaultAndRange() {
        RVP_WindData defaults = GSON.fromJson("{}", RVP_WindData.class);
        RVP_WindData negative = GSON.fromJson("{\"turbulence_frequency\":-1}", RVP_WindData.class);
        RVP_WindData excessive = GSON.fromJson("{\"turbulence_frequency\":2}", RVP_WindData.class);
        RVP_WindData invalid = GSON.fromJson("{\"turbulence_frequency\":\"NaN\"}", RVP_WindData.class);

        assertEquals(0.02f, defaults.getTurbulenceFrequency(), 1.0E-6f);
        assertEquals(0.0f, negative.getTurbulenceFrequency(), 1.0E-6f);
        assertEquals(0.5f, excessive.getTurbulenceFrequency(), 1.0E-6f);
        assertEquals(0.02f, invalid.getTurbulenceFrequency(), 1.0E-6f);
    }

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
                {"wind_data":{"enabled":true,"speed":0.1,"response":0.03,
                "turbulence":0.006,"turbulence_frequency":0.02}}
                """, RVP_ProjectileData.class);
        Vec3 first = RVP_WindDriftUtil.apply(Vec3.ZERO, new Vec3(1, 0, 0),
                projectile.getWindData(), 1234L, 17);
        Vec3 second = RVP_WindDriftUtil.apply(Vec3.ZERO, new Vec3(1, 0, 0),
                projectile.getWindData(), 1234L, 17);

        assertEquals(first, second);
        assertTrue(Double.isFinite(first.x) && Double.isFinite(first.z));
    }

    @Test
    void differentSeedsProduceDifferentSmoothWanderPhases() {
        RVP_WindData wind = parseWind(0.01f, 0.02f);
        Vec3 first = turbulenceComponent(wind, 1234L, 17);
        Vec3 second = turbulenceComponent(wind, 5678L, 17);

        assertNotEquals(first, second);
        assertTrue(first.distanceToSqr(second) > 1.0E-8D);
    }

    @Test
    void lowFrequencyKeepsAdjacentTurbulenceDirectionsContinuous() {
        RVP_WindData wind = parseWind(0.01f, 0.01f);
        Vec3 first = turbulenceComponent(wind, 4321L, 20).normalize();
        Vec3 second = turbulenceComponent(wind, 4321L, 21).normalize();

        assertTrue(first.dot(second) > 0.99D);
    }

    @Test
    void configuredFrequencyControlsWanderTurnRate() {
        RVP_WindData lowFrequency = parseWind(0.01f, 0.01f);
        RVP_WindData highFrequency = parseWind(0.01f, 0.25f);
        Vec3 lowFirst = turbulenceComponent(lowFrequency, 2468L, 10).normalize();
        Vec3 lowSecond = turbulenceComponent(lowFrequency, 2468L, 11).normalize();
        Vec3 highFirst = turbulenceComponent(highFrequency, 2468L, 10).normalize();
        Vec3 highSecond = turbulenceComponent(highFrequency, 2468L, 11).normalize();

        assertTrue(highFirst.dot(highSecond) < lowFirst.dot(lowSecond));
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

    @Test
    void independentContributionConvergesWithoutChangingDeploymentVelocity() {
        RVP_WindData wind = GSON.fromJson("""
                {"enabled":true,"speed":0.11,"response":0.045,
                "turbulence":0.0,"vertical_factor":0.0}
                """, RVP_WindData.class);
        Vec3 deployment = new Vec3(1.4D, 0.0D, -0.3D);
        Vec3 contribution = Vec3.ZERO;
        for (int tick = 0; tick < 200; tick++) {
            // 调用本项目独立风偏积分：只更新风偏贡献，部署速度由调用方原样保留并最终相加。
            contribution = RVP_WindDriftUtil.updateContribution(
                    contribution, new Vec3(1.0D, 0.0D, 0.0D), wind, 9L, tick);
        }

        assertEquals(0.11D, contribution.x, 1.0E-4D);
        assertEquals(0.0D, contribution.y, 1.0E-12D);
        assertEquals(0.0D, contribution.z, 1.0E-9D);
        assertEquals(new Vec3(1.4D, 0.0D, -0.3D), deployment);
    }

    @Test
    void independentTurbulenceRemainsInsideBoundedTargetSpeed() {
        RVP_WindData wind = parseWind(0.012f, 0.0f);
        Vec3 contribution = Vec3.ZERO;
        for (int tick = 0; tick < 300; tick++) {
            contribution = RVP_WindDriftUtil.updateContribution(
                    contribution, new Vec3(1.0D, 0.0D, 0.0D), wind, 1357L, tick);
        }

        assertTrue(contribution.length() <= wind.getSpeed() + wind.getTurbulence() + 1.0E-6D);
        assertEquals(0.0D, contribution.y, 1.0E-12D);
    }

    /** 解析只包含本测试所需字段的已启用风漂配置。 */
    private static RVP_WindData parseWind(float turbulence, float frequency) {
        return GSON.fromJson("""
                {
                  "enabled": true,
                  "speed": 0.1,
                  "response": 0.03,
                  "turbulence": %s,
                  "turbulence_frequency": %s
                }
                """.formatted(turbulence, frequency), RVP_WindData.class);
    }

    /**
     * 调用本项目风漂工具并扣除固定目标风速收敛量，只保留待比较的平滑扰动分量。
     */
    private static Vec3 turbulenceComponent(RVP_WindData wind, long seed, int tick) {
        Vec3 result = RVP_WindDriftUtil.apply(Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), wind, seed, tick);
        return result.subtract(wind.getSpeed() * wind.getResponse(), 0.0D, 0.0D);
    }
}
