package org.ywzj.rvp.client.gui;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.light.RVP_RocketPlumeManager;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientWarnRelay;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** HITL 切入/退出及缓存喷焰回归，不构造 OpenGL；被屏蔽入口必须在访问游戏/绘图对象前返回。 */
class RVP_HitlVehicleOverlaySuppressionTest {
    @Test
    void hitlHidesVehicleOverlaysAndOwnCachedPlumesThenRestoresExternalView() throws Exception {
        Field active = RVP_ClientHitlState.class.getDeclaredField("activeMissileId");
        active.setAccessible(true);
        int previous = active.getInt(null);
        try {
            RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
            manager.beginWorld(new Object(), 10);
            // 两个 UUID 代表同一导弹的本地/远端克隆，另一个 ID 是仍需看见的其它导弹。
            manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 0, 1, false, 71);
            manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 0, 1, false, 71);
            manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 0, 1, false, 72);
            active.setInt(null, -1);
            assertEquals(3, manager.collect(Vec3.ZERO, 0, plume -> true).size());
            active.setInt(null, 71);
            // 不推进 tick，验证切入首帧即隐藏上一帧缓存，而不是等 TTL 到期。
            assertEquals(1, manager.collect(Vec3.ZERO, 0, plume -> true).size());
            assertDoesNotThrow(() -> new RVP_RadarOverlay().render(null, null, 0, 1280, 720));
            assertDoesNotThrow(() -> new RVP_EcmFakeRwrOverlay().render(null, null, 0, 1280, 720));
            assertDoesNotThrow(() -> new RVP_ScopeOverlay().render(null, null, 0, 1280, 720));
            assertDoesNotThrow(() -> RVP_ScopeOverlay.renderAimLockTarget(null, 0));
            assertTrue(RVP_ScopeOverlay.shouldSkipVanillaAimLockTarget());
            active.setInt(null, 72);
            assertEquals(2, manager.collect(Vec3.ZERO, 0, plume -> true).size());
            active.setInt(null, -1);
            assertEquals(3, manager.collect(Vec3.ZERO, 0, plume -> true).size());
        } finally {
            active.setInt(null, previous);
        }
    }

    @Test
    void selfWarningPacketIsRejectedBeforeAccessingMinecraftOrPlayingSound() {
        assertDoesNotThrow(() -> RVP_ClientWarnRelay.handle(71, 71, WarnType.RADAR_SEARCH, "self", true));
        assertDoesNotThrow(() -> RVP_ClientWarnRelay.handle(71, 71, WarnType.RADAR_LOCK, "self", true));
    }
}
