package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionVelocityUtilTest {

    /** 使用项目当前 Gson 行为构造不同的 payload 配置。 */
    private final Gson gson = new Gson();

    @Test
    void fixedWorldImpulseIsAddedAfterExistingVelocity() {
        RVP_SubmunitionPayloadData payload = payload("""
                {"payloads_velocity": [0.0, -3.0, 1.0]}
                """);

        Vec3 result = RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(
                new Vec3(4.0, 2.0, -1.0), payload, RandomSource.create(1L));

        assertVectorEquals(new Vec3(4.0, -1.0, 0.0), result);
    }

    @Test
    void randomFactorUsesOneMultiplierAndKeepsConfiguredDirection() {
        RVP_SubmunitionPayloadData payload = payload("""
                {
                  "payloads_velocity": [2.0, -4.0, 6.0],
                  "payloads_velocity_factor": 0.25
                }
                """);
        Vec3 base = new Vec3(10.0, 20.0, 30.0);
        RandomSource random = RandomSource.create(42L);

        for (int i = 0; i < 100; i++) {
            Vec3 result = RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(base, payload, random);
            Vec3 applied = result.subtract(base);
            double multiplier = applied.x / 2.0D;
            assertTrue(multiplier >= 0.75D && multiplier <= 1.25D);
            assertEquals(multiplier, applied.y / -4.0D, 1.0E-9D);
            assertEquals(multiplier, applied.z / 6.0D, 1.0E-9D);
        }
    }

    @Test
    void zeroConfigurationDoesNotChangeVelocityOrConsumeRandomness() {
        RVP_SubmunitionPayloadData payload = payload("{}");
        Vec3 base = new Vec3(1.0, 2.0, 3.0);
        RandomSource actualRandom = RandomSource.create(99L);
        RandomSource controlRandom = RandomSource.create(99L);

        Vec3 result = RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(base, payload, actualRandom);

        assertEquals(base, result);
        assertEquals(controlRandom.nextDouble(), actualRandom.nextDouble());
    }

    @Test
    void zeroFactorWithImpulseDoesNotConsumeRandomness() {
        RVP_SubmunitionPayloadData payload = payload("""
                {"payloads_velocity": [0.0, -3.0, 0.0], "payloads_velocity_factor": 0.0}
                """);
        RandomSource actualRandom = RandomSource.create(123L);
        RandomSource controlRandom = RandomSource.create(123L);

        RVP_SubmunitionVelocityUtil.applyConfiguredImpulse(Vec3.ZERO, payload, actualRandom);

        assertEquals(controlRandom.nextDouble(), actualRandom.nextDouble());
    }

    private RVP_SubmunitionPayloadData payload(String json) {
        return gson.fromJson(json, RVP_SubmunitionPayloadData.class);
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-9D);
        assertEquals(expected.y, actual.y, 1.0E-9D);
        assertEquals(expected.z, actual.z, 1.0E-9D);
    }
}
