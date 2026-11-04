package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.virtualflight.server.RVP_EntityTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileSavedData;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.environment;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.write;

/** 执行正式主动雷达搜索，比较真实/虚拟来袭弹，并防止搜索器把自身或己方弹当目标。 */
class RVP_RuntimeVirtualRadarTargetTest {
    @BeforeAll
    static void bootstrap() {
        // 在首次加载 ServerLevel 前准备原版注册表，测试不能依赖其它用例先完成初始化。
        org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.initialize();
    }

    @Test
    void seekerCanAcquireRealOrVirtualAmmoWhileRejectingSelfAndOwnVehicleAmmo() {
        var level = RVP_ProjectileSimulationLevel.create(0, null);
        // 无高度门的导引头搜索也不得偷偷查询沿途高度图。
        level.forbidTerrainQueries();
        var data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":1000,"projectile_data":{"velocity":4,"gravity":0},
                 "guidance_data":{"guidance_type":"ARH","max_lock_angle":360,"max_guidance_angle":180}}
                """, RVP_WeaponData.class);
        data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
        data.setWeaponId(net.minecraft.resources.ResourceLocation.tryParse("rvp:radar_search_test"));
        var seeker = missile(level, data, new Vec3(0, 100, 0));
        level.addObserver(seeker);
        var config = RVP_GuidanceModelResolver.resolveActive(data.getGuidanceData(), seeker.getGuidancePhaseState().phase());
        assertNull(RVP_RuntimeSeekerSupport.scanRadarTarget(seeker, config), "仅有搜索弹自身时不能自锁");

        var real = missile(level, data, new Vec3(100, 100, 0));
        level.addObserver(real);
        assertSame(real, RVP_RuntimeSeekerSupport.scanRadarTarget(seeker, config));
        var state = new RVP_VirtualMissileState(real.getUUID(), Level.OVERWORLD.location(), data.getWeaponId(),
                null, null, real.getVirtualMidcourseLaunchPosition(), real.createVirtualMidcourseSnapshot(),
                real.createVirtualMidcourseKinematics(), real.getId(), 0, 0, 0,
                RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        assertTrue(RVP_VirtualMissileSavedData.get(level).add(state));
        var virtual = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(level, state, data, null, null);
        real.discard();
        assertSame(virtual, RVP_RuntimeSeekerSupport.scanRadarTarget(seeker, config));

        // 使用明确排除当前目标高度的配置，验证虚拟记录仍可截获/跟踪且全程不访问地形。
        var restricted = GsonUtil.GSON.fromJson("""
                {"guidance_type":"ARH","max_lock_angle":360,"max_guidance_angle":180,
                 "guidance_altitude_range":"[[500,600]]"}
                """, org.ywzj.rvp.weapon.data.RVP_GuidanceData.class);
        var restrictedConfig = RVP_GuidanceModelResolver.resolveActive(restricted, seeker.getGuidancePhaseState().phase());
        assertNotNull(restrictedConfig.altitudeRange());
        assertTrue(org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry.passesAcquireLimits(seeker, virtual, restrictedConfig));
        assertTrue(org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry.passesTrackLimits(seeker, virtual, restrictedConfig));
        assertSame(virtual, RVP_RuntimeSeekerSupport.scanRadarTarget(seeker, restrictedConfig));

        var ownVehicle = environment(FixedWingVehicle.class);
        // 注入发射来源元数据，不替换正式搜索和敌我判定。
        write(RVP_BaseBullet.class, seeker, "shooterVehicle", ownVehicle);
        write(RVP_BaseBullet.class, virtual, "shooterVehicle", ownVehicle);
        assertNull(RVP_RuntimeSeekerSupport.scanRadarTarget(seeker, config), "同车弹药不可被自主搜索误锁");
    }

    /** 正式构造并初始化导弹，只提供位置与姿态这些环境输入。 */
    private static RVP_MissileEntity missile(RVP_ProjectileSimulationLevel level, RVP_WeaponData data, Vec3 position) {
        var missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, data.getWeaponId());
        missile.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, null, null, position,
                new RVP_BaseBullet.AimRot(0, -90), new Vec3(4, 0, 0));
        missile.setGuidanceTargetPos(new Vec3(10000, 100, 0));
        missile.setOldPosAndRot();
        return missile;
    }
}
