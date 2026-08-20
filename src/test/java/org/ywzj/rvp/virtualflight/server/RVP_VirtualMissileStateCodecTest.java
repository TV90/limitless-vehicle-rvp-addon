package org.ywzj.rvp.virtualflight.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_RvpTrajectoryIntegrator;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryState;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_VirtualMissileStateCodecTest {
    @Test
    void nbtRoundTripPreservesCompleteRuntimeState() {
        RVP_VirtualMissileState original = state();
        RVP_VirtualMissileState loaded = RVP_VirtualMissileState.load(original.save()).orElseThrow();

        assertEquals(original.flightUuid, loaded.flightUuid);
        assertEquals(original.dimension, loaded.dimension);
        assertEquals(original.weaponId, loaded.weaponId);
        assertEquals(original.ownerUuid, loaded.ownerUuid);
        assertEquals(original.launchPosition, loaded.launchPosition);
        assertEquals(original.trajectory(), loaded.trajectory());
        assertEquals(RVP_GuidancePhase.TERMINAL, loaded.snapshot.guidancePhase());
        assertEquals(345, loaded.snapshot.motorBurnEndTick());
        assertEquals(42, loaded.snapshot.secondPulseBurnTimeTick());
        assertTrue(loaded.snapshot.activeRadarOn());
        assertTrue(loaded.snapshot.gpsCruiseVerticalResetApplied());
        assertEquals(new Vec3(1, 2, 3), loaded.snapshot.gpsTargetOffset());
        assertEquals(new Vec3(300, 160, 400), loaded.snapshot.topAttackApexPosition());
        assertEquals(RVP_RvpTrajectoryIntegrator.ID, loaded.trajectoryImplementationId);
        assertEquals(RVP_RvpTrajectoryIntegrator.VERSION, loaded.trajectoryStateVersion);
        assertEquals(RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS, loaded.phase);
        assertEquals(2, loaded.restoreAttemptCount);
    }

    @Test
    void rejectsUnsupportedSchemaMissingFieldsAndNonFiniteCoordinates() {
        CompoundTag unsupported = state().save();
        unsupported.putInt("stateSchemaVersion", 999);
        assertTrue(RVP_VirtualMissileState.load(unsupported).isEmpty());

        CompoundTag missing = state().save();
        missing.remove("snapshot");
        assertTrue(RVP_VirtualMissileState.load(missing).isEmpty());

        CompoundTag nonFinite = state().save();
        nonFinite.getCompound("snapshot").getCompound("position").putDouble("x", Double.NaN);
        assertTrue(RVP_VirtualMissileState.load(nonFinite).isEmpty());
    }

    @Test
    void savedDataRoundTripAndDuplicateProtection() {
        RVP_VirtualMissileSavedData data = new RVP_VirtualMissileSavedData();
        RVP_VirtualMissileState state = state();
        assertTrue(data.add(state));
        assertFalse(data.add(state));

        RVP_VirtualMissileSavedData loaded = RVP_VirtualMissileSavedData.load(data.save(new CompoundTag()));
        assertEquals(1, loaded.size());
        assertTrue(loaded.contains(state.flightUuid));
    }

    @Test
    void virtualTickAppliesExplicitFixedGpsSubsystemSemantics() {
        RVP_VirtualMissileState state = state();
        RVP_VirtualTrajectoryState advanced = new RVP_VirtualTrajectoryState(
                new Vec3(104, 120.2, -193), new Vec3(4, 0.2, 7), 1, -60,
                9.5, 1242.6, 211, 799, 190);

        state.updateTrajectory(advanced);

        assertEquals(advanced, state.trajectory());
        assertEquals(state.snapshot.targetPosition(), state.snapshot.lastGuidancePosition());
        assertEquals(RVP_GuidancePhase.MAIN, state.snapshot.guidancePhase());
        assertFalse(state.snapshot.activeRadarOn());
        assertFalse(state.snapshot.activeRadarCatch());
        assertEquals(0, state.snapshot.activeRadarLostTargetTick());
        assertTrue(state.snapshot.gpsCruiseVerticalResetApplied());
        assertFalse(state.snapshot.topAttackApexReached());
        assertEquals(-1, state.snapshot.topAttackTriggerTick());
        assertEquals(Integer.MIN_VALUE, state.snapshot.irSeekerGraceUntilTick());
        assertFalse(state.snapshot.irSeekerLossGraceStarted());
    }

    private static RVP_VirtualMissileState state() {
        UUID flight = UUID.fromString("11111111-2222-3333-4444-555555555555");
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        RVP_VirtualTrajectoryState trajectory = new RVP_VirtualTrajectoryState(
                new Vec3(100, 120, -200), new Vec3(4, 0.2, 7), 3, 25,
                9.5, 1234.5, 210, 800, 190);
        RVP_VirtualMissileSnapshot snapshot = new RVP_VirtualMissileSnapshot(
                trajectory, new Vec3(5000, 70, 6000), new Vec3(4990, 70, 5990), null,
                new Vec3(0.1, 0, -0.2), RVP_GuidancePhase.TERMINAL, 345, 42, true, false, 7, true,
                new Vec3(1, 2, 3), true, new Vec3(0, 80, 0), new Vec3(5000, 70, 6000),
                new Vec3(300, 160, 400), true, 88, 240, true);
        return new RVP_VirtualMissileState(
                flight, dimension, ResourceLocation.fromNamespaceAndPath("rvp", "test_gps"),
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), null,
                Vec3.ZERO, snapshot, 77, 10, 1000, 1050,
                RVP_RvpTrajectoryIntegrator.ID, RVP_RvpTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS, 50, 12, 2, 1038);
    }
}
