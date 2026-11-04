package org.ywzj.rvp.projectile.simulation;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖来源读取、真实参数生效、生产积分器接入以及高速运动停止边界。 */
class RVP_ProjectileSimulationTest {
    /** 独立目录不改权威武器或用户已有回放。 */
    @TempDir Path temporary;

    @Test
    void changedWeaponJSONChangesMotionAndDigestWithoutTouchingTheSource() throws Exception {
        String original = """
                {"type":"rvp:machinegun","projectile_data":{"velocity":40,"gravity":-0.1,"drag":0.02}}
                """;
        Path file = temporary.resolve("probe.json"); Files.writeString(file, original);
        var slow = RVP_ProjectileSimulationWeapon.read(temporary, "rvp:probe");
        var scene = scene("rvp:probe"); scene.ticks = 2;
        var first = new RVP_ProjectileSimulation().run(scene, slow);
        // 炮弹第一刻先平移 40 格，第二刻才反映摩擦与重力，不能错套火箭弹顺序。
        assertArrayEquals(new double[]{0, 100, 40}, first.frames().get(1).objects().get(0).position(), 1.0E-5);
        assertEquals(99.9, first.frames().get(2).objects().get(0).position()[1], 1.0E-5);
        assertEquals(original, Files.readString(file));
        Files.writeString(file, original.replace("40", "80"));
        var fast = RVP_ProjectileSimulationWeapon.read(temporary, "rvp:probe");
        var second = new RVP_ProjectileSimulation().run(scene, fast);
        assertNotEquals(slow.sha256(), fast.sha256());
        assertEquals(80, second.frames().get(1).objects().get(0).position()[2], 1.0E-4);
    }

    @Test
    void omittedGroupsKeepProductionDefaultsAndDoNotReadOldGravityAliases() throws Exception {
        Files.writeString(temporary.resolve("probe.json"), """
                {"type":"rvp:bomb","gravity_scale":99}
                """);
        var weapon = RVP_ProjectileSimulationWeapon.read(temporary, "rvp:probe");
        assertEquals(10, weapon.data().getProjectileVelocity());
        assertEquals(1200, weapon.data().getLife());
        assertEquals(0, weapon.data().getGravity());
        var replay = new RVP_ProjectileSimulation().run(scene("rvp:probe"), weapon);
        assertTrue(replay.frames().get(1).objects().get(0).position()[1] < 100,
                "未配置重力的炸弹应采用现有炸弹步进的本体默认重力");
    }

    @Test
    void replayCopiesRealEntityStateAndKeepsEngineMetadata() {
        var source = JsonParser.parseString("""
                {"type":"rvp:missile","life":50,"projectile_data":{
                  "velocity":2,"has_rocket_engine":true,"mass":100,"fuel_mass":20,
                  "thrust":10,"motor_burn_time":20,"gravity":-0.01,"drag_coefficient":0.02,
                  "rvp_aero_steering":true,"rvp_attack_angle_limit_deg":30},
                 "guidance_data":{"guidance_type":"GPS"}}
                """).getAsJsonObject();
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", source, "0".repeat(64));
        var scene = scene("rvp:probe"); scene.target = new double[]{100, 100, 500}; scene.ticks = 1;
        // 直接读取真实实体运行结果，验证导出器没有修改坐标、速度或 Java 姿态。
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        var expected = flight.step();
        var replay = new RVP_ProjectileSimulation().run(scene, weapon);
        var actual = replay.frames().get(1).objects().get(0);
        assertArrayEquals(new double[]{expected.position().x, expected.position().y, expected.position().z}, actual.position(), 0);
        assertEquals(expected.xRot(), ((Number)actual.values().get("entityPitch")).floatValue());
        assertEquals(expected.yRot(), ((Number)actual.values().get("entityYaw")).floatValue());
        assertEquals(2, replay.schemaVersion());
        assertEquals("rvp-entity-flight", replay.physics().get("implementation"));
        assertEquals("RVP_MissileEntity", replay.physics().get("entityClass"));
    }

    @Test
    void stoppingConditionsDoNotRewriteEntityPositionOrVelocity() {
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", JsonParser.parseString("""
                {"type":"rvp:machinegun","life":0,"projectile_data":{"velocity":100}}
                """).getAsJsonObject(), "0".repeat(64));
        var scene = scene("rvp:probe"); scene.target = new double[]{0, 100, 50}; scene.targetRadius = 1;
        var replay = new RVP_ProjectileSimulation().run(scene, weapon);
        assertEquals(2, replay.frames().size());
        assertEquals(100, replay.frames().get(1).objects().get(0).position()[2], 1.0E-4);
        assertTrue(replay.events().stream().anyMatch(event -> event.type().equals("TARGET_REACHED")));
        scene.target = null; scene.pitch = -90;
        var ground = new RVP_ProjectileSimulation().run(scene, weapon);
        assertEquals(0, ground.frames().get(1).objects().get(0).position()[1], 1.0E-5);
        assertTrue(ground.events().stream().anyMatch(event -> event.type().equals("GROUND_CONTACT")));
        scene.pitch = 0;
        var expired = new RVP_ProjectileSimulation().run(scene, weapon);
        assertEquals("配置寿命结束", expired.frames().get(1).objects().get(0).values().get("status"));
        assertEquals(100, expired.frames().get(1).objects().get(0).position()[2], 1.0E-4);
    }

    @Test
    void sourcePathAndInvalidSceneAreRejectedBeforePublishingAnIndex() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RVP_ProjectileSimulationWeapon.read(temporary, "rvp:../probe"));
        assertThrows(IllegalArgumentException.class, () -> RVP_ProjectileSimulationWeapon.read(temporary, "rvp:_backup/probe"));
        assertThrows(IllegalArgumentException.class, () -> RVP_ProjectileSimulationWeapon.parse("rvp:probe",
                JsonParser.parseString("{\"type\":\"rvp:laser\"}").getAsJsonObject(), "0".repeat(64)));
        var scene = scene("rvp:probe"); scene.position = new double[]{0, Double.NaN, 0};
        assertThrows(IllegalArgumentException.class, scene::validate);
        Files.writeString(temporary.resolve("a.json"), "{\"schemaVersion\":2}");
        Path output = temporary.resolve("out");
        assertThrows(IllegalArgumentException.class, () -> RVP_ProjectileSimulationMain.main(new String[]{
                temporary.resolve("a.json").toString(), output.toString(), temporary.toString(), ""}));
        assertFalse(Files.exists(output.resolve("index.json")));
    }

    @Test
    void authoritativeExamplesAreDeterministicAndAllAirProjectileConfigurationsLoad() throws Exception {
        Path weapons = Path.of("limitless_vehicle/rvp/data/rvp/weapons");
        int count = 0;
        try (var files = Files.list(weapons)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!List.of("rvp:missile", "rvp:machinegun", "rvp:rocket", "rvp:bomb", "rvp:torpedo", "rvp:dispenser").contains(json.get("type").getAsString())) continue;
                String id = "rvp:" + path.getFileName().toString().replaceFirst("\\.json$", "");
                var loaded = RVP_ProjectileSimulationWeapon.read(weapons, id);
                var shortScene = scene(id); shortScene.ticks = 2; shortScene.guidance = false;
                if (loaded.kind() == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.TORPEDO) shortScene.waterY = 200.0;
                assertEquals("rvp-entity-flight", new RVP_ProjectileSimulation().run(shortScene, loaded).physics().get("implementation"));
                count++;
            }
        }
        assertTrue(count > 0);
        try (var files = Files.list(Path.of("src/test/resources/projectile-simulation"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var scene = RVP_ProjectileSimulationMain.read(path);
                var weapon = RVP_ProjectileSimulationWeapon.read(weapons, scene.weapon);
                var first = new RVP_ProjectileSimulation().run(scene, weapon);
                var second = new RVP_ProjectileSimulation().run(scene, weapon);
                assertEquals(RVP_ProjectileSimulationMain.JSON.toJson(first), RVP_ProjectileSimulationMain.JSON.toJson(second), scene.id);
                assertEquals(first.frames().size() - 1, first.frames().get(first.frames().size() - 1).tick());
            }
        }
    }

    @Test
    void simulationSourcesCannotFallBackToVirtualSegmentMath() throws Exception {
        var paths = new java.util.ArrayList<Path>();
        try (var files = Files.list(Path.of("src/test/java/org/ywzj/rvp/projectile/simulation"))) {
            paths.addAll(files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().endsWith("Test.java")).toList());
        }
        paths.add(Path.of("src/test/java/org/ywzj/rvp/entity/projectile/RVP_ProjectileEntitySimulation.java"));
        for (Path path : paths) {
            String source = Files.readString(path);
            assertFalse(source.contains("import org.ywzj.rvp.virtualflight"), path.toString());
            assertFalse(source.contains("import org.ywzj.rvp.guidance.trajectorymath.virtualguidance"), path.toString());
            assertFalse(source.contains("RVP_RvpTrajectoryIntegrator"), path.toString());
            assertFalse(source.contains("RVP_UnguidedBallisticMath.step"), "导出器不得绕过实体的分派和姿态：" + path);
        }
    }

    @Test
    void missingTargetStillAdvancesProductionGuidancePhaseWithoutInventingSteering() {
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", JsonParser.parseString("""
                {"type":"rvp:missile","projectile_data":{"velocity":2},
                 "guidance_data":{"guidance_type":"NONE","terminal_guidance":{
                   "guidance_type":"ARH","guidance_start_tick":2}}}
                """).getAsJsonObject(), "0".repeat(64));
        var scene = scene("rvp:probe"); scene.guidance = true;
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        assertEquals("MAIN", flight.step().phase());
        var state = flight.step();
        assertEquals("TERMINAL", state.phase());
        // 默认先搜索再交接；阶段已到末段，但未捕获时操纵源仍为原NONE，不凭空制造ARH制导。
        assertEquals(org.ywzj.rvp.guidance.RVP_EnumGuidanceType.NONE, flight.entity().getActiveSourceType());
        assertTrue(flight.entity().isTerminalSeekerHandoffPending());
        assertEquals(0, state.velocity().x, 1.0E-12);
    }

    @Test
    void waypointTargetFeedsActualEntityPositionAndOutgoingVelocityAtEveryTick() {
        // 调用当前武器解析器创建测试弹；只检查目标观测，不依赖特定权威武器 ID。
        var weapon = RVP_ProjectileSimulationWeapon.parse("rvp:probe", JsonParser.parseString("""
                {"type":"rvp:missile","life":200,"projectile_data":{"velocity":2},
                 "guidance_data":{"guidance_type":"NONE"}}
                """).getAsJsonObject(), "0".repeat(64));
        var scene = scene("rvp:probe"); scene.ticks = 80; scene.target = new double[]{100, 200, 300};
        scene.targetVelocity = new double[]{999, 999, 999}; scene.targetAcceleration = new double[]{10, 10, 10};
        scene.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{
                new RVP_ProjectileSimulationScenario.TargetPoint(20, new double[]{120, 200, 300}),
                new RVP_ProjectileSimulationScenario.TargetPoint(40, new double[]{120, 220, 280}),
                new RVP_ProjectileSimulationScenario.TargetPoint(60, new double[]{120, 220, 280})};
        // 调用场景脚本核对显式期望，防止对照实体和场景时共享错误而误通过。
        assertArrayEquals(new double[]{110, 200, 300}, scene.targetAt(10).position(), 0);
        assertArrayEquals(new double[]{0, 20, -20}, scene.targetAt(20).velocity(), 0);
        assertArrayEquals(new double[]{120, 210, 290}, scene.targetAt(30).position(), 0);
        assertArrayEquals(new double[]{120, 210.5, 289.5}, scene.targetAt(30.5).position(), 0);
        assertArrayEquals(new double[]{0, 0, 0}, scene.targetAt(40).velocity(), 0);
        assertArrayEquals(new double[]{120, 220, 280}, scene.targetAt(80).position(), 0);
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        for (int tick = 0; tick <= scene.ticks; tick++) {
            // 调用真实实体步进和目标读取器，确认生产制导观察到的不是仍在运行的恒加速度路线。
            if (tick > 0) flight.step();
            var expected = scene.targetAt(tick);
            assertEquals(new Vec3(expected.position()[0], expected.position()[1], expected.position()[2]), flight.targetPosition());
            var observed = flight.entity().getTargetEntity().getDeltaMovement().scale(20);
            assertArrayEquals(expected.velocity(), new double[]{observed.x, observed.y, observed.z}, 1.0E-12);
        }
        // 调用绝对时刻取样，末点后倒拖仍必须恢复首段观测。
        assertArrayEquals(new double[]{20, 0, 0}, scene.targetAt(0).velocity(), 0);
    }

    @Test
    void invalidWaypointTimesAndPositionsAreRejectedBeforeEntityCreation() {
        var scene = scene("rvp:probe"); scene.target = new double[]{0, 100, 500};
        for (double tick : new double[]{0, -1, 1.5, 6, Double.NaN, Double.POSITIVE_INFINITY}) {
            scene.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{new RVP_ProjectileSimulationScenario.TargetPoint(tick, scene.target)};
            assertThrows(IllegalArgumentException.class, scene::validate);
        }
        scene.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{
                new RVP_ProjectileSimulationScenario.TargetPoint(2, scene.target), new RVP_ProjectileSimulationScenario.TargetPoint(2, scene.target)};
        assertThrows(IllegalArgumentException.class, scene::validate);
        scene.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{new RVP_ProjectileSimulationScenario.TargetPoint(2, new double[]{0, Double.NaN, 0})};
        assertThrows(IllegalArgumentException.class, scene::validate);
        scene.targetPath = new RVP_ProjectileSimulationScenario.TargetPoint[]{new RVP_ProjectileSimulationScenario.TargetPoint(2, scene.target)};
        scene.target = null;
        assertThrows(IllegalArgumentException.class, scene::validate);
    }

    /** 稳定的短场景夹具，目标与发射姿态由各测试显式设置。 */
    private static RVP_ProjectileSimulationScenario scene(String weapon) {
        var scene = new RVP_ProjectileSimulationScenario();
        scene.id = "probe"; scene.name = "数值回归"; scene.weapon = weapon;
        scene.position = new double[]{0, 100, 0}; scene.groundY = 0; scene.ticks = 5;
        return scene;
    }
}
