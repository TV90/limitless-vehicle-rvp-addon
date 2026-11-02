package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 身份与计时跨网络保持，倒计时不把失效许可自行升为可部署。 */
class RVP_AuxiliaryHudContextTest {
    @Test void allRolesAndPermissionsRoundTripWithoutTrailingBytes() {
        for (var role : RVP_AuxiliaryHudSnapshot.Role.values()) {
            var original = new S2CAuxiliaryHudContext(new RVP_AuxiliaryHudSnapshot(UUID.randomUUID(), role, 299, true, false, true));
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2CAuxiliaryHudContext.encode(original, buffer);
                assertEquals(original, S2CAuxiliaryHudContext.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void displayClockAdvancesWithoutExtendingCooldownOrGrantingPermission() {
        var s = new RVP_AuxiliaryHudSnapshot(UUID.randomUUID(), RVP_AuxiliaryHudSnapshot.Role.PARENT, 40, false, false, false);
        assertEquals(21, s.elapsed(100,119).cooldownTicks());
        assertEquals(0, s.elapsed(100,150).cooldownTicks());
        assertFalse(s.elapsed(100,150).deployAllowed());
        assertEquals(40, s.elapsed(100,90).cooldownTicks());
    }
}
