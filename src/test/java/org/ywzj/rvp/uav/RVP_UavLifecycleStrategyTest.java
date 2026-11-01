package org.ywzj.rvp.uav;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 联动生命周期、终态与延迟回乘策略的纯状态回归。 */
class RVP_UavLifecycleStrategyTest {

    @Test
    void relinkingKeepsBothDirectionsConsistentAndOldClearCannotEraseNewLink() {
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        UUID childA = UUID.randomUUID();
        UUID childB = UUID.randomUUID();
        try {
            RVP_DeployableUavLinkRegistry.link(parentA, childA);
            RVP_DeployableUavLinkRegistry.link(parentA, childB);
            assertNull(RVP_DeployableUavLinkRegistry.getParentUuid(childA));
            assertEquals(childB, RVP_DeployableUavLinkRegistry.getChildUuid(parentA));
            assertEquals(parentA, RVP_DeployableUavLinkRegistry.getParentUuid(childB));

            RVP_DeployableUavLinkRegistry.link(parentB, childB);
            assertNull(RVP_DeployableUavLinkRegistry.getChildUuid(parentA));
            assertEquals(parentB, RVP_DeployableUavLinkRegistry.getParentUuid(childB));
            assertEquals(childB, RVP_DeployableUavLinkRegistry.getChildUuid(parentB));

            // 旧 parent 的延迟清理不能删除已转移给 parentB 的反向映射。
            RVP_DeployableUavLinkRegistry.clearByParent(parentA);
            assertEquals(parentB, RVP_DeployableUavLinkRegistry.getParentUuid(childB));
        } finally {
            RVP_DeployableUavLinkRegistry.clearRuntime();
        }
    }

    @Test
    void terminalTombstoneSurvivesNbtRoundTripWithoutTreatingMissingEntityAsTerminal() {
        UUID removedChild = UUID.randomUUID();
        UUID merelyUnloadedChild = UUID.randomUUID();
        RVP_UavTerminalEntitySavedData data = new RVP_UavTerminalEntitySavedData();
        data.markTerminal(removedChild);
        CompoundTag saved = data.save(new CompoundTag());
        RVP_UavTerminalEntitySavedData restored = RVP_UavTerminalEntitySavedData.load(saved);
        assertTrue(restored.isTerminal(removedChild));
        assertFalse(restored.isTerminal(merelyUnloadedChild));
    }

    @Test
    void pendingReturnWaitsForDismountAndCrossDimensionTransferAndCountsDown() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        ResourceLocation nether = new ResourceLocation("minecraft", "the_nether");
        RVP_DeployableUavService.PendingAutoRide pending = new RVP_DeployableUavService.PendingAutoRide(
                UUID.randomUUID(), UUID.randomUUID(), overworld, nether, 2, 60, 42, overworld, new Vec3(10, 70, 20), false);

        assertEquals(RVP_DeployableUavService.PendingRideDecision.WAIT,
                RVP_DeployableUavService.classifyPendingRide(true, nether, false, false, 41, pending));
        assertEquals(RVP_DeployableUavService.PendingRideDecision.READY,
                RVP_DeployableUavService.classifyPendingRide(true, nether, false, false, 42, pending));
        assertEquals(RVP_DeployableUavService.PendingRideDecision.CANCEL,
                RVP_DeployableUavService.classifyPendingRide(true, overworld, false, false, 42, pending));
        assertEquals(RVP_DeployableUavService.PendingRideDecision.ALREADY_PARENT,
                RVP_DeployableUavService.classifyPendingRide(true, overworld, true, true, 42, pending));
        assertEquals(RVP_DeployableUavService.PendingRideDecision.CANCEL,
                RVP_DeployableUavService.classifyPendingRide(false, nether, false, false, 42, pending));

        RVP_DeployableUavService.PendingAutoRide oneLeft =
                RVP_DeployableUavService.decrementPendingRetry(pending);
        assertEquals(59, oneLeft.retryLeft());
        assertNull(RVP_DeployableUavService.decrementPendingRetry(new RVP_DeployableUavService.PendingAutoRide(
                pending.childUuid(), pending.parentUuid(), pending.parentDimension(), pending.playerDimension(), pending.seatIndex(),
                1, pending.notBeforeTick(), pending.returnDimension(), pending.returnPosition(), pending.returned())));
    }
}
