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
    void allShockwaveClassesRoundTripWithDirectionalReach() {
        for (var hitClass : org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile.HitClass.values()) {
            var profile = new org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile(hitClass, 63.25, 0.7F,
                    java.util.List.of(10F, 11F, 12F, 13F, 14F, 15F, 16F, 17F));
            var message = shockwaveMessage(profile);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2CVisualEffectEvent.encode(message, buffer);
                assertEquals(message, S2CVisualEffectEvent.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }

    @Test
    void malformedAndTruncatedShockwavePayloadIsRejected() {
        var profile = new org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile(
                org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile.HitClass.GROUND, 64, 0.7F,
                java.util.Collections.nCopies(8, 20F));
        for (int corruption = 0; corruption < 3; corruption++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2CVisualEffectEvent.encode(shockwaveMessage(profile), buffer);
                if (corruption == 0) { buffer.setByte(buffer.writerIndex() - 45, 255); }
                if (corruption == 1) { buffer.setFloat(buffer.writerIndex() - 32, Float.NaN); }
                if (corruption == 2) { buffer.writerIndex(buffer.writerIndex() - 1); }
                assertThrows(RuntimeException.class, () -> S2CVisualEffectEvent.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    private static S2CVisualEffectEvent shockwaveMessage(org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile profile) {
        return new S2CVisualEffectEvent(ResourceLocation.fromNamespaceAndPath("rvp", "wilson_mach_shockwave"),
                ResourceLocation.fromNamespaceAndPath("rvp", "default"), "{}",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), new Vec3(1, 64, 2),
                8, 1, 1, 220, 8192, 123, 456, true, true, true, false, profile);
    }

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
                true, null);
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
                false, null);
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
