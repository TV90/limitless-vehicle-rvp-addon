package org.ywzj.rvp.maintenance.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.maintenance.RVP_RepairPlanner;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 超过原 64 块上限的完整队列必须往返保持，异常长度必须拒绝。 */
class RVP_RepairOrderCodecTest {
    @Test void fullQueuesAndRequestsRoundTrip() {
        List<String> era = IntStream.range(0, 110).mapToObj(i -> "era-" + i).toList();
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SSetRepairOrder.encode(new C2SSetRepairOrder(17, era, List.of("Engine")), buf);
            var read = C2SSetRepairOrder.decode(buf);
            assertEquals(era, read.eraBones);
            assertEquals(List.of("Engine"), read.deviceBones);
            assertFalse(read.requestOnly);
            assertEquals(0, buf.readableBytes());
            C2SSetRepairOrder.encode(new C2SSetRepairOrder(17), buf);
            assertTrue(C2SSetRepairOrder.decode(buf).requestOnly);
            var response = new S2CRepairOrder(17, era, List.of("Engine"));
            S2CRepairOrder.encode(response, buf);
            assertEquals(response, S2CRepairOrder.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test void negativeAndOversizedCountsRejectPacketInsteadOfReadingWrongQueue() {
        for (int count : new int[] {-1, RVP_RepairPlanner.MAX_BONES + 1}) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> C2SSetRepairOrder.readBones(buf));
            } finally {
                buf.release();
            }
        }
    }
}
