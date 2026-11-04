package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;

import static org.junit.jupiter.api.Assertions.*;

/** 实际线协议必须保留选骨、脱锁点和独立禁锁期，且不能遗留未读取字节。 */
class RVP_HitlLockStatePacketTest {
    @Test void allLockAndBreakStatesRoundTrip() {
        for (Status status : Status.values()) {
            boolean entity = status == Status.ENTITY;
            boolean empty = status == Status.NONE || status == Status.DIRCM;
            var lock = new RVP_HitlLockSnapshot(status, entity ? 42 : -1,
                    empty ? null : new Vec3(-1024.5, 128.25, 2048.125),
                    entity ? new RVP_NamedVehicleObbs.Key("turret_top", 2) : null,
                    status == Status.DIRCM ? 60 : 0);
            // TV 白闪已结束，但禁锁仍有 60 tick：两个状态必须独立通过网络。
            var original = S2CHitlLinkState.of(11, false, false, false, 0, 60, lock);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2CHitlLinkState.encode(original, buffer);
                var decoded = S2CHitlLinkState.decode(buffer);
                assertEquals(11, decoded.missileEntityId);
                assertFalse(decoded.blocked);
                assertFalse(decoded.severed);
                assertFalse(decoded.dircmJam);
                assertEquals(0, decoded.dircmJamRemainTick);
                assertEquals(60, decoded.dircmJamTotalTick);
                assertEquals(lock, decoded.lock);
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }

    @Test void identityChangesAndBanExpiryBypassPositionThrottling() {
        var key = new RVP_NamedVehicleObbs.Key("turret", 0);
        var locked = new RVP_HitlLockSnapshot(Status.ENTITY, 42, Vec3.ZERO, key, 0);
        assertTrue(locked.sameIdentity(new RVP_HitlLockSnapshot(Status.ENTITY, 42, new Vec3(8, 0, 0), key, 0)));
        assertFalse(locked.sameIdentity(new RVP_HitlLockSnapshot(Status.SPEED, -1, Vec3.ZERO, null, 0)));
        assertFalse(locked.sameIdentity(new RVP_HitlLockSnapshot(Status.ENTITY, 43, Vec3.ZERO, key, 0)));
        assertFalse(locked.sameIdentity(new RVP_HitlLockSnapshot(Status.ENTITY, 42, Vec3.ZERO,
                new RVP_NamedVehicleObbs.Key("turret_top", 0), 0)));
        var ban = new RVP_HitlLockSnapshot(Status.DIRCM, -1, null, null, 1);
        assertTrue(ban.sameIdentity(new RVP_HitlLockSnapshot(Status.DIRCM, -1, null, null, 60)));
        assertFalse(ban.sameIdentity(new RVP_HitlLockSnapshot(Status.DIRCM, -1, null, null, 0)));
    }
}
