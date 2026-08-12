package org.ywzj.rvp.network.visual;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                25.0F,
                4.0F,
                5000,
                50_000.0D,
                1234L,
                5678L,
                true,
                false,
                true,
                true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CVisualEffectEvent.encode(message, buffer);

            S2CVisualEffectEvent decoded = S2CVisualEffectEvent.decode(buffer);
            assertEquals(message, decoded);
            assertTrue(decoded.experimentalDynamicParticleBudget());
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

    @Test
    void zeroValuesAreAcceptedWithoutBeingRaisedToFormerMinimums() {
        S2CVisualEffectEvent message = new S2CVisualEffectEvent(
                ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric"),
                ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_standard"),
                "{}",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                Vec3.ZERO,
                0.0F,
                0.0F,
                0.0F,
                0,
                0.0D,
                1L,
                2L,
                false,
                false,
                false,
                false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CVisualEffectEvent.encode(message, buffer);

            S2CVisualEffectEvent decoded = S2CVisualEffectEvent.decode(buffer);
            assertEquals(message, decoded);
            assertFalse(decoded.experimentalDynamicParticleBudget());
        } finally {
            buffer.release();
        }
    }
}
