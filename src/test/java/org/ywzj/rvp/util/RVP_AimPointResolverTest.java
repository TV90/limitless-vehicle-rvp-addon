package org.ywzj.rvp.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §49 红外族随机锁点的纯数学部分：种子稳定、下标不越界、候选全部可达。
 * 载具 OBB 列表依赖运行时实体，这里只验证不依赖世界的抽取逻辑。
 */
class RVP_AimPointResolverTest {

    @Test
    void pickIndexIsStableForTheSameLockSeed() {
        long seed = RVP_AimPointResolver.lockSeed(42, null);
        assertEquals(RVP_AimPointResolver.pickIndex(seed, 9), RVP_AimPointResolver.pickIndex(seed, 9));
    }

    @Test
    void pickIndexCoversEveryCandidateAndStaysInRange() {
        int size = 9;
        Set<Integer> seen = new HashSet<>();
        for (int projectileId = 0; projectileId < 2000; projectileId++) {
            int index = RVP_AimPointResolver.pickIndex(
                    RVP_AimPointResolver.lockSeed(projectileId, null), size);
            assertTrue(index >= 0 && index < size, "index out of range: " + index);
            seen.add(index);
        }
        assertEquals(size, seen.size());
    }

    @Test
    void pickIndexHandlesSingleOrEmptyCandidateLists() {
        assertEquals(0, RVP_AimPointResolver.pickIndex(123L, 1));
        assertEquals(0, RVP_AimPointResolver.pickIndex(123L, 0));
    }

    @Test
    void lockSeedChangesBetweenProjectilesAndIsStableForOne() {
        assertNotEquals(RVP_AimPointResolver.lockSeed(1, null), RVP_AimPointResolver.lockSeed(2, null));
        assertEquals(RVP_AimPointResolver.lockSeed(7, null), RVP_AimPointResolver.lockSeed(7, null));
    }
}
