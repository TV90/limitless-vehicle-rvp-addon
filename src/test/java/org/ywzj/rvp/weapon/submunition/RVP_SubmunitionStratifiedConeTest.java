package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionStratifiedConeTest {

    /** 测试 JSON 解析使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void samplesUnitSpeedInsideWorldDownCone() {
        RVP_SubmunitionSpreadData spread = GSON.fromJson("""
                {
                  "mode": "stratified_cone",
                  "cone_half_angle": 72.0,
                  "cone_axis": "world_down",
                  "radial_distribution": "uniform_area",
                  "azimuth_jitter": 0.0,
                  "radial_jitter": 0.0
                }
                """, RVP_SubmunitionSpreadData.class);
        Vec3 axis = new Vec3(0.0, -1.0, 0.0);
        Vec3 sum = Vec3.ZERO;
        int count = 24;
        for (int index = 0; index < count; index++) {
            Vec3 sample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                    1.5, spread, index, count, RandomSource.create(1000L + index));
            assertEquals(1.5, sample.length(), 1.0E-9);
            double angle = Math.toDegrees(Math.acos(sample.normalize().dot(axis)));
            assertTrue(angle <= 72.0 + 1.0E-7);
            assertTrue(sample.y < 0.0);
            assertTrue(sample.y <= -1.5D * Math.cos(Math.toRadians(72.0D)) + 1.0E-9D);
            sum = sum.add(sample);
        }

        assertTrue(sum.y < -10.0);
        assertTrue(Math.abs(sum.x) < 1.0);
        assertTrue(Math.abs(sum.z) < 1.0);
    }

    @Test
    void lowLaunchSpeedCanUseIndependentRadialOpeningSpeed() {
        RVP_SubmunitionSpreadData spread = GSON.fromJson("""
                {
                  "mode": "stratified_cone",
                  "cone_half_angle": 84.0,
                  "cone_radial_speed": 1.5,
                  "azimuth_jitter": 0.0,
                  "radial_jitter": 0.0
                }
                """, RVP_SubmunitionSpreadData.class);
        double maxHorizontalSpeed = 0.0D;
        double horizontalSpeedSum = 0.0D;
        int count = 24;
        for (int index = 0; index < count; index++) {
            // 调用本项目分层圆锥采样器：验证低下落速度不会再限制径向展开速度。
            Vec3 sample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                    0.2D, spread, index, count, RandomSource.create(2000L + index));
            double horizontalSpeed = Math.sqrt(sample.x * sample.x + sample.z * sample.z);
            maxHorizontalSpeed = Math.max(maxHorizontalSpeed, horizontalSpeed);
            horizontalSpeedSum += horizontalSpeed;
            assertTrue(sample.y < 0.0D);
            assertTrue(Math.abs(sample.y) <= 0.2D + 1.0E-9D);
        }

        assertTrue(maxHorizontalSpeed > 1.45D);
        assertTrue(horizontalSpeedSum / count > 1.0D);
    }

    @Test
    void invalidRadialSpeedFallsBackToLaunchSpeed() {
        RVP_SubmunitionSpreadData spread = GSON.fromJson("""
                {"mode":"stratified_cone","cone_radial_speed":"NaN"}
                """, RVP_SubmunitionSpreadData.class);

        assertEquals(0.2D, spread.resolveConeRadialSpeed(0.2D), 1.0E-9D);
    }

}
