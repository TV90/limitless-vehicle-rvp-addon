package org.ywzj.rvp.weapon.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 自爆事务回归：回传必须先于扣弹和爆炸，重入及失败不产生第二次伤害。 */
class RVP_SelfDestructSequenceTest {
    @Test void returnFailureDoesNotConsumeOrDetonate() {
        List<String> calls = new ArrayList<>();
        assertFalse(RVP_SelfDestructSequence.execute(UUID.randomUUID(), () -> false,
                () -> { calls.add("ammo"); return true; }, () -> calls.add("blast"), () -> calls.add("finish")));
        assertTrue(calls.isEmpty());
    }
    @Test void confirmationPrecedesAmmoBlastAndCleanupAndRejectsReentry() {
        UUID vehicle = UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        assertTrue(RVP_SelfDestructSequence.execute(vehicle,
                () -> { calls.add("return"); return true; },
                () -> { calls.add("ammo"); return true; },
                () -> {
                    calls.add("blast");
                    assertFalse(RVP_SelfDestructSequence.execute(vehicle, () -> true, () -> true,
                            () -> fail("duplicate blast"), () -> fail("duplicate cleanup")));
                }, () -> calls.add("finish")));
        assertEquals(List.of("return", "ammo", "blast", "finish"), calls);
    }
    @Test void failedCommitDoesNotDetonateOrFinalize() {
        assertFalse(RVP_SelfDestructSequence.execute(UUID.randomUUID(), () -> true, () -> false,
                () -> fail("blast"), () -> fail("finish")));
    }
    @Test void effectFailureStillFinalizesCommittedVehicle() {
        List<String> calls = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> RVP_SelfDestructSequence.execute(UUID.randomUUID(),
                () -> true, () -> true, () -> { throw new IllegalStateException("effect"); }, () -> calls.add("finish")));
        assertEquals(List.of("finish"), calls);
    }
}
