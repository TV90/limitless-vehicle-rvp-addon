package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import static org.junit.jupiter.api.Assertions.*;

/** 实际网络编解码必须完整消费点/实体/骨选择/清除四种消息，旧模式不携带伪造骨。 */
class RVP_HitlObbPacketTest {
    @Test void designationVariantsRoundTripIncludingStableObbIdentity() {
        var key = new RVP_NamedVehicleObbs.Key("Periscope", 2);
        for (var original : new C2SHitlDesignate[]{C2SHitlDesignate.clear(10),
                C2SHitlDesignate.point(10, new Vec3(1, 2, 3)),
                C2SHitlDesignate.entity(10, 20, new Vec3(1, 2, 3)),
                C2SHitlDesignate.entity(10, 20, new Vec3(1, 2, 3), key)}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                C2SHitlDesignate.encode(original, buffer);
                var decoded = C2SHitlDesignate.decode(buffer);
                assertEquals(original.missileEntityId, decoded.missileEntityId);
                assertEquals(original.clearDesignation, decoded.clearDesignation);
                assertEquals(original.targetEntityId, decoded.targetEntityId);
                assertEquals(original.targetPos, decoded.targetPos);
                assertEquals(original.targetObb, decoded.targetObb);
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
}
