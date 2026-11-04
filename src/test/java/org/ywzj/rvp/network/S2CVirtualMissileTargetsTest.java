package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 网络往返必须同时保留飞行 UUID、武器快照、扫描高度和恢复交接语义。 */
class S2CVirtualMissileTargetsTest {
    @Test
    void snapshotRoundTripKeepsIdentityAndRestorationDistinctFromRemoval() {
        CompoundTag data = new CompoundTag();
        data.putUUID("UUID", UUID.randomUUID());
        data.putString("weaponId", "rvp:virtual_contact_test");
        var message = new S2CVirtualMissileTargets(ResourceLocation.tryParse("minecraft:overworld"),
                List.of(new S2CVirtualMissileTargets.Entry(140, data, 64)), Set.of(139));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 调用生产网络编解码，不重新实现序列化。
            S2CVirtualMissileTargets.encode(message, buffer);
            var decoded = S2CVirtualMissileTargets.decode(buffer);
            assertEquals(message, decoded);
            assertEquals(data.getUUID("UUID"), decoded.entries().get(0).data().getUUID("UUID"));
            assertEquals(Set.of(139), decoded.restoredIds());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test
    void emptyCompleteSetCanExplicitlyRevokeEveryPreviousTarget() {
        var message = new S2CVirtualMissileTargets(ResourceLocation.tryParse("minecraft:overworld"), List.of(), Set.of());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CVirtualMissileTargets.encode(message, buffer);
            assertEquals(message, S2CVirtualMissileTargets.decode(buffer));
        } finally { buffer.release(); }
    }
}
