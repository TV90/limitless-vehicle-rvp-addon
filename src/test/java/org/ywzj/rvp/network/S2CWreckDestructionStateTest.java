package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState.Mode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 网络往返必须保留 UUID、维度和绝对时间，重连补发不能变成新一次击毁。 */
class S2CWreckDestructionStateTest {
    @Test
    void roundTripPreservesDestructionTimeline() {
        S2CWreckDestructionState original = new S2CWreckDestructionState(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), UUID.randomUUID(),
                new RVP_WreckDestructionState(Mode.DELAYED, 123456, 480, 384, 60, true));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 调用真实网络编解码，断言传输后的时刻和已执行标记不变。
            S2CWreckDestructionState.encode(original, buffer);
            S2CWreckDestructionState decoded = S2CWreckDestructionState.decode(buffer);
            assertEquals(original, decoded);
            assertFalse(decoded.state().isLaunchDue(999999));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsAbsentTimeline() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeNbt(null);
            // 调用生产解码，缺失时间表不能静默退化为会随机点燃的默认分支。
            assertThrows(IllegalArgumentException.class, () -> S2CWreckDestructionState.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
