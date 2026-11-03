package org.ywzj.rvp.projectile.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleState;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用权威武器与真实实体逐刻验证低速机头限幅和首次交会，禁止绕圈重逢掩盖脱靶。 */
class RVP_ProjectileInertiaSimulationTest {
    /** 本轮独立场景目录，不混入默认 052D 回放。 */
    private static final Path SCENARIOS = Path.of("src/test/resources/projectile-inertia-simulation");
    /** 只读取项目根的权威武器配置。 */
    private static final Path WEAPONS = Path.of("limitless_vehicle/rvp/data/rvp/weapons");

    /** 各种发射速度、离轴角与目标运动均须独立报告测试结果。 */
    @TestFactory
    List<DynamicTest> authoritativeMissilesRespectNoseCapAndFirstEncounter() throws IOException {
        try (var paths = Files.list(SCENARIOS)) {
            return paths.filter(path -> path.toString().endsWith(".json")).sorted()
                    .map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> verify(path))).toList();
        }
    }

    /** 真正推进游戏实体，直接测量机头总夹角与相对运动段，不以引信半径冒充几何误差。 */
    private void verify(Path path) throws IOException {
        // 调用现有场景与权威武器解析器，不复制武器参数或在生产代码中区分武器 ID。
        var scene = RVP_ProjectileSimulationMain.read(path);
        var weapon = RVP_ProjectileSimulationWeapon.read(WEAPONS, scene.weapon);
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        var before = flight.snapshot();
        Vec3 oldTarget = flight.targetPosition();
        double best = before.position().distanceTo(oldTarget);
        boolean angularMotionSeen = false;
        int closestTick = 0;
        for (int tick = 1; tick <= scene.ticks; tick++) {
            var data = weapon.data().getProjectileData();
            // 调用生产参数解析，使用当前制导刻、施力前速度与高度；上限期望值独立按公式计算。
            Float factor = data.resolveTurningFactor(before.flightTick() + 1);
            var limits = data.resolveAeroSteeringLimits(weapon.data().getProjectileVelocity(), before.position().y,
                    factor == null ? 0.5f : factor);
            double ratio = before.velocity().length() / limits.referenceSpeed();
            double pressure = Math.max(0.05, Math.min(1.0, limits.densityFactor() * ratio * ratio));
            double cap = 90.0 * (limits.rvpMaxGs() != null ? 0.5 : limits.turningFactor()) * pressure;
            // 调用真实实体制导、攻角兜底与运动入口；所有生产状态保持连续。
            var after = flight.step();
            Vec3 target = flight.targetPosition();
            var previousPose = new RVP_AttackAngleState(before.xRot(), before.yRot(), 0, 0);
            var nextPose = new RVP_AttackAngleState(after.xRot(), after.yRot(), 0, 0);
            double turn = Math.toDegrees(RVP_BallisticTrajectoryMath.angleBetween(previousPose.direction(), nextPose.direction()));
            assertTrue(turn <= cap + 5.0E-5, scene.id + " 第 " + tick + " 刻机头转角=" + turn + "，上限=" + cap);
            angularMotionSeen |= Math.abs(after.pitchRateDeg()) + Math.abs(after.yawRateDeg()) > 1.0E-8;
            // 独立计算相对运动段的最近距离，保证高速越过目标时也不漏掉首次交会。
            Vec3 relative = before.position().subtract(oldTarget);
            Vec3 delta = after.position().subtract(target).subtract(relative);
            double fraction = delta.lengthSqr() <= 1.0E-16 ? 0
                    : Math.max(0, Math.min(1, -relative.dot(delta) / delta.lengthSqr()));
            double miss = relative.add(delta.scale(fraction)).length();
            if (miss > best + 1.0E-6 && closestTick > 1) break;
            if (miss < best) { best = miss; closestTick = tick; }
            before = after;
            oldTarget = target;
        }
        assertTrue(angularMotionSeen, "必须实际推进双轴角速度，不能一直直飞通过限幅检查");
        // 此门槛比当前五格近炸半径更严；实体验证允许既有时间顺序/重力，纯数学 PIP 另守半格预算。
        assertTrue(best < 2.0, scene.id + " 首次交会误差=" + best);
        System.out.printf("INERTIA-ENTITY %s closestTick=%d firstMiss=%.9f%n", scene.id, closestTick, best);
    }
}
