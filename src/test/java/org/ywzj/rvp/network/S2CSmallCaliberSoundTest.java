package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 服务端确认的弹体声音消息编解码与边界校验。 */
class S2CSmallCaliberSoundTest {

    @Test
    void bothSoundKindsRoundTripWithDimensionPositionAndVariant() {
        for (S2CSmallCaliberSound.Kind kind : S2CSmallCaliberSound.Kind.values()) {
            S2CSmallCaliberSound original = new S2CSmallCaliberSound(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), kind,
                    new Vec3(123.25D, 64.5D, -29.75D), kind.variantCount() - 1);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                // 调用生产编解码，验证类型、坐标及样本编号不会丢失。
                S2CSmallCaliberSound.encode(original, buffer);
                assertEquals(original, S2CSmallCaliberSound.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsNonFinitePositionsAndInvalidVariants() {
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        // 调用消息构造器，确认非法坐标和越界样本不会进入声音引擎。
        assertThrows(IllegalArgumentException.class, () -> new S2CSmallCaliberSound(dimension,
                S2CSmallCaliberSound.Kind.INCOMING, new Vec3(Double.NaN, 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new S2CSmallCaliberSound(dimension,
                S2CSmallCaliberSound.Kind.INCOMING, Vec3.ZERO, 6));
        assertThrows(IllegalArgumentException.class, () -> new S2CSmallCaliberSound(dimension,
                S2CSmallCaliberSound.Kind.BLOCK_IMPACT, Vec3.ZERO, -1));
    }
}
