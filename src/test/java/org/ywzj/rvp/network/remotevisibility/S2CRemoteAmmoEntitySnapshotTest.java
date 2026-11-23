package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S2CRemoteAmmoEntitySnapshotTest {
    @Test
    void codecRoundTripPreservesEntityState() {
        CompoundTag data = new CompoundTag();
        data.putInt("Age", 17);
        S2CRemoteAmmoEntitySnapshot message = new S2CRemoteAmmoEntitySnapshot(
                new ResourceLocation("minecraft", "overworld"),
                List.of(new S2CRemoteAmmoEntitySnapshot.Entry(
                        23,
                        new ResourceLocation("rvp", "missile"),
                        new Vec3(1.25, 70.5, -3.75),
                        new Vec3(0.1, 0.2, -0.3),
                        data)));

        assertEquals(message, roundTrip(message));
    }

    @Test
    void emptySnapshotRoundTrips() {
        S2CRemoteAmmoEntitySnapshot message = new S2CRemoteAmmoEntitySnapshot(
                new ResourceLocation("minecraft", "the_nether"), List.of());

        assertEquals(message, roundTrip(message));
    }

    @Test
    void rejectsTooManyEntries() {
        S2CRemoteAmmoEntitySnapshot.Entry entry = new S2CRemoteAmmoEntitySnapshot.Entry(
                1,
                new ResourceLocation("rvp", "missile"),
                Vec3.ZERO,
                Vec3.ZERO,
                new CompoundTag());
        List<S2CRemoteAmmoEntitySnapshot.Entry> entries = new ArrayList<>(
                S2CRemoteAmmoEntitySnapshot.MAX_ENTRIES + 1);
        for (int index = 0; index <= S2CRemoteAmmoEntitySnapshot.MAX_ENTRIES; index++) {
            entries.add(entry);
        }

        assertThrows(IllegalArgumentException.class, () -> new S2CRemoteAmmoEntitySnapshot(
                new ResourceLocation("minecraft", "overworld"), entries));
    }

    @Test
    void rejectsNullEntityDataOnDecode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(new ResourceLocation("minecraft", "overworld"));
            buffer.writeVarInt(1);
            buffer.writeInt(23);
            buffer.writeResourceLocation(new ResourceLocation("rvp", "missile"));
            writeVec3(buffer, Vec3.ZERO);
            writeVec3(buffer, Vec3.ZERO);
            buffer.writeNbt(null);

            assertThrows(DecoderException.class, () -> S2CRemoteAmmoEntitySnapshot.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static S2CRemoteAmmoEntitySnapshot roundTrip(S2CRemoteAmmoEntitySnapshot message) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CRemoteAmmoEntitySnapshot.encode(message, buffer);
            return S2CRemoteAmmoEntitySnapshot.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }
}
