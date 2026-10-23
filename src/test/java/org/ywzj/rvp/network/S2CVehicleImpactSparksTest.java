package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证命中位置、向外法线、入射方向和口径不会在网络传输中丢失。 */
class S2CVehicleImpactSparksTest {
    @Test
    void roundTripPreservesSurfaceSnapshot() {
        S2CVehicleImpactSparks original = new S2CVehicleImpactSparks(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new Vec3(123.25, 65.5, -25.75), new Vec3(-0.6, 0.8, 0), new Vec3(1, -2, 3).normalize(), 125f);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 调用生产编解码，验证所有字段完整往返且无多余字节。
            S2CVehicleImpactSparks.encode(original, buffer);
            assertEquals(original, S2CVehicleImpactSparks.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsInvalidParticleInputs() {
        ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleImpactSparks(
                dimension, new Vec3(Double.NaN, 0, 0), Vec3.ZERO, Vec3.ZERO, 30));
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleImpactSparks(
                dimension, Vec3.ZERO, new Vec3(0, Double.POSITIVE_INFINITY, 0), Vec3.ZERO, 30));
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleImpactSparks(
                dimension, Vec3.ZERO, Vec3.ZERO, new Vec3(0, Double.NaN, 0), 30));
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleImpactSparks(
                dimension, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new S2CVehicleImpactSparks(
                dimension, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 201));
    }
}
