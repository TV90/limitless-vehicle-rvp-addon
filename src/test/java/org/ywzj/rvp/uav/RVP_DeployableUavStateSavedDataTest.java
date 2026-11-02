package org.ywzj.rvp.uav;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖母车选择、冷却和部署租约的 NBT 往返及重复终结。 */
class RVP_DeployableUavStateSavedDataTest {
    @Test
    void zeroCooldownStillHoldsLeaseUntilFinishAndStaleFinishCannotClearNewChild() {
        UUID parent = UUID.randomUUID();
        UUID otherParent = UUID.randomUUID();
        UUID firstChild = UUID.randomUUID();
        UUID secondChild = UUID.randomUUID();
        RVP_DeployableUavStateSavedData data = RVP_DeployableUavStateSavedData.load(new CompoundTag());

        assertTrue(data.selectOption(parent, "suav", 0L, 10L));
        assertTrue(data.selectOption(otherParent, "radar", 0L, 10L));
        assertEquals("suav", data.getSelectedOptionId(parent, "fallback"));
        assertEquals("radar", data.getSelectedOptionId(otherParent, "fallback"));
        assertTrue(data.recordDeployment(parent, firstChild, "suav", 0));
        assertTrue(data.beginTermination(parent, firstChild, "returning", 20L));
        var returning = data.getSnapshot(parent, "fallback", 20L);
        assertTrue(returning.deployed());
        assertEquals(0, returning.remainingCooldownTick());
        assertEquals("returning", returning.lockedReason());
        assertFalse(data.selectOption(parent, "radar", returning.revision(), 20L));

        assertTrue(data.finishTermination(parent, firstChild));
        assertTrue(data.recordDeployment(parent, secondChild, "radar", 40));
        assertFalse(data.finishTermination(parent, firstChild));
        var current = data.getSnapshot(parent, "fallback", 21L);
        assertTrue(current.deployed());
        assertEquals("deployed", current.lockedReason());
        assertEquals("radar", data.getDeploymentOptionId(parent, secondChild));
        assertNull(data.getDeploymentOptionId(parent, firstChild));
    }
    @Test
    void selectionCooldownAndLeaseSurviveNbtReloadAndSettleOnlyOnce() {
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        RVP_DeployableUavStateSavedData data = RVP_DeployableUavStateSavedData.load(new CompoundTag());

        assertTrue(data.selectOption(parent, "suav", 0L, 100L));
        assertFalse(data.selectOption(parent, "other", 0L, 100L));
        assertTrue(data.recordDeployment(parent, child, "suav", 80));
        assertFalse(data.recordDeployment(parent, UUID.randomUUID(), "other", 10));
        assertTrue(data.beginTermination(parent, child, "returning", 1_000L));

        RVP_DeployableUavStateSavedData loaded = RVP_DeployableUavStateSavedData.load(
                data.save(new CompoundTag()));
        var pending = loaded.getSnapshot(parent, "fallback", 1_020L);
        assertEquals("suav", pending.selectedOptionId());
        assertEquals(3, pending.revision()); // 选择、部署、首次终结各推进一次revision
        assertEquals(60, pending.remainingCooldownTick());
        assertTrue(pending.deployed());
        assertEquals("returning", pending.lockedReason());
        assertEquals("suav", loaded.getDeploymentOptionId(parent, child));
        assertFalse(loaded.selectOption(parent, "other", pending.revision(), 1_020L));

        assertTrue(loaded.finishTermination(parent, child));
        assertFalse(loaded.finishTermination(parent, child));
        RVP_DeployableUavStateSavedData.clearRuntime(); // 清运行态不得清掉 SavedData 冷却
        var settled = loaded.getSnapshot(parent, "fallback", 1_040L);
        assertFalse(settled.deployed());
        assertEquals(40, settled.remainingCooldownTick());
        assertNull(loaded.getDeploymentOptionId(parent, child));
    }

    @Test
    void offlineReturnSurvivesRestartAndKeepsTerminalLeaseEvenAfterCooldownExpires() {
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        var data = RVP_DeployableUavStateSavedData.load(new CompoundTag());
        assertTrue(data.recordDeployment(parent, child, "fpv", 20));
        CompoundTag request = new CompoundTag();
        request.putUUID("parent", parent);
        request.putUUID("child", child);
        request.putString("dimension", "minecraft:overworld");
        request.putDouble("x", 123.25);
        data.putPendingReturn(player, request);
        assertTrue(data.beginTermination(parent, child, "returning", 100));
        var restored = RVP_DeployableUavStateSavedData.load(data.save(new CompoundTag()));
        assertEquals(123.25, restored.pendingReturns().get(player).getDouble("x"));
        assertEquals(0, restored.getSnapshot(parent, "", 500).remainingCooldownTick());
        assertTrue(restored.getSnapshot(parent, "", 500).deployed());
        assertFalse(restored.finishTermination(parent, child));
        assertTrue(restored.readyTerminations().isEmpty());
        restored.clearPendingReturn(player);
        assertEquals(child, restored.readyTerminations().get(parent));
        assertTrue(restored.finishTermination(parent, child));
        assertTrue(restored.selectOption(parent, "suav", restored.getSnapshot(parent, "", 500).revision(), 500));
    }
}
