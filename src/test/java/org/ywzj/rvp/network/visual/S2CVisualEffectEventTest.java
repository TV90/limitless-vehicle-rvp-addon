package org.ywzj.rvp.network.visual;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S2CVisualEffectEventTest {
    @Test
    void codecRoundTripPreservesDomainEvent() {
        S2CVisualEffectEvent message = new S2CVisualEffectEvent(
                ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric"),
                ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_standard"),
                "{\"scale\":1.25}",
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                new Vec3(12.5D, 64.0D, -7.25D),
                8.0F,
                1.25F,
                0.8F,
                100,
                768.0D,
                1234L,
                5678L,
                true,
                false,
                true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CVisualEffectEvent.encode(message, buffer);

            assertEquals(message, S2CVisualEffectEvent.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void unsupportedSchemaVersionIsRejected() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(S2CVisualEffectEvent.SCHEMA_VERSION + 1);

            assertThrows(DecoderException.class, () -> S2CVisualEffectEvent.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
