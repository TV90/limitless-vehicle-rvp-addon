package org.ywzj.rvp.uav;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖漏判原生 UAV、下车清空锚点、离机被取消及传送假成功，不依赖运行 Minecraft 客户端。 */
class RVP_NativeUavReturnServiceTest {
    @Test void directNativeUavRequiresReturnAndDeploymentIdentityWins() {
        assertEquals(RVP_NativeUavReturnService.ReturnMode.NATIVE, RVP_NativeUavReturnService.classify(true, false));
        assertEquals(RVP_NativeUavReturnService.ReturnMode.MANNED, RVP_NativeUavReturnService.classify(false, false));
        assertEquals(RVP_NativeUavReturnService.ReturnMode.DEPLOYED, RVP_NativeUavReturnService.classify(false, true));
        assertEquals(RVP_NativeUavReturnService.ReturnMode.DEPLOYED, RVP_NativeUavReturnService.classify(true, true));
    }

    @Test void failedNativeReturnCannotFallBackToMannedOrDeployedRoute() {
        assertFalse(RVP_NativeUavReturnService.returnForMode(
                RVP_NativeUavReturnService.classify(true, false),
                () -> fail("direct UAV must not use parent return"), () -> false));
        assertTrue(RVP_NativeUavReturnService.returnForMode(
                RVP_NativeUavReturnService.classify(false, false),
                () -> fail("manned vehicle"), () -> fail("manned vehicle")));
    }

    @Test void nativeSnapshotSurvivesDismountClearingOriginalNbt() {
        CompoundTag tag = pointTag(100, 64, -20);
        Vec3 snapshot = RVP_NativeUavReturnService.readNativeReturnPoint(tag);
        tag.remove("fakeOperatorPositionX");
        tag.remove("fakeOperatorPositionY");
        tag.remove("fakeOperatorPositionZ");
        assertEquals(new Vec3(100, 64, -20), snapshot);
        assertNull(RVP_NativeUavReturnService.readNativeReturnPoint(tag));
    }

    @Test void missingPartialOrNonFiniteNativePointIsNotAZeroCoordinateFallback() {
        assertNull(RVP_NativeUavReturnService.readNativeReturnPoint(new CompoundTag()));
        CompoundTag partial = pointTag(10, 64, 20);
        partial.remove("fakeOperatorPositionY");
        assertNull(RVP_NativeUavReturnService.readNativeReturnPoint(partial));
        assertNull(RVP_NativeUavReturnService.readNativeReturnPoint(pointTag(Double.NaN, 64, 0)));
        assertNull(RVP_NativeUavReturnService.readNativeReturnPoint(pointTag(0, Double.POSITIVE_INFINITY, 0)));
        assertEquals(Vec3.ZERO, RVP_NativeUavReturnService.readNativeReturnPoint(pointTag(0, 0, 0)));
    }

    @Test void anchorBelongsToTheExactOperator() {
        UUID operator = UUID.randomUUID(), dummy = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putUUID("operator", operator);
        tag.putUUID("dummy", dummy);
        tag.putString("dimension", "minecraft:overworld");
        tag.putDouble("x", 100); tag.putDouble("y", 64); tag.putDouble("z", -20);
        assertNull(RVP_NativeUavReturnService.readOperatorAnchor(tag, UUID.randomUUID()));
        var anchor = RVP_NativeUavReturnService.readOperatorAnchor(tag, operator);
        assertNotNull(anchor);
        assertEquals(dummy, anchor.dummyId());
        assertEquals(new Vec3(100, 64, -20), anchor.position());
    }

    @Test void unsafeDestinationStopsBeforeAnyDismount() {
        List<String> calls = new ArrayList<>();
        var step = new RVP_NativeUavReturnService.ReturnStep(
                () -> RVP_NativeUavReturnService.outsideBlast(new Vec3(14, 0, 0), Vec3.ZERO, 12),
                () -> calls.add("dismount"), () -> true,
                () -> { calls.add("teleport"); return true; }, () -> true);
        assertFalse(RVP_NativeUavReturnService.executeSteps(List.of(step)));
        assertTrue(calls.isEmpty());
        assertTrue(RVP_NativeUavReturnService.outsideBlast(new Vec3(14.01, 0, 0), Vec3.ZERO, 12));
        assertFalse(RVP_NativeUavReturnService.outsideBlast(null, Vec3.ZERO, 12));
        assertFalse(RVP_NativeUavReturnService.outsideBlast(new Vec3(100, 0, 0), Vec3.ZERO, Float.NaN));
    }

    @Test void everyDismountFinishesBeforeAnyTeleportAndAllPositionsAreConfirmedLast() {
        List<String> calls = new ArrayList<>();
        List<RVP_NativeUavReturnService.ReturnStep> steps = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            int player = i;
            steps.add(new RVP_NativeUavReturnService.ReturnStep(() -> true,
                    () -> calls.add("dismount" + player), () -> true,
                    () -> { calls.add("teleport" + player); return true; },
                    () -> { calls.add("confirm" + player); return true; }));
        }
        assertTrue(RVP_NativeUavReturnService.executeSteps(steps));
        assertEquals(List.of("dismount1", "dismount2", "teleport1", "teleport2", "confirm1", "confirm2"), calls);
    }

    @Test void canceledDismountPreventsTeleport() {
        var step = new RVP_NativeUavReturnService.ReturnStep(() -> true, () -> {}, () -> false,
                () -> fail("still riding"), () -> fail("still riding"));
        assertFalse(RVP_NativeUavReturnService.executeSteps(List.of(step)));
    }

    @Test void successfulTeleportApiWithoutConfirmedPositionIsFailure() {
        var step = new RVP_NativeUavReturnService.ReturnStep(() -> true, () -> {}, () -> true,
                () -> true, () -> false);
        assertFalse(RVP_NativeUavReturnService.executeSteps(List.of(step)));
    }

    private static CompoundTag pointTag(double x, double y, double z) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("fakeOperatorPositionX", x);
        tag.putDouble("fakeOperatorPositionY", y);
        tag.putDouble("fakeOperatorPositionZ", z);
        return tag;
    }
}
