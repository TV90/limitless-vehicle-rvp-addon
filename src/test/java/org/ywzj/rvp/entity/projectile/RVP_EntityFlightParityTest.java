package org.ywzj.rvp.entity.projectile;

import com.google.gson.JsonParser;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationScenario;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 独立调度与完整生产实体 Tick 逐刻对照；参考实体只隔离外部世界，绝不覆写 tickMotion。 */
class RVP_EntityFlightParityTest {
    /** 临时武器输入不修改权威包。 */ @TempDir Path temporary;
    /** 一组具有明确运动边界的配置。 */
    record FlightCase(/** 名称。 */ String name, /** 公开弹体类型。 */ String kind,
                      /** 弹体 JSON 字段。 */ String projectile, /** 制导 JSON 字段。 */ String guidance,
                      /** 是否水中起飞。 */ boolean wet, /** 是否带目标观测。 */ boolean target,
                      /** 是否冷发射。 */ boolean cold, /** 是否部署分量。 */ boolean deployment,
                      /** 最长对照刻数。 */ int ticks) { @Override public String toString() { return name; } }

    /** 同时覆盖六类实体、空气/水中、发动机、恒速、姿态、过载、攻角、拦截和二脉冲。 */
    static Stream<FlightCase> cases() {
        var cases = new ArrayList<FlightCase>();
        for (String kind : List.of("missile", "rocket", "machinegun", "bomb", "torpedo", "dispenser")) {
            for (boolean wet : List.of(false, true)) {
                cases.add(new FlightCase(kind + "_" + wet + "_ballistic", kind,
                        "\"velocity\":3,\"gravity\":-0.04,\"gravity_in_water\":-0.012,\"drag\":0.02,\"drag_in_water\":0.08",
                        "\"guidance_type\":\"NONE\"", wet, false, false, false, 25));
                cases.add(new FlightCase(kind + "_" + wet + "_engine", kind,
                        "\"velocity\":2,\"gravity\":-0.01,\"gravity_in_water\":-0.015,\"has_rocket_engine\":true,\"mass\":100,\"fuel_mass\":20,\"thrust\":10,\"motor_burn_time\":8,\"ignition_delay_tick\":3,\"drag_coefficient\":0.07,\"max_speed\":5",
                        "\"guidance_type\":\"GPS\"", wet, false, false, false, 30));
            }
            cases.add(new FlightCase(kind + "_constant_and_attitude", kind,
                    "\"velocity\":3,\"gravity\":-0.05,\"drag\":0.2,\"constant_speed\":true,\"rotate_to_motion\":false,\"min_speed\":1,\"max_speed\":4,\"altitude_max_speed_factor\":{\"[[0,inf]]\":0.8}",
                    "\"guidance_type\":\"NONE\"", false, false, false, false, 30));
        }
        String aero = "\"velocity\":3,\"has_rocket_engine\":true,\"mass\":100,\"fuel_mass\":20,\"thrust\":12,\"motor_burn_time\":20,\"gravity\":0,\"drag_coefficient\":0.04,\"rvp_maxg\":20,\"rvp_aero_steering\":true,\"rvp_ref_speed\":4,\"rvp_induced_drag\":0.08,\"rvp_turn_rate_limit\":5,\"rvp_attack_angle_limit_deg\":60,\"max_speed\":6";
        cases.add(new FlightCase("GPS_attack_angle", "missile", aero, "\"guidance_type\":\"GPS\",\"max_guidance_angle\":180", false, true, false, false, 50));
        cases.add(new FlightCase("IR_predictive_intercept", "missile", aero, "\"guidance_type\":\"IR\",\"max_guidance_angle\":180,\"predict_target_pos\":true,\"loft_height_above_target\":60", false, true, false, false, 50));
        cases.add(new FlightCase("PRESET_cold_launch", "missile", aero + ",\"ignition_delay_tick\":5,\"inherit_vehicle_velocity\":true", "\"guidance_type\":\"GPS\",\"preset_cruise_altitude\":200,\"max_guidance_angle\":180", false, true, true, false, 60));
        cases.add(new FlightCase("HITL_direct", "missile", aero.replace("\"rvp_attack_angle_limit_deg\":60", "\"rvp_attack_angle_limit_deg\":0"), "\"guidance_type\":\"HITL_CLOS_TV\",\"max_guidance_angle\":180", false, true, false, false, 35));
        for (String trigger : List.of("\"second_pulse_trigger_speed\":100", "\"second_pulse_trigger_speed_ratio\":1", "\"second_pulse_trigger_distance\":5000", "\"second_pulse_trigger_apex\":true")) {
            cases.add(new FlightCase("pulse_" + trigger, "missile",
                    "\"velocity\":3,\"has_rocket_engine\":true,\"mass\":100,\"fuel_mass\":10,\"thrust\":1,\"motor_burn_time\":3,\"gravity\":-0.05,\"max_speed\":4,\"second_pulse\":true,\"second_pulse_thrust\":2,\"second_pulse_burn_time\":5," + trigger,
                    "\"guidance_type\":\"GPS\"", false, true, false, false, 75));
        }
        for (String kind : List.of("rocket", "bomb", "dispenser")) {
            cases.add(new FlightCase(kind + "_deployment_wind", kind,
                    "\"velocity\":2,\"gravity\":-0.03,\"deployment_horizontal_half_life_ticks\":5,\"deployment_vertical_half_life_ticks\":8,\"wind_data\":{\"enabled\":true,\"direction_mode\":\"north:45\",\"speed\":0.3,\"response\":0.1,\"turbulence\":0.03}",
                    "\"guidance_type\":\"NONE\"", false, false, false, true, 35));
        }
        cases.add(new FlightCase("parent_wind", "bomb",
                "\"velocity\":2,\"gravity\":-0.03,\"wind_data\":{\"enabled\":true,\"direction_mode\":\"parent_facing_reverse\",\"speed\":0.3,\"response\":0.1,\"vertical_factor\":0.5,\"turbulence\":0.03}",
                "\"guidance_type\":\"NONE\"", false, false, false, false, 35));
        cases.add(new FlightCase("smart_fuse_motion", "missile", aero,
                "\"guidance_type\":\"GPS\"", false, false, false, false, 25));
        cases.add(new FlightCase("small_mass_drag", "missile",
                "\"velocity\":3,\"has_rocket_engine\":true,\"mass\":0.2,\"fuel_mass\":0.05,\"thrust\":0.01,\"motor_burn_time\":10,\"drag_coefficient\":0.1,\"gravity\":0,\"rotate_to_motion\":false",
                "\"guidance_type\":\"NONE\"", false, false, false, false, 30));
        cases.add(new FlightCase("torpedo_enter_water", "torpedo", "\"velocity\":2,\"gravity\":-0.04,\"gravity_in_water\":-0.01",
                "\"guidance_type\":\"NONE\"", false, false, false, false, 30));
        cases.add(new FlightCase("torpedo_leave_water", "torpedo", "\"velocity\":2,\"gravity\":-0.04,\"gravity_in_water\":0.1",
                "\"guidance_type\":\"NONE\"", true, false, false, false, 30));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void directEntityStepMatchesProductionTick(FlightCase sample) throws Exception {
        String json = "{\"type\":\"rvp:" + sample.kind + "\",\"life\":10000,\"projectile_data\":{" + sample.projectile
                + "},\"guidance_data\":{" + sample.guidance + "},\"torpedo_data\":{\"water_speed\":1.5,\"water_entry_lerp_tick\":5,\"depth_damping\":0.8}}";
        Files.writeString(temporary.resolve("probe.json"), json);
        var weapon = RVP_ProjectileSimulationWeapon.read(temporary, "rvp:probe");
        var scene = new RVP_ProjectileSimulationScenario(); scene.id = "parity"; scene.name = sample.name; scene.weapon = "rvp:probe";
        scene.groundY = -1000; scene.position = new double[]{0, 100, 0}; scene.pitch = 20;
        if (sample.wet) scene.waterY = 300.0;
        if (sample.target) { scene.target = new double[]{500, 120, 1400}; scene.targetVelocity = new double[]{12, 1, -3}; scene.targetAcceleration = new double[]{0.3, 0, 0}; }
        if (sample.name.startsWith("pulse_")) { scene.guidance = false; scene.pitch = 60; }
        if (sample.cold) { scene.coldLaunchTicks = 10; scene.coldLaunchVelocity = new double[]{0, 40, 0}; scene.carrierRotation = new double[]{25, 10, -5}; scene.vehicleVelocity = new double[]{-10, 4, 30}; }
        if (sample.deployment) scene.deploymentVelocity = new double[]{20, 10, -5};
        if (sample.name.equals("parent_wind")) { scene.parentRotation = new double[]{45, 20, 0}; scene.parentVelocity = new double[]{20, 5, 30}; }
        if (sample.name.equals("smart_fuse_motion")) scene.smartFuseTarget = new double[]{2000, 100, 3000};
        if (sample.name.equals("torpedo_enter_water")) { scene.waterY = 99.0; scene.pitch = -25; }
        if (sample.name.equals("torpedo_leave_water")) { scene.waterY = 101.0; scene.pitch = 60; }
        var direct = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        var ports = new Ports();
        var reference = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene,
                (type, level) -> createReference(weapon.kind(), type, level, ports));
        ports.reference = reference;
        // 排除发射事件广播和原版世界感知；它们不是运动。所有制导/运动与姿态方法继续走生产实体。
        RVP_ProjectileSimulationBootstrap.write(Projectile.class, reference.entity(), "hasBeenShot", true);
        RVP_ProjectileSimulationBootstrap.write(Projectile.class, reference.entity(), "leftOwner", true);
        boolean secondPulseSeen = false;
        boolean angularResponseSeen = false;
        // 攻角停用惯量后角速度应为零，仍必须实际建立气动载荷，不能靠关闭攻角通过对照。
        boolean aerodynamicLoadSeen = false;
        boolean previousWater = direct.snapshot().inWater(), waterTransitionSeen = false;
        for (int tick = 1; tick <= sample.ticks; tick++) {
            var actual = direct.step();
            secondPulseSeen |= actual.motorStage().equals("第二脉冲燃烧");
            angularResponseSeen |= Math.abs(actual.pitchRateDeg()) + Math.abs(actual.yawRateDeg()) > 1.0E-9;
            aerodynamicLoadSeen |= actual.aeroLoadFactor() > 1.0E-9;
            waterTransitionSeen |= actual.inWater() != previousWater; previousWater = actual.inWater();
            reference.entity().setOldPosAndRot(); reference.entity().tickCount++; reference.observeEnvironment();
            // 调用完整生产 Tick；参考类没有覆写 tick、tickMotion 或炮弹专属运动。
            reference.entity().tick();
            var expected = reference.snapshot();
            assertEquals(expected, actual, sample.name + " 第 " + tick + " 刻");
        }
        if (sample.name.startsWith("pulse_")) assertTrue(secondPulseSeen, "该触发条件必须实际点燃二脉冲，不能只比较未触发路径");
        if (sample.name.equals("GPS_attack_angle") || sample.name.equals("IR_predictive_intercept")) {
            // 调用正式攻角门控，确认逐刻相等覆盖了真实攻角响应，并验证惯量角速度确已停用。
            assertTrue(RVP_ProjectileMotion.usesAttackAngle(direct.entity()), "必须保持攻角模式开启");
            assertTrue(aerodynamicLoadSeen, "必须实际建立攻角载荷，不能把攻角关掉后声称对齐");
            assertFalse(angularResponseSeen, "关闭惯量后不能继续推进两轴角速度");
        }
        if (sample.name.equals("parent_wind")) assertTrue(direct.entity().getInheritedWindDirection().lengthSqr() > 0);
        if (sample.name.startsWith("torpedo_enter_") || sample.name.startsWith("torpedo_leave_")) assertTrue(waterTransitionSeen);
    }

    /** 对照实体唯一共享的输入端口。 */
    private static final class Ports {
        /** 只传入环境观测与目标意图，不提供积分结果。 */ RVP_ProjectileEntitySimulation reference;
        /** 完整实体 Tick 在制导点读取同一场景目标。 */ void guide() { reference.executeGuidance(); }
    }

    /** 弹种按生产枚举选择对应子类；全部运动继承自各自真实实体。 */
    private static RVP_BaseBullet createReference(RVP_EnumWeaponKind kind, EntityType<? extends Projectile> type, Level level, Ports ports) {
        return switch (kind) {
            case MISSILE -> new RefMissile(type, level, ports);
            case ROCKET -> new RefRocket(type, level, ports);
            case MACHINEGUN -> new RefCannon(type, level, ports);
            case BOMB -> new RefBomb(type, level, ports);
            case TORPEDO -> new RefTorpedo(type, level, ports);
            case DISPENSER -> new RefDispenser(type, level, ports);
            default -> throw new IllegalArgumentException("没有实体运动：" + kind);
        };
    }

    /** RVP_MissileEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefMissile extends RVP_MissileEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefMissile(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }

    /** RVP_RocketEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefRocket extends RVP_RocketEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefRocket(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }

    /** RVP_BulletEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefCannon extends RVP_BulletEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefCannon(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }

    /** RVP_BombEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefBomb extends RVP_BombEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefBomb(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }

    /** RVP_TorpedoEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefTorpedo extends RVP_TorpedoEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefTorpedo(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }

    /** RVP_DispensedEntity 参考实体：只隔离非运动的世界与网络边界。 */
    private static final class RefDispenser extends RVP_DispensedEntity {
        /** 场景观测入口。 */ private final Ports ports;
        /** 保留真实父类构造与全部物理初值。 */ RefDispenser(EntityType<? extends Projectile> type, Level level, Ports ports) { super(type, level); this.ports = ports; }
        /** 世界已提供时钟、水状态与上一帧姿态，不重复运行世界查询。 */ @Override public void baseTick() {}
        /** 射手在本试验中一直有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 所有测试区块已就绪。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 输入相同目标观测，其余制导处理仍由生产控制器执行。 */ @Override protected void tickGuidance() { ports.guide(); }
        /** 试验比较自由飞行，不让世界命中提前终止积分。 */ @Override protected void tickHit() {}
        /** 不发送网络尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 本次仅比较实体段，不进入卸载区块后的另一套实现。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }
}
