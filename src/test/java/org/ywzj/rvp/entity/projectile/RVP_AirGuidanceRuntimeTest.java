package org.ywzj.rvp.entity.projectile;

import org.ywzj.vehicle.custom.serialize.GsonUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.guidance.*;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeAirGuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/** 真导弹、真制导源与原世界实体查询；只把外部雷达观测作为环境输入。 */
class RVP_AirGuidanceRuntimeTest {
    @BeforeAll static void initialize() { RVP_ProjectileSimulationBootstrap.initialize(); }
    @Test void earlySearchKeepsMemoryFrozenAndDefersBothLossTimers() {
        var f = new Fixture(); var t = f.target(1000, 0); f.designate(t);
        for (int i = 0; i < 80; i++) f.step();
        assertTrue(f.m.isAutonomousSeekerOn()); assertEquals(0, f.m.getAutonomousSeekerLostTargetTick());
        Vec3 memory = f.m.getTargetPos(); t.setPos(1500, 100, 0); f.step();
        assertNull(f.m.getTargetEntity()); assertEquals(memory, f.m.getTargetPos());
        f.m.setPos(950, 100, 0); f.step();
        assertEquals(1, f.m.getAutonomousSeekerLostTargetTick());
        f.m.setPos(0, 100, 0); f.step(); assertEquals(2, f.m.getAutonomousSeekerLostTargetTick());
        for (int i = 0; i < 58; i++) f.step();
        assertEquals(0, f.m.lifeLeft());
    }
    @Test void radarCanRecoverAndLoseOriginalTargetWithoutLeakingNewCoordinates() {
        var f = new Fixture(); var t = f.target(1000, 0); f.designate(t); f.step();
        t.setPos(1400, 100, 0); f.m.radar = true; f.step();
        assertSame(t, f.m.getTargetEntity()); assertFalse(f.m.hasAutonomousSeekerCatch());
        assertEquals(1400, f.m.getTargetPos().x);
        f.m.radar = false; t.setPos(1600, 100, 0); f.step();
        assertNull(f.m.getTargetEntity()); assertEquals(1400, f.m.getTargetPos().x);
    }
    @Test void infraredCapturesWithoutRadarAndCanReturnToRadarAfterLosingItsTarget() {
        var f = new Fixture(); var t = f.target(0, 200); f.designate(t); f.step();
        assertSame(t, f.m.getTargetEntity()); assertTrue(f.m.hasAutonomousSeekerCatch());
        t.setPos(10, 100, 220); f.step(); assertTrue(f.m.hasAutonomousSeekerCatch());
        t.setPos(3000, 100, 0); f.m.radar = true; f.step();
        assertFalse(f.m.hasAutonomousSeekerCatch()); assertSame(t, f.m.getTargetEntity());
    }
    @Test void radarMayGuideToChaffButInfraredNeverAcquiresOrReacquiresIt() {
        var f = new Fixture(); var chaff = new Decoy(f.level, RVP_EnumCountermeasureType.CHAFF);
        chaff.setPos(0,100,500); f.level.addObserver(chaff); f.designate(chaff); f.m.radar = true; f.step();
        assertSame(chaff, f.m.getTargetEntity()); assertFalse(f.m.hasAutonomousSeekerCatch());
        f.m.setPos(0,100,450); f.step();
        assertTrue(f.m.isAutonomousSeekerOn()); assertSame(chaff, f.m.getTargetEntity());
        assertFalse(f.m.hasAutonomousSeekerCatch());
        f.m.radar = false; chaff.setPos(0,100,600); f.step();
        assertNull(f.m.getTargetEntity()); assertEquals(500, f.m.getTargetPos().z);
        var flare = new Decoy(f.level, RVP_EnumCountermeasureType.FLARE);
        flare.setPos(0,100,490); f.level.addObserver(flare); f.step(); f.step();
        assertSame(flare, f.m.getTargetEntity()); assertTrue(f.m.hasAutonomousSeekerCatch());
    }
    @Test void virtualRuntimeRestoresTheDelayedLossTimerLatch() {
        var f = new Fixture(); var t = f.target(1000,0); f.designate(t); f.m.setPos(950,100,0); f.step();
        var saved = f.m.saveVirtualRuntimeData(); assertTrue(saved.getBoolean("airLossTimerArmed"));
        var restored = new Fixture(); restored.m.restoreVirtualRuntimeData(saved);
        assertEquals(true, RVP_ProjectileSimulationBootstrap.read(RVP_MissileEntity.class, restored.m, "airLossTimerArmed"));
    }
    @Test void airHasAnIndependentInfraredLaunchChannelAndKeepsRadarLaunchAuthorization() {
        var f = new Fixture();
        assertTrue(RVP_IrLockHelper.supportsInfraredLaunchSearch(f.data));
        assertFalse(RVP_IrLockHelper.isIrLaunchWeapon(f.data));
        assertTrue(f.data.isRadarHoming());
        var radar = GsonUtil.GSON.fromJson("{\"guidance_data\":{\"guidance_type\":\"ARH\"}}", RVP_WeaponData.class);
        assertFalse(RVP_IrLockHelper.supportsInfraredLaunchSearch(radar));
    }
    @Test void freeInfraredSearchUsesConfiguredRangeAndNeverChoosesChaff() {
        var f = new Fixture(); f.m.setTargetPos(new Vec3(0,100,1000));
        var chaff = new Decoy(f.level, RVP_EnumCountermeasureType.CHAFF);
        chaff.setPos(0,100,50);f.level.addObserver(chaff);
        var flare = new Decoy(f.level, RVP_EnumCountermeasureType.FLARE);
        flare.setPos(0,100,500);f.level.addObserver(flare);
        f.step();f.step();
        assertSame(flare, f.m.getTargetEntity());assertTrue(f.m.hasAutonomousSeekerCatch());
    }
    @Test void airWithoutRadarCapturesOnlyInsideItsConfiguredSearchRange() {
        var f = new Fixture(384, ",\"seeker_search_distance_range\":\"[[0,384]]\"");
        var target = f.target(0, 1000);
        f.designate(target);
        Vec3 launchPoint = f.m.getTargetPos();

        f.step();
        target.setPos(30, 100, 800);
        f.step();
        assertTrue(f.m.isAutonomousSeekerOn(), "无雷达时仍立即搜索");
        assertFalse(f.m.hasAutonomousSeekerCatch(), "远距发射授权不能直接变成弹载锁定");
        assertNull(f.m.getTargetEntity());
        assertEquals(launchPoint, f.m.getTargetPos());

        target.setPos(0, 100, 384);
        f.step();
        assertSame(target, f.m.getTargetEntity());
        assertTrue(f.m.hasAutonomousSeekerCatch());

        target.setPos(30, 100, 800);
        f.step();
        assertSame(target, f.m.getTargetEntity(), "持续跟踪仍使用独立的跟踪距离");
    }

    @Test void freeAirSearchUsesTheSameCaptureRangeAsTheDesignatedTarget() {
        var f = new Fixture(384, ",\"seeker_search_distance_range\":\"[[0,384]]\"");
        var flare = new Decoy(f.level, RVP_EnumCountermeasureType.FLARE);
        flare.setPos(0, 100, 385);
        f.level.addObserver(flare);
        f.step();
        f.step();
        assertNull(f.m.getTargetEntity());

        flare.setPos(0, 100, 384);
        f.step();
        f.step();
        assertSame(flare, f.m.getTargetEntity());
    }

    @Test void airSearchSkipsCannonRoundsBeforeRankingTargets() {
        var f = new Fixture();
        var round = f.addAmmo(new RVP_BulletEntity(RVP_Entities.RVP_BULLET.get(), f.level),
                RVP_EnumWeaponKind.MACHINEGUN, new Vec3(0, 100, 20));
        assertTrue(round.getSignatureSize() > 0, "机炮弹原本会进入信号筛选");
        f.step();
        f.step();
        assertNull(f.m.getTargetEntity());

        var rocket = f.addAmmo(new RVP_RocketEntity(RVP_Entities.RVP_ROCKET.get(), f.level),
                RVP_EnumWeaponKind.ROCKET, new Vec3(10, 100, 100));
        f.step();
        f.step();
        assertSame(rocket, f.m.getTargetEntity(), "更近、更居中的机炮弹不应挡住有效候选");
        assertTrue(f.m.hasAutonomousSeekerCatch());
    }

    @Test void airDropsExistingCannonLockEvenWithRadarSupport() {
        var f = new Fixture();
        var round = f.addAmmo(new RVP_BulletEntity(RVP_Entities.RVP_BULLET.get(), f.level),
                RVP_EnumWeaponKind.MACHINEGUN, new Vec3(0, 100, 20));
        f.designate(round);
        Vec3 memory = f.m.getTargetPos();
        f.m.setTargetEntity(round);
        f.m.markAutonomousSeekerCatch();
        f.m.radar = true;
        round.setPos(0, 100, 30);
        f.step();
        f.step();
        assertFalse(f.m.hasAutonomousSeekerCatch());
        assertNull(f.m.getTargetEntity());
        assertEquals(memory, f.m.getTargetPos());
    }

    private static final class Fixture {
        final RVP_ProjectileSimulationLevel level = RVP_ProjectileSimulationLevel.create(0, null);
        /** 当前测试装载的武器配置。 */
        final RVP_WeaponData data;
        final TestMissile m = new TestMissile(level);

        Fixture() {
            this(100, "");
        }

        Fixture(int activationRange, String searchConfig) {
            data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":1200,"projectile_data":{"velocity":1},
                 "guidance_data":{"guidance_type":"AIR","active_radar_activation_range":%d,
                 "guidance_target_distance_range":"[[0,2000]]","max_lock_angle":30,
                 "max_guidance_angle":60,"scan_interval_tick":2,"enable_inertial_guidance":true%s}}
                """.formatted(activationRange, searchConfig), RVP_WeaponData.class);
            data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
            data.setWeaponId(net.minecraft.resources.ResourceLocation.tryParse("rvp:air_probe"));
            m.initFromWeapon(data,RVP_EnumWeaponKind.MISSILE,null,null,new Vec3(0,100,0),
                    new RVP_BaseBullet.AimRot(0,0),new Vec3(0,0,1));
        }
        Marker target(double x,double z) { var t=new Marker(EntityType.MARKER,level);t.setPos(x,100,z);level.addObserver(t);return t; }
        <T extends RVP_BaseBullet> T addAmmo(T ammo, RVP_EnumWeaponKind kind, Vec3 position) {
            var weapon = new RVP_WeaponData();
            weapon.setWeaponKind(kind);
            ammo.initFromWeapon(weapon, kind, null, null, position,
                    new RVP_BaseBullet.AimRot(0, 0), new Vec3(0, 0, 1));
            level.addObserver(ammo);
            return ammo;
        }

        void designate(Entity t) { m.rvp$setActiveSeekerDesignatedTarget(t);m.setTargetPos(t.position()); }
        void step() {
            m.tickCount++;level.observeTick(m.tickCount);
            invoke("tickActiveSeekerTargetManagement",new Class<?>[]{RVP_EnumGuidanceType.class},RVP_EnumGuidanceType.AIR);
            new RVP_RuntimeAirGuidanceSource().evaluate(new RVP_GuidanceRuntimeContext(m,data,
                    RVP_GuidanceModelResolver.resolveActive(data,RVP_GuidancePhase.MAIN)));
            invoke("tickAirTargetLoss",new Class<?>[0]);
        }
        void invoke(String name,Class<?>[] parameters,Object... args) {
            try { Method method=RVP_MissileEntity.class.getDeclaredMethod(name,parameters);method.setAccessible(true);method.invoke(m,args); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
    /** 只替换外部雷达是否看见目标的观测；目标选择和状态更新仍执行生产方法。 */
    private static final class TestMissile extends RVP_MissileEntity {
        boolean radar;
        int lifeLeft() { return life; }
        TestMissile(RVP_ProjectileSimulationLevel level) { super(RVP_Entities.RVP_MISSILE.get(),level); }
        @Override public boolean rvp$hasActiveSeekerSupportForDesignatedTarget() { return radar; }
    }
    private static final class Decoy extends Marker implements RVP_Decoy {
        final RVP_EnumCountermeasureType type;
        Decoy(RVP_ProjectileSimulationLevel level,RVP_EnumCountermeasureType type) { super(EntityType.MARKER,level);this.type=type; }
        @Override public RVP_EnumCountermeasureType rvp$decoyType() { return type; }
    }
}
