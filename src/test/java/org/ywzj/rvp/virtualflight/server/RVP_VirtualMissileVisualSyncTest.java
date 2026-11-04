package org.ywzj.rvp.virtualflight.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Entry;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 生产只读同步、实际发动机窗口和观察者预算回归，不以替身弹道宣称正式轨迹一致。 */
class RVP_VirtualMissileVisualSyncTest {
    @Test
    void walkingNearObserverNeedsNoRadarAndFarAuthorizationHasAHardBoundary() {
        assertTrue(RVP_VirtualMissileVisualSync.isVisible(0, false, false));
        assertTrue(RVP_VirtualMissileVisualSync.isVisible(512 * 512, false, false));
        assertTrue(RVP_VirtualMissileVisualSync.isVisible(4096D * 4096, false, false));
        assertFalse(RVP_VirtualMissileVisualSync.isVisible(4097D * 4097, false, false));
        assertTrue(RVP_VirtualMissileVisualSync.isVisible(8192D * 8192, true, false));
        assertTrue(RVP_VirtualMissileVisualSync.isVisible(8192D * 8192, false, true));
        assertFalse(RVP_VirtualMissileVisualSync.isVisible(8193D * 8193, true, true));
        assertFalse(RVP_VirtualMissileVisualSync.isVisible(Double.NaN, true, true));
    }

    @Test
    void budgetIsBoundedStableAndPrioritizesLockedOwnedAndRetained() {
        List<RVP_VirtualMissileVisualSync.Choice> choices = new ArrayList<>();
        for (int index = 1; index <= 140; index++) choices.add(new RVP_VirtualMissileVisualSync.Choice(
                entry(index), index == 140, index == 139, 1000D * 1000, index <= 120));
        var first = RVP_VirtualMissileVisualSync.select(choices);
        assertEquals(128, first.size());
        assertEquals(139, first.get(0).entityId());
        assertEquals(140, first.get(1).entityId());
        for (int id = 1; id <= 120; id++) {
            int expected = id;
            assertTrue(first.stream().anyMatch(entry -> entry.entityId() == expected));
        }
        Collections.reverse(choices);
        assertEquals(first, RVP_VirtualMissileVisualSync.select(choices));
    }

    @Test
    void syncReadsCompletedProductionStateAndNeverCreatesTicksOrQueriesTerrain() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        level.forbidTerrainQueries();
        var weapon = weapon();
        var real = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, weapon.getWeaponId());
        // 调用真实发射初始化仅建立测试输入；同步器本身严禁重新执行此方法。
        real.initFromWeapon(weapon, RVP_EnumWeaponKind.MISSILE, null, null, new Vec3(300, 100, 0),
                new RVP_BaseBullet.AimRot(-20, 60), new Vec3(4, 0, 0));
        real.setGuidanceTargetPos(new Vec3(10000, 100, 0));
        real.setOldPosAndRot();
        var state = new RVP_VirtualMissileState(real.getUUID(), Level.OVERWORLD.location(), real.getWeaponId(),
                null, null, real.position(), real.createVirtualMidcourseSnapshot(), real.createVirtualMidcourseKinematics(),
                real.getId(), 0, 0, 0, RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        var saved = RVP_VirtualMissileSavedData.get(level);
        saved.add(state);
        var target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(level, state, weapon, null, null);
        var before = state.save();
        var entityBefore = target.createVirtualMidcourseSnapshot();
        var kinematicsBefore = target.createVirtualMidcourseKinematics();
        for (int iteration = 0; iteration < 10; iteration++) {
            // 调用生产显示提取和完整同步，世界没有玩家，因此不会发送网络包。
            Entry snapshot = RVP_VirtualMissileVisualSync.sample(target, state.phase);
            RVP_VirtualMissileVisualSync.syncLevel(level);
            assertEquals(target.position(), snapshot.position());
            assertEquals(target.getYRot(), snapshot.yaw());
            assertEquals(target.getXRot(), snapshot.pitch());
        }
        assertEquals(before, state.save());
        assertEquals(entityBefore, target.createVirtualMidcourseSnapshot());
        assertEquals(kinematicsBefore, target.createVirtualMidcourseKinematics());
        assertNull(level.getEntity(target.getId()), "显示提取不能把计算对象加入世界");
        state.trajectoryEntity = null;
        RVP_VirtualMissileVisualSync.syncLevel(level);
        assertNull(state.trajectoryEntity, "缺计算缓存只能跳过，不能为了发包补建对象");
    }

    @Test
    void authoritativeMotorPhaseDoesNotTreatConfiguredEngineAsBurningForever() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var weapon = weapon();
        var missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, weapon.getWeaponId());
        missile.initFromWeapon(weapon, RVP_EnumWeaponKind.MISSILE, null, null, new Vec3(0, 100, 0),
                new RVP_BaseBullet.AimRot(0, 0), new Vec3(4, 0, 0));
        missile.tickCount = 5;
        Entry burning = RVP_VirtualMissileVisualSync.sample(missile, RVP_VirtualFlightPhase.VIRTUAL_CRUISE);
        assertEquals(MotorPhase.PRIMARY, burning.motorPhase());
        assertEquals(5, burning.motorBurnRemainingTicks());
        missile.tickCount = 10;
        Entry boundary = RVP_VirtualMissileVisualSync.sample(missile, RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS);
        assertEquals(MotorPhase.PRIMARY, boundary.motorPhase());
        assertEquals(0, boundary.motorBurnRemainingTicks());
        missile.tickCount = 11;
        Entry idle = RVP_VirtualMissileVisualSync.sample(missile, RVP_VirtualFlightPhase.VIRTUAL_CRUISE);
        assertEquals(MotorPhase.IDLE, idle.motorPhase());
        assertEquals(0, idle.motorBurnRemainingTicks());
    }

    @Test
    void remoteReceptionRevisionDistinguishesUpdatesWithinOneClientTickWithoutAdvancingFlight() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var proxy = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level);
        long initial = proxy.getRemoteVisualUpdateRevision();
        Vec3 position = proxy.position();
        // 调用实际远程数据入口，同一 tick 内两次读取必须可以分辨，且不能运行飞行。
        proxy.readData(new net.minecraft.nbt.CompoundTag());
        assertEquals(initial + 1, proxy.getRemoteVisualUpdateRevision());
        proxy.readData(new net.minecraft.nbt.CompoundTag());
        assertEquals(initial + 2, proxy.getRemoteVisualUpdateRevision());
        assertEquals(0, proxy.tickCount);
        assertEquals(position, proxy.position());
    }

    /** 真实当前 schema 数据；武器只存在于测试内存，不改权威包。 */
    private static RVP_WeaponData weapon() {
        var data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":10000,
                 "projectile_data":{"velocity":4,"has_rocket_engine":true,"mass":100,"fuel_mass":10,
                   "thrust":1,"motor_burn_time":10,"gravity":0},
                 "guidance_data":{"guidance_type":"GPS","max_guidance_angle":180}}
                """, RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        data.setWeaponId(ResourceLocation.tryParse("rvp:visual_test"));
        return data;
    }

    /** 排序输入不携带目标资格，只表示已通过视觉筛选。 */
    private static Entry entry(int id) {
        return new Entry(new UUID(0, id), id, ResourceLocation.tryParse("rvp:visual_test"), Vec3.ZERO, Vec3.ZERO,
                0, 0, 1, RVP_VirtualFlightPhase.VIRTUAL_CRUISE, MotorPhase.IDLE, 0, 0);
    }
}
