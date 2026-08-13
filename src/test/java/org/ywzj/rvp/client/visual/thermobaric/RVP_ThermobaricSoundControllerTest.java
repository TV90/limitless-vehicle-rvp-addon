package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricSoundControllerTest {
    @AfterEach
    void clearDedupeState() {
        RVP_ThermobaricSoundController.clear();
    }

    @Test
    void mainSoundIsImmediateThroughTwentyFourBlocksThenUsesSpeedOfSound() {
        assertEquals(0, RVP_ThermobaricSoundController.resolveMainArrivalTick(0.0D));
        assertEquals(0, RVP_ThermobaricSoundController.resolveMainArrivalTick(24.0D));
        assertEquals(2, RVP_ThermobaricSoundController.resolveMainArrivalTick(24.01D));
        assertEquals(8, RVP_ThermobaricSoundController.resolveMainArrivalTick(128.0D));
        assertEquals(30, RVP_ThermobaricSoundController.resolveMainArrivalTick(512.0D));
    }

    @Test
    void nearSoundThresholdUsesFourVisualRadiiWithTwentyFourBlockFloor() {
        assertEquals(24.0D,
                RVP_ThermobaricSoundController.resolveNearSoundDistance(2.0F));
        assertEquals(40.0D,
                RVP_ThermobaricSoundController.resolveNearSoundDistance(10.0F));
    }

    @Test
    void tailDelayIsStableAndAlwaysBetweenThreeAndSixTicks() {
        for (long seed = -128L; seed <= 128L; seed++) {
            int first = RVP_ThermobaricSoundController.resolveTailDelayTicks(seed);
            int second = RVP_ThermobaricSoundController.resolveTailDelayTicks(seed);
            assertEquals(first, second);
            assertTrue(first >= 3 && first <= 6);
        }
    }

    @Test
    void dedupeKeySeparatesDimensionQuantizedPositionAndSeed() {
        ResourceLocation overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        ResourceLocation nether = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
        RVP_ThermobaricSoundController.EventKey base =
                RVP_ThermobaricSoundController.createEventKey(overworld,
                        new Vec3(10.01D, 20.01D, 30.01D), 9L);
        RVP_ThermobaricSoundController.EventKey sameQuantizedPosition =
                RVP_ThermobaricSoundController.createEventKey(overworld,
                        new Vec3(10.04D, 20.04D, 30.04D), 9L);
        RVP_ThermobaricSoundController.EventKey otherDimension =
                new RVP_ThermobaricSoundController.EventKey(nether, 80L, 160L, 240L, 9L);
        RVP_ThermobaricSoundController.EventKey otherPosition =
                new RVP_ThermobaricSoundController.EventKey(overworld, 81L, 160L, 240L, 9L);
        RVP_ThermobaricSoundController.EventKey otherSeed =
                new RVP_ThermobaricSoundController.EventKey(overworld, 80L, 160L, 240L, 10L);

        assertEquals(base, sameQuantizedPosition);
        assertNotEquals(base, otherDimension);
        assertNotEquals(base, otherPosition);
        assertNotEquals(base, otherSeed);
        assertTrue(RVP_ThermobaricSoundController.claimEvent(base, 100L));
        assertFalse(RVP_ThermobaricSoundController.claimEvent(base, 299L));
        assertTrue(RVP_ThermobaricSoundController.claimEvent(base, 300L));
        RVP_ThermobaricSoundController.clear();
        assertTrue(RVP_ThermobaricSoundController.claimEvent(base, 300L));
    }
}
