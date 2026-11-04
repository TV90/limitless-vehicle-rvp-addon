package org.ywzj.rvp.entity.projectile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.guidance.*;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeTerminalSeekerHandoff;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真导弹、真主段及末段制导；仅用可指定方向的武器站替代操作手外部输入。 */
class RVP_TerminalSeekerHandoffTest {
    @BeforeAll static void initialize() { RVP_ProjectileSimulationBootstrap.initialize(); }

    @Test void infraredSearchStartsAtTwoSecondsAndDoesNotInterruptLiveCommandBeforeCapture() {
        Fixture f = new Fixture("SACLOS", "IR", "");
        Flare flare = f.flare(0, 500);
        f.guide(39);
        assertFalse(f.m.getGuidancePhaseState().isTerminal());
        assertSame(RVP_EnumGuidanceType.SACLOS, f.m.getActiveSourceType());
        f.guide(40);
        assertTrue(f.m.hasTerminalSeekerCaptured());
        assertSame(flare, f.m.getTargetEntity());
        assertSame(RVP_EnumGuidanceType.IR, f.m.getActiveSourceType());
    }

    @Test void pendingSearchKeepsTheRealSaclosSourceAndItsChangingOperatorDirection() {
        Fixture f = new Fixture("SACLOS", "IR", "");
        f.unit.direction = new Vec3(.1, 0, 1);
        f.guide(40);
        assertTrue(f.m.isTerminalSeekerHandoffPending());
        assertSame(RVP_EnumGuidanceType.SACLOS, f.m.getActiveSourceType());
        assertTrue(f.m.getTargetPos().x > 0);
        f.unit.direction = new Vec3(-.1, 0, 1);
        f.guide(41);
        assertTrue(f.m.getTargetPos().x < 0);
        assertSame(RVP_EnumGuidanceType.SACLOS, f.m.resolveEffectiveGuidanceType());
    }

    @Test void realVehicleIsAcquiredAutonomouslyWithNoRadarLockOrLaunchTarget() {
        Fixture f = new Fixture("SACLOS", "IR", "",128);
        var target = f.vehicle(0,100);
        assertNull(f.m.getTargetEntity());
        f.guide(39); assertFalse(f.m.hasTerminalSeekerCaptured());
        f.guide(40);
        assertSame(target,f.m.getTargetEntity());
        assertTrue(f.m.hasTerminalSeekerCaptured());
    }

    @Test void autonomousVehicleSearchHonors128BlockRangeWithoutRadar() {
        Fixture f = new Fixture("SACLOS", "IR", "",128);
        f.vehicle(0,150); f.guide(40);
        assertTrue(f.m.isTerminalSeekerHandoffPending());
        var near=f.vehicle(0,120); f.guide(42);
        assertSame(near,f.m.getTargetEntity());
    }

    @Test void obscuredBestCandidateCannotStarveAVisibleVehicleAtTheEdgeOfTheCone() {
        Fixture f = new Fixture("SACLOS", "IR", "",128);
        f.vehicle(0,100);
        var visible=f.vehicle(8,100);
        var obstruction=new Obstruction(f.level); obstruction.setPos(0,100,50);
        obstruction.setBoundingBox(new net.minecraft.world.phys.AABB(-1,98,49,1,102,51));
        f.level.addObserver(obstruction);
        assertFalse(org.ywzj.rvp.countermeasure.RVP_CountermeasureState.isOpticalTargetVisible(f.m,f.level.getEntity(900101)));
        assertTrue(org.ywzj.rvp.countermeasure.RVP_CountermeasureState.isOpticalTargetVisible(f.m,visible));
        f.guide(40);
        assertSame(visible,f.m.getTargetEntity(),"自由搜索不能反复选中锥心被挡目标而忽略可见载具");
        assertFalse(f.m.hasSmokeBreakLock(),"未捕获的被挡候选不能造成永久烟雾脱锁");
    }

    @Test void destroyedBestCandidateCannotStarveALiveVehicle() {
        Fixture f = new Fixture("SACLOS", "IR", "",128);
        f.vehicle(0,100).destroyed=true;
        var live=f.vehicle(5,100); f.guide(40);
        assertSame(live,f.m.getTargetEntity());
    }

    @Test void friendlyDesignatedMissileCannotBeCapturedOrRetainedThroughIrGrace() {
        Fixture f=new Fixture("GPS","IR","");
        Marker shooter=f.target(0,-10);
        f.m.setOwner(shooter);
        TestMissile ammo=new TestMissile(f.level); ammo.setPos(0,100,100); ammo.setOwner(shooter);
        f.m.setTargetEntity(ammo); f.guide(40);
        assertFalse(f.m.hasTerminalSeekerCaptured());
        assertTrue(f.m.isTerminalSeekerHandoffPending());
        // 已经接管后的旧错误目标也必须立即停止实体追踪，不能再享受正常脱锁宽限。
        f.m.markTerminalSeekerCaptured();
        f.m.setTargetEntity(ammo); f.guide(42);
        assertNotSame(ammo,f.m.getTargetEntity());
    }

    @Test void inheritedTargetMustPassTheNarrowCaptureConeAndSeparateSearchRange() {
        Fixture f = new Fixture("GPS", "IR", "");
        Marker target = f.target(0, 1000);
        f.m.setTargetEntity(target);
        f.guide(40);
        assertFalse(f.m.hasTerminalSeekerCaptured(), "目标在跟踪范围内但在搜索范围外，不得套锁");
        target.setPos(500 * Math.tan(Math.toRadians(10)), 100, 500);
        f.guide(42);
        assertFalse(f.m.hasTerminalSeekerCaptured(), "15度全锥角的半角只有7.5度");
        target.setPos(0, 100, 500);
        f.guide(44);
        assertTrue(f.m.hasTerminalSeekerCaptured());
    }

    @Test void capturedSaclosInfraredTargetSurvivesFullParentTickAndUsesWideTrackingEnvelope() {
        Fixture f = new Fixture("SACLOS", "IR", "");
        Flare flare = f.flare(0, 500);
        f.guide(40);
        flare.setPos(1000 * Math.sin(Math.toRadians(20)), 100, 1000 * Math.cos(Math.toRadians(20)));
        // 搜索只到900格/7.5度，但跟踪仍到1800格/50度。
        f.guide(41);
        assertSame(flare, f.m.getTargetEntity());
        assertSame(RVP_EnumGuidanceType.IR, f.m.getActiveSourceType());
        assertTrue(RVP_GuidanceRuntimeGeometry.passesTrackLimits(f.m, flare, f.terminal()));
        assertFalse(RVP_GuidanceRuntimeGeometry.passesAcquireLimits(f.m, flare, f.terminal()));
        f.guide(42);
        assertSame(flare, f.m.getTargetEntity(), "主段SACLOS不得在每刻清掉已接管末段的目标");
    }

    @Test void losingCapturedTargetNeverReturnsToCommandGuidanceAndBothSavePathsKeepHandoff() {
        Fixture f = new Fixture("SACLOS", "IR", "");
        Flare flare = f.flare(0, 500); f.guide(40);
        flare.discard(); f.guide(42);
        assertTrue(f.m.hasTerminalSeekerCaptured());
        assertFalse(f.m.isTerminalSeekerHandoffPending());
        assertSame(RVP_EnumGuidanceType.IR, f.m.getActiveSourceType());
        Fixture restored = new Fixture("SACLOS", "IR", "");
        restored.m.getGuidancePhaseState().restore(RVP_GuidancePhase.TERMINAL);
        restored.m.restoreVirtualRuntimeData(f.m.saveVirtualRuntimeData());
        assertTrue(restored.m.hasTerminalSeekerCaptured());
        CompoundTag tag = new CompoundTag(); f.m.saveAdditional(tag);
        Fixture fromNbt = new Fixture("SACLOS", "IR", ""); fromNbt.m.readAdditional(tag);
        assertTrue(fromNbt.m.getGuidancePhaseState().isTerminal());
        assertFalse(fromNbt.m.isTerminalSeekerHandoffPending());
    }

    @Test void defaultIsEnabledAndExplicitFalseKeepsImmediateTerminalTakeover() {
        Fixture enabled = new Fixture("SACLOS", "IR", ""); enabled.guide(40);
        assertTrue(enabled.m.isTerminalSeekerHandoffPending());
        Fixture disabled = new Fixture("SACLOS", "IR", ",\"retain_main_guidance_until_capture\":false"); disabled.guide(40);
        assertFalse(disabled.m.usesTerminalSeekerHandoff());
        assertSame(RVP_EnumGuidanceType.IR, disabled.m.getActiveSourceType());
    }

    @Test void radarPendingSearchDoesNotStartNoTargetSelfDestructionAndCapturesOnlyInsideActivationDistance() {
        Fixture empty = new Fixture("GPS", "ARH", "");
        for (int tick = 40; tick < 140; tick++) empty.guide(tick);
        assertTrue(empty.m.isTerminalSeekerHandoffPending()); assertTrue(empty.m.lifeLeft() > 0);
        assertEquals(0, empty.m.getAutonomousSeekerLostTargetTick());
        Fixture f = new Fixture("GPS", "ARH", "");
        Marker t = f.target(0, 200); f.m.setTargetEntity(t); f.guide(40);
        assertFalse(f.m.hasTerminalSeekerCaptured());
        t.setPos(0, 100, 50); f.guide(42);
        assertTrue(f.m.hasTerminalSeekerCaptured()); assertTrue(f.m.hasAutonomousSeekerCatch());
    }

    @Test void radiationMemoryIsNotAFreshEmitterCapture() {
        Fixture f = new Fixture("SACLOS", "ARM", "");
        f.m.setAntiRadiationSignalAcquired(true); f.m.setAntiRadiationMemoryLeftTick(100);
        f.guide(40);
        assertTrue(f.m.isTerminalSeekerHandoffPending());
        assertSame(RVP_EnumGuidanceType.SACLOS, f.m.getActiveSourceType());
    }

    @Test void failedTerminalSearchDoesNotApplyGpsDispersionAgainEveryTick() {
        Fixture f = new Fixture("GPS", "IR", "");
        // 仅给环境中的主段配置正常GPS散布，生产入口生成且保存唯一一次偏移。
        RVP_ProjectileSimulationBootstrap.write(org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS.class,
                f.data.getGuidanceData(), "gpsSpreadRadius", 10f);
        f.m.setTargetPos(new Vec3(0,100,1500));
        Vec3 point = f.m.getTargetPos();
        for (int tick=40; tick<50; tick++) {
            f.guide(tick);
            assertEquals(point, f.m.getTargetPos());
        }
    }

    @Test void chaffCannotBecomeTheTerminalInfraredTargetAndAirAliasUsesSameRoute() {
        Fixture f = new Fixture("GPS", "AIR", "");
        Decoy chaff = new Decoy(f.level, RVP_EnumCountermeasureType.CHAFF);
        chaff.setPos(0, 100, 100); f.level.addObserver(chaff); f.m.setTargetEntity(chaff); f.guide(40);
        assertTrue(f.m.isTerminalSeekerHandoffPending());
        Flare flare = f.flare(0, 500); f.guide(42);
        assertSame(flare, f.m.getTargetEntity()); assertSame(RVP_EnumGuidanceType.IR, f.m.getActiveSourceType());
        assertEquals("message.ywzj_rvp.seeker_captured_ir", RVP_RuntimeTerminalSeekerHandoff.captureMessageKey(RVP_EnumGuidanceType.IR));
        assertEquals("message.ywzj_rvp.seeker_captured_radar", RVP_RuntimeTerminalSeekerHandoff.captureMessageKey(RVP_EnumGuidanceType.ARH));
        assertEquals("message.ywzj_rvp.seeker_captured_arm", RVP_RuntimeTerminalSeekerHandoff.captureMessageKey(RVP_EnumGuidanceType.ARM));
    }

    @Test void bothHq13GuidanceLayoutsCanActuallyIgniteTheConfiguredFiveSecondSecondPulse() {
        for (String type : new String[]{"AIR", "SACLOS"}) {
            Fixture f = new Fixture(type, "IR", "");
            f.m.tickCount = 61; RVP_ProjectileMotion.tickMissileMove(f.m);
            assertEquals(61, f.m.getSecondPulseStartTick());
            assertTrue(RVP_ProjectileMotion.resolvePIPMotorState(f.m).burning());
            assertEquals(100f, f.data.getProjectileData().getResolvedSecondPulseBurnTime());
        }
    }

    private static final class Fixture {
        final RVP_ProjectileSimulationLevel level = RVP_ProjectileSimulationLevel.create(0, null);
        final TestUnit unit = RVP_ProjectileSimulationBootstrap.environment(TestUnit.class);
        final RVP_WeaponData data;
        final TestMissile m = new TestMissile(level);
        Fixture(String main, String terminal, String options) {
            this(main,terminal,options,900);
        }
        Fixture(String main, String terminal, String options, int searchRange) {
            data = GsonUtil.GSON.fromJson("""
                {"type":"rvp:missile","life":1200,
                 "projectile_data":{"velocity":1,"max_speed":5,"has_rocket_engine":true,"mass":100,"fuel_mass":10,
                    "thrust":20,"motor_burn_time":60,"second_pulse":true,"second_pulse_trigger_speed":10,
                    "second_pulse_burn_time":100,"second_pulse_thrust":10},
                 "guidance_data":{"guidance_type":"%s","guidance_tick_range":"[[11,inf]]",
                    "guidance_target_distance_range":"[[0,4500]]","max_guidance_angle":80,"enable_inertial_guidance":true,
                    "terminal_guidance":{"guidance_type":"%s","guidance_start_tick":40,
                      "max_lock_angle":15,"max_guidance_angle":50,"scan_interval_tick":2,
                      "seeker_search_distance_range":"[[0,%d]]","guidance_target_distance_range":"[[0,1800]]",
                      "active_radar_activation_range":100,"enable_inertial_guidance":true%s}}}
                """.formatted(main, terminal, searchRange, options), RVP_WeaponData.class);
            data.setWeaponKind(RVP_EnumWeaponKind.MISSILE);
            data.setWeaponId(ResourceLocation.tryParse("rvp:terminal_handoff_probe"));
            m.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, null, null,
                    new Vec3(0,100,0), new RVP_BaseBullet.AimRot(0,0), new Vec3(0,0,1));
            unit.direction = new Vec3(0,0,1); unit.testData = new WeaponUnitData("test_unit"); m.unit = unit;
            m.setTargetPos(new Vec3(0,100,1500));
        }
        void guide(int tick) { m.tickCount = tick; level.observeTick(tick); m.guide(); }
        RVP_GuidanceActiveConfig terminal() { return RVP_GuidanceModelResolver.resolveActive(data, RVP_GuidancePhase.TERMINAL); }
        Marker target(double x, double z) { Marker t = new Marker(EntityType.MARKER,level); t.setPos(x,100,z); level.addObserver(t); return t; }
        Flare flare(double x, double z) { Flare t = new Flare(level); t.setPos(x,100,z); level.addObserver(t); return t; }
        /** 外部世界提供真实载具候选；不提供雷达列表或发射前锁定。 */
        VehicleTarget vehicle(double x,double z) {
            var t=RVP_ProjectileSimulationBootstrap.environment(VehicleTarget.class);
            RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class,t,"level",level);
            RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class,t,"position",new Vec3(x,100,z));
            RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class,t,"bb",new net.minecraft.world.phys.AABB(x-2,99,z-4,x+2,101,z+4));
            RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class,t,"dimensions",net.minecraft.world.entity.EntityDimensions.scalable(4,2));
            RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class,t,"deltaMovement",Vec3.ZERO);
            t.setUUID(java.util.UUID.randomUUID()); t.setId(900001+(int)x+(int)z); level.addObserver(t); return t;
        }
    }
    private static final class TestMissile extends RVP_MissileEntity {
        WeaponUnit unit;
        TestMissile(RVP_ProjectileSimulationLevel level) { super(RVP_Entities.RVP_MISSILE.get(), level); }
        void guide() { super.tickGuidance(); }
        int lifeLeft() { return life; }
        void saveAdditional(CompoundTag tag) { super.addAdditionalSaveData(tag); }
        void readAdditional(CompoundTag tag) { super.readAdditionalSaveData(tag); }
        @Override public WeaponUnit getShooterWeaponUnit() { return unit; }
    }
    /** 外部输入只提供真实武器站接口上的方向，不替换 SACLOS 制导或弹体转向算法。 */
    private static final class TestUnit extends WeaponUnit {
        Vec3 direction;
        WeaponUnitData testData;
        private TestUnit() { super(0,null,null); }
        @Override public boolean isParentWeaponUnitAim() { return false; }
        @Override public Vec3 worldVec() { return direction; }
        @Override public Vec3 worldPivotPosition() { return new Vec3(0,100,0); }
        @Override public WeaponUnitData getData() { return testData; }
    }
    private static class Decoy extends Marker implements RVP_Decoy {
        final RVP_EnumCountermeasureType type;
        Decoy(RVP_ProjectileSimulationLevel level, RVP_EnumCountermeasureType type) { super(EntityType.MARKER,level); this.type=type; }
        @Override public RVP_EnumCountermeasureType rvp$decoyType() { return type; }
    }
    private static final class Flare extends Decoy {
        Flare(RVP_ProjectileSimulationLevel level) { super(level,RVP_EnumCountermeasureType.FLARE); }
    }
    /** 真实载具候选的环境边界：没有雷达、乘员、OBB资源或网络连接，仍走生产目标搜索。 */
    private static final class VehicleTarget extends org.ywzj.vehicle.entity.vehicle.WheeledVehicle {
        /** 世界中可存在尚未移除的残骸，必须排除。 */ boolean destroyed;
        private VehicleTarget() { super(null,null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isDestroyed() { return destroyed; }
        @Override public ResourceLocation getVehicleId() { return ResourceLocation.tryParse("rvp:terminal_vehicle_probe"); }
        @Override public java.util.List<org.ywzj.vehicle.vehicle.part.PartUnit<?>> getPartUnits() { return java.util.List.of(); }
        @Override public java.util.List<org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB> getVehicleCubeOBBs() { return java.util.List.of(); }
    }
    /** 光学遮挡环境边界；直接复用本体的遮挡接口和生产射线检测。 */
    private static final class Obstruction extends Marker implements org.ywzj.vehicle.api.entity.SightObstruction {
        Obstruction(RVP_ProjectileSimulationLevel level) { super(EntityType.MARKER,level); }
    }
}
