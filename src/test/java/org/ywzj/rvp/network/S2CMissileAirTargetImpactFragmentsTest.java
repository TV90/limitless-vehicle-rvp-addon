package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S2CMissileAirTargetImpactFragmentsTest {
    /** 视觉事件编解码必须保留位置、初速度和运行时参数快照。 */
    @Test
    void codecRoundTripPreservesVisualSnapshot() {
        S2CMissileAirTargetImpactFragments message = new S2CMissileAirTargetImpactFragments(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                12.5D, 70.0D, -7.25D,
                1.2D, -0.3D, 2.4D,
                3, 1234L, 5678L,
                30.0F,
                0.9F, 0.05F, 0.15F, 0.015F,
                1, 1.5F, 80,
                0.42F, 0.0F,
                2, 0.35F, 360.0F, 0.18F, 8,
                600, 1536.0D);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CMissileAirTargetImpactFragments.encode(message, buffer);

            assertEquals(message, S2CMissileAirTargetImpactFragments.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    /** 网络解码应拒绝超出碎片数量上限的恶意或损坏消息。 */
    @Test
    void decoderRejectsInvalidFragmentCount() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
            buffer.writeDouble(0.0D);
            buffer.writeDouble(64.0D);
            buffer.writeDouble(0.0D);
            buffer.writeDouble(1.0D);
            buffer.writeDouble(0.0D);
            buffer.writeDouble(0.0D);
            buffer.writeVarInt(S2CMissileAirTargetImpactFragments.MAX_COUNT + 1);
            buffer.writeLong(1L);
            buffer.writeLong(2L);
            buffer.writeFloat(30.0F);
            buffer.writeFloat(0.9F);
            buffer.writeFloat(0.05F);
            buffer.writeFloat(0.15F);
            buffer.writeFloat(0.015F);
            buffer.writeVarInt(1);
            buffer.writeFloat(1.5F);
            buffer.writeVarInt(8);
            buffer.writeFloat(0.42F);
            buffer.writeFloat(0.0F);
            buffer.writeVarInt(2);
            buffer.writeFloat(0.35F);
            buffer.writeFloat(360.0F);
            buffer.writeFloat(0.18F);
            buffer.writeVarInt(8);
            buffer.writeVarInt(600);
            buffer.writeDouble(1536.0D);

            assertThrows(DecoderException.class,
                    () -> S2CMissileAirTargetImpactFragments.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
