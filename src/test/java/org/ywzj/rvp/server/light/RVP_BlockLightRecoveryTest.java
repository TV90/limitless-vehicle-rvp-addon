package org.ywzj.rvp.server.light;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 用可控假世界验证真实光恢复的外部改动、卸载、写失败和停服分支。 */
class RVP_BlockLightRecoveryTest {
    @Test
    void expiredOwnedLightRestoresOriginalState() {
        AtomicReference<String> block = new AtomicReference<>("light:15");
        RVP_BlockLightRecovery.Result result = RVP_BlockLightRecovery.recover(true, true, "light:15", "cave_air",
                block.get(), original -> { block.set(original); return true; });
        assertEquals("cave_air", block.get());
        assertTrue(result.release());
    }

    @Test
    void playerReplacementIsPreservedAndCredentialReleased() {
        AtomicReference<String> block = new AtomicReference<>("player_stone");
        RVP_BlockLightRecovery.Result result = RVP_BlockLightRecovery.recover(true, true, "light:15", "air",
                block.get(), original -> { fail("Player block must not be overwritten"); return false; });
        assertEquals("player_stone", block.get());
        assertEquals(RVP_BlockLightRecovery.Result.EXTERNAL_CHANGE, result);
        assertTrue(result.release());
    }

    @Test
    void unloadedChunkKeepsRecoveryCredentialWithoutTouchingWorld() {
        RVP_BlockLightRecovery.Result result = RVP_BlockLightRecovery.recover(false, true, "light:15", "air", null,
                original -> { fail("Unloaded chunk must not be accessed"); return false; });
        assertEquals(RVP_BlockLightRecovery.Result.WAIT_FOR_CHUNK, result);
        assertFalse(result.release());
    }

    @Test
    void restoreWriteFailureRetriesRatherThanLosingCredential() {
        RVP_BlockLightRecovery.Result result = RVP_BlockLightRecovery.recover(true, true, "light:15", "air", "light:15",
                original -> false);
        assertEquals(RVP_BlockLightRecovery.Result.RETRY, result);
        assertFalse(result.release());
    }

    @Test
    void shutdownForceExpiresLightAndUnexpiredLightDoesNotWrite() {
        assertEquals(RVP_BlockLightRecovery.Result.ACTIVE,
                RVP_BlockLightRecovery.recover(true, false, "light:15", "air", "light:15",
                        original -> { fail("Active light must not be restored early"); return false; }));
        assertEquals(RVP_BlockLightRecovery.Result.RESTORED,
                RVP_BlockLightRecovery.recover(true, true, "light:15", "air", "light:15", original -> true));
    }

    @Test
    void crashBetweenJournalAndPlacementDoesNotWriteAnUnownedPosition() {
        assertEquals(RVP_BlockLightRecovery.Result.EXTERNAL_CHANGE,
                RVP_BlockLightRecovery.recover(true, true, "light:15", "air", "air",
                        original -> { fail("No placed light remains to restore"); return false; }));
    }

    @Test
    void continuousFireCannotRenewSameLeaseForever() {
        long expires = 108;
        for (long now = 101; now < 140; now++) {
            expires = RVP_BlockLightRecovery.renewedExpiry(100, expires, now, 10);
            assertTrue(expires <= 140);
        }
        assertEquals(140, expires);
    }
}
