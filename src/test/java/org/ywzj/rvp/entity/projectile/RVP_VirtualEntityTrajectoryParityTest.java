package org.ywzj.rvp.entity.projectile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationScenario;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.virtualflight.server.RVP_EntityTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileEligibility;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 完整生产实体 Tick 与实际虚拟飞行入口逐刻对照，同时覆盖存档重启和恢复后的连续运动。 */
class RVP_VirtualEntityTrajectoryParityTest {
    /** 临时武器只供测试，不改根目录权威载具包。 */ @TempDir Path temporary;
    /** 基础动力配置，主燃烧足够跨过虚拟化和存档重建。 */
    private static final String ENGINE = "\"velocity\":3,\"has_rocket_engine\":true,\"mass\":100,\"fuel_mass\":20,"
            + "\"thrust\":12,\"motor_burn_time\":55,\"gravity\":0,\"drag_coefficient\":0.05,\"max_speed\":7";
    /** 显式开启真实攻角和双轴惯量，不允许关闭气动后再声称轨迹对齐。 */
    private static final String AERO = ENGINE + ",\"rvp_maxg\":20,\"rvp_aero_steering\":true,\"rvp_ref_speed\":4,"
            + "\"rvp_induced_drag\":0.08,\"rvp_turn_rate_limit\":5,\"rvp_attack_angle_limit_deg\":45";

    /** 一项必须触发相应飞行状态的对照用例。 */
    record FlightCase(/** 用例名。 */ String name, /** 弹体字段。 */ String projectile,
                      /** 制导字段。 */ String guidance, /** 是否冷发射。 */ boolean cold,
                      /** 转虚拟前已完成的真实飞行刻。 */ int entryTick) {
        @Override public String toString() { return name; }
    }

    /** 所有用例都执行同一生产制导和运动链；变化只来自当前 schema 的武器参数。 */
    static Stream<FlightCase> cases() {
        var cases = new ArrayList<FlightCase>();
        cases.add(new FlightCase("zero_gravity", ENGINE, "", false, 6));
        cases.add(new FlightCase("unpowered_drag", "\"velocity\":3,\"gravity\":-0.03,\"drag\":0.025", "", false, 6));
        cases.add(new FlightCase("GPS_cruise_reset", ENGINE,
                "\"cruise_start_tick\":18,\"cruise_end_horizontal_dist\":30,\"cruise_gravity_scale\":0.1", false, 6));
        cases.add(new FlightCase("attack_angle", AERO, "", false, 6));
        cases.add(new FlightCase("PRESET_cold_launch", AERO + ",\"ignition_delay_tick\":8,\"inherit_vehicle_velocity\":true",
                "\"preset_cruise_altitude\":260", true, 16));
        cases.add(new FlightCase("constant_speed", AERO + ",\"constant_speed\":true", "", false, 6));
        cases.add(new FlightCase("body_axis_without_guidance", ENGINE + ",\"rotate_to_motion\":false,\"inherit_vehicle_velocity\":true",
                "\"guidance_tick_range\":\"[[80,inf]]\"", false, 6));
        cases.add(new FlightCase("changing_altitude_limits", AERO
                + ",\"altitude_drag_factor\":{\"[[0,130]]\":1.5,\"[[130,300]]\":0.2},"
                + "\"altitude_max_speed_factor\":{\"[[0,130]]\":1.4,\"[[130,300]]\":0.6}",
                "\"preset_cruise_altitude\":260", false, 6));
        cases.add(new FlightCase("attack_angle_intervals", AERO.replace("\"rvp_maxg\":20,", "")
                + ",\"turning_factor\":{\"[[0,25]]\":0.25,\"[[26,40]]\":1,\"[[41,inf]]\":0.25}", "", false, 6));
        cases.add(new FlightCase("wind_turbulence", AERO
                + ",\"wind_data\":{\"enabled\":true,\"direction_mode\":\"parent_facing_reverse\","
                + "\"speed\":0.3,\"response\":0.1,\"turbulence\":0.03}", "", false, 6));
        cases.add(new FlightCase("thrust_curve", AERO
                + ",\"thrust_curve\":{\"[[0,25]]\":15,\"[[26,55]]\":6}", "", false, 6));
        for (String trigger : List.of("speed", "speed_ratio", "distance", "apex")) {
            String condition = switch (trigger) {
                case "speed" -> "\"second_pulse_trigger_speed\":100";
                case "speed_ratio" -> "\"second_pulse_trigger_speed_ratio\":1";
                case "distance" -> "\"second_pulse_trigger_distance\":100000";
                default -> "\"second_pulse_trigger_apex\":true";
            };
            cases.add(new FlightCase("second_pulse_" + trigger,
                    "\"velocity\":3,\"has_rocket_engine\":true,\"mass\":100,\"fuel_mass\":10,\"thrust\":1,"
                            + "\"motor_burn_time\":10,\"gravity\":-0.06,\"max_speed\":4,\"second_pulse\":true,"
                            + "\"second_pulse_thrust\":2,\"second_pulse_burn_time\":12," + condition,
                    "\"guidance_tick_range\":\"[[1000,inf]]\"", false, 6));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void virtualFlightAndRestoredFlightMatchEveryProductionTick(FlightCase sample) throws Exception {
        String guidance = "\"guidance_type\":\"GPS\",\"max_guidance_angle\":180"
                + (sample.guidance.isEmpty() ? "" : "," + sample.guidance);
        String json = "{\"type\":\"rvp:missile\",\"life\":10000,\"projectile_data\":{" + sample.projectile
                + "},\"guidance_data\":{" + guidance + "},\"virtual_midcourse_data\":{\"enabled\":true,"
                + "\"entry_min_distance_from_launch\":0,\"entry_min_target_distance\":100,\"restore_target_distance\":20}}";
        Files.writeString(temporary.resolve("probe.json"), json);
        // 调用正式 JSON 数据解析和真实实体构造；参考实体仅隔离不参与弹道计算的世界边界。
        var weapon = RVP_ProjectileSimulationWeapon.read(temporary, "rvp:probe");
        var scene = new RVP_ProjectileSimulationScenario();
        scene.id = "virtual-parity"; scene.name = sample.name; scene.weapon = "rvp:probe";
        scene.groundY = -1000; scene.position = new double[]{0, 100, 0}; scene.pitch = 25;
        scene.target = new double[]{1200, 100, 5000};
        scene.vehicleVelocity = new double[]{-10, 4, 30};
        if (sample.cold) {
            scene.coldLaunchTicks = 10; scene.coldLaunchVelocity = new double[]{0, 40, 0};
            scene.carrierRotation = new double[]{25, 10, -5};
        }
        if (sample.name.equals("wind_turbulence")) {
            scene.parentRotation = new double[]{45, 20, 0}; scene.parentVelocity = new double[]{20, 5, 30};
        }
        var reference = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene,
                (type, level) -> new ReferenceMissile(type, level, weapon.data().getWeaponId()));
        var actual = (RVP_MissileEntity) reference.entity();
        // 调用测试环境查询保护；普通 GPS 没有高度门，真实与虚拟飞行均不得偷偷读取地形。
        ((RVP_ProjectileSimulationLevel) actual.level()).forbidTerrainQueries();
        // 排除原版首次射击广播和离开发射者的碰撞查询，完整生产 Tick 的制导/运动仍不替换。
        RVP_ProjectileSimulationBootstrap.write(Projectile.class, actual, "hasBeenShot", true);
        RVP_ProjectileSimulationBootstrap.write(Projectile.class, actual, "leftOwner", true);
        for (int tick = 1; tick <= sample.entryTick; tick++) tickReference(reference);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                RVP_VirtualMissileEligibility.evaluate(actual, weapon.data()), sample.name);
        // 调用正式快照出口，虚拟态从已真实飞过的同一帧开始，不能重新初始化发射姿态。
        var state = new RVP_VirtualMissileState(actual.getUUID(), Level.OVERWORLD.location(), actual.getWeaponId(),
                null, null, actual.getVirtualMidcourseLaunchPosition(), actual.createVirtualMidcourseSnapshot(),
                actual.createVirtualMidcourseKinematics(), actual.getId(), actual.getColdLaunchTimeTick(), 0, 0,
                RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        assertTrue(state.isStructurallyValid());
        if (sample.cold) {
            assertTrue(actual.hasPresetProfileInitialized());
            assertNotEquals(actual.getVirtualMidcourseLaunchPosition(), actual.getPresetLaunchPos(),
                    "冷发射后必须保存实际点火点，不能恰好等于生成点而漏测断层");
        }
        boolean angularResponseSeen = false;
        boolean secondPulseSeen = false;
        boolean cruiseResetSeen = false;
        for (int tick = sample.entryTick + 1; tick <= 100; tick++) {
            if (tick == 35) {
                // 调用真实在途改靶入口；虚拟态只更新相同目标点，不能重新叠加 GPS 散布。
                Vec3 retarget = new Vec3(-500, 130, 4500);
                actual.setGuidanceTargetPos(retarget); state.updateTargetPosition(retarget);
            }
            if (tick == 25 || tick == 50) {
                // 调用真实 NBT 编解码丢弃计算对象，模拟停服重启，验证完整运动记忆足以逐刻重建。
                state = RVP_VirtualMissileState.load(state.save()).orElseThrow();
            }
            if (tick == 60) {
                // 调用公开轨迹替换接口施加相同外部位移，验证计算缓存不会覆盖新权威位置。
                actual.setPos(actual.position().add(5, 3, -2));
                state.updateTrajectory(actual.createVirtualTrajectoryState());
            }
            tickReference(reference);
            // 调用生产虚拟态入口，覆盖真实管理器所用的缓存和存档后重建路径。
            var result = RVP_EntityTrajectoryIntegrator.step(actual.level(), state, weapon.data(), null, null);
            assertFalse(result.invalid(), sample.name + " 第 " + tick + " 刻");
            assertEquals(actual.createVirtualMidcourseSnapshot(), state.snapshot, sample.name + " 第 " + tick + " 刻完整快照");
            // NBT 会把 -0.0 与 +0.0 统一；运动记忆按编码值比较，位置/速度/角速度仍按精确值逐刻比较。
            assertEquals(actual.createVirtualMidcourseKinematics().save(), state.kinematics.save(), sample.name + " 第 " + tick + " 刻运动记忆");
            angularResponseSeen |= Math.abs(state.trajectory().pitchRateDeg()) + Math.abs(state.trajectory().yawRateDeg()) > 1.0E-9;
            secondPulseSeen |= state.trajectory().secondPulseStartTick() >= 0;
            cruiseResetSeen |= state.snapshot.gpsCruiseVerticalResetApplied();
        }
        if (sample.name.startsWith("second_pulse_")) assertTrue(secondPulseSeen, "必须实际触发该二脉冲条件");
        if (sample.name.equals("attack_angle")) assertTrue(angularResponseSeen, "必须实际推进双轴角速度");
        if (sample.name.equals("GPS_cruise_reset")) assertTrue(cruiseResetSeen, "必须在虚拟态实际执行 GPS 垂直重置");
        if (sample.name.equals("wind_turbulence")) assertTrue(state.kinematics.inheritedWindDirection().lengthSqr() > 0);
        // 调用真实末段恢复入口；不按速度强制改朝向，恢复后继续比较二十刻制导和运动。
        RVP_MissileEntity restored = RVP_EntityTrajectoryIntegrator.createEntity(actual.level(), state, weapon.data(), null, null);
        assertEquals(actual.createVirtualMidcourseSnapshot(), restored.createVirtualMidcourseSnapshot());
        assertEquals(actual.createVirtualMidcourseKinematics().save(), restored.createVirtualMidcourseKinematics().save());
        for (int tick = 101; tick <= 120; tick++) {
            tickReference(reference);
            restored.tickVirtualMidcourseFlight();
            assertEquals(actual.createVirtualMidcourseSnapshot(), restored.createVirtualMidcourseSnapshot(), sample.name + " 恢复后第 " + tick + " 刻");
            assertEquals(actual.createVirtualMidcourseKinematics().save(), restored.createVirtualMidcourseKinematics().save());
        }
    }

    /** 调用完整生产 Tick；场景环境只提供水状态和世界时间，不计算制导或运动。 */
    private static void tickReference(RVP_ProjectileEntitySimulation reference) {
        reference.entity().setOldPosAndRot();
        reference.entity().tickCount++;
        reference.observeEnvironment();
        reference.entity().tick();
    }

    /** 正常 RVP 导弹的参考子类，不覆写任何制导、运动或姿态方法。 */
    private static final class ReferenceMissile extends RVP_MissileEntity {
        /** 真实父类构造器建立全部物理初值。 */
        private ReferenceMissile(EntityType<? extends Projectile> type, Level level,
                net.minecraft.resources.ResourceLocation weaponId) { super(type, level, weaponId); }
        /** 测试外部环境已经提供时间、水状态及姿态历史。 */ @Override public void baseTick() {}
        /** 对照中发射者始终有效。 */ @Override protected boolean checkShooterValid() { return true; }
        /** 对照区块均已就绪，不申请真实区块票据。 */ @Override protected boolean shouldKeepDynamicChunkPathLoaded() { return false; }
        /** 对照自由飞行，不让外部碰撞提前结束。 */ @Override protected void tickHit() {}
        /** 对照无网络接收者，不发送尾迹。 */ @Override protected void broadcastTrailParticles() {}
        /** 参考实体始终保留为实体，只有另一支进入正式虚拟飞行入口。 */ @Override protected boolean tryEnterVirtualMidcourse() { return false; }
    }
}
