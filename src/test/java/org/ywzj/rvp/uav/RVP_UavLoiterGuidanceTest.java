package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定翼布尔杆量闭环制导与可持续盘旋解算的单元测试。
 * 符号约定：航向误差正值 = 期望 MC yaw 增大；zRot 负值 = 左坡度。
 */
class RVP_UavLoiterGuidanceTest {

    @Test
    void headingToVectorMatchesVanillaYawInAllQuadrants() {
        double[][] vectors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (double[] vector : vectors) {
            double yaw = Math.toRadians(RVP_UavLoiterGuidance.yawTo(vector[0], vector[1]));
            double lookX = -Math.sin(yaw);
            double lookZ = Math.cos(yaw);
            double dot = lookX * vector[0] + lookZ * vector[1];
            assertTrue(dot > 0, "目标航向必须朝向向量 " + vector[0] + "," + vector[1]);
        }
        assertEquals(-90.0, RVP_UavLoiterGuidance.yawTo(1, 0), 0.001);
        assertEquals(90.0, RVP_UavLoiterGuidance.yawTo(-1, 0), 0.001);
        assertEquals(0.0, RVP_UavLoiterGuidance.yawTo(0, 1), 0.001);
        assertEquals(180.0, Math.abs(Mth.wrapDegrees((float) RVP_UavLoiterGuidance.yawTo(0, -1))), 0.001);
    }

    @Test
    void axisCompensationProducesContinuousTargetOnEveryTick() {
        for (float step : new float[]{0.2F, 0.5F}) {
            for (float signedStep : new float[]{step, -step}) {
                for (float target : new float[]{-0.7F, 0.0F, 0.35F}) {
                    RVP_UavLoiterGuidance.CompensatedInput input =
                            RVP_UavLoiterGuidance.compensateInput(target, signedStep);
                    assertEquals(input.target(), input.setterValue() + signedStep, 1.0E-6F);
                }
            }
        }
    }

    @Test
    void fixedWingMinimumSpeedAccountsForScaleAirAndAngleOfAttack() {
        double lowAltitude = RVP_UavLoiterGuidance.estimateFixedWingMinSpeedMps(
                4074, 16.296, 24.444, 3.0, 1.0, 5.0, 25.0);
        double highAltitude = RVP_UavLoiterGuidance.estimateFixedWingMinSpeedMps(
                4074, 16.296, 24.444, 3.0, 0.7, 5.0, 25.0);
        double invalidAoA = RVP_UavLoiterGuidance.estimateFixedWingMinSpeedMps(
                4074, 16.296, 24.444, 3.0, 0.7, -10.0, 25.0);
        assertTrue(highAltitude > lowAltitude);
        assertTrue(invalidAoA > highAltitude);
    }

    @Test
    void loiterThrottleIsMutuallyExclusiveAndCollectivelyProtectsHighAltitudeOverspeed() {
        double targetSpeed = 32.0;
        RVP_UavLoiterGuidance.GuidanceOutput overspeedHigh = RVP_UavLoiterGuidance.computeLoiter(
                100, 120, 0, 0, 0, 64, 0, 100, 99, 0,
                false, 0, 0, targetSpeed * 1.2, 35, targetSpeed);
        assertFalse(overspeedHigh.forward());
        assertTrue(overspeedHigh.backward());

        RVP_UavLoiterGuidance.GuidanceOutput underspeedLow = RVP_UavLoiterGuidance.computeLoiter(
                100, 80, 0, 0, 0, 64, 0, 100, 99, -2,
                false, 0, 0, targetSpeed * 0.75, 35, targetSpeed);
        assertTrue(underspeedLow.forward());
        assertFalse(underspeedLow.backward());

        RVP_UavLoiterGuidance.GuidanceOutput highButFast = RVP_UavLoiterGuidance.computeLoiter(
                100, 130, 0, 0, 0, 64, 0, 100, 99, 0,
                false, 0, 0, targetSpeed * 1.15, 35, targetSpeed);
        assertFalse(highButFast.forward());
        // 实际36.8m/s，超过目标32m/s的1.08倍；高出目标31格只禁止爬升油门，不抑制超速收油。
        assertTrue(highButFast.backward());
    }

    @Test
    void approachOnlyCapturesWithRadialHeadingAltitudeAndSpeedConditions() {
        RVP_UavLoiterGuidance.GuidanceOutput captured = RVP_UavLoiterGuidance.computeApproach(
                100, 99, 0, 0, 0, 64, 0, 100, 99, 0,
                1, false, 0, 0, 30, 30);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.LOITER, captured.nextPhase());

        RVP_UavLoiterGuidance.GuidanceOutput wrongHeading = RVP_UavLoiterGuidance.computeApproach(
                100, 99, 0, 90, 0, 64, 0, 100, 99, 0,
                1, false, 0, 0, 30, 30);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.APPROACH, wrongHeading.nextPhase());
    }

    @Test
    void energyRecoveryTakesPriorityOverRadialErrorAndUsesAltitudeHysteresis() {
        double speed = 32.0;
        RVP_UavLoiterGuidance.GuidanceOutput outsideAndSlow = RVP_UavLoiterGuidance.computeLoiter(
                220, 80, 0, 0, 0, 64, 0, 100, 99, -2,
                false, 0, 50, speed * 0.7, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.RECOVERY, outsideAndSlow.nextPhase());
        assertTrue(outsideAndSlow.forward());
        assertFalse(outsideAndSlow.backward());

        RVP_UavLoiterGuidance.GuidanceOutput outsideAndDropping = RVP_UavLoiterGuidance.computeLoiter(
                220, 70, 0, 0, 0, 64, 0, 100, 99, -2,
                false, 0, 20, speed, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.RECOVERY, outsideAndDropping.nextPhase());
        RVP_UavLoiterGuidance.GuidanceOutput radialMustNotCancelRecovery = RVP_UavLoiterGuidance.computeRecovery(
                220, 70, 0, 0, 0, 64, 0, 100, 99, -2,
                1, 0, 20, speed, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.RECOVERY, radialMustNotCancelRecovery.nextPhase());

        RVP_UavLoiterGuidance.GuidanceOutput stillTooHigh = RVP_UavLoiterGuidance.computeRecovery(
                220, 108, 0, 0, 0, 64, 0, 100, 99, 0,
                1, 0, 0, speed, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.RECOVERY, stillTooHigh.nextPhase());
        assertFalse(stillTooHigh.forward());

        RVP_UavLoiterGuidance.GuidanceOutput stillTooLow = RVP_UavLoiterGuidance.computeRecovery(
                220, 50, 0, 0, 0, 64, 0, 100, 99, 0,
                1, 0, 0, speed * 1.2, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.RECOVERY, stillTooLow.nextPhase());
        assertFalse(stillTooLow.forward());
        assertTrue(stillTooLow.backward());

        RVP_UavLoiterGuidance.GuidanceOutput recovered = RVP_UavLoiterGuidance.computeRecovery(
                220, 99, 0, 0, 0, 64, 0, 100, 99, 0,
                1, 0, 0, speed, 35, speed);
        assertEquals(RVP_UavLoiterManager.LoiterPhase.APPROACH, recovered.nextPhase());
    }

    @Test
    void existingMarkedAndFollowPlansSurviveToggleSwitchbackAndNbtReload() {
        java.util.UUID uav = java.util.UUID.randomUUID();
        java.util.UUID parent = java.util.UUID.randomUUID();
        try {
            RVP_UavLoiterManager.enableMarkedCenter(uav, new Vec3(80, 120, -40), 100, 135);
            RVP_UavLoiterManager.disable(uav);
            // M 的 autoLoiterOnSwitchBack 走 enableFollowParent 时也必须保留原标点。
            RVP_UavLoiterManager.enableFollowParent(uav, parent, 180, 30, 1000, 64, -800);
            RVP_UavLoiterManager.LoiterState marked = RVP_UavLoiterManager.get(uav);
            assertTrue(marked.active());
            assertTrue(marked.markedCenter());
            assertEquals(null, marked.followParentUuid());
            assertEquals(80.0, marked.centerX(), 0.001);
            assertEquals(120.0, marked.centerY(), 0.001);
            assertEquals(-40.0, marked.centerZ(), 0.001);

            net.minecraft.nbt.CompoundTag saved = RVP_UavLoiterManager.serialize(marked);
            RVP_UavLoiterManager.clearRuntime();
            RVP_UavLoiterManager.restore(uav, saved);
            RVP_UavLoiterManager.LoiterState restored = RVP_UavLoiterManager.get(uav);
            assertTrue(restored.active());
            assertTrue(restored.markedCenter());
            assertEquals(100.0, restored.requestedRadius(), 0.001);
            assertEquals(135.0, restored.altitude(), 0.001);

            // 显式地图重标才把标记圆心改为新点并进入重新接入阶段。
            RVP_UavLoiterManager.updateCenter(uav, new Vec3(300, 150, 220), 150);
            RVP_UavLoiterManager.LoiterState recaptured = RVP_UavLoiterManager.get(uav);
            assertEquals(RVP_UavLoiterManager.LoiterPhase.APPROACH, recaptured.phase());
            assertEquals(300.0, recaptured.centerX(), 0.001);

            RVP_UavLoiterManager.clearRuntime();
            RVP_UavLoiterManager.enableFollowParent(uav, parent, 120, 35, 10, 64, 20);
            RVP_UavLoiterManager.pauseForSignal(uav);
            net.minecraft.nbt.CompoundTag followSaved = RVP_UavLoiterManager.serialize(
                    RVP_UavLoiterManager.get(uav));
            RVP_UavLoiterManager.clearRuntime();
            RVP_UavLoiterManager.restore(uav, followSaved);
            RVP_UavLoiterManager.LoiterState followRestored = RVP_UavLoiterManager.get(uav);
            assertTrue(followRestored.signalPaused());
            assertEquals(parent, followRestored.followParentUuid());
            assertEquals(64.0, followRestored.centerY(), 0.001);
            assertEquals(99.0, followRestored.altitude(), 0.001);
            assertTrue(RVP_UavLoiterManager.resumeExistingPlan(uav));
            assertEquals(parent, RVP_UavLoiterManager.get(uav).followParentUuid());
        } finally {
            RVP_UavLoiterManager.clearRuntime();
        }
    }

    @Test
    void deployableControlSeatPolicyPreservesDefaultAndExplicitAllowedSeats() {
        ResourceLocation vehicleId = new ResourceLocation("rvp", "carrier");
        RVP_DeployableUavConfig defaultSeats = new RVP_DeployableUavConfig(true, vehicleId, "uav",
                Vec3.ZERO, "parent", true, true, true, 0, true, 0, 2048, java.util.List.of());
        assertTrue(RVP_DeployableUavService.isDeployableControlSeatAllowed(defaultSeats, 0));
        assertFalse(RVP_DeployableUavService.isDeployableControlSeatAllowed(defaultSeats, 1));
        assertFalse(RVP_DeployableUavService.isDeployableControlSeatAllowed(defaultSeats, -1));

        RVP_DeployableUavConfig explicitSeat = new RVP_DeployableUavConfig(true, vehicleId, "uav",
                Vec3.ZERO, "parent", true, true, true, 0, true, 0, 2048, java.util.List.of(2));
        assertTrue(RVP_DeployableUavService.isDeployableControlSeatAllowed(explicitSeat, 2));
        assertFalse(RVP_DeployableUavService.isDeployableControlSeatAllowed(explicitSeat, 0));
    }

    @Test
    void positiveYawErrorUsesRightYawAndLeftRoll() {
        // FixedWingVehicle 中 rightYaw 令 yaw 增大；目标坡度 -40° 时滚转仍向左。
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                60f, -40f, 0f, 0.0, 0.0, 0);
        assertFalse(c.leftYaw());
        assertTrue(c.rightYaw());
        assertTrue(c.rollLeft());
        assertFalse(c.rollRight());
    }

    @Test
    void negativeYawErrorUsesLeftYawAndRightRoll() {
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                -60f, 40f, 0f, 0.0, 0.0, 0);
        assertFalse(c.rightYaw());
        assertTrue(c.leftYaw());
        assertTrue(c.rollRight());
        assertFalse(c.rollLeft());
    }

    @Test
    void bankClosedLoopReleasesRollAtTargetBank() {
        // 目标 -24°，当前 -25° → 坡度误差 1° < 死区 3° → 滚转杆释放（航向 40° 偏航杆仍置位）
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                40f, -24f, -25f, 0.0, 0.0, 0);
        assertFalse(c.leftYaw());
        assertTrue(c.rightYaw());
        assertFalse(c.rollLeft());
        assertFalse(c.rollRight());
    }

    @Test
    void bankOvershootCorrectsOppositely() {
        // 目标 -12°，当前 -20°（压过头）→ 坡度误差 +8° → rollRight
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                20f, -12f, -20f, 0.0, 0.0, 0);
        assertTrue(c.rollRight());
        assertFalse(c.rollLeft());
    }

    @Test
    void altitudeLoopTracksTargetClimbRateWithDamping() {
        // 高度误差 30 格 → 目标爬升率 +2.4 m/s；垂直速度 0 → 误差 +2.4 → up 脉冲
        RVP_UavLoiterGuidance.FixedWingControls climb = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 0.0, 0);
        assertTrue(climb.up());
        assertFalse(climb.down());
        // 已在 +2.4 m/s（垂直速度误差归零死区内）→ 双杆释放（阻尼，不再过冲飞天）
        RVP_UavLoiterGuidance.FixedWingControls damped = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 2.4, 0);
        assertFalse(damped.up());
        assertFalse(damped.down());
        // 爬升过冲到 +4 m/s → 误差 -1.6 超死区 → down 修正（旧版纯高度误差会继续 up）
        RVP_UavLoiterGuidance.FixedWingControls overshoot = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 4.0, 0);
        assertTrue(overshoot.down());
        assertFalse(overshoot.up());
    }

    @Test
    void loiterSolverKeepsConfiguredRadiusWhenLiftAllows() {
        // 大升力余量气动（v_min=20 m/s）：100 格半径临界坡度 asin(400/980)=24.1° < 40° 上限
        // → 配置半径可行，坡度取临界值，目标速度 = sqrt(100×9.8×tan24.1°) ≈ 43.8 且 ≥ v_min
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 20.0, 3.0);
        assertEquals(100.0, s.actualRadius(), 0.5);
        assertEquals(24.1, s.targetBankDeg(), 0.4);
        assertTrue(s.targetSpeedMps() >= 20.0);
    }

    @Test
    void loiterSolverFallsBackToPhysicalRadiusWhenLiftInsufficient() {
        // 高 v_min 气动（v_min=28.6 m/s，用户实测调参近似）：临界坡度 asin(818/980)=56.5° > 40° 上限
        // → 坡度封顶 40°，半径放大到 v_min²/(g·sin40°) ≈ 130 格（物理不可能就不再硬撑配置值）
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 28.6, 3.0);
        assertEquals(40.0, s.targetBankDeg(), 0.1);
        assertEquals(129.7, s.actualRadius(), 1.0);
        // 目标速度 = sqrt(129.7×9.8×tan40°) ≈ 32.7 m/s ≥ v_min——此速度下 40° 坡度恰好守得住高度
        assertTrue(s.targetSpeedMps() >= 28.6);
    }

    @Test
    void loiterSolverGeometricFallbackWithoutAeroData() {
        // v_min 不可得（≤0）：按当前空速几何反算；低速时配置半径可行
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 0, 1.2);
        assertEquals(100.0, s.actualRadius(), 0.5);
        assertTrue(s.targetBankDeg() >= 0);
    }

    @Test
    void tangentYawMatchesLoiterDirection() {
        // 圆东侧（dx=+100, dz=0）：右盘旋（顺时针）应朝南（yaw≈0），左盘旋朝北（yaw≈180）
        double right = RVP_UavLoiterGuidance.tangentYaw(100.0, 0.0, 1);
        double left = RVP_UavLoiterGuidance.tangentYaw(100.0, 0.0, -1);
        assertEquals(0.0, Mth.wrapDegrees((float) right), 0.5);
        assertEquals(180.0, Math.abs(Mth.wrapDegrees((float) left)), 0.5);
        // 圆南侧（dx=0, dz=+100）：右盘旋应朝西（yaw=90）
        double south = RVP_UavLoiterGuidance.tangentYaw(0.0, 100.0, 1);
        assertEquals(90.0, Mth.wrapDegrees((float) south), 0.5);
        // 切线必须与位置向量垂直（点积为 0）——防止再出"朝心/朝外"的假切线
        double t = Math.toRadians(RVP_UavLoiterGuidance.tangentYaw(60.0, 80.0, 1));
        double dot = (60.0 * -Math.sin(t)) + (80.0 * Math.cos(t));
        assertEquals(0.0, dot, 1.0E-6);
    }
}
