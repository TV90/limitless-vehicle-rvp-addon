package org.ywzj.rvp.weapon.submunition;

import com.google.gson.Gson;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionStratifiedConeTest {

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
                    0.9, spread, index, count, RandomSource.create(1000L + index));
            assertEquals(0.9, sample.length(), 1.0E-9);
            double angle = Math.toDegrees(Math.acos(sample.normalize().dot(axis)));
            assertTrue(angle <= 72.0 + 1.0E-7);
            assertTrue(sample.y < 0.0);
            sum = sum.add(sample);
        }

        assertTrue(sum.y < -5.0);
        assertTrue(Math.abs(sum.x) < 1.0);
        assertTrue(Math.abs(sum.z) < 1.0);
    }
}
