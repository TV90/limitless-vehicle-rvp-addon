package org.ywzj.rvp.weapon.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 使用根目录 HQ9B 的真实配置，执行服务端发射检查，复现终端呼叫者与拦截射手相同。 */
class RVP_SupportAmmoLaunchTest {
    /** 同一名炮火呼叫者兼任舰上射手。 */ private LivingEntity caller;
    /** 提供拦截弹发射平台身份的环境对象。 */ private Carrier carrier;
    /** 持有目标锁定的 RF 火控站。 */ private Station station;
    /** 运行真实发射前检查的 HQ9B 武器。 */ private RVP_ProjectileWeapon weapon;
    /** owner 为呼叫者、没有发射载具的终端火箭弹。 */ private RVP_RocketEntity supportRound;

    @BeforeAll static void bootstrap() { initialize(); }

    @BeforeEach void prepare() throws Exception {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        // 射手只提供身份与世界，不启动依赖完整 Forge 属性注册表的生物构造流程。
        caller = environment(ArmorStand.class);
        caller.setUUID(UUID.randomUUID());
        write(Entity.class, caller, "level", level);
        carrier = environment(Carrier.class);
        carrier.setUUID(UUID.randomUUID());
        write(Entity.class, carrier, "level", level);
        station = environment(Station.class);
        write(PartUnit.class, station, "vehicle", carrier);
        var data = RVP_ProjectileSimulationWeapon.read(
                Path.of("limitless_vehicle/rvp/data/rvp/weapons"), "rvp:052d_hq9b").data();
        weapon = new RVP_ProjectileWeapon(carrier, station, 0, data, "support_intercept_probe", null);
        station.weapon = weapon;
        var rocketData = GsonUtil.GSON.fromJson("""
                {"type":"rvp:rocket","projectile_data":{"velocity":2}}
                """, RVP_WeaponData.class);
        rocketData.setWeaponKind(RVP_EnumWeaponKind.ROCKET);
        supportRound = new RVP_RocketEntity(RVP_Entities.RVP_ROCKET.get(), level);
        // 调用和终端投送相同的生产初始化：伤害归属是 caller，发射载具明确为空。
        supportRound.initFromWeapon(rocketData, RVP_EnumWeaponKind.ROCKET, null, caller,
                new Vec3(0, 100, 100), new RVP_BaseBullet.AimRot(0, 180), new Vec3(0, 0, -2));
        station.locked = supportRound;
    }

    @Test void actualHQ9BLaunchGateAcceptsSupportRocketWithSeekerOnOrOff() {
        assertTrue(weapon.getData().isRequireLock());
        for (boolean seekerOn : new boolean[]{false, true}) {
            station.seekerOn = seekerOn;
            var approved = weapon.prepareShot(List.of(new AimContext()), caller);
            assertNotNull(approved, "独立炮火不能因同一伤害归属人被拒射；seekerOn=" + seekerOn);
            assertSame(supportRound, approved.target());
        }
    }

    @Test void missingLockAndOwnCarrierPreviousRoundRemainRejected() {
        station.locked = null;
        assertNull(weapon.prepareShot(List.of(new AimContext()), caller), "require_lock 门槛保持不变");
        station.locked = supportRound;
        write(RVP_BaseBullet.class, supportRound, "shooterVehicle", carrier);
        assertNull(weapon.prepareShot(List.of(new AimContext()), caller), "本车前一发仍不能成为拦截目标");
    }

    /** 不启动真实载具和乘员系统，只提供敌我与发射来源查询所需的环境。 */
    private static final class Carrier extends WheeledVehicle {
        private Carrier() { super(null, null); }
        @Override public ResourceLocation getVehicleId() { return ResourceLocation.tryParse("rvp:052d"); }
        @Override public LivingEntity getDriver() { return null; }
        @Override public Team getTeam() { return null; }
    }

    /** 锁定与导引头状态作为输入；武器目标解析、归属判定和发射授权均使用生产实现。 */
    private static final class Station extends WeaponUnit {
        /** 当前实际选中的武器。 */ private RVP_ProjectileWeapon weapon;
        /** 当前已锁定目标。 */ private Entity locked;
        /** 导引头开关输入。 */ private boolean seekerOn;
        private Station() { super(0, null, null); }
        @Override public WeaponUnit getRootParentWeaponUnit() { return this; }
        @Override public Optional<AbstractVehicleWeapon<?>> getCurrentWeapon() { return Optional.of(weapon); }
        @Override public List<RadarUnit> getRadarUnits() { return List.of(); }
        @Override public Entity getLockedEntity() { return locked; }
        @Override public boolean isSeekerOn() { return seekerOn; }
        @Override public WeaponUnitData.FireControlSensorType getFireControlSensorType() {
            return WeaponUnitData.FireControlSensorType.RF;
        }
    }
}
