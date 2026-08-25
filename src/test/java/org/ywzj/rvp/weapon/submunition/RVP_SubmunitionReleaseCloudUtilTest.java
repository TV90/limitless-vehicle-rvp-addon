package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionReleaseCloudUtilTest {

    /** 测试当前 JSON schema 解析行为使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void disabledCloudKeepsCenterAndDoesNotConsumeRandomness() {
        RVP_SubmunitionReleaseData release = release("""
                {
                  "release_cloud_enabled": false,
                  "release_cloud_radius": {"horizontal": 4.0, "vertical": 2.0}
                }
                """);
        RandomSource actualRandom = RandomSource.create(12345L);
        RandomSource controlRandom = RandomSource.create(12345L);
        Vec3 center = new Vec3(10.0D, 20.0D, -30.0D);

        // 调用本项目释放云采样器：验证默认关闭时不会改变旧武器位置或随机数序列。
        Vec3 result = RVP_SubmunitionReleaseCloudUtil.samplePosition(center, release, actualRandom);

        assertEquals(center, result);
        assertEquals(controlRandom.nextDouble(), actualRandom.nextDouble());
    }

    @Test
    void zeroRadiusKeepsCenterAndDoesNotConsumeRandomness() {
        RVP_SubmunitionReleaseData release = release("""
                {
                  "release_cloud_enabled": true,
                  "release_cloud_radius": {"horizontal": 0.0, "vertical": 0.0}
                }
                """);
        RandomSource actualRandom = RandomSource.create(54321L);
        RandomSource controlRandom = RandomSource.create(54321L);
        Vec3 center = new Vec3(-5.0D, 8.0D, 13.0D);

        // 调用本项目释放云采样器：验证显式零半径仍保持同点释放兼容行为。
        Vec3 result = RVP_SubmunitionReleaseCloudUtil.samplePosition(center, release, actualRandom);

        assertEquals(center, result);
        assertEquals(controlRandom.nextDouble(), actualRandom.nextDouble());
    }

    @Test
    void samplesUniformFourByTwoBlockEllipsoidVolume() {
        RVP_SubmunitionReleaseData release = release("""
                {
                  "release_cloud_enabled": true,
                  "release_cloud_radius": {"horizontal": 4.0, "vertical": 2.0}
                }
                """);
        RandomSource random = RandomSource.create(20260825L);
        Vec3 center = new Vec3(100.0D, 64.0D, -40.0D);
        int samples = 20_000;
        Vec3 sumOffset = Vec3.ZERO;
        double normalizedRadiusSum = 0.0D;
        double maxAbsX = 0.0D;
        double maxAbsY = 0.0D;
        double maxAbsZ = 0.0D;

        for (int index = 0; index < samples; index++) {
            // 调用本项目释放云采样器：固定种子验证权威生成点均匀覆盖水平 4 格、竖直 2 格的椭球体积。
            Vec3 position = RVP_SubmunitionReleaseCloudUtil.samplePosition(center, release, random);
            Vec3 offset = position.subtract(center);
            double normalizedRadius = Math.sqrt(
                    offset.x * offset.x / 16.0D
                            + offset.y * offset.y / 4.0D
                            + offset.z * offset.z / 16.0D);
            assertTrue(normalizedRadius <= 1.0D + 1.0E-9D);
            sumOffset = sumOffset.add(offset);
            normalizedRadiusSum += normalizedRadius;
            maxAbsX = Math.max(maxAbsX, Math.abs(offset.x));
            maxAbsY = Math.max(maxAbsY, Math.abs(offset.y));
            maxAbsZ = Math.max(maxAbsZ, Math.abs(offset.z));
        }

        Vec3 meanOffset = sumOffset.scale(1.0D / samples);
        assertTrue(Math.abs(meanOffset.x) < 0.05D);
        assertTrue(Math.abs(meanOffset.y) < 0.05D);
        assertTrue(Math.abs(meanOffset.z) < 0.05D);
        assertTrue(maxAbsX > 3.8D);
        assertTrue(maxAbsY > 1.9D);
        assertTrue(maxAbsZ > 3.8D);
        assertEquals(0.75D, normalizedRadiusSum / samples, 0.02D);
    }

    private static RVP_SubmunitionReleaseData release(String json) {
        return GSON.fromJson(json, RVP_SubmunitionReleaseData.class);
    }
}
