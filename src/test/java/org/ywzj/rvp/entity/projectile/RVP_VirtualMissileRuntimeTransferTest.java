package org.ywzj.rvp.entity.projectile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.virtualflight.server.RVP_EntityTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实原版索引移出/重入、全类型运行态交接、空目标和实际操控序号，不依赖游戏窗口。 */
class RVP_VirtualMissileRuntimeTransferTest {
    /** 独立运行交接测试也先准备真实注册表元信息。 */
    @org.junit.jupiter.api.BeforeAll
    static void initialize() { RVP_ProjectileSimulationBootstrap.initialize(); }

    @Test
    void originalObjectLeavesVanillaTrackingAndTickingThenRejoinsWithTheSameIdentity() {
        var missile = missile("HITL_CLOS_TV");
        missile.rvp$setHitlSteeringInput(12, -8, 4);
        var lifecycle = new Lifecycle();
        var manager = new PersistentEntitySectionManager<Entity>(Entity.class, lifecycle, new Storage());
        manager.updateChunkStatus(missile.chunkPosition(), Visibility.TICKING);
        assertTrue(manager.addNewEntityWithoutEvent(missile));
        assertSame(missile, manager.getEntityGetter().get(missile.getId()));
        assertEquals(1, lifecycle.ticking);
        assertEquals(1, lifecycle.tracking);
        var uuid = missile.getUUID();
        int id = missile.getId();

        // 调用生产交接，原版真实回调必须同步停止实体调度、追踪并移出空间索引。
        missile.detachForVirtualMidcourse();
        assertNull(manager.getEntityGetter().get(id));
        assertFalse(manager.isLoaded(uuid));
        assertEquals(0, lifecycle.ticking);
        assertEquals(0, lifecycle.tracking);
        assertEquals(0, lifecycle.destroyed, "转换不是击毁，不能触发原版销毁回调");
        assertTrue(missile.isAlive());
        assertTrue(missile.isVirtualMidcourseTarget());
        assertEquals(12, missile.rvp$getHitlInputYaw());

        missile.prepareVirtualMidcourseRestoration();
        assertTrue(manager.addNewEntityWithoutEvent(missile));
        assertSame(missile, manager.getEntityGetter().get(id));
        assertEquals(uuid, missile.getUUID());
        assertEquals(1, lifecycle.ticking);
        assertEquals(1, lifecycle.tracking);
        assertFalse(missile.isVirtualMidcourseTarget());
        assertEquals(-8, missile.rvp$getHitlInputPitch());
        assertTrue(missile.hasWorldDependentMidcourseMotion(), "恢复后的短暂冷却防止反复转换");
    }

    @Test
    void controlInputAndAntiRadiationCountermeasuresSurviveActualSavedDataRoundTrip() {
        var missile = missile("HITL_CLOS_TV");
        missile.rvp$setHitlSteeringInput(12, -8, 40);
        missile.saclosOffsetVec = new Vec3(1, 2, 3);
        missile.saclosVelVec = new Vec3(0.1, 0.2, 0.3);
        missile.antiRadiationMemoryLeftTick = 81;
        missile.antiRadiationSignalAcquired = true;
        missile.dircmJamRemainTick = 23;
        missile.dircmHitlTemporary = true;
        missile.ecmActiveJamRemainTick = 17;
        missile.airburstDist = 800;
        missile.airburstTravelled = 333;
        missile.selfDestructTravelled = 444;
        missile.bounceFuseCountdown = 5;
        missile.radarUplinkTargeting = true;
        missile.getGuidancePhaseState().restore(RVP_GuidancePhase.TERMINAL);
        var state = state(missile);
        CompoundTag before = missile.saveVirtualRuntimeData();

        // 调用生产存档和重建入口，而不是另写一份“看起来相同”的字段复制。
        var loaded = RVP_VirtualMissileState.load(state.save()).orElseThrow();
        assertTrue(loaded.radarUplinkTargeting);
        var rebuilt = RVP_EntityTrajectoryIntegrator.createEntity(missile.level(), loaded, missile.getRvpData(), null, null);
        assertEquals(before, rebuilt.saveVirtualRuntimeData());
        assertEquals(missile.createVirtualMidcourseSnapshot(), rebuilt.createVirtualMidcourseSnapshot());
        rebuilt.rvp$setHitlSteeringInput(-30, 30, 39);
        assertEquals(12, rebuilt.rvp$getHitlInputYaw(), "旧操控序号不能因恢复而重新生效");
        rebuilt.rvp$setHitlSteeringInput(-10, 5, 41);
        assertEquals(-10, rebuilt.rvp$getHitlInputYaw());
        assertEquals(5, rebuilt.rvp$getHitlInputPitch());
    }

    /** 合并后的电视脱锁状态必须跨越完整存档/重建链，烟内点不能被再次随机改写。 */
    @Test
    void hitlFrozenPointAndBreakReasonSurviveVirtualSavedDataRebuild() {
        for (Status status : List.of(Status.POINT, Status.SMOKE, Status.OCCLUDED, Status.SPEED, Status.NONE)) {
            var missile = missile("HITL_TV");
            Vec3 frozen = status == Status.NONE ? null : new Vec3(80, 30, 160);
            // 调用实际脱锁入口，再走生产存档和重建，验证新增状态能接入本地虚拟弹生命周期。
            missile.rvp$freezeHitlTarget(frozen, status);
            var loaded = RVP_VirtualMissileState.load(state(missile).save()).orElseThrow();
            var rebuilt = RVP_EntityTrajectoryIntegrator.createEntity(missile.level(), loaded, missile.getRvpData(), null, null);
            assertEquals(status, rebuilt.rvp$getHitlPointStatus());
            assertEquals(frozen, rebuilt.getTargetPos());
            assertEquals(missile.rvp$getHitlLockSnapshot(), rebuilt.rvp$getHitlLockSnapshot());
        }
    }

    /** 真实弹与虚拟弹必须都能点锁地面表面，同时拒绝锁住地面背后的坐标。 */
    @Test
    void realAndVirtualTVDesignationShareSurfaceToleranceAndOcclusion() {
        for (boolean virtual : List.of(false, true)) {
            var missile = missile("HITL_TV");
            missile.setVirtualMidcourseTarget(virtual);
            Vec3 surface = new Vec3(8, 0, 16);
            // 调用实际电视指定入口，完整执行反制和只读地形射线，不能绕过服务端接纳门。
            missile.rvp$setHitlDesignatedTarget(surface);
            assertEquals(surface, missile.getTargetPos(), "可见地面表面应允许点锁");
            assertEquals(Status.POINT, missile.rvp$getHitlPointStatus());
            missile.rvp$setHitlDesignatedTarget(new Vec3(8, -4, 16));
            assertEquals(surface, missile.getTargetPos(), "遮挡后保留最后有效点");
            assertEquals(Status.OCCLUDED, missile.rvp$getHitlPointStatus());
        }
    }

    @Test
    void liveTargetUuidRebindsAndAnUnguidedMissileMayHaveNoTarget() {
        var missile = missile("IR");
        var level = (RVP_ProjectileSimulationLevel) missile.level();
        var target = new Marker(EntityType.MARKER, level);
        target.setPos(500, 100, 200);
        level.addObserver(target);
        missile.setTargetEntity(target);
        var loaded = RVP_VirtualMissileState.load(state(missile).save()).orElseThrow();
        var restored = RVP_EntityTrajectoryIntegrator.createEntity(level, loaded, missile.getRvpData(), null, null);
        assertSame(target, restored.getTargetEntity());

        var unguided = missile("NONE");
        unguided.clearTarget();
        var noTarget = RVP_VirtualMissileState.load(state(unguided).save()).orElseThrow();
        assertNull(noTarget.snapshot.targetPosition());
        var free = RVP_EntityTrajectoryIntegrator.createEntity(unguided.level(), noTarget, unguided.getRvpData(), null, null);
        free.tickVirtualMidcourseFlight();
        assertTrue(free.isAlive());
        assertEquals(unguided.getFlightTickCount() + 1, free.getFlightTickCount());
    }

    @Test
    void virtualRightClickDetonationWaitsForRealWorldRestoration() {
        var missile = missile("HITL_CLOS_TV");
        missile.setVirtualMidcourseTarget(true);
        missile.rvp$hitlDetonate();
        assertTrue(missile.hasVirtualHitlDetonationPending());
        assertTrue(missile.isAlive());
        assertTrue(missile.needsVirtualWorldRestoration());
    }

    @Test
    void pausedDesignationAndPendingUplinkSurviveSaveAndConfigurationRebuild() {
        var missile = missile("HITL_TV");
        var state = state(missile);
        var cached = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(
                missile.level(), state, missile.getRvpData(), null, null);
        state.phase = RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS;
        Vec3 designated = new Vec3(500, 200, 700);
        cached.setGuidanceTargetPos(designated);
        // 等待期间没有任何飞行 Tick，新的指定点也必须进入实际存档和配置重建。
        var saved = RVP_VirtualMissileState.load(state.save()).orElseThrow();
        assertEquals(designated, saved.snapshot.targetPosition());
        var rebuilt = RVP_EntityTrajectoryIntegrator.createEntity(
                missile.level(), state, missile.getRvpData(), null, null);
        assertEquals(designated, rebuilt.getTargetPos());

        Vec3 uplink = new Vec3(-100, 180, 900);
        state.updateTargetPosition(uplink);
        assertEquals(uplink, RVP_VirtualMissileState.load(state.save()).orElseThrow().snapshot.targetPosition());
        assertEquals(designated, cached.getTargetPos(), "存档不得偷偷执行尚未提交的上行命令");
    }

    @Test
    void knownCollisionAndPayloadDeadlinesRequireRestorationBeforeWorldEffects() {
        var missile = missile("NONE");
        assertFalse(missile.needsVirtualWorldRestoration());
        missile.setDeltaMovement(new Vec3(0, -60, 0));
        assertTrue(missile.needsVirtualWorldRestoration(), "已知地面应在运动穿越之前要求恢复");

        var nearby = missile("NONE");
        var target = new Marker(EntityType.MARKER, nearby.level());
        target.setPos(0, 100, 6);
        ((RVP_ProjectileSimulationLevel) nearby.level()).addObserver(target);
        assertTrue(nearby.needsVirtualWorldRestoration(), "已加载实体的近场不能跳过真实碰撞链");

        var carrier = missile("NONE", """
                "submunition_data":{"releases":[{"delay_tick":7,"release_events":3,
                  "payloads":[{"weapon_id":"rvp:test_payload","count":1}]}]}
                """);
        carrier.setVirtualMidcourseTarget(true);
        int ticks = 0;
        while (!carrier.needsVirtualWorldRestoration() && ticks < 20) {
            carrier.tickVirtualMidcourseFlight(); ticks++;
        }
        assertEquals(5, ticks, "提前两刻恢复，虚拟段只推进未到期的布撒计时");
        var runnerState = carrier.submunitionRunner.saveVirtualState();
        assertEquals(3, runnerState.getList("waves", 10).getCompound(0).getInt("remaining"),
                "虚拟段不得消耗波次或生成子体");
        var rebuilt = RVP_EntityTrajectoryIntegrator.createEntity(carrier.level(),
                RVP_VirtualMissileState.load(state(carrier).save()).orElseThrow(), carrier.getRvpData(), null, null);
        assertEquals(runnerState, rebuilt.submunitionRunner.saveVirtualState());
        assertTrue(rebuilt.needsVirtualWorldRestoration());
    }

    @Test
    void sourceAndHitHistoryUseUuidsWhenRuntimeEntityIdsChangeAfterRestart() {
        var missile = missile("ARM");
        var emitter = new Marker(EntityType.MARKER, missile.level());
        ((RVP_ProjectileSimulationLevel) missile.level()).addObserver(emitter);
        missile.preselectedVehicleId = emitter.getId();
        missile.dircmSourceVehicleId = emitter.getId();
        missile.jammingSourceVehicleId = emitter.getId();
        missile.directHitIds.add(emitter.getId());
        missile.radiationPulseTickMap.put(((long) emitter.getId() << 32) | 2, 7);
        var saved = RVP_VirtualMissileState.load(state(missile).save()).orElseThrow();

        var restarted = RVP_ProjectileSimulationLevel.create(0, null);
        var sameEmitter = new Marker(EntityType.MARKER, restarted);
        sameEmitter.setUUID(emitter.getUUID());
        restarted.addObserver(sameEmitter);
        assertNotEquals(emitter.getId(), sameEmitter.getId());
        var restored = RVP_EntityTrajectoryIntegrator.createEntity(restarted, saved, missile.getRvpData(), null, null);
        assertEquals(sameEmitter.getId(), restored.preselectedVehicleId);
        assertEquals(sameEmitter.getId(), restored.dircmSourceVehicleId);
        assertEquals(sameEmitter.getId(), restored.jammingSourceVehicleId);
        assertEquals(java.util.Set.of(sameEmitter.getId()), restored.directHitIds);
        assertEquals(java.util.Map.of(((long) sameEmitter.getId() << 32) | 2, 7), restored.radiationPulseTickMap);
    }

    @Test
    void virtualInterceptorSavesDesignationAndCanRebindAfterItsTargetIsRebuiltLater() {
        var interceptor = missile("ARH");
        var payload = missile("NONE");
        var level = (RVP_ProjectileSimulationLevel) interceptor.level();
        var payloadState = state(payload);
        var target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(level, payloadState, payload.getRvpData(), null, null);
        org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileSavedData.get(level).add(payloadState);
        interceptor.rvp$setActiveSeekerDesignatedTarget(target);
        var interceptorState = RVP_VirtualMissileState.load(state(interceptor).save()).orElseThrow();
        assertEquals(target.getUUID(), interceptorState.runtimeData.getUUID("activeSeekerDesignatedUuid"));

        var restarted = RVP_ProjectileSimulationLevel.create(0, null);
        var loadedPayload = RVP_VirtualMissileState.load(payloadState.save()).orElseThrow();
        var saved = org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileSavedData.get(restarted);
        saved.add(interceptorState); saved.add(loadedPayload);
        // 调用正式重建入口，刻意让拦截弹先于被拦截弹创建，覆盖读档中的引用顺序。
        var rebuiltInterceptor = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(
                restarted, interceptorState, interceptor.getRvpData(), null, null);
        assertNull(rebuiltInterceptor.rvp$getActiveSeekerDesignatedTargetEntity());
        var rebuiltTarget = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(
                restarted, loadedPayload, payload.getRvpData(), null, null);
        // 与管理器第二遍相同，全部身份建立后再用原运行态重绑，不用旧整数编号猜测目标。
        rebuiltInterceptor.restoreVirtualRuntimeData(interceptorState.runtimeData);
        assertSame(rebuiltTarget, rebuiltInterceptor.rvp$getActiveSeekerDesignatedTargetEntity());
        assertSame(rebuiltTarget, rebuiltInterceptor.getTargetEntity());
    }

    @Test
    void corruptedRuntimeSteeringAndMissingOptionalGpsExtensionAreHandled() {
        var missile = missile("HITL_CLOS_TV");
        var saved = state(missile).save();
        saved.getCompound("runtimeData").putFloat("hitlInputYaw", Float.NaN);
        assertTrue(RVP_VirtualMissileState.load(saved).isEmpty());
        var invalidLock = state(missile).save();
        invalidLock.getCompound("runtimeData").putString("hitlPointStatus", "INVALID");
        assertTrue(RVP_VirtualMissileState.load(invalidLock).isEmpty(), "坏脱锁状态不能进入弹体重建");
        var gps = state(missile("GPS")).save();
        gps.remove("runtimeData");
        assertTrue(RVP_VirtualMissileState.load(gps).isPresent(), "原有 GPS 存档无需迁移即可继续读取");
    }

    /** 当前 schema 的真实配置，所有武器只存在测试内存，不修改权威载具包。 */
    private static RVP_MissileEntity missile(String guidance) {
        return missile(guidance, "");
    }

    /** 可附加真实引信/布撒配置，让恢复预检实际读取正式数据而非测试开关。 */
    private static RVP_MissileEntity missile(String guidance, String extra) {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":10000,
                 "projectile_data":{"velocity":4,"gravity":0},
                 "guidance_data":{"guidance_type":"%s","hitl_right_click_detonate":true},
                 %s
                 "virtual_midcourse_data":{"enabled":true}}
                """.formatted(guidance, extra.isBlank() ? "" : extra + ","), RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        data.setWeaponId(ResourceLocation.tryParse("rvp:runtime_transfer_test"));
        var missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, data.getWeaponId());
        missile.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, null, null, new Vec3(0, 100, 0),
                new RVP_BaseBullet.AimRot(0, 0), new Vec3(0, 0, 4));
        return missile;
    }

    /** 与生产容器一致，用完整基础快照和额外运行态建立独立存档。 */
    private static RVP_VirtualMissileState state(RVP_MissileEntity missile) {
        var state = new RVP_VirtualMissileState(missile.getUUID(), missile.level().dimension().location(),
                missile.getWeaponId(), null, null, missile.position(), missile.createVirtualMidcourseSnapshot(),
                missile.createVirtualMidcourseKinematics(), missile.getId(), 0, 0, 0,
                RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        state.runtimeData = missile.saveVirtualRuntimeData();
        return state;
    }

    /** 只观测原版生命周期，不替代原版索引的增删实现。 */
    private static final class Lifecycle implements LevelCallback<Entity> {
        /** 正被世界逐刻调度的实体数。 */ private int ticking;
        /** 正被世界追踪的实体数。 */ private int tracking;
        /** 原版认为已经销毁的次数，转换时应为零。 */ private int destroyed;
        @Override public void onCreated(Entity entity) { }
        @Override public void onDestroyed(Entity entity) { destroyed++; }
        @Override public void onTickingStart(Entity entity) { ticking++; }
        @Override public void onTickingEnd(Entity entity) { ticking--; }
        @Override public void onTrackingStart(Entity entity) { tracking++; }
        @Override public void onTrackingEnd(Entity entity) { tracking--; }
        @Override public void onSectionChange(Entity entity) { }
    }

    /** 测试持久化边界不读写真实世界，仍使用原版区块实体容器。 */
    private static final class Storage implements EntityPersistentStorage<Entity> {
        @Override public CompletableFuture<ChunkEntities<Entity>> loadEntities(ChunkPos position) {
            return CompletableFuture.completedFuture(new ChunkEntities<>(position, List.of()));
        }
        @Override public void storeEntities(ChunkEntities<Entity> entities) { }
        @Override public void flush(boolean force) { }
    }
}
