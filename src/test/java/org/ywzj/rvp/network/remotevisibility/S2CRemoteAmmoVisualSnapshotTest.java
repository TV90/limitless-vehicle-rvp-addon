package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S2CRemoteAmmoVisualSnapshotTest {
    @Test
    void codecRoundTripPreservesVisibleAndBurningSets() {
        S2CRemoteAmmoVisualSnapshot message = new S2CRemoteAmmoVisualSnapshot(
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                Set.of(3, 7, 11),
                Set.of(7, 11));

        assertEquals(message, roundTrip(message));
    }

    @Test
    void emptySnapshotRoundTrips() {
        S2CRemoteAmmoVisualSnapshot message = new S2CRemoteAmmoVisualSnapshot(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                Set.of(),
                Set.of());

        assertEquals(message, roundTrip(message));
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
