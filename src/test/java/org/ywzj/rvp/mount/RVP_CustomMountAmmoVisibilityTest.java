package org.ywzj.rvp.mount;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 验证多挂架多弹体的顺序/轮转可见性分配。 */
class RVP_CustomMountAmmoVisibilityTest {

    @Test
    void sequentialKeepsBlockOrder() {
        int[] counts = {4, 4};
        assertArrayEquals(new int[]{0, 0}, RVP_CustomMountAmmoVisibility.sequential(counts, 0));
        assertArrayEquals(new int[]{1, 0}, RVP_CustomMountAmmoVisibility.sequential(counts, 1));
        assertArrayEquals(new int[]{4, 0}, RVP_CustomMountAmmoVisibility.sequential(counts, 4));
        assertArrayEquals(new int[]{4, 1}, RVP_CustomMountAmmoVisibility.sequential(counts, 5));
        assertArrayEquals(new int[]{4, 4}, RVP_CustomMountAmmoVisibility.sequential(counts, 8));
    }

    @Test
    void roundRobinAlternatesTwoMounts() {
        int[] counts = {4, 4};
        assertArrayEquals(new int[]{0, 0}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 0));
        assertArrayEquals(new int[]{1, 0}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 1));
        assertArrayEquals(new int[]{1, 1}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 2));
        assertArrayEquals(new int[]{2, 1}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 3));
        assertArrayEquals(new int[]{2, 2}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 4));
        assertArrayEquals(new int[]{4, 4}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 8));
    }

    @Test
    void roundRobinCyclesFourMounts() {
        int[] counts = {4, 4, 4, 4};
        assertArrayEquals(new int[]{1, 0, 0, 0}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 1));
        assertArrayEquals(new int[]{1, 1, 1, 1}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 4));
        assertArrayEquals(new int[]{2, 1, 1, 1}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 5));
        assertArrayEquals(new int[]{2, 2, 2, 2}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 8));
        assertArrayEquals(new int[]{4, 4, 4, 4}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 16));
    }

    @Test
    void roundRobinSkipsExhaustedMount() {
        int[] counts = {1, 3};
        assertArrayEquals(new int[]{1, 0}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 1));
        assertArrayEquals(new int[]{1, 1}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 2));
        assertArrayEquals(new int[]{1, 2}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 3));
        assertArrayEquals(new int[]{1, 3}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 4));
    }

    @Test
    void firedIsClampedToVisibleBones() {
        int[] counts = {2, 2};
        assertArrayEquals(new int[]{2, 2}, RVP_CustomMountAmmoVisibility.roundRobin(counts, 99));
        assertArrayEquals(new int[]{0, 0}, RVP_CustomMountAmmoVisibility.roundRobin(counts, -1));
        assertArrayEquals(new int[]{2, 2}, RVP_CustomMountAmmoVisibility.sequential(counts, 99));
    }
}
