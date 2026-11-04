package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class C2SRemoteViewDistanceTest {
    /** 上报只含视距，不接受其他玩家编号，并覆盖正常值与上下限编解码。 */
    @Test
    void roundTripPreservesClientRange() {
        for (int chunks : new int[]{2, 8, 16, 32}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                C2SRemoteViewDistance message = new C2SRemoteViewDistance(chunks);
                // 调用实际网络编解码，确保端点收到的范围与客户端设置一致。
                C2SRemoteViewDistance.encode(message, buffer);
                assertEquals(message, C2SRemoteViewDistance.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    /** 非法视距不能覆盖服务器保存的有效设置。 */
    @Test
    void rejectsInvalidWireValues() {
        for (int chunks : new int[]{-1, 0, 1, 33, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(chunks);
                // 调用实际解码，异常请求在进入每玩家状态表之前就被拒绝。
                assertThrows(IllegalArgumentException.class, () -> C2SRemoteViewDistance.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }
}
