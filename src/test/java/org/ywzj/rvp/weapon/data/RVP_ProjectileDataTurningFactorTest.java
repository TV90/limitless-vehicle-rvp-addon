package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ProjectileDataTurningFactorTest {

    private final Gson gson = new Gson();

    @Test
    void resolvesTurningFactorByFlightTick() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "turning_factor": {
                    "[[0,20],[100,inf]]": 0.05,
                    "[[20,100]]": 0.15
                  }
                }
                """, RVP_ProjectileData.class);

        assertTrue(data.hasTurningFactor());
        assertEquals(0.05f, data.resolveTurningFactor(0));
        assertEquals(0.05f, data.resolveTurningFactor(20));
        assertEquals(0.15f, data.resolveTurningFactor(21));
        assertEquals(0.05f, data.resolveTurningFactor(100));
        assertEquals(0.05f, data.resolveTurningFactor(500));
    }

    @Test
    void returnsNullWhenNoRangeMatches() {
        RVP_ProjectileData data = gson.fromJson("""
                {"turning_factor":{"[[10,20]]":0.2}}
                """, RVP_ProjectileData.class);

        assertNull(data.resolveTurningFactor(9));
        assertEquals(0.2f, data.resolveTurningFactor(10));
        assertNull(data.resolveTurningFactor(21));
    }

    @Test
    void clampsConfiguredFactorToPhysicalDomain() {
        RVP_ProjectileData high = gson.fromJson("""
                {"turning_factor":{"[[0,inf]]":4.0}}
                """, RVP_ProjectileData.class);
        RVP_ProjectileData low = gson.fromJson("""
                {"turning_factor":{"[[0,inf]]":-1.0}}
                """, RVP_ProjectileData.class);

        assertEquals(1f, high.resolveTurningFactor(0));
        assertEquals(0f, low.resolveTurningFactor(0));
    }

    @Test
    void absentConfigurationRemainsDistinctFromZero() {
        RVP_ProjectileData data = new RVP_ProjectileData();

        assertFalse(data.hasTurningFactor());
        assertNull(data.resolveTurningFactor(0));
    }
}
