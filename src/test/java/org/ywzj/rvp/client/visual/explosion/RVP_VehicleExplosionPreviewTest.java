package org.ywzj.rvp.client.visual.explosion;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;

import static org.junit.jupiter.api.Assertions.*;

/** 验证世界隔离、暂停、定格续播和循环，不需要启动Minecraft或创建实体。 */
class RVP_VehicleExplosionPreviewTest {
    @BeforeEach @AfterEach
    void reset() {
        // 调用唯一参数清理，测试之间不共享美术覆盖。
        RVP_VehicleExplosionSettings.reset();
    }

    @Test
    void staysAtWorldPositionAndUsesGameClockInsteadOfCameraOrWallClock() {
        var preview = new RVP_VehicleExplosionPreview();
        Object world = new Object();
        Vec3 origin = new Vec3(30000000, 70, -30000000);
        preview.start(world, origin, 100);
        var first = preview.sample(world, 100.5);
        var paused = preview.sample(world, 100.5);
        assertEquals(first.center(), paused.center());
        assertEquals(first.age(), paused.age());
        assertEquals(origin.x, first.center().x);
        assertEquals(origin.z, first.center().z);
        assertEquals(origin.y + 0.85 * 0.5, first.center().y, 0.000001);
        assertNull(preview.sample(world, 106));
        assertFalse(preview.active(world), "播完后不再要求屏幕深度输入");
    }

    @Test
    void holdFreezesTheEntireTimelineAndReleasingContinuesAtChosenProgress() {
        var preview = new RVP_VehicleExplosionPreview();
        Object world = new Object();
        preview.start(world, Vec3.ZERO, 0);
        RVP_VehicleExplosionSettings.set(Parameter.HOLD, 1);
        RVP_VehicleExplosionSettings.set(Parameter.PROGRESS, 0.25);
        var held = preview.sample(world, 100);
        assertEquals(1.5, held.age());
        assertEquals(held.center(), preview.sample(world, 500).center());
        RVP_VehicleExplosionSettings.set(Parameter.PROGRESS, 0.5);
        assertEquals(3, preview.sample(world, 501).age());
        RVP_VehicleExplosionSettings.set(Parameter.HOLD, 0);
        assertEquals(3.25, preview.sample(world, 501.25).age());
    }

    @Test
    void loopingLeavesAVisibleGapAndRestartReplacesTheOnlyOrigin() {
        var preview = new RVP_VehicleExplosionPreview();
        Object world = new Object();
        RVP_VehicleExplosionSettings.set(Parameter.LOOP, 1);
        preview.start(world, new Vec3(1, 2, 3), 10);
        assertNotNull(preview.sample(world, 15.9));
        assertNull(preview.sample(world, 16.3), "两轮之间不绘制任何烟火");
        assertTrue(preview.active(world));
        assertEquals(0.1, preview.sample(world, 16.7).age(), 0.000001);
        preview.start(world, new Vec3(9, 8, 7), 20);
        assertEquals(new Vec3(9, 8, 7), preview.sample(world, 20).center());
        assertFalse(preview.active(new Object()));
        assertFalse(preview.active(world), "旧世界不能因重新查询而恢复");
    }

    @Test
    void eachFrameReadsEditsWhileEarlierSnapshotsRemainStableAndBounded() {
        var preview = new RVP_VehicleExplosionPreview();
        Object world = new Object();
        preview.start(world, Vec3.ZERO, 0);
        var original = preview.sample(world, 1);
        RVP_VehicleExplosionSettings.set(Parameter.RADIUS, 16);
        RVP_VehicleExplosionSettings.set(Parameter.STRETCH, 2.5);
        RVP_VehicleExplosionSettings.set(Parameter.SMOKE_EXPANSION, 2);
        RVP_VehicleExplosionSettings.set(Parameter.WIND_X, -4);
        var edited = preview.sample(world, 1);
        assertEquals(4.5, original.settings().get(Parameter.RADIUS));
        assertEquals(16, edited.settings().get(Parameter.RADIUS));
        assertEquals(-4, edited.center().x);
        assertTrue(edited.boundRadius() >= 16 * 3 * 2.5, "极值时包围范围覆盖拉伸后的黑烟");
        assertTrue(Float.isFinite(edited.boundRadius()));
        assertThrows(IllegalArgumentException.class, () -> preview.start(world, new Vec3(Double.NaN, 0, 0), 0));
        preview.clear();
        assertNull(preview.sample(world, 1));
    }
}
