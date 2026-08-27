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

    @Test
    void defaultsToExistingGoldenAngleAzimuth() {
        RVP_SubmunitionSpreadData defaultSpread = GSON.fromJson("""
                {"mode":"stratified_cone","azimuth_jitter":0.15,"radial_jitter":0.1}
                """, RVP_SubmunitionSpreadData.class);
        RVP_SubmunitionSpreadData explicitSpread = GSON.fromJson("""
                {
                  "mode":"stratified_cone",
                  "cone_azimuth_mode":"golden_angle",
                  "azimuth_jitter":0.15,
                  "radial_jitter":0.1
                }
                """, RVP_SubmunitionSpreadData.class);

        Vec3 spawnOffset = new Vec3(5.0D, 2.0D, -3.0D);
        // 调用本项目分层圆锥采样器：确认缺省方位模式与显式 golden_angle 完全一致。
        Vec3 defaultSample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                0.5D, defaultSpread, spawnOffset, 7, 34, RandomSource.create(3100L));
        Vec3 explicitSample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                0.5D, explicitSpread, spawnOffset, 7, 34, RandomSource.create(3100L));

        assertEquals("golden_angle", defaultSpread.getConeAzimuthMode());
        assertEquals(defaultSample.x, explicitSample.x, 1.0E-12D);
        assertEquals(defaultSample.y, explicitSample.y, 1.0E-12D);
        assertEquals(defaultSample.z, explicitSample.z, 1.0E-12D);
    }

    @Test
    void spawnRadialAzimuthPointsOutwardInEveryHorizontalQuadrant() {
        RVP_SubmunitionSpreadData spread = GSON.fromJson("""
                {
                  "mode":"stratified_cone",
                  "cone_half_angle":42.0,
                  "cone_radial_speed":0.9,
                  "cone_azimuth_mode":"spawn_radial",
                  "azimuth_jitter":0.0,
                  "radial_jitter":0.08
                }
                """, RVP_SubmunitionSpreadData.class);
        Vec3[] spawnOffsets = {
                new Vec3(4.0D, 2.5D, 3.0D),
                new Vec3(-4.0D, -2.5D, 3.0D),
                new Vec3(-4.0D, 1.0D, -3.0D),
                new Vec3(4.0D, -1.0D, -3.0D)
        };

        for (int index = 0; index < spawnOffsets.length; index++) {
            Vec3 spawnOffset = spawnOffsets[index];
            // 调用本项目散布分派入口：确认生成器传入的最终出生偏移会进入 spawn_radial 圆锥方位。
            Vec3 sample = RVP_SubmunitionSpreadApplicator.applyVelocitySpread(
                    new Vec3(0.0D, -0.5D, 0.0D), 0.0F, 0.0F, spread, spawnOffset,
                    index + 4, 34, RandomSource.create(3200L + index));
            Vec3 horizontalOffset = new Vec3(spawnOffset.x, 0.0D, spawnOffset.z).normalize();
            Vec3 horizontalVelocity = new Vec3(sample.x, 0.0D, sample.z).normalize();

            assertEquals(1.0D, horizontalVelocity.dot(horizontalOffset), 1.0E-12D);
            assertTrue(sample.y < 0.0D);
        }
    }

    @Test
    void spawnRadialDegenerateOffsetUsesFiniteDeterministicGoldenAngleFallback() {
        RVP_SubmunitionSpreadData spread = GSON.fromJson("""
                {
                  "mode":"stratified_cone",
                  "cone_azimuth_mode":"spawn_radial",
                  "azimuth_jitter":0.0,
                  "radial_jitter":0.0
                }
                """, RVP_SubmunitionSpreadData.class);

        // 调用本项目分层圆锥采样器：确认中心点和非法水平偏移共用按序号确定的有限兜底方向。
        Vec3 zeroOffsetSample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                0.5D, spread, Vec3.ZERO, 11, 34, RandomSource.create(3300L));
        Vec3 invalidOffsetSample = RVP_SubmunitionSpreadApplicator.sampleStratifiedCone(
                0.5D, spread, new Vec3(Double.NaN, 2.0D, Double.POSITIVE_INFINITY),
                11, 34, RandomSource.create(3301L));

        assertTrue(Double.isFinite(zeroOffsetSample.x));
        assertTrue(Double.isFinite(zeroOffsetSample.y));
        assertTrue(Double.isFinite(zeroOffsetSample.z));
        assertTrue(zeroOffsetSample.y < 0.0D);
        assertEquals(zeroOffsetSample.x, invalidOffsetSample.x, 1.0E-12D);
        assertEquals(zeroOffsetSample.y, invalidOffsetSample.y, 1.0E-12D);
        assertEquals(zeroOffsetSample.z, invalidOffsetSample.z, 1.0E-12D);
    }

    @Test
    void unknownOrNullAzimuthModeFallsBackToGoldenAngle() {
        RVP_SubmunitionSpreadData unknown = GSON.fromJson("""
                {"mode":"stratified_cone","cone_azimuth_mode":"legacy_value"}
                """, RVP_SubmunitionSpreadData.class);
        RVP_SubmunitionSpreadData explicitNull = GSON.fromJson("""
                {"mode":"stratified_cone","cone_azimuth_mode":null}
                """, RVP_SubmunitionSpreadData.class);
        RVP_SubmunitionSpreadData mixedCase = GSON.fromJson("""
                {"mode":"stratified_cone","cone_azimuth_mode":"SpAwN_RaDiAl"}
                """, RVP_SubmunitionSpreadData.class);

        assertEquals("golden_angle", unknown.getConeAzimuthMode());
        assertEquals("golden_angle", explicitNull.getConeAzimuthMode());
        assertEquals("spawn_radial", mixedCase.getConeAzimuthMode());
    }

}
