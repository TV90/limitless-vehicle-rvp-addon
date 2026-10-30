package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 世界轨迹、近远接管、暂停/卸载和齐射预算回归，不以测试内粒子模拟替代生产管理器。 */
class RVP_RocketTrailManagerTest {
    /** 固定世界身份，不需要启动游戏或显卡。 */
    private final Object world = new Object();
    /** 测试使用的实际生产管理器实例，各用例互不共享状态。 */
    private final RVP_RocketTrailManager manager = new RVP_RocketTrailManager();
    /** 固定尺寸和明确的 20 tick 保持 / 20 tick 淡出，便于检查边界。 */
    private static final RVP_RocketTrailManager.Profile PROFILE =
            new RVP_RocketTrailManager.Profile(1, 1, 20, 20, 1, 0.65F, 8, 4, false);

    @Test
    void codeSwitchKeepsOldParticlesOffAndVolumeOn() {
        assertTrue(RVP_RocketTrailParameters.VOLUME_ENABLED);
        assertFalse(RVP_RocketTrailParameters.ORIGINAL_PARTICLES_ENABLED);
    }

    @Test
    void smokeSurvivesProjectileRemovalThenFadesAndExpires() {
        manager.beginWorld(world, 10);
        manager.append(1, new Vec3(0, 0, -20), Vec3.ZERO, PROFILE, false);
        manager.stop(1);
        manager.beginWorld(world, 29);
        assertEquals(1, all().get(0).startDensity(), 0.00001);
        manager.beginWorld(world, 40);
        assertEquals(0.5, all().get(0).startDensity(), 0.00001);
        assertTrue(manager.hasActive());
        manager.beginWorld(world, 50);
        assertFalse(manager.hasActive());
        assertEquals(0, manager.size());
    }

    @Test
    void turningProjectileDoesNotPullOldSmokeToItsNewPosition() {
        manager.beginWorld(world, 1);
        manager.append(1, new Vec3(0, 0, -20), Vec3.ZERO, PROFILE, false);
        manager.beginWorld(world, 2);
        manager.append(1, new Vec3(4, 0, -20), Vec3.ZERO, PROFILE, false);
        manager.beginWorld(world, 3);
        List<RVP_RocketTrailSegment> before = all();
        manager.append(1, new Vec3(4, 4, -20), Vec3.ZERO, PROFILE, false);
        List<RVP_RocketTrailSegment> after = all();
        assertTrue(after.containsAll(before), "转弯只能追加烟段，不能移动已有烟段");
        assertTrue(after.stream().anyMatch(segment -> segment.end().y - segment.start().y > 3));
    }

    @Test
    void pausedClockAndCameraRotationDoNotChangeWorldSmoke() {
        manager.beginWorld(world, 10);
        manager.append(1, new Vec3(0, 0, -20), new Vec3(0, 0, -1), PROFILE, false);
        List<RVP_RocketTrailSegment> first = manager.collect(Vec3.ZERO, 0.25F, sample -> true);
        manager.beginWorld(world, 10);
        manager.append(1, new Vec3(80, 0, -20), Vec3.ZERO, PROFILE, false);
        assertEquals(first, manager.collect(new Vec3(200, 0, 0), 0.25F, sample -> true));
        assertEquals(1, manager.size());
    }

    @Test
    void sameSyncedEntityIdDoesNotEmitTwiceAcrossLocalAndRemotePaths() {
        manager.beginWorld(world, 1);
        manager.append(12, new Vec3(0, 0, -20), Vec3.ZERO, PROFILE, false);
        manager.append(12, new Vec3(10, 0, -20), Vec3.ZERO, PROFILE, true);
        assertEquals(1, manager.size());
        manager.beginWorld(world, 2);
        manager.append(12, new Vec3(8, 0, -20), Vec3.ZERO, PROFILE, true);
        assertEquals(2, manager.size(), "下一 tick 远距接管必须立即接上，不能空等两 tick 漏掉高速导弹烟线");
        assertTrue(all().stream().anyMatch(segment -> segment.start().distanceTo(segment.end()) > 7));
    }

    @Test
    void teleportsAndLongSnapshotGapsNeverDrawConnectingSmokeLines() {
        manager.beginWorld(world, 1);
        manager.append(1, Vec3.ZERO, Vec3.ZERO, PROFILE, false);
        manager.beginWorld(world, 2);
        manager.append(1, new Vec3(1000, 0, 0), Vec3.ZERO, PROFILE, false);
        manager.beginWorld(world, 10);
        manager.append(1, new Vec3(1002, 0, 0), Vec3.ZERO, PROFILE, false);
        assertEquals(3, all().size());
        assertTrue(all().stream().allMatch(segment -> segment.start().equals(segment.end())));
    }

    @Test
    void stopBreaksFutureConnectionsWithoutErasingOldSmoke() {
        manager.beginWorld(world, 1);
        manager.append(1, Vec3.ZERO, Vec3.ZERO, PROFILE, false);
        manager.stop(1);
        manager.beginWorld(world, 2);
        manager.append(1, new Vec3(4, 0, 0), Vec3.ZERO, PROFILE, false);
        assertEquals(2, manager.size());
        assertTrue(all().stream().allMatch(segment -> segment.start().equals(segment.end())));
    }

    @Test
    void washAndAirTrailHaveIndependentSameTickDeduplication() {
        manager.beginWorld(world, 1);
        var wash = new RVP_RocketTrailManager.Profile(1, 1, 0, 80, 0, 0.6F, 4, 4, true);
        for (int index = 0; index < 8; index++) { manager.addWash(1, Vec3.ZERO, new Vec3(0.1, 0, 0), wash); }
        manager.append(1, new Vec3(0, 2, 0), Vec3.ZERO, PROFILE, false);
        assertEquals(RVP_RocketTrailParameters.WASH_PUFFS_PER_TICK + 1, manager.size());
    }

    @Test
    void alphaRatioCanFadeEarlierWithoutShorteningTotalLifetime() {
        manager.beginWorld(world, 1);
        manager.append(1, new Vec3(0, 0, -20), Vec3.ZERO, PROFILE, false);
        var early = new RVP_RocketTrailManager.Profile(1, 1, 20, 20, 0.25F, 0.65F, 8, 4, false);
        manager.append(2, new Vec3(4, 0, -20), Vec3.ZERO, early, false);
        manager.beginWorld(world, 16);
        var smoke = all();
        assertEquals(1, smoke.stream().filter(segment -> segment.source() == 1).findFirst().orElseThrow().startDensity());
        assertTrue(smoke.stream().filter(segment -> segment.source() == 2).findFirst().orElseThrow().startDensity() < 1);
        assertEquals(2, manager.size());
    }

    @Test
    void lookingAwayDoesNotDestroySmokeAndReturningShowsItAgain() {
        manager.beginWorld(world, 1);
        manager.append(1, new Vec3(0, 0, -20), Vec3.ZERO, PROFILE, false);
        assertTrue(manager.collect(Vec3.ZERO, 0, sample -> false).isEmpty());
        assertEquals(1, all().size());
    }

    @Test
    void worldChangesReconnectsAndClockRewindsClearOldState() {
        manager.beginWorld(world, 100);
        manager.append(1, Vec3.ZERO, Vec3.ZERO, PROFILE, false);
        manager.beginWorld(new Object(), 100);
        assertFalse(manager.hasActive());
        manager.beginWorld(world, 100);
        manager.append(1, Vec3.ZERO, Vec3.ZERO, PROFILE, false);
        manager.beginWorld(world, 99);
        assertEquals(0, manager.size());
        manager.clear();
        manager.append(1, Vec3.ZERO, Vec3.ZERO, PROFILE, false);
        assertEquals(0, manager.size());
    }

    @Test
    void invalidCoordinatesAndSettingsCannotEnterTheWorldCache() {
        manager.beginWorld(world, 1);
        manager.append(1, new Vec3(Double.NaN, 0, 0), Vec3.ZERO, PROFILE, false);
        manager.append(2, Vec3.ZERO, new Vec3(0, Double.POSITIVE_INFINITY, 0), PROFILE, false);
        manager.append(3, Vec3.ZERO, Vec3.ZERO,
                new RVP_RocketTrailManager.Profile(Float.NaN, 1, 20, 20, 1, 0.6F, 8, 4, false), false);
        assertEquals(0, manager.size());
    }

    @Test
    void longSalvosStayBoundedAndGiveEveryVisibleSourceABudget() {
        var longLived = new RVP_RocketTrailManager.Profile(1, 1, 12000, 100, 1, 0.65F, 32, 4, false);
        for (int tick = 0; tick < 140; tick++) {
            manager.beginWorld(world, tick);
            for (int source = 0; source < RVP_RocketTrailParameters.MAX_SOURCES; source++) {
                manager.append(source, new Vec3(tick, source * 2, -4096), Vec3.ZERO, longLived, false);
            }
        }
        assertTrue(manager.size() <= RVP_RocketTrailParameters.MAX_STORED_SEGMENTS);
        var visible = all();
        assertTrue(visible.size() <= RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS);
        assertEquals(RVP_RocketTrailParameters.MAX_SOURCES, visible.stream().map(RVP_RocketTrailSegment::source).distinct().count());
        assertTrue(visible.stream().allMatch(segment -> segment.center().z < -4000), "不受环境光 256 格距离限制");
    }

    /** 调用完整烟段收集，数量上限不能在面积预算之前就把同来源的烟外尾迹全部裁掉。 */
    @Test
    void manyEnclosingWashPuffsDoNotConsumeAllSlotsBeforeExteriorTrail() {
        manager.beginWorld(world, 0);
        var distant = new RVP_RocketTrailManager.Profile(1, 1, 1000, 1000, 1, 0.65F, 8, 4, false);
        var wash = new RVP_RocketTrailManager.Profile(1, RVP_RocketTrailParameters.WASH_DENSITY,
                1000, 1000, 1, 0.62F, 4, 4, true);
        int sources = RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS / RVP_RocketTrailParameters.MAX_SEGMENTS_PER_SOURCE + 1;
        int ticks = RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS
                / (sources * RVP_RocketTrailParameters.WASH_PUFFS_PER_TICK) + 8;
        for (int source = 0; source < sources; source++) {
            // 调用实际来源缓存，让测试数量超过当前绘制上限，同时不突破单来源缓存限制。
            manager.append(source, new Vec3(source * 2, 0, -40), Vec3.ZERO, distant, false);
        }
        for (int tick = 0; tick < ticks; tick++) {
            manager.beginWorld(world, tick);
            for (int source = 0; source < sources; source++) {
                // 调用正式冲刷烟入口，产生包住相机的大量烟团，确保扩大后的数量预算也不会挤掉烟外尾迹。
                manager.addWash(source, Vec3.ZERO, Vec3.ZERO, wash);
                manager.addWash(source, Vec3.ZERO, Vec3.ZERO, wash);
            }
        }
        assertTrue(manager.size() > RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS);
        var visible = all();
        assertTrue(visible.stream().anyMatch(segment -> segment.center().z < -30), "烟内很多同源烟团也不能使外部烟消失");
        assertTrue(visible.stream().anyMatch(segment -> segment.contains(Vec3.ZERO)));
        assertTrue(visible.size() <= RVP_RocketTrailParameters.MAX_VISIBLE_SEGMENTS);
    }

    /** 通过生产收集接口读取全部可见烟，不修改管理器内部状态。 */
    private List<RVP_RocketTrailSegment> all() { return manager.collect(Vec3.ZERO, 0, sample -> true); }
}
