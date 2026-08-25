package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionCloudRadialSpreadTest {

    /** 测试 JSON 解析使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void samplesAlongHorizontalCloudOffsetWithoutVerticalImpulse() {
        RVP_SubmunitionSpreadData spread = parseSpread("""
                {
                  "mode": "cloud_radial_horizontal",
                  "cloud_direction_jitter": 0.0,
                  "cloud_speed_jitter": 0.0
                }
                """);

        // 调用本项目速度散布入口：验证新模式会读取最终出生偏移，且 Y 不参与外散方向或速度。
        Vec3 sampled = RVP_SubmunitionSpreadApplicator.applyVelocitySpread(
                new Vec3(0.0D, 0.0D, 2.0D), 0.0F, 0.0F, spread,
                new Vec3(3.0D, 8.0D, 4.0D), 0, 1, RandomSource.create(1L));

        assertEquals(1.2D, sampled.x, 1.0E-9D);
        assertEquals(0.0D, sampled.y, 1.0E-9D);
        assertEquals(1.6D, sampled.z, 1.0E-9D);
        assertEquals(2.0D, sampled.length(), 1.0E-9D);
    }

    @Test
    void configuredJitterRemainsOutwardAndInsideConfiguredSpeedRange() {
        RVP_SubmunitionSpreadData spread = parseSpread("""
                {
                  "mode": "cloud_radial_horizontal",
                  "cloud_direction_jitter": 15.0,
                  "cloud_speed_jitter": 0.25
                }
                """);
        Vec3 outward = new Vec3(1.0D, 0.0D, 0.0D);

        for (int seed = 0; seed < 512; seed++) {
            // 调用本项目云径向采样器：覆盖示例方向与速度随机边界。
            Vec3 sampled = RVP_SubmunitionSpreadApplicator.sampleCloudRadialHorizontal(
                    1.0D, new Vec3(5.0D, -2.0D, 0.0D), spread, RandomSource.create(seed));
            double angle = Math.toDegrees(Math.acos(sampled.normalize().dot(outward)));

            assertEquals(0.0D, sampled.y, 1.0E-12D);
            assertTrue(sampled.length() >= 0.75D - 1.0E-9D);
            assertTrue(sampled.length() <= 1.25D + 1.0E-9D);
            assertTrue(angle <= 15.0D + 1.0E-9D);
            assertTrue(sampled.dot(outward) >= 0.0D);
        }
    }

    @Test
    void degenerateAndInvalidOffsetsUseFiniteHorizontalFallback() {
        RVP_SubmunitionSpreadData spread = parseSpread("""
                {"mode":"cloud_radial_horizontal"}
                """);
        Vec3[] offsets = new Vec3[]{
                Vec3.ZERO,
                new Vec3(0.0D, 5.0D, 0.0D),
                new Vec3(Double.NaN, 0.0D, 1.0D),
                new Vec3(Double.POSITIVE_INFINITY, 0.0D, 0.0D)
        };

        for (int index = 0; index < offsets.length; index++) {
            // 调用本项目云径向采样器：退化偏移必须回退为有限水平单位方向。
            Vec3 sampled = RVP_SubmunitionSpreadApplicator.sampleCloudRadialHorizontal(
                    1.0D, offsets[index], spread, RandomSource.create(100L + index));
            assertTrue(Double.isFinite(sampled.x));
            assertEquals(0.0D, sampled.y, 1.0E-12D);
            assertTrue(Double.isFinite(sampled.z));
            assertEquals(1.0D, sampled.length(), 1.0E-9D);
        }
    }

    @Test
    void invalidCloudJitterValuesNormalizeToSafeLimits() {
        RVP_SubmunitionSpreadData invalid = parseSpread("""
                {
                  "mode": "cloud_radial_horizontal",
                  "cloud_direction_jitter": "NaN",
                  "cloud_speed_jitter": "Infinity"
                }
                """);
        RVP_SubmunitionSpreadData oversized = parseSpread("""
                {
                  "mode": "cloud_radial_horizontal",
                  "cloud_direction_jitter": 180.0,
                  "cloud_speed_jitter": 2.0
                }
                """);

        assertTrue(invalid.usesCloudRadialHorizontal());
        assertEquals(0.0F, invalid.getCloudDirectionJitter(), 0.0F);
        assertEquals(0.0F, invalid.getCloudSpeedJitter(), 0.0F);
        assertEquals(90.0F, oversized.getCloudDirectionJitter(), 0.0F);
        assertEquals(1.0F, oversized.getCloudSpeedJitter(), 0.0F);
    }

    @Test
    void ellipsoidCloudPositionAndParentInheritanceComposeWithRadialSpread() {
        RVP_SubmunitionReleaseData release = GSON.fromJson("""
                {
                  "release_cloud_enabled": true,
                  "release_cloud_radius": {"horizontal": 5.0, "vertical": 2.0}
                }
                """, RVP_SubmunitionReleaseData.class);
        RVP_SubmunitionPayloadData payload = GSON.fromJson("""
                {
                  "inherit_parent_velocity": false,
                  "inherit_parent_horizontal_velocity": true,
                  "velocity_scale": 0.40,
                  "launch_speed": 1.0,
                  "payloads_velocity": [0.0, 0.0, 0.0],
                  "spread": {
                    "mode": "cloud_radial_horizontal",
                    "cloud_direction_jitter": 15.0,
                    "cloud_speed_jitter": 0.25
                  }
                }
                """, RVP_SubmunitionPayloadData.class);
        Vec3 parentPosition = new Vec3(20.0D, 80.0D, -30.0D);
        Vec3 parentVelocity = new Vec3(6.0D, -2.0D, 0.0D);
        RandomSource random = RandomSource.create(20260825L);

        for (int index = 0; index < 38; index++) {
            // 调用本项目释放云采样器：验证水平与竖直出生偏移分别服从两个轴半径。
            Vec3 spawnPosition = RVP_SubmunitionReleaseCloudUtil.samplePosition(parentPosition, release, random);
            Vec3 spawnOffset = spawnPosition.subtract(parentPosition);
            double normalizedRadius = Math.sqrt(
                    spawnOffset.x * spawnOffset.x / 25.0D
                            + spawnOffset.y * spawnOffset.y / 4.0D
                            + spawnOffset.z * spawnOffset.z / 25.0D);
            assertTrue(normalizedRadius <= 1.0D + 1.0E-9D);

            // 调用本项目云径向采样器：最终出生偏移决定水平外散方向，且不提供 Y 初速。
            Vec3 radialVelocity = RVP_SubmunitionSpreadApplicator.sampleCloudRadialHorizontal(
                    payload.getLaunchSpeed(), spawnOffset, payload.getSpread(), random);
            // 调用本项目水平继承工具：保留母弹 X/Z 的 40%，不继承母弹 Y。
            Vec3 parentHorizontal = RVP_SubmunitionVelocityUtil.resolveParentHorizontalVelocity(
                    parentVelocity, payload);
            Vec3 composedVelocity = radialVelocity.add(parentHorizontal);

            assertEquals(2.4D, parentHorizontal.x, 1.0E-6D);
            assertEquals(0.0D, parentHorizontal.y, 1.0E-9D);
            assertEquals(0.0D, radialVelocity.y, 1.0E-9D);
            assertEquals(0.0D, composedVelocity.y, 1.0E-9D);
        }
    }

    private static RVP_SubmunitionSpreadData parseSpread(String json) {
        return GSON.fromJson(json, RVP_SubmunitionSpreadData.class);
    }
}
