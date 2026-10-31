package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证合并后补齐的类型化持续光接口，不让调参工具误改干扰机的独立红光。 */
class RVP_EoJammerLightCompatibilityTest {
    @AfterEach
    void reset() {
        // 调用真实会话清理，避免测试覆盖污染后续光照测试。
        RVP_LightRuntimeTuning.reset();
    }

    @Test
    void jammerKeepsItsOwnPresetDirectionAndHeartbeatInsteadOfEngineOverrides() {
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        Object world = new Object();
        UUID key = UUID.randomUUID();
        manager.beginWorld(world, 100);
        RVP_LightRuntimeTuning.set(RVP_LightRuntimeTuning.Group.engine, RVP_LightRuntimeTuning.Parameter.radiusScale, 0);
        RVP_LightRuntimeTuning.set(RVP_LightRuntimeTuning.Group.engine, RVP_LightRuntimeTuning.Parameter.brightness, 0);
        RVP_LightParameters source = RVP_LightParameters.eoJammer();
        // 调用渲染器实际使用的五参数接口，保留类型并将轴正规化为单位长度。
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, source, new Vec3(0, 0, 2));
        RVP_ScreenLight light = manager.collectVisible(new Vec3(0, 0, 4), 0.5F, 256, 8, value -> true).get(0);
        assertEquals(RVP_LightSourceType.EO_JAMMER, light.type());
        assertEquals(new Vec3(0, 0, 1), light.hemisphereAxis());
        assertEquals(source.radius(), light.radius());
        assertEquals(source.intensity(), light.intensity());
        manager.tick(world, 102);
        assertEquals(0, manager.size());
    }

    @Test
    void engineRemainsOmnidirectionalAndInvalidHemisphereNeverReplacesValidLight() {
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        UUID key = UUID.randomUUID();
        // 调用原发动机接口，确认已有调用仍保持全向光。
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightParameters.engine());
        assertNull(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).hemisphereAxis());
        assertThrows(IllegalArgumentException.class, () -> manager.upsertPersistent(key, Vec3.ZERO,
                RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), Vec3.ZERO));
        assertEquals(RVP_LightSourceType.ROCKET_ENGINE,
                manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).type());
    }
}
