package org.ywzj.rvp.radar;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 执行真实 START 事件、本体过期清理及硬锁检查，复现首次发射卡顿后全表消失的时序。 */
class RVP_RadarContactHoldOrderTest {
    /** 测试结束后恢复本体客户端上下文。 */ private LocalVehiclePlayer previousPlayer;
    /** 只提供实体身份、时间与平地高度的无窗口世界。 */ private TestLevel level;
    /** 当前玩家的载机。 */ private TestVehicle vehicle;
    /** 使用本体真实探测表与过期清理的相控阵雷达。 */ private TestRadar radar;
    /** 几何检查使用的有效目标。 */ private TestTarget target;
    /** 测试数据替代未经过 Mixin 扩展的原始数据类。 */ private TestRadarData data;

    @BeforeAll static void bootstrap() { initialize(); }

    @BeforeEach void prepare() {
        previousPlayer = LocalVehiclePlayer.instance;
        level = environment(TestLevel.class);
        level.live = new HashMap<>();
        write(Level.class, level, "isClientSide", true);
        vehicle = environment(TestVehicle.class);
        vehicle.setId(71);
        write(Entity.class, vehicle, "level", level);
        write(Entity.class, vehicle, "position", new Vec3(0, 100, 0));
        radar = environment(TestRadar.class);
        data = environment(TestRadarData.class);
        data.phase = true;
        write(PartUnit.class, radar, "vehicle", vehicle);
        write(PartUnit.class, radar, "data", data);
        write(PartUnit.class, radar, "id", "radar");
        write(RadarUnit.class, radar, "detectedObjects", new HashMap<>());
        write(RadarUnit.class, radar, "on", true);
        write(RadarUnit.class, radar, "maxScanDistance", 1800F);
        write(RadarUnit.class, radar, "scanSectorAngle", 120F);
        // 与根目录 j10c 的相控阵配置一致：不机械旋转，发现新目标间隔 30 刻。
        radar.yRotSpeed = 0;
        radar.yRotMin = -85;
        radar.yRotMax = 85;
        vehicle.parts = List.of(radar);
        TestPlayer player = new TestPlayer();
        player.vehicle = vehicle;
        player.operator = environment(ServerPlayer.class);
        player.station = environment(TestStation.class);
        player.station.operator = player.operator;
        LocalVehiclePlayer.instance = player;
        target = target(72, 100);
        // 调用本体正式探测写入，再模拟首发加载使现实时间过去 5 秒；不靠 sleep 制造偶然结果。
        radar.detect(target);
        ageContacts();
    }

    @AfterEach void restoreContext() { LocalVehiclePlayer.instance = previousPlayer; }

    @Test void baseZeroSpeedRadarReallyDeletesEveryContactAfterAStall() {
        // 调用本体原方法，直接证实零转速溢出后实际按 100ms 寿命清表。
        radar.tickTargets();
        assertTrue(radar.getDetectedEntities().isEmpty());
    }

    @Test void startHoldPreservesAllContactsAndHardLockWithoutDiscoveringNewTargets() {
        TestTarget second = target(73, 150);
        radar.detect(second);
        target(74, 200);
        radar.setLockedEntity(target);
        ageContacts();
        // 调用生产 START 入口后继续执行本体清理/锁定，而非仅测试“算出来应保留”的布尔值。
        RVP_ClientRadarTickHandler.onClientTickStart(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        radar.tickTargets();
        radar.tickLock();
        assertEquals(2, radar.getDetectedEntities().size());
        assertSame(target, radar.getDetectedEntities().get(72).entity);
        assertSame(second, radar.getDetectedEntities().get(73).entity);
        assertFalse(radar.getDetectedEntities().containsKey(74), "保活不能提前发现下一扫描轮次的目标");
        assertSame(target, radar.getLockedEntity());
    }

    @Test void deadAndOutOfRangeContactsAreStillRemovedBeforeBaseCleanup() {
        TestTarget dead = target(73, 150);
        TestTarget outside = target(74, 2000);
        radar.detect(dead);
        radar.detect(outside);
        dead.dead = true;
        ageContacts();
        RVP_ClientRadarTickHandler.onClientTickStart(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        radar.tickTargets();
        assertEquals(List.of(72), List.copyOf(radar.getDetectedEntities().keySet()));
        assertNull(radar.getLockedEntity(), "保活不得把现有 TWS 航迹升级为硬锁");
    }

    @Test void mechanicalRadarKeepsBaseExpiration() {
        data.phase = false;
        RVP_ClientRadarTickHandler.onClientTickStart(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        radar.tickTargets();
        assertTrue(radar.getDetectedEntities().isEmpty());
    }

    @Test void poweredOffRadarCannotKeepContactsAlive() {
        vehicle.unpowered = true;
        RVP_ClientRadarTickHandler.onClientTickStart(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        radar.tickTargets();
        assertTrue(radar.getDetectedEntities().isEmpty());
    }

    @Test void endEventCannotStandInForPreCleanupHold() {
        RVP_ClientRadarTickHandler.onClientTickStart(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        radar.tickTargets();
        assertTrue(radar.getDetectedEntities().isEmpty());
    }

    /** 只移动生产接触的时间戳，不替换本体的过期算法。 */
    private void ageContacts() {
        radar.getDetectedEntities().values().forEach(contact -> contact.detectedTime = System.currentTimeMillis() - 5000);
    }

    /** 目标生死与位置为外部输入，包围盒仍由真实雷达保活逻辑读取。 */
    private TestTarget target(int id, double z) {
        TestTarget result = environment(TestTarget.class);
        result.setId(id);
        write(Entity.class, result, "level", level);
        write(Entity.class, result, "position", new Vec3(0, 100, z));
        write(Entity.class, result, "bb", new AABB(-1, 99, z - 1, 1, 101, z + 1));
        write(Entity.class, result, "deltaMovement", Vec3.ZERO);
        level.live.put(id, result);
        return result;
    }

    /** 客户端世界环境；不构造窗口、区块或网络连接。 */
    private static final class TestLevel extends ClientLevel {
        /** 当前被原生跟踪的实体。 */ private Map<Integer, Entity> live;
        private TestLevel() { super(null, null, null, null, 2, 2, null, null, false, 0); }
        @Override public Entity getEntity(int id) { return live.get(id); }
        @Override public long getGameTime() { return 100; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { return 0; }
    }

    /** 只提供载机工作状态，部件检查仍执行生产代码。 */
    private static final class TestVehicle extends FixedWingVehicle {
        /** 载机当前部件。 */ private List<PartUnit<?>> parts;
        /** 是否模拟断电。 */ private boolean unpowered;
        private TestVehicle() { super(null, null); }
        @Override public List<PartUnit<?>> getPartUnits() { return parts; }
        @Override public boolean isDestroyed() { return false; }
        @Override public boolean hasPower() { return !unpowered; }
        @Override public LivingEntity getDriver() { return null; }
    }

    /** 固定测试扫描姿态；探测、清理与硬锁检查使用本体实现，仅隔离网络写锁。 */
    private static final class TestRadar extends RadarUnit {
        private TestRadar() { super(0, null, null); }
        @Override public Vec3 worldRadarPosition() { return new Vec3(0, 100, 0); }
        @Override public Vec2 aimRot(Vec3 position) { return Vec2.ZERO; }
        @Override public void setLockedEntity(Entity entity) { write(RadarUnit.class, this, "lockedEntity", entity); }
    }

    /** 仅提供输入上下文，避免访问未启动的 Minecraft 单例。 */
    private static final class TestPlayer extends LocalVehiclePlayer {
        /** 操作手的对象身份。 */ private Player operator;
        /** 操作手拥有的武器站。 */ private TestStation station;
        @Override public Player getPlayer() { return operator; }
        @Override public WeaponUnit getWeaponUnit() { return station; }
    }

    /** 只隔离未联网的乘员归属读取。 */
    private static final class TestStation extends WeaponUnit {
        /** 实际持有火控权限的操作手。 */ private Player operator;
        private TestStation() { super(0, null, null); }
        @Override public LivingEntity getOwner() { return operator; }
    }

    /** 生死为可控环境输入，其余接触身份与包围盒为本体字段。 */
    private static final class TestTarget extends ArmorStand {
        /** 是否模拟目标已经死亡。 */ private boolean dead;
        private TestTarget() { super(null, 0, 0, 0); }
        @Override public boolean isAlive() { return !dead; }
    }

    /** 独立 JUnit 不加载 Mixin，显式提供当前 schema 的相控阵扩展接口。 */
    private static final class TestRadarData extends RadarUnitData implements RadarUnitDataExt {
        /** 是否模拟 scan_animation_mode=phase。 */ private boolean phase;
        private TestRadarData() { super("radar"); }
        @Override public String ywzj_rvp$getRadarRole() { return "ALL"; }
        @Override public String ywzj_rvp$getScanAnimationMode() { return phase ? "phase" : "mechanical"; }
        @Override public String ywzj_rvp$getNctrMode() { return "MODERN"; }
        @Override public int ywzj_rvp$getScanPeriodTick() { return 30; }
        @Override public boolean ywzj_rvp$isScanLineWhenLocked() { return false; }
        @Override public int ywzj_rvp$getContactHoldTick() { return 0; }
        @Override public RVP_RadarHmsMode ywzj_rvp$getHmsMode() { return RVP_RadarHmsMode.FULL; }
        @Override public float ywzj_rvp$getScanMinHeight() { return 25; }
        @Override public float ywzj_rvp$getScanMaxHeight() { return 10000; }
        @Override public float ywzj_rvp$getChaffResistance() { return 0.5F; }
        @Override public boolean ywzj_rvp$isScanVehicleOnly() { return false; }
        @Override public boolean ywzj_rvp$isScanSea() { return false; }
    }
}
