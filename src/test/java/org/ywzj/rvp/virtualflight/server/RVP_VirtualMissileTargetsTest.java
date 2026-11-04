package org.ywzj.rvp.virtualflight.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.guidance.RVP_GuidanceTargetUtil;
import org.ywzj.rvp.radar.RVP_RadarScanHelper;
import org.ywzj.rvp.vehicle.RVP_ApsRuntimeManager;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.util.BulletHitResult;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.environment;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.write;

/** 使用真实弹体、扫描和命中方法；环境只提供固定平地与内存实体索引。 */
class RVP_VirtualMissileTargetsTest {
    @BeforeAll
    static void bootstrap() { RVP_ProjectileSimulationBootstrap.initialize(); }

    @Test
    void nonWorldTargetKeepsIdentityMotionAndAspectSignal() {
        Probe p = probe(new Vec3(300, 100, 0));
        assertNull(p.level.getEntity(p.target.getId()));
        assertSame(p.target, RVP_VirtualMissileTargets.resolve(p.level, p.real.getId()));
        assertEquals(p.real.getUUID(), p.target.getUUID());
        assertEquals(p.real.getBoundingBox(), p.target.getBoundingBox());
        assertTrue(RVP_GuidanceTargetUtil.isRadarScannableTarget(p.target));
        for (Vec3 observer : List.of(new Vec3(0, 100, 0), new Vec3(300, 100, 500), new Vec3(600, 100, 0))) {
            assertEquals(p.real.getRadarSignatureTowards(observer), p.target.getRadarSignatureTowards(observer));
        }
        Vec3 before = p.target.position();
        // 调用生产飞行入口，雷达引用必须跟随运动，不能停留在虚拟化位置。
        RVP_EntityTrajectoryIntegrator.step(p.level, p.state, p.data, null, null);
        assertSame(p.target, RVP_VirtualMissileManager.targets(p.level).get(0));
        assertNotEquals(before, p.target.position());
        assertEquals(p.state.trajectory().position(), p.target.position());
    }

    @Test
    void radarKeepsAspectDistanceAndTrackingWhileVirtualTerrainHeightIsDisabled() {
        Probe p = probe(new Vec3(300, 100, 0));
        ProbeRadar radar = radar(p.level);
        for (Vec3 velocity : List.of(new Vec3(-4, 0, 0), new Vec3(0, 0, 4), new Vec3(4, 0, 0))) {
            p.real.setDeltaMovement(velocity); p.target.setDeltaMovement(velocity);
            assertEquals(scanned(radar, p.real, false), scanned(radar, p.target, false));
        }
        p.target.setDeltaMovement(new Vec3(-4, 0, 0));
        assertFalse(scanned(radar, p.target, false), "迎头信号 0.2，300 格超出有效 200 格");
        p.target.setDeltaMovement(new Vec3(0, 0, 4));
        assertTrue(scanned(radar, p.target, false));
        p.real.setDeltaMovement(new Vec3(0, 0, 4));
        p.real.setPos(300, 10, 0);
        assertFalse(scanned(radar, p.real, false), "真实弹仍保留最低离地高度限制");
        p.target.setPos(300, 10, 0);
        p.level.forbidTerrainQueries();
        assertTrue(scanned(radar, p.target, false), "关闭后虚拟弹忽略最低离地高度，且不得查询高度图");
        p.target.setPos(300, 100, 0); radar.targetAim = new Vec2(0, 30);
        assertFalse(scanned(radar, p.target, true), "机械扫描线尚未扫到");
        assertTrue(scanned(radar, p.target, false), "相控阵不要求机械扫描线");
    }

    @Test
    void virtualTerrainDisabledSkipsHeightSnapshotsAndCompatibilityGroundFilters() {
        Probe p = probe(new Vec3(300, -20, 0));
        ProbeRadar radar = radar(p.level);
        p.level.forbidTerrainQueries();
        p.target.setVirtualMidcourseGroundHeight(1234);
        // 调用正式同步高度入口，关闭开关后旧客户端缓存值也不得变成高度限制依据。
        assertEquals(0, RVP_VirtualMissileTargets.groundHeight(
                p.target, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING));
        assertTrue(RVP_RadarScanHelper.isWithinScanHeight(radar, p.target, p.target.position()));
        p.target.setPos(300, 20000, 0);
        assertTrue(RVP_RadarScanHelper.isWithinScanHeight(radar, p.target, p.target.position()));
        assertTrue(org.ywzj.rvp.guidance.RVP_GuidanceMath.isTargetPassAltFilter(p.target, 25000));
        p.target.setPos(300, -20, 0);
        assertFalse(org.ywzj.rvp.guidance.RVP_GuidanceMath.isEntityNearGroundBlocks(p.target, 20));
    }

    @Test
    void collisionCandidatesDeduplicateAndRespectBoxAndOriginalFilter() {
        Probe p = probe(new Vec3(5, 100, 0));
        AABB box = new AABB(0, 95, -5, 10, 105, 5);
        assertEquals(List.of(p.target), RVP_VirtualMissileTargets.mergeCandidates(
                List.of(p.target), List.of(p.target), box, ignored -> true));
        assertTrue(RVP_VirtualMissileTargets.merge(p.level, box, List.of(), ignored -> false).isEmpty());
        assertTrue(RVP_VirtualMissileTargets.merge(p.level, box.move(100, 0, 0), List.of(), ignored -> true).isEmpty());
        assertEquals(List.of(p.target), RVP_VirtualMissileTargets.merge(p.level, box, List.of(), ignored -> true));
    }

    @Test
    void transferAndRebuildPreserveRadarLockAndInFlightInterceptor() {
        Probe p = probe(new Vec3(300, 100, 0));
        ProbeRadar radar = radar(p.level);
        ProbeInterceptor interceptor = interceptor(p.level);
        p.level.addObserver(radar.getVehicle()); p.level.addObserver(interceptor);
        radar.detect(p.real); radar.setLockedEntity(p.real); interceptor.setTargetEntity(p.real);
        // 调用生产交接，不能通过重新索敌或新建锁定掩盖身份断层。
        RVP_VirtualMissileTargets.replaceReferences(p.level, p.real, p.target);
        assertSame(p.target, radar.getLockedEntity());
        assertSame(p.target, radar.getDetectedEntities().get(p.target.getId()).entity);
        assertSame(p.target, interceptor.getTargetEntity());
        p.state.updateTrajectory(p.state.trajectory());
        var rebuilt = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(p.level, p.state, p.data, null, null);
        assertNotSame(p.target, rebuilt); assertFalse(p.target.isAlive());
        assertEquals(p.target.getId(), rebuilt.getId());
        assertSame(rebuilt, radar.getLockedEntity()); assertSame(rebuilt, interceptor.getTargetEntity());
        var restored = RVP_EntityTrajectoryIntegrator.createEntity(p.level, p.state, p.data, null, null);
        assertFalse(restored.isVirtualMidcourseTarget());
        RVP_VirtualMissileTargets.replaceReferences(p.level, rebuilt, restored);
        RVP_VirtualMissileSavedData.get(p.level).remove(p.state.flightUuid);
        assertFalse(rebuilt.isAlive());
        assertSame(restored, radar.getLockedEntity()); assertSame(restored, interceptor.getTargetEntity());
    }

    @Test
    void waitingTargetRemainsInterceptableAndOldRealObjectCannotDeleteIt() {
        Probe p = probe(new Vec3(300, 100, 0));
        p.state.phase = RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS;
        RVP_VirtualMissileManager.removeTarget(p.real);
        assertTrue(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
        assertSame(p.target, RVP_VirtualMissileTargets.resolve(p.level, p.target.getId()));
        ProbeRadar radar = radar(p.level);
        p.level.addObserver(radar.getVehicle()); radar.detect(p.target); radar.setLockedEntity(p.target);
        p.target.discard();
        assertFalse(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
        assertTrue(RVP_VirtualMissileManager.targets(p.level).isEmpty());
        assertNull(RVP_VirtualMissileTargets.resolve(p.level, p.target.getId()));
        assertNull(radar.getLockedEntity()); assertTrue(radar.getDetectedEntities().isEmpty());
    }

    @Test
    void directHitFindsNonWorldTargetAndDeletesAuthoritativeRecord() throws Exception {
        Probe p = probe(new Vec3(5, 100, 0));
        ProbeInterceptor interceptor = interceptor(p.level);
        // 射线穿过弹体盒中心；沿 AABB 下边界擦过时，真实弹体也不会被本体 clip 判为穿入。
        Vec3 start = new Vec3(0, 100.2, 0), end = new Vec3(10, 100.2, 0);
        assertNotNull(org.ywzj.vehicle.util.EntityUtil.getHitResult(interceptor, p.real, start, end));
        BulletHitResult hit = interceptor.directHit(start, end);
        assertNotNull(hit); assertSame(p.target, hit.getEntity());
        // 调用真实直击结算，目标引爆和拦截弹消耗必须同时完成。
        var impact = RVP_BaseBullet.class.getDeclaredMethod("handleEntityImpact", BulletHitResult.class);
        impact.setAccessible(true); impact.invoke(interceptor, hit);
        assertFalse(p.target.isAlive()); assertFalse(interceptor.isAlive());
        assertFalse(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
    }

    @Test
    void sweptProximityUsesAmmoRadiusAndDeletesVirtualRecord() {
        Probe p = probe(new Vec3(5, 100, 2.5));
        ProbeInterceptor interceptor = interceptor(p.level);
        Entity hit = interceptor.proximityHit(new Vec3(0, 100, 0), new Vec3(10, 100, 0));
        assertSame(p.target, hit, "基础半径 2 格外，弹药倍率 2 仍应触发扫掠近炸");
        interceptor.proximityDetonate(hit);
        assertFalse(p.target.isAlive()); assertFalse(interceptor.isAlive());
        assertFalse(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
    }

    @Test
    void apsUsesOriginalNeutralizationAndDeletesVirtualRecord() throws Exception {
        Probe p = probe(new Vec3(5, 100, 0));
        // 调用生产 APS 结算，不以手工删记录代替拦截链。
        var neutralize = RVP_ApsRuntimeManager.class.getDeclaredMethod("neutralizeProjectile", Projectile.class);
        neutralize.setAccessible(true); neutralize.invoke(null, p.target);
        assertFalse(p.target.isAlive());
        assertFalse(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
    }

    @Test
    void reloadedFlightKeepsUuidAndAllocatesNewSessionId() {
        Probe p = probe(new Vec3(300, 100, 0));
        var loaded = RVP_VirtualMissileState.load(p.state.save()).orElseThrow();
        assertNull(loaded.targetEntityId);
        var target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(p.level, loaded, p.data, null, null);
        assertNotEquals(p.real.getId(), target.getId()); assertEquals(p.real.getUUID(), target.getUUID());
    }

    @Test
    void configurationReloadAndRestorationKeepNativeHitboxHistory() {
        Probe p = probe(new Vec3(300, 100, 0));
        RVP_EntityTrajectoryIntegrator.step(p.level, p.state, p.data, null, null);
        var before = org.ywzj.vehicle.util.HitboxHelper.getFixedBoundingBox(p.target, null);
        var rebuilt = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(p.level, p.state, data(), null, null);
        assertEquals(before, org.ywzj.vehicle.util.HitboxHelper.getFixedBoundingBox(rebuilt, null));
        var restored = RVP_EntityTrajectoryIntegrator.createEntity(p.level, p.state, rebuilt.getRvpData(), null, null);
        assertEquals(before, org.ywzj.vehicle.util.HitboxHelper.getFixedBoundingBox(restored, null));
    }

    @Test
    void cachedTargetRebindsSourceWhenLauncherBecomesAvailable() {
        Probe p = probe(new Vec3(300, 100, 0));
        var launcher = environment(FixedWingVehicle.class);
        assertNull(p.target.getShooterVehicle());
        var target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(p.level, p.state, p.data, launcher, null);
        assertSame(p.target, target); assertSame(launcher, target.getShooterVehicle());
        assertSame(launcher, target.vehicle);
        RVP_EntityTrajectoryIntegrator.getOrCreateEntity(p.level, p.state, p.data, null, null);
        assertSame(launcher, target.getShooterVehicle(), "临时卸载不能清掉已知发射来源");
    }

    @Test
    void sameVehicleProjectileStillFailsBothDirectAndProximityFilters() {
        Probe p = probe(new Vec3(5, 100, 0));
        ProbeInterceptor interceptor = interceptor(p.level);
        var vehicle = environment(FixedWingVehicle.class);
        p.target.vehicle = vehicle; interceptor.vehicle = vehicle;
        assertNull(interceptor.directHit(new Vec3(0, 100.2, 0), new Vec3(10, 100.2, 0)));
        assertNull(interceptor.proximityHit(new Vec3(0, 100, 0), new Vec3(10, 100, 0)));
        assertTrue(RVP_VirtualMissileSavedData.get(p.level).contains(p.state.flightUuid));
    }

    /** 用当前 JSON 数据类解析参数，真实实体仍通过正式初始化创建。 */
    private static RVP_WeaponData data() {
        var data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":10000,
                "projectile_data":{"velocity":4,"gravity":0,"collision_box_size":0.4},
                "guidance_data":{"guidance_type":"GPS","max_guidance_angle":180},
                "misc_data":{"ammo_radar_rcs_factor":[0.2,1,0.6]},
                "fuse_data":{"proximity_radius":2,"proximity_radius_ammo_factor":2}}
                """, RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        data.setWeaponId(net.minecraft.resources.ResourceLocation.tryParse("rvp:virtual_contact_test"));
        return data;
    }

    /** 建立权威记录，计算对象从未加入原版实体索引。 */
    private static Probe probe(Vec3 position) {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var data = data();
        var real = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, data.getWeaponId());
        real.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, null, null, position,
                new RVP_BaseBullet.AimRot(0, -90), new Vec3(4, 0, 0));
        real.setGuidanceTargetPos(new Vec3(10000, 100, 0));
        // 用例从已初始化位置开始，原版世界会在实体 Tick 前建立这组历史坐标。
        real.setOldPosAndRot();
        var state = new RVP_VirtualMissileState(real.getUUID(), Level.OVERWORLD.location(), data.getWeaponId(),
                null, null, position, real.createVirtualMidcourseSnapshot(), real.createVirtualMidcourseKinematics(),
                real.getId(), 0, 0, 0, RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        state.targetEntityId = real.getId();
        assertTrue(RVP_VirtualMissileSavedData.get(level).add(state));
        var target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(level, state, data, null, null);
        return new Probe(level, data, state, real, target);
    }

    /** 只注入固定雷达姿态输入，扫描过滤全部执行正式方法。 */
    private static ProbeRadar radar(RVP_ProjectileSimulationLevel level) {
        var vehicle = environment(FixedWingVehicle.class);
        write(Entity.class, vehicle, "level", level);
        var radar = environment(ProbeRadar.class); radar.targetAim = Vec2.ZERO;
        write(PartUnit.class, radar, "vehicle", vehicle);
        write(AbstractVehicle.class, vehicle, "partUnits", List.of(radar));
        write(RadarUnit.class, radar, "detectedObjects", new HashMap<>());
        write(RadarUnit.class, radar, "maxScanDistance", 1000f);
        write(RadarUnit.class, radar, "scanSectorAngle", 180f);
        write(RadarUnit.class, radar, "on", true);
        radar.yRotMin = -180; radar.yRotMax = 180; radar.yRotSpeed = 10;
        return radar;
    }

    /** 真实扫描同一枚弹的两种表示。 */
    private static boolean scanned(ProbeRadar radar, Entity target, boolean trackingLine) {
        var found = new ArrayList<Entity>();
        RVP_RadarScanHelper.appendRvpAmmoTargets(radar, found, List.of(target), trackingLine);
        return found.contains(target);
    }

    /** 构造真实拦截弹，命中、引信和引爆均不替换。 */
    private static ProbeInterceptor interceptor(RVP_ProjectileSimulationLevel level) {
        var missile = new ProbeInterceptor(RVP_Entities.RVP_MISSILE.get(), level);
        missile.initFromWeapon(data(), RVP_EnumWeaponKind.MISSILE, null, null, new Vec3(0, 100.2, 0),
                new RVP_BaseBullet.AimRot(0, -90), new Vec3(10, 0, 0));
        return missile;
    }

    /** 每个用例独立持有真实生产对象。 */
    private record Probe(
            /** 固定平地世界。 */ RVP_ProjectileSimulationLevel level,
            /** 当前 schema 配置。 */ RVP_WeaponData data,
            /** 权威记录。 */ RVP_VirtualMissileState state,
            /** 真实表示。 */ RVP_MissileEntity real,
            /** 虚拟表示。 */ RVP_MissileEntity target) {}

    /** 雷达姿态属于测试提供的外部环境输入。 */
    private static final class ProbeRadar extends RadarUnit {
        /** 雷达相对目标的角度输入。 */ private Vec2 targetAim;
        private ProbeRadar() { super(0, null, null); }
        @Override public Vec3 worldRadarPosition() { return new Vec3(0, 100, 0); }
        @Override public Vec2 aimRot(Vec3 position) { return targetAim; }
    }

    /** 仅公开保护方法，不改写正式命中实现。 */
    private static final class ProbeInterceptor extends RVP_MissileEntity {
        private ProbeInterceptor(EntityType<? extends Projectile> type, Level level) { super(type, level); }
        private BulletHitResult directHit(Vec3 start, Vec3 end) {
            return findEntityOnPathForSegment(start, end, end.subtract(start));
        }
        private Entity proximityHit(Vec3 start, Vec3 end) { return findProximityTargetOnSegment(start, end, 2, 20); }
        private void proximityDetonate(Entity target) { detonateFuseAt(position(), FuseDetonation.PROXIMITY, target); }
    }
}
