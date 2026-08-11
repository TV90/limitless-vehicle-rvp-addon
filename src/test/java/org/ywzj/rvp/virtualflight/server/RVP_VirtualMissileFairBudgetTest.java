package org.ywzj.rvp.virtualflight.server;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_VirtualMissileFairBudgetTest {
    @Test
    void globalTicketBudgetIsHardBoundedAndRoundRobin() {
        int[] missing = new int[200];
        java.util.Arrays.fill(missing, 10);
        List<Integer> first = RVP_VirtualMissileManager.fairAllocationOrder(missing, 16, 0);
        List<Integer> second = RVP_VirtualMissileManager.fairAllocationOrder(missing, 16, 16);

        assertEquals(16, first.size());
        assertEquals(16, second.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), first);
        assertEquals(16, second.get(0));
        Set<Integer> combined = new HashSet<>(first);
        combined.addAll(second);
        assertEquals(32, combined.size());
    }

    @Test
    void skipsSatisfiedStatesWithoutWastingBudget() {
        List<Integer> order = RVP_VirtualMissileManager.fairAllocationOrder(
                new int[]{0, 2, 0, 1}, 10, 0);
        assertEquals(List.of(1, 3, 1), order);
        assertTrue(order.size() <= RVP_VirtualMissileManager.MAX_NEW_RESTORE_CHUNKS_PER_TICK);
    }

    @Test
    void twoHundredDefaultRadiusRequestsFinishWithinDefaultTimeoutBudgetWindow() {
        int[] missing = new int[200];
        // 默认半径 1 为 3x3，加一个可能位于半径外的首 Tick 终点区块，最坏 10 个。
        java.util.Arrays.fill(missing, 10);
        int cursor = 0;
        int ticks = 0;
        while (java.util.Arrays.stream(missing).sum() > 0 && ticks < 200) {
            List<Integer> order = RVP_VirtualMissileManager.fairAllocationOrder(missing, 16, cursor);
            assertTrue(order.size() <= 16);
            for (int index : order) missing[index]--;
            cursor = (cursor + Math.max(order.size(), 1)) % missing.length;
            ticks++;
        }
        assertEquals(0, java.util.Arrays.stream(missing).sum());
        assertTrue(ticks <= 130, "2000 chunks / 16 per tick should complete in at most 125 useful ticks");
    }
}
