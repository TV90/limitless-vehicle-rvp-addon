package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S2CRemoteAmmoVisualSnapshotTest {
    @Test
    void codecRoundTripPreservesVisibleIdsAndRemainingBurnTicks() {
        S2CRemoteAmmoVisualSnapshot message = new S2CRemoteAmmoVisualSnapshot(
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                Set.of(3, 7, 11),
                Map.of(7, 0, 11, 240));

        assertEquals(message, roundTrip(message));
    }

    @Test
    void emptySnapshotRoundTrips() {
        S2CRemoteAmmoVisualSnapshot message = new S2CRemoteAmmoVisualSnapshot(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                Set.of(),
                Map.of());

        assertEquals(message, roundTrip(message));
    }

    @Test
    void rejectsBurnStateForEntityOutsideVisibleSet() {
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        assertThrows(IllegalArgumentException.class, () -> new S2CRemoteAmmoVisualSnapshot(
                dimension, Set.of(3), Map.of(7, 20)));
    }

    @Test
    void rejectsRemainingBurnTicksBeyondProtocolLimit() {
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        assertThrows(IllegalArgumentException.class, () -> new S2CRemoteAmmoVisualSnapshot(
                dimension, Set.of(3),
                Map.of(3, S2CRemoteAmmoVisualSnapshot.MAX_MOTOR_BURN_REMAINING_TICKS + 1)));
    }

    private static S2CRemoteAmmoVisualSnapshot roundTrip(S2CRemoteAmmoVisualSnapshot message) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CRemoteAmmoVisualSnapshot.encode(message, buffer);
            return S2CRemoteAmmoVisualSnapshot.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
