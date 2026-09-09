package org.ywzj.rvp.firesupport.delivery;

import java.lang.reflect.Field;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 共享单 Tick 积分与地射/空投反解的纯数学回归。 */
class RVP_FireSupportBallisticSolverTest {
    @Test
    void groundSolverFindsHighAndLowBranchesAtNominalSpeed() {
        RVP_WeaponData rocket = weapon(6.0, -0.045, 0.0, 6.0);
        Vec3 launch = new Vec3(-256.0, 70.0, -64.0);
        Vec3 target = new Vec3(0.0, 70.0, -64.0);
        RVP_FireSupportBallisticSolver.GroundSolution high =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        launch, target, 512.0);
        RVP_FireSupportBallisticSolver.GroundSolution low =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        launch, target, 0.0);
        assertNotNull(high);
        assertNotNull(low);
        assertTrue(high.launchAngleDegrees() >= low.launchAngleDegrees());
        assertTrue(high.predictionErrorMeters() <= RVP_FireSupportBallisticSolver.MAX_PREDICTION_ERROR_METERS);
        assertTrue(low.predictionErrorMeters() <= RVP_FireSupportBallisticSolver.MAX_PREDICTION_ERROR_METERS);
        assertTrue(Math.abs(high.initializedMotion().length() - 6.0) < 1.0E-6);
    }

    @Test
    void groundSolverUsesSurfaceReferenceInsteadOfGroundProximityFuseHeight() {
        RVP_WeaponData rocket = weapon(6.0, -0.045, 0.0, 6.0);
        Vec3 launch = new Vec3(-768.0, 70.0, -64.0);

        // 地表基准终点保留高/低弹道解；空爆弹也必须使用这一条相同的反解路径。
        RVP_FireSupportBallisticSolver.GroundSolution surfaceSolution =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        launch, new Vec3(0.0, 70.5, -64.0), 512.0);

        // M30（35 格）和 M30-WP（50 格）近地引信高度不是反解终点；作为终点会使该固定炮位无解。
        RVP_FireSupportBallisticSolver.GroundSolution m30FuseHeightSolution =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        launch, new Vec3(0.0, 105.0, -64.0), 512.0);
        RVP_FireSupportBallisticSolver.GroundSolution m30WpFuseHeightSolution =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        launch, new Vec3(0.0, 120.0, -64.0), 512.0);

        assertNotNull(surfaceSolution);
        assertNull(m30FuseHeightSolution);
        assertNull(m30WpFuseHeightSolution);
    }

    @Test
    void longImpactRegionFallsBackFromUnreachableAnchorRangeToPerRoundLaunchPoint() {
        RVP_WeaponData rocket = weapon(6.0, -0.045, 0.0, 6.0);
        Vec3 inbound = new Vec3(1.0, 0.0, 0.0);
        Vec3 anchorLaunch = RVP_GroundLaunchCandidateResolver.fromAnchor(0.0, -64.0, inbound, 768.0);
        Vec3 impact = new Vec3(256.0, 70.5, -64.0);

        RVP_FireSupportBallisticSolver.GroundSolution anchorSolution =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        new Vec3(anchorLaunch.x, 70.0, anchorLaunch.z), impact, 512.0);
        double fallbackDistance = RVP_GroundLaunchCandidateResolver.initialFallbackDistance(
                anchorLaunch, impact.x, impact.z, 768.0);
        Vec3 fallbackLaunch = RVP_GroundLaunchCandidateResolver.fromImpact(
                impact.x, impact.z, inbound, fallbackDistance);
        RVP_FireSupportBallisticSolver.GroundSolution fallbackSolution =
                RVP_FireSupportBallisticSolver.solveGround(rocket, RVP_EnumWeaponKind.ROCKET,
                        new Vec3(fallbackLaunch.x, 70.0, fallbackLaunch.z), impact, 512.0);

        assertNull(anchorSolution);
        assertEquals(768.0, fallbackDistance);
        assertNotNull(fallbackSolution);
    }

    @Test
    void fallbackCandidatesNeverIncreaseRangeAndReachExactMinimumAfterSixteenMeterSteps() {
        Vec3 inbound = new Vec3(1.0, 0.0, 0.0);
        Vec3 anchorLaunch = RVP_GroundLaunchCandidateResolver.fromAnchor(0.0, 0.0, inbound, 768.0);
        assertEquals(704.0, RVP_GroundLaunchCandidateResolver.initialFallbackDistance(
                anchorLaunch, -64.0, 0.0, 768.0));
        assertEquals(284.0, RVP_GroundLaunchCandidateResolver.nextFallbackDistance(300.0, 256.0));
        assertEquals(268.0, RVP_GroundLaunchCandidateResolver.nextFallbackDistance(284.0, 256.0));
        assertEquals(256.0, RVP_GroundLaunchCandidateResolver.nextFallbackDistance(268.0, 256.0));
        assertTrue(Double.isNaN(RVP_GroundLaunchCandidateResolver.nextFallbackDistance(256.0, 256.0)));
    }

    @Test
    void supersededLaunchChunkIsReleasedOnlyWhenNeitherImpactNorReplacementUsesIt() {
        var oldChunk = new net.minecraft.world.level.ChunkPos(1, 1);
        var nextChunk = new net.minecraft.world.level.ChunkPos(2, 1);
        assertTrue(RVP_GroundLaunchCandidateResolver.shouldReleaseSupersededLaunchChunk(
                oldChunk, nextChunk, new net.minecraft.world.level.ChunkPos(3, 1)));
        assertTrue(!RVP_GroundLaunchCandidateResolver.shouldReleaseSupersededLaunchChunk(
                oldChunk, oldChunk, new net.minecraft.world.level.ChunkPos(3, 1)));
        assertTrue(!RVP_GroundLaunchCandidateResolver.shouldReleaseSupersededLaunchChunk(
                oldChunk, nextChunk, oldChunk));
    }

    @Test
    void airSolverReleaseDistanceMatchesSharedBombIntegrator() {
        RVP_WeaponData bomb = weapon(0.1, -0.03, 0.0, 3.0);
        RVP_FireSupportBallisticSolver.AirSolution solution =
                RVP_FireSupportBallisticSolver.solveAirRelease(bomb, 320.0, 64.5, 2.5);
        assertNotNull(solution);
        assertTrue(solution.releaseDistanceMeters() > 0.0);

        Vec3 position = new Vec3(0.0, 320.0, 0.0);
        Vec3 velocity = new Vec3(2.5, 0.0, 0.0);
        double previousX = position.x;
        double previousY = position.y;
        for (int tick = 0; tick < solution.flightTicks(); tick++) {
            RVP_UnguidedBallisticMath.Step step =
                    RVP_UnguidedBallisticMath.stepBomb(position, velocity, bomb);
            position = step.position();
            velocity = step.velocity();
            if (position.y <= 64.5) {
                double fraction = (previousY - 64.5) / (previousY - position.y);
                double crossingX = previousX + (position.x - previousX) * fraction;
                assertTrue(Math.abs(crossingX - solution.releaseDistanceMeters()) < 1.0E-6);
                return;
            }
            previousX = position.x;
            previousY = position.y;
        }
        throw new AssertionError("炸弹未在预计 Tick 穿过目标高度");
    }

    @Test
    void airSolverUsesProjectileIntegratorForRocketAndMissileKinds() {
        RVP_WeaponData projectile = weapon(4.0, -0.03, 0.0, 4.0);
        RVP_FireSupportBallisticSolver.AirSolution rocket =
                RVP_FireSupportBallisticSolver.solveAirRelease(projectile, RVP_EnumWeaponKind.ROCKET,
                        320.0, 64.5, 2.5);
        RVP_FireSupportBallisticSolver.AirSolution missile =
                RVP_FireSupportBallisticSolver.solveAirRelease(projectile, RVP_EnumWeaponKind.MISSILE,
                        320.0, 64.5, 2.5);
        assertNotNull(rocket);
        assertNotNull(missile);
        assertTrue(rocket.releaseDistanceMeters() > 0.0);
        assertTrue(missile.releaseDistanceMeters() > 0.0);
    }

    @Test
    void actualAirSolverKeepsCarrierVerticalVelocityInWorldInitialMotion() {
        RVP_WeaponData weapon = weapon(4.0, -0.03, 0.0, 8.0);
        Vec3 release = new Vec3(0.0D, 320.0D, 0.0D);
        Vec3 target = new Vec3(220.0D, 64.5D, 40.0D);
        Vec3 carrierMotion = new Vec3(2.0D, 1.0D, 0.75D);

        for (RVP_EnumWeaponKind kind : new RVP_EnumWeaponKind[] {
                RVP_EnumWeaponKind.BOMB, RVP_EnumWeaponKind.ROCKET, RVP_EnumWeaponKind.MISSILE}) {
            RVP_FireSupportBallisticSolver.ActualAirSolution solution =
                    RVP_FireSupportBallisticSolver.solveActualAirRelease(
                            weapon, kind, release, target, carrierMotion);
            assertNotNull(solution, "当前载机速度下 " + kind + " 应能找到可重试的离散弹道");
            // 载机速度先参与世界速度，再由相对投射速度补偿误差；不能退化为旧实现的零竖直速度初值。
            assertTrue(Math.abs(solution.initializedMotion().y()) > 1.0E-6D,
                    "弹种 " + kind + " 丢失载机竖直速度");
            assertTrue(solution.predictionErrorMeters()
                    <= RVP_FireSupportBallisticSolver.MAX_PREDICTION_ERROR_METERS);
        }
    }

    private static RVP_WeaponData weapon(double velocity, double gravity, double drag, double maxSpeed) {
        RVP_WeaponData data = allocateWithoutVehicleRegistry();
        RVP_ProjectileData projectile = new RVP_ProjectileData();
        set(data, "projectileData", projectile);
        set(data, "life", 1200);
        set(projectile, "velocity", (float) velocity);
        set(projectile, "gravity", (float) gravity);
        set(projectile, "drag", (float) drag);
        set(projectile, "maxSpeed", (float) maxSpeed);
        return data;
    }

    /** 测试夹具只设置当前 schema 对应私有字段，避免引入生产代码 setter。 */
    private static void set(Object data, String name, Object value) {
        try {
            Class<?> owner = data.getClass();
            Field field = null;
            while (owner != null && field == null) {
                try {
                    field = owner.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    owner = owner.getSuperclass();
                }
            }
            if (field == null) throw new NoSuchFieldException(name);
            field.setAccessible(true);
            field.set(data, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /** 绕过本体武器默认物品字段的 Forge 注册表构造，仅用于纯数学单测夹具。 */
    private static RVP_WeaponData allocateWithoutVehicleRegistry() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (RVP_WeaponData) ((sun.misc.Unsafe) field.get(null)).allocateInstance(RVP_WeaponData.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
