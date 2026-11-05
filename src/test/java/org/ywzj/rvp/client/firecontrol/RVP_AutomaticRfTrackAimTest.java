package org.ywzj.rvp.client.firecontrol;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 直接执行真实火控入口，普通 RF 站的自动航迹不能回退成本体强制瞄准。 */
class RVP_AutomaticRfTrackAimTest {
    /** 没有配置 rvp_fire_control_mode 的普通 RF 武器站，复现 052D 的配置。 */
    private TestStation station;
    /** 同时用于站级航迹和手动雷达硬锁的目标。 */
    private Entity target;
    /** 只隔离网络写入的实际雷达。 */
    private TestRadar radar;

    @BeforeAll static void bootstrap() { initialize(); }

    @BeforeEach void prepare() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        var carrier = environment(WheeledVehicle.class);
        carrier.setId(9052);
        write(Entity.class, carrier, "level", level);
        station = environment(TestStation.class);
        write(PartUnit.class, station, "vehicle", carrier);
        // 独立测试不启动 Mixin；未扩展的数据也必须受到同一自动航迹保护。
        write(PartUnit.class, station, "data", environment(WeaponUnitData.class));
        var data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","guidance_data":{"guidance_type":"ARH"}}
                """, RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        station.weapon = new RVP_ProjectileWeapon(carrier, station, 0, data, "rf_aim_probe", null);
        station.seekerOn = true;
        target = new Marker(EntityType.MARKER, level);
        station.track = target;
        radar = environment(TestRadar.class);
        write(PartUnit.class, radar, "data", environment(RadarUnitData.class));
        write(RadarUnit.class, radar, "on", true);
        station.radars = List.of(radar);
    }

    @Test void ordinaryRfStationConsumesAutomaticAimWithoutMovingOrClearingTheTrack() {
        assertTrue(RVP_RadarRoleHelper.isAutomaticRfSeekerTrack(station));
        // 返回 true 才能阻止已有转发点回退 self.aim；目标仍留给 HUD、发射和数据链消费。
        assertTrue(RVP_BallisticLeadFireControlExecutor.tryApply(station, new Vec3(100, 100, 0)));
        assertEquals(0, station.aimCalls);
        assertSame(target, station.getLockedEntity());
        assertNull(radar.getLockedEntity(), "自动航迹不得升级为雷达硬锁");
    }

    @Test void manualRadarHardLockKeepsOrdinaryStationBaseAim() {
        write(RadarUnit.class, radar, "lockedEntity", target);
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrack(station));
        assertFalse(RVP_BallisticLeadFireControlExecutor.tryApply(station, new Vec3(100, 100, 0)),
                "普通 RF 站的手动硬锁继续交由本体瞄准");
    }

    @Test void pendingManualLockIsNotTreatedAsAnAutomaticTrack() {
        RVP_WeaponLockStateTable.setPendingRadarLockEntityId(station, target.getId());
        try {
            assertFalse(RVP_BallisticLeadFireControlExecutor.tryApply(station, new Vec3(100, 100, 0)));
        } finally {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(station);
        }
    }

    @Test void seekerOffKeepsExistingManualStationLockBehavior() {
        station.seekerOn = false;
        assertFalse(RVP_BallisticLeadFireControlExecutor.tryApply(station, new Vec3(100, 100, 0)));
    }

    /** 站点、武器和锁定为测试输入；模式判定、目标分类和火控执行使用生产实现。 */
    private static final class TestStation extends WeaponUnit {
        /** 当前已解包的真实武器。 */ private RVP_ProjectileWeapon weapon;
        /** 只有武器站持有的自动航迹。 */ private Entity track;
        /** 导引头开机状态。 */ private boolean seekerOn;
        /** 参与手动锁定的雷达列表。 */ private List<RadarUnit> radars;
        /** 实际对准目标的调用次数。 */ private int aimCalls;
        private TestStation() { super(0, null, null); }
        @Override public Optional<AbstractVehicleWeapon<?>> getCurrentWeapon() { return Optional.of(weapon); }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public List<RadarUnit> getRadarUnits() { return radars; }
        @Override public Entity getLockedEntity() { return track; }
        @Override public boolean isSeekerOn() { return seekerOn; }
        @Override public WeaponUnitData.FireControlSensorType getFireControlSensorType() {
            return WeaponUnitData.FireControlSensorType.RF;
        }
        @Override public void aim(Vec3 position) { aimCalls++; }
    }

    /** 不启动字节码注入，显式实现已有访问接口以供真实雷达角色解析读取数据。 */
    private static final class TestRadar extends RadarUnit implements PartUnitAccessorMixin {
        private TestRadar() { super(0, null, null); }
        @Override public PartUnitData ywzj_rvp$getData() { return getData(); }
    }
}
