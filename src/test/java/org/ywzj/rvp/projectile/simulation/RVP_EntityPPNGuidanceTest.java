package org.ywzj.rvp.projectile.simulation;

import com.google.gson.JsonParser;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;
import org.ywzj.rvp.guidance.*;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实弹体和正式意图执行器验证准入、配置门控及高抛交接，不替换转向或运动实现。 */
class RVP_EntityPPNGuidanceTest {
    /** 构造可解析验证的瞬时航迹；1 表示无气动模式下的完整执行器响应。 */
    private static final String PROJECTILE = "\"velocity\":5,\"has_rocket_engine\":false,"
            + "\"turning_factor\":{\"[[0,inf]]\":1}";
    /** 与解析角度比较的浮点容差。 */
    private static final double EPSILON = 1.0E-6;

    /**
     * @param flight 真实生产构造器初始化的弹体环境
     * @param target 仅提供当前坐标和速度的观测实体
     * @param context 正式配置解析器产生的活动阶段
     */
    private record Sample(RVP_ProjectileEntitySimulation flight, Marker target,
                          RVP_GuidanceRuntimeContext context) {}

    @ParameterizedTest
    @EnumSource(value = RVP_EnumGuidanceType.class, names = {"IR", "AIR", "ARH", "SARH"})
    void onlyAuthorizedEntitySeekersExecuteTheAnalyticPPNCommand(RVP_EnumGuidanceType type) {
        var sample = sample("rvp:missile", type, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        // 调用正式意图执行器；该几何 PPN 转角为 0.03 弧度，PIP 的提前角约为 0.20 弧度。
        assertTrue(apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, type)));
        assertVector(new Vec3(5 * Math.sin(0.03), 0, 5 * Math.cos(0.03)), sample.flight.entity().getDeltaMovement());
    }

    @ParameterizedTest
    @EnumSource(value = RVP_EnumGuidanceType.class, names = {"GPS", "ARM", "SALH", "LH", "SACLOS", "HITL_TV"})
    void otherEntityIntentPathsKeepTheirExistingPIP(RVP_EnumGuidanceType type) {
        var sample = sample("rvp:missile", type, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        assertTrue(apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, type)));
        // 恒速解析相遇点要求横向速度恰为目标的 1 格/刻；不应被 PPN 的约 0.15 替代。
        assertEquals(1, sample.flight.entity().getDeltaMovement().x, EPSILON);
    }

    @Test
    void bombsCoordinateMemoryAndDirectControlNeverEnterPPN() {
        var bomb = sample("rvp:bomb", RVP_EnumGuidanceType.IR, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        assertTrue(apply(bomb, RVP_GuidanceIntent.entity(bomb.target, false, 1, RVP_EnumGuidanceType.IR)));
        assertEquals(1, bomb.flight.entity().getDeltaMovement().x, EPSILON);
        var point = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        assertTrue(apply(point, RVP_GuidanceIntent.point(point.target.position(), false, 1, RVP_EnumGuidanceType.IR)));
        assertEquals(0, point.flight.entity().getDeltaMovement().x, EPSILON);
        var direct = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        assertTrue(apply(direct, RVP_GuidanceIntent.point(direct.target.position(), true, 1, RVP_EnumGuidanceType.IR)));
        assertEquals(0, direct.flight.entity().getDeltaMovement().x, EPSILON);
    }

    @Test
    void originalPredictionSwitchStartTickAndZeroGainStillGateTheNewLaw() {
        for (String setting : new String[]{"\"predict_target_pos\":false", "\"predict_target_pos_start_tick\":10",
                "\"predict_target_pos_gain\":0"}) {
            var sample = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE, setting, RVP_GuidancePhase.MAIN);
            assertTrue(apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, RVP_EnumGuidanceType.IR)));
            assertEquals(0, sample.flight.entity().getDeltaMovement().x, EPSILON, setting);
        }
        var stronger = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE,
                "\"predict_target_pos_gain\":5", RVP_GuidancePhase.MAIN);
        apply(stronger, RVP_GuidanceIntent.entity(stronger.target, false, 1, RVP_EnumGuidanceType.IR));
        assertEquals(5 * Math.sin(0.05), stronger.flight.entity().getDeltaMovement().x, EPSILON);
    }

    @Test
    void explicitZeroGAndDisabledActuatorResponseCannotBeBypassed() {
        for (String physical : new String[]{PROJECTILE.replace(":1}", ":0}"),
                PROJECTILE + ",\"rvp_aero_steering\":true,\"rvp_attack_angle_limit_deg\":60,\"rvp_maxg\":0"}) {
            var sample = sample("rvp:missile", RVP_EnumGuidanceType.IR, physical, "", RVP_GuidancePhase.MAIN);
            Vec3 before = sample.flight.entity().getDeltaMovement();
            apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, RVP_EnumGuidanceType.IR));
            assertVector(before, sample.flight.entity().getDeltaMovement());
        }
    }

    @Test
    void decoyHoldSecondPulseWaitAndTopAttackKeepTheirOriginalGuidance() {
        var jammed = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE, "", RVP_GuidancePhase.MAIN);
        // 调用正式反制保持入口，不能仅在测试里伪造 PPN 资格布尔值。
        jammed.flight.entity().markJamGracePeriod();
        apply(jammed, RVP_GuidanceIntent.entity(jammed.target, false, 1, RVP_EnumGuidanceType.IR));
        assertEquals(0, jammed.flight.entity().getDeltaMovement().x, EPSILON);
        String dualPulse = PROJECTILE.replace("\"has_rocket_engine\":false", "\"has_rocket_engine\":true")
                + ",\"motor_burn_time\":1,\"second_pulse\":true,\"second_pulse_thrust\":1,"
                + "\"second_pulse_burn_time\":10,\"second_pulse_trigger_distance\":20";
        var waiting = sample("rvp:missile", RVP_EnumGuidanceType.IR, dualPulse, "", RVP_GuidancePhase.MAIN);
        waiting.flight.entity().tickCount = 20;
        assertTrue(waiting.context.data().getProjectileData().usesSecondPulse());
        apply(waiting, RVP_GuidanceIntent.entity(waiting.target, false, 1, RVP_EnumGuidanceType.IR));
        assertEquals(0, waiting.flight.entity().getDeltaMovement().x, EPSILON);
        assertEquals(-1, waiting.flight.entity().getSecondPulseStartTick(), "制导只读取等待状态，不能提前点火");
        var topAttack = sample("rvp:missile", RVP_EnumGuidanceType.IR, PROJECTILE,
                "\"top_attack_height\":30", RVP_GuidancePhase.MAIN);
        apply(topAttack, RVP_GuidanceIntent.entity(topAttack.target, false, 1, RVP_EnumGuidanceType.IR));
        assertEquals(0, topAttack.flight.entity().getDeltaMovement().x, EPSILON);
        assertTrue(topAttack.flight.entity().getDeltaMovement().y > 0);
    }

    @Test
    void GPSTerminalSeekerUsesPPNEvenForAStationaryEntity() {
        var sample = sample("rvp:missile", RVP_EnumGuidanceType.GPS,
                PROJECTILE + ",\"rvp_aero_steering\":true,\"rvp_maxg\":1000", "", RVP_GuidancePhase.TERMINAL);
        sample.target.setPos(30, 164, 100);
        sample.target.setBoundingBox(new AABB(sample.target.position(), sample.target.position()));
        sample.target.setDeltaMovement(Vec3.ZERO);
        assertTrue(apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, RVP_EnumGuidanceType.IR)));
        // r=(30,0,100)、v=(0,0,5)，PPN 请求转角为 450/10900 弧度。
        assertEquals(5 * Math.sin(450.0 / 10900.0), sample.flight.entity().getDeltaMovement().x, EPSILON);
    }

    @Test
    void fullLoftKeepsPIPAndZeroLoftOrAutonomousCatchUsesPPN() {
        for (boolean caught : new boolean[]{false, true}) {
            var sample = sample("rvp:missile", RVP_EnumGuidanceType.ARH, PROJECTILE,
                    "\"loft_height_above_target\":30,\"loft_end_distance\":20,\"loft_blend_distance\":20",
                    RVP_GuidancePhase.MAIN);
            if (caught) ((RVP_MissileEntity) sample.flight.entity()).markAutonomousSeekerCatch();
            apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, RVP_EnumGuidanceType.ARH));
            if (caught) assertEquals(0, sample.flight.entity().getDeltaMovement().y, EPSILON);
            else assertTrue(sample.flight.entity().getDeltaMovement().y > 1, "满高抛阶段必须保留原路线请求");
        }
    }

    @Test
    void fullLoftWithDisabledAeroAppliesTheOriginalTurningFactorExactlyOnce() {
        String physical = PROJECTILE.replace(":1}", ":0.1}");
        var sample = sample("rvp:missile", RVP_EnumGuidanceType.ARH, physical,
                "\"loft_height_above_target\":30,\"loft_end_distance\":20,\"loft_blend_distance\":20",
                RVP_GuidancePhase.MAIN);
        var entity = sample.flight.entity();
        // 调用保留的原 PIP 入口计算满高抛基线；新衔接不能把 0.1 响应再缩成约 0.01。
        Vec3 expected = RVP_GuidanceRuntimeMath.steerPredictiveIntercept(entity, entity.position(),
                entity.getDeltaMovement(), sample.target.position().add(0, 30, 0),
                sample.target.getDeltaMovement(), entity.getDeltaMovement().length(), 0.1F);
        assertTrue(apply(sample, RVP_GuidanceIntent.entity(sample.target, false, 1, RVP_EnumGuidanceType.ARH)));
        assertVector(expected, entity.getDeltaMovement());
    }

    /** 调用正式执行器及单刻准备，测试只替换目标观测，不提前运行额外一次气动积分。 */
    private static boolean apply(Sample sample, RVP_GuidanceIntent intent) {
        sample.flight.entity().prepareKinematicGuidanceTick();
        return RVP_GuidanceRuntimeMath.applyIntent(sample.context, intent);
    }

    /** 从当前 schema 构造真实实体，保留生产配置默认值，避免测试依赖硬编码武器 ID。 */
    private static Sample sample(String kind, RVP_EnumGuidanceType type, String physical, String extra,
                                 RVP_GuidancePhase phase) {
        var json = JsonParser.parseString("{\"type\":\"" + kind + "\",\"projectile_data\":{" + physical
                + "},\"guidance_data\":{\"guidance_type\":\"" + type + "\",\"predict_target_pos\":true,"
                + "\"predict_target_pos_start_tick\":0,\"max_guidance_angle\":180"
                + (phase == RVP_GuidancePhase.TERMINAL ? ",\"terminal_guidance\":{\"guidance_type\":\"IR\","
                    + "\"predict_target_pos\":true,\"max_guidance_angle\":180}" : "")
                + (extra.isEmpty() ? "" : "," + extra) + "}}").getAsJsonObject();
        // 调用生产武器解析与真实弹体环境；场景只提供目标的当前平移速度。
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", json, "0".repeat(64));
        var scene = new RVP_ProjectileSimulationScenario();
        scene.id = "ppn-intent"; scene.name = "实体 PPN 接入验证"; scene.weapon = "rvp:probe";
        scene.target = new double[]{0, 164, 100}; scene.targetVelocity = new double[]{20, 0, 0};
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        Marker target = new Marker(EntityType.MARKER, flight.entity().level());
        target.setPos(0, 164, 100); target.setBoundingBox(new AABB(target.position(), target.position()));
        target.setDeltaMovement(1, 0, 0);
        // 调用正式活动配置解析，末段测试同样继承主段增益和总飞行计时。
        var context = new RVP_GuidanceRuntimeContext(flight.entity(), weapon.data(),
                RVP_GuidanceModelResolver.resolveActive(weapon.data(), phase));
        return new Sample(flight, target, context);
    }

    /** 逐分量比较实体已提交速度，不能只检查理想数学结果。 */
    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
