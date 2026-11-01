package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 兜底声音消息必须独立保留位置、载具身份与武器 ID，不依赖客户端生成实体。 */
class S2CVehicleSoundTest {

    @Test
    void everySoundKindRoundTripsWithoutAnEntityOrWorld() {
        for (S2CVehicleSound.Kind kind : S2CVehicleSound.Kind.values()) {
            S2CVehicleSound original = new S2CVehicleSound(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), kind,
                    UUID.randomUUID(), 42,
                    ResourceLocation.fromNamespaceAndPath("test", "missile"),
                    new Vec3(-12.25, 78.5, 2047.125));
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                // 调用生产编解码，验证各发射声分类的消息均完整往返。
                S2CVehicleSound.encode(original, buffer);
                assertEquals(original, S2CVehicleSound.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsNonFinitePositionAndMissileWithoutWeaponIdentity() {
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        UUID vehicle = UUID.randomUUID();
        // 调用生产构造校验：不能把非法坐标送进声音引擎，也不能跳过武器自定义声音保护。
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleSound(dimension,
                S2CVehicleSound.Kind.COLD_LAUNCH, vehicle, 1,
                ResourceLocation.fromNamespaceAndPath("test", "missile"), new Vec3(Double.NaN, 0, 0)));
        assertThrows(NullPointerException.class, () -> new S2CVehicleSound(dimension,
                S2CVehicleSound.Kind.AIR2AIR, vehicle, 1, null, Vec3.ZERO));
    }
}
