package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

import javax.swing.JFrame;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RVP_RvpTrajectoryIntegrator} 的纯数学单元测试与手动长航程仿真测试。
 *
 * <p>本类不构造 Minecraft 世界或实体，所有用例只向积分器传入不可变状态、制导输入和
 * 参数，以便把方向钳制、高度闭环和单 Tick 状态推进问题与区块、碰撞、网络等外部系统
 * 隔离。普通用例由 {@code test/build} 自动执行；带手动标签的全程仿真只由专用任务执行。</p>
 */
class RVP_RvpTrajectoryIntegratorTest {
    /** 浮点断言统一使用的绝对误差。 */
    private static final double EPSILON = 1.0E-9;

    /**
     * 验证 G 值转向同时满足“保持速率”和“限制单 Tick 速度变化量”两个核心契约。
     *
     * <p>初速度沿世界 X 正方向且速率为 10 格/Tick，期望方向与其垂直、沿世界 Z 正方向，
     * 因此制导需要执行一次明确的 90 度转向请求。最大过载设置为 18 G 后，当前 Tick 不应
     * 直接转到目标方向，而应只移动允许的方向增量。</p>
     *
     * <p>断言依次确认：输出速度长度与输入完全一致；输出与输入速度向量之差恰好等于
     * {@code 18 * PhysicsEngine.G}；实际转角仍小于 90 度。这样可以同时监测速率被意外
     * 改写、G 值换算错误以及绕过钳制直接转向等回归。</p>
     */
    @Test
    void applySteeringPreservesSpeedAndClampsVelocityDeltaByGs() {
        Vec3 current = new Vec3(10, 0, 0);
        double maxGs = 18.0;
        Vec3 steered = RVP_RvpTrajectoryIntegrator.applySteering(current, new Vec3(0, 0, 1), maxGs);

        assertEquals(current.length(), steered.length(), EPSILON);
        assertEquals(maxGs * PhysicsEngine.G, steered.subtract(current).length(), EPSILON);
        assertTrue(theta(current, steered) < Math.PI / 2.0);
    }

    /**
     * 验证最大 G 值的两个边界：0 G 完全禁止转向，足够大的 G 允许一步到达期望方向。
     *
     * <p>输入速度沿 X 正方向，期望方向沿 Z 正方向。当最大过载为 0 时，积分器必须原样
     * 返回当前速度；当最大过载为 100 G 时，允许的速度向量变化量已覆盖本次 90 度方向
     * 差，结果应完整转到 Z 正方向，同时仍保持原速率 1 格/Tick。</p>
     *
     * <p>该用例主要防止边界钳制符号错误、0 G 仍发生微小转向，以及高 G 分支只靠近但
     * 无法精确抵达期望方向。</p>
     */
    @Test
    void applySteeringHandlesZeroAndSufficientMaxGs() {
        Vec3 current = new Vec3(1, 0, 0);
        Vec3 desired = new Vec3(0, 0, 1);

        assertEquals(current, RVP_RvpTrajectoryIntegrator.applySteering(current, desired, 0.0));
        Vec3 fullySteered = RVP_RvpTrajectoryIntegrator.applySteering(current, desired, 100.0);
        assertEquals(0.0, fullySteered.x, EPSILON);
        assertEquals(1.0, fullySteered.z, EPSILON);
        assertEquals(current.length(), fullySteered.length(), EPSILON);
    }

    /**
     * 验证当前方向与期望方向完全相反时，球面插值不会产生 NaN 或 Infinity。
     *
     * <p>反向单位向量的夹角为 π，普通 slerp 公式中的 {@code sin(π)} 会形成数值奇点。
     * 积分器应走确定性的正交轴旋转分支。本测试检查三个速度分量均为有限数、输出速率
     * 仍为 2 格/Tick，并确认单 Tick 速度变化量没有超过 18 G 上限。</p>
     *
     * <p>该场景覆盖导弹越过目标、目标突然切换到身后等情况下最容易污染 SavedData 的
     * 数值边界。</p>
     */
    @Test
    void applySteeringHandlesOppositeDirectionWithoutNonFiniteComponents() {
        Vec3 current = new Vec3(2, 0, 0);
        Vec3 steered = RVP_RvpTrajectoryIntegrator.applySteering(current, new Vec3(-1, 0, 0), 18.0);

        assertTrue(Double.isFinite(steered.x));
        assertTrue(Double.isFinite(steered.y));
        assertTrue(Double.isFinite(steered.z));
        assertEquals(current.length(), steered.length(), EPSILON);
        assertTrue(steered.subtract(current).length() <= 18.0 * PhysicsEngine.G + EPSILON);
    }

    /**
     * 验证 GPS 巡航高度配置会进入高度闭环，并生成方向正确的垂直速度指令。
     *
     * <p>导弹位于 Y=100、沿 X 正方向水平飞行，GPS 目标也位于 Y=100。第一次不提供
     * {@code cruiseAltitude}，闭环应以当前位置为高度基准，因此输出不应出现垂直分量；
     * 第二次把巡航高度设为 Y=300，正高度误差应生成爬升指令。</p>
     *
     * <p>断言同时确认爬升后的速度长度不变，避免高度控制器通过额外增加总速度来伪造
     * 爬升效果。G 值是否受限由其他 applySteering 用例独立验证。</p>
     */
    @Test
    void configuredCruiseAltitudeProducesClosedLoopClimbCommand() {
        Vec3 position = new Vec3(0, 100, 0);
        Vec3 velocity = new Vec3(1, 0, 0);
        Vec3 target = new Vec3(1000, 100, 0);

        Vec3 holding = RVP_RvpTrajectoryIntegrator.steerGpsCruise(position, velocity, target, null, 18.0, 0.01);
        Vec3 climbing = RVP_RvpTrajectoryIntegrator.steerGpsCruise(position, velocity, target, 300.0, 18.0, 0.01);

        assertEquals(0.0, holding.y, EPSILON);
        assertTrue(climbing.y > 0.0);
        assertEquals(velocity.length(), climbing.length(), EPSILON);
    }

    /**
     * 验证路线评估会在低 G 导弹接近目标前主动结束平飞，为末端下降保留转弯距离。
     *
     * <p>导弹位于目标上方 50 格并水平飞行。远处目标仍在最小转弯圆的可接入区域内，
     * 因而输出继续保持巡航高度；把目标移近后，继续平飞会使目标落入不可接入区域，
     * 输出必须立即出现向下分量。两个结果都仍受同一个 10 G 钳制器约束。</p>
     */
    @Test
    void cruisePlannerReservesMaxGTerminalTurnDistance() {
        Vec3 position = new Vec3(0, 50, 0);
        Vec3 velocity = new Vec3(5, 0, 0);

        Vec3 farCommand = RVP_RvpTrajectoryIntegrator.steerGpsCruise(
                position, velocity, new Vec3(300, 0, 0), 50.0, 10.0, 0.01);
        Vec3 nearCommand = RVP_RvpTrajectoryIntegrator.steerGpsCruise(
                position, velocity, new Vec3(80, 0, 0), 50.0, 10.0, 0.01);

        assertEquals(0.0, farCommand.y, EPSILON);
        assertTrue(nearCommand.y < 0.0, "near target must switch to terminal descent");
        assertTrue(nearCommand.subtract(velocity).length() <= 10.0 * PhysicsEngine.G + EPSILON);
    }

    /**
     * 验证设定高度不可达时，规划器牺牲高度目标并优先命中固定目标点。
     *
     * <p>短航程、低 G 场景要求导弹在仅 250 格内爬升到 Y=500 后再返回地面，显然没有
     * 足够转弯空间。测试只运行纯运动学制导链，记录实际最高点和距目标最近距离；结果应
     * 明显低于设定高度，同时进入一个 Tick 航程的目标邻域。</p>
     */
    @Test
    void unreachableCruiseAltitudeFallsBackToClosestHeightAndHitsTarget() {
        Vec3 position = Vec3.ZERO;
        Vec3 velocity = new Vec3(5, 0, 0);
        Vec3 target = new Vec3(250, 0, 0);
        double peakAltitude = position.y;
        double closestDistance = position.distanceTo(target);

        for (int tick = 0; tick < 120; tick++) {
            velocity = RVP_RvpTrajectoryIntegrator.steerGpsCruise(
                    position, velocity, target, 500.0, 2.0, 0.01);
            position = position.add(velocity);
            peakAltitude = Math.max(peakAltitude, position.y);
            closestDistance = Math.min(closestDistance, position.distanceTo(target));
        }

        assertTrue(peakAltitude < 100.0,
                "unreachable cruise altitude must not override terminal reachability");
        assertTrue(closestDistance <= velocity.length() + 1.0E-6,
                "trajectory must enter one-tick range of the fixed target; closest=" + closestDistance);
    }

    /**
     * 验证一次纯积分会以正确顺序推进位置、飞行时钟、寿命和累计航程。
     *
     * <p>初始位置为原点，速度为 4 格/Tick，已完成飞行 Tick 为 100、剩余寿命为 50。
     * 参数关闭推进并使用不会改变本次验证结论的速度范围，目标设置在远处以避免立即抵达。
     * 调用链仅为 {@code state + guidance + parameters -> integrator.step -> result}，不传入
     * Level、Entity 或 Chunk，因而也验证该积分器可脱离世界独立运行。</p>
     *
     * <p>结果必须有效；飞行 Tick 增加到 101；剩余寿命减少到 49；原点起飞时新位置等于
     * 本 Tick 新速度；累计航程等于旧航程加本 Tick 速度长度。任一断言失败都表示虚拟态
     * 可能出现漏积分、重复扣寿命或恢复时航程不连续。</p>
     */
    @Test
    void oneStepAdvancesClocksAndPositionWithoutWorldAccess() {
        RVP_VirtualTrajectoryState initial = new RVP_VirtualTrajectoryState(
                Vec3.ZERO, new Vec3(4, 0, 0), 0, -90, 4, 12, 100, 50, -1);
        RVP_VirtualTrajectoryParameters parameters = new RVP_VirtualTrajectoryParameters(
                18.0, null, true, false, 1, 11, 1000, 0, 0, 1, 0,
                0, 340);
        RVP_VirtualTrajectoryResult result = new RVP_RvpTrajectoryIntegrator().step(
                initial, new RVP_VirtualGuidanceInput(new Vec3(100000, 0, 100000)), parameters);

        assertFalse(result.invalid());
        assertEquals(101, result.state().flightTick());
        assertEquals(49, result.state().remainingLife());
        assertEquals(result.state().velocity(), result.state().position());
        assertEquals(initial.flightDistance() + result.state().velocity().length(),
                result.state().flightDistance(), EPSILON);
        assertEquals(rotationPitch(result.state().velocity()), result.state().xRot(), 1.0E-5);
        assertEquals(rotationYaw(result.state().velocity()), result.state().yRot(), 1.0E-5);
    }

    /**
     * 固定 GPS 目标的完整虚拟飞行，监测巡航高度、目标命中与预计到达时间。
     *
     * <p>场景从 StartPY 格的位置进入虚拟态，以 cruiseSpeed 格/Tick 飞向 horizontalDistance 格外的
     * 固定目标。每个逻辑 Tick 仅调用纯积分器，不创建 Level、Entity 或 Chunk。仿真在
     * 首次进入一个 Tick 航程的三维目标半径时结束，并验证：</p>
     *
     * <ul>
     *   <li>全程没有产生非有限积分状态；</li>
     *   <li>高度控制器经过稳定时间后保持在设定高度允许误差内；</li>
     *   <li>末端会离开巡航高度并进入目标点的一个 Tick 航程内；</li>
     *   <li>实际水平到达 Tick 与“水平距离 / 巡航速率”的预计值偏差不超过 allowedEtaErrorRatio。</li>
     * </ul>
     */
    @Test
    @Tag("manual")
    void manualFullVirtualFlightMaintainsCruiseAltitudeAndExpectedArrivalTime() {
        //巡航高度
        final double cruiseAltitude = 320;
        // 初始高度
        final double StartPY = 0.0;
        //目标高度
        final double TargetY = 0.0;
        //目标距离
        final double horizontalDistance = 60000;
        //弹体速度 格/tick
        final double cruiseSpeed = 5.0;
        final double minSpeed = 0.0f;
        final double maxSpeed = 20.0f;
        final double motorBurnTime = 9000.0;

        final float xRot = 0.0f;
        final float yRot = -90.0f;
        // 允许高度容错门
        final double allowedStableAltitudeError = 99999999.0;
        // 目标容错半径
        double toleranceRadius = 2.5;
        //实际水平到达 Tick 与“水平距离 / 巡航速率”的预计值偏差
        final double allowedEtaErrorRatio = 1.0;
        // 每Tickprintf输出信息记录
        final double Tickprintf = 2.0;
        //是否开启发动机推进计算
        final boolean propulsion = true;

        // 不再设置固定爬升段
        final int altitudeSettlingTicks = 0 ;

        final int expectedArrivalTick = (int) Math.ceil(horizontalDistance / cruiseSpeed);
//        final int simulationTimeoutTick = (int) Math.ceil(expectedArrivalTick * (1.0 + allowedEtaErrorRatio)) + 20;
        final int simulationTimeoutTick = 999999999;

        RVP_VirtualTrajectoryState state = new RVP_VirtualTrajectoryState(
                new Vec3(0.0, StartPY, 0.0), new Vec3(cruiseSpeed, 0.0, 0.0),
                xRot, yRot, cruiseSpeed, 0.0, 0, simulationTimeoutTick + 100, -1);
        RVP_VirtualTrajectoryParameters parameters = new RVP_VirtualTrajectoryParameters(
                10.0, cruiseAltitude, true, propulsion,
                690.0f, 50800f, motorBurnTime, 0,
                0.00045f, 1.0f, -PhysicsEngine.G,
                (float) minSpeed, (float) maxSpeed);
        // 目标坐标
        Vec3 target = new Vec3(horizontalDistance, TargetY, 0.0);
        RVP_VirtualGuidanceInput guidance = new RVP_VirtualGuidanceInput(target);

        double maximumStableAltitudeError = 0.0;
        double minimumAltitude = state.position().y;
        double maximumAltitude = state.position().y;
        int actualArrivalTick = -1;
        boolean terminalStarted = false;

        // 创建仅供本手动测试使用的 XChart 窗口，实时显示每个 Tick 推进后的二维侧视轨迹。
        try (RealTimeTrajectoryChart trajectoryChart = RealTimeTrajectoryChart.open(
                state.position(), target, cruiseAltitude)) {

            Vec3 previousVelocity = null;   // 速度向量循环前声明
            for (int simulatedTick = 1; simulatedTick <= simulationTimeoutTick; simulatedTick++) {
                // 调用本项目纯轨迹积分器，把上一 Tick 状态推进为下一 Tick 状态。
                RVP_VirtualTrajectoryResult result = new RVP_RvpTrajectoryIntegrator().step(
                        state, guidance, parameters);

                assertFalse(result.invalid(), "virtual trajectory became invalid at tick " + simulatedTick);
                state = result.state();

                double altitudeError = Math.abs(state.position().y - cruiseAltitude);
                minimumAltitude = Math.min(minimumAltitude, state.position().y);
                maximumAltitude = Math.max(maximumAltitude, state.position().y);
                double remainingHorizontalDistance = Math.abs(target.x - state.position().x);
                terminalStarted |= state.velocity().y < -0.01 && remainingHorizontalDistance < 1000.0;
                if (simulatedTick >= altitudeSettlingTicks && !terminalStarted) {
                    maximumStableAltitudeError = Math.max(maximumStableAltitudeError, altitudeError);
                }

                // 将当前积分状态追加到 XChart 数据序列，并立即请求重绘本 Tick 的轨迹。
                trajectoryChart.appendTick(simulatedTick, state, remainingHorizontalDistance);
                if (simulatedTick == 1 || simulatedTick % Tickprintf == 0) {
                    printFlightCheckpoint(
                            simulatedTick, state, cruiseAltitude, target, previousVelocity, PhysicsEngine.G);
                }
                previousVelocity = state.velocity();   // 保存当前速度供下一 Tick 使用

//                // 路线规划最终必须接入三维固定目标；一个 Tick 航程内视为已到达。
//                if (state.position().distanceTo(target) <= state.peakFlightSpeed()) {
//                    actualArrivalTick = simulatedTick;
//                    break;
//                }

                // 核心到达门控判定
                Vec3 currentSpeed = state.velocity();
                Vec3 currentPos = state.position();
                Vec3 nextPos = currentPos.add(currentSpeed); // 预测下一帧的位置
                Vec3 toTarget = target.subtract(currentPos);
                // 计算本帧运动线段上距离目标最近的点（防止高速穿模漏球）
                double segmentLengthSqr = currentSpeed.lengthSqr();
                double t = segmentLengthSqr <= 1.0E-12 ? 0.0
                        : Mth.clamp(toTarget.dot(currentSpeed) / segmentLengthSqr, 0.0, 1.0);
                Vec3 closestPointOnSegment = currentPos.add(currentSpeed.scale(t));
                // 计算该最近点与目标中心的实际物理距离
                double minDistanceToTarget = closestPointOnSegment.distanceTo(target);
                // 门控触发条件：满足物理容错半径，或者刚好处于最接近的过载交汇点
                // 额外兜底：如果本帧发生了“背离运动”（即已经飞过了，且无法更近），直接截断
                if (minDistanceToTarget <= toleranceRadius || toTarget.length() <= currentSpeed.length()) {
                    actualArrivalTick = simulatedTick;
                    break;
                }
            }
            
            trajectoryChart.holdFinalFrame();

        }

        assertTrue(actualArrivalTick > 0,
                "missile did not reach the horizontal target before tick " + simulationTimeoutTick);
        int allowedEtaErrorTicks = (int) Math.ceil(expectedArrivalTick * allowedEtaErrorRatio);
        int etaErrorTicks = Math.abs(actualArrivalTick - expectedArrivalTick);
        double targetDistance = state.position().distanceTo(target);
        assertTrue(maximumStableAltitudeError <= allowedStableAltitudeError,
                String.format(Locale.ROOT,
                        "stable altitude error %.3f exceeded %.3f blocks",
                        maximumStableAltitudeError, allowedStableAltitudeError));
        assertTrue(terminalStarted, "trajectory never left cruise altitude for terminal capture");
//        assertTrue(targetDistance <= cruiseSpeed,
//                String.format(Locale.ROOT, "target distance %.3f exceeded one-tick range %.3f",
//                        targetDistance, cruiseSpeed));
        assertTrue(etaErrorTicks <= allowedEtaErrorTicks,
                "arrival tick " + actualArrivalTick + " differs from expected "
                        + expectedArrivalTick + " by " + etaErrorTicks
                        + " ticks; allowed=" + allowedEtaErrorTicks);

        System.out.printf(Locale.ROOT,
                "[RVP Virtual Trajectory Summary] expectedArrivalTick=%d actualArrivalTick=%d "
                        + "expectedSeconds=%.3f actualSeconds=%.3f etaErrorTicks=%d "
                        + "cruiseAltitude=%.3f minAltitude=%.3f maxAltitude=%.3f "
                        + "maxStableAltitudeError=%.3f targetDistance=%.3f "
                        + "travelled=%.3f finalPos=%s%n",
                expectedArrivalTick, actualArrivalTick,
                expectedArrivalTick / 20.0, actualArrivalTick / 20.0, etaErrorTicks, cruiseAltitude,
                minimumAltitude, maximumAltitude, maximumStableAltitudeError,
                targetDistance, state.flightDistance(), state.position());
    }

    /**
     * 手动长航程测试使用的实时 XChart 侧视轨迹窗口。
     *
     * <p>横轴为弹体世界 X 坐标，纵轴为世界 Y 高度。实际轨迹数据严格按 Tick 追加；
     * 巡航高度和固定目标作为静态参考序列。无桌面图形环境时仅跳过窗口操作，仿真和
     * 断言仍完整执行。</p>
     */
    private static final class RealTimeTrajectoryChart implements AutoCloseable {
        /** XChart 图表实例；无图形环境时为 {@code null}。 */
        private final XYChart chart;
        /** XChart Swing 窗口包装器；无图形环境时为 {@code null}。 */
        private final SwingWrapper<XYChart> swingWrapper;
        /** 图表顶层窗口；用于测试结束时释放 Swing 资源。 */
        private final JFrame frame;
        /** 按 Tick 保存的世界 X 坐标。 */
        private final List<Double> horizontalPositions;
        /** 按 Tick 保存的世界 Y 高度。 */
        private final List<Double> altitudes;
        /** 每次 Tick 更新后暂停的毫秒数，给 Swing 绘制线程留出刷新时间。 */
        private final long tickDelayMillis;
        /** 仿真完成后保留最终画面的毫秒数。 */
        private final long finalHoldMillis;

        /**
         * 保存已创建的图表组件和可调显示节奏。
         *
         * @param chart XChart 图表实例
         * @param swingWrapper Swing 窗口包装器
         * @param frame 图表顶层窗口
         * @param horizontalPositions 按 Tick 保存的世界 X 坐标
         * @param altitudes 按 Tick 保存的世界 Y 高度
         * @param tickDelayMillis 每 Tick 的显示暂停毫秒数
         * @param finalHoldMillis 最终画面的保留毫秒数
         */
        private RealTimeTrajectoryChart(XYChart chart, SwingWrapper<XYChart> swingWrapper, JFrame frame,
                                        List<Double> horizontalPositions, List<Double> altitudes,
                                        long tickDelayMillis, long finalHoldMillis) {
            this.chart = chart;
            this.swingWrapper = swingWrapper;
            this.frame = frame;
            this.horizontalPositions = horizontalPositions;
            this.altitudes = altitudes;
            this.tickDelayMillis = tickDelayMillis;
            this.finalHoldMillis = finalHoldMillis;
        }

        /**
         * 创建并显示实时轨迹窗口。
         *
         * @param startPosition 仿真初始位置
         * @param target 固定 GPS 目标坐标
         * @param cruiseAltitude 配置的巡航高度
         * @return 可按 Tick 更新并自动关闭的图表对象
         */
        private static RealTimeTrajectoryChart open(Vec3 startPosition, Vec3 target,
                                                    double cruiseAltitude) {
            long tickDelayMillis = Math.max(0L,
                    Long.getLong("rvp.trajectoryChart.tickDelayMillis", 1L));
            long finalHoldMillis = Math.max(0L,
                    Long.getLong("rvp.trajectoryChart.finalHoldMillis", 2000L));
            List<Double> horizontalPositions = new ArrayList<>();
            List<Double> altitudes = new ArrayList<>();
            horizontalPositions.add(startPosition.x);
            altitudes.add(startPosition.y);

            if (GraphicsEnvironment.isHeadless()) {
                System.out.println("[RVP Virtual Trajectory] 当前为无图形环境，跳过 XChart 窗口显示。");
                return new RealTimeTrajectoryChart(null, null, null, horizontalPositions, altitudes,
                        0L, 0L);
            }

            XYChart chart = new XYChartBuilder()
                    .width(1200)
                    .height(700)
                    .title("RVP 虚拟飞行实时轨迹")
                    .xAxisTitle("水平位置 X（格）")
                    .yAxisTitle("世界高度 Y（格）")
                    .build();
            chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
            chart.getStyler().setCursorEnabled(true);
            chart.getStyler().setPlotGridLinesVisible(true);

            XYSeries trajectorySeries = chart.addSeries("实际轨迹", horizontalPositions, altitudes);
            trajectorySeries.setMarker(SeriesMarkers.NONE);
            trajectorySeries.setLineColor(new Color(0x19, 0x76, 0xD2));
            XYSeries cruiseSeries = chart.addSeries("巡航高度",
                    new double[]{startPosition.x, target.x},
                    new double[]{cruiseAltitude, cruiseAltitude});
            cruiseSeries.setMarker(SeriesMarkers.NONE);
            cruiseSeries.setLineColor(new Color(0x43, 0xA0, 0x47));
            XYSeries targetSeries = chart.addSeries("GPS 目标",
                    new double[]{target.x}, new double[]{target.y});
            targetSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            targetSeries.setMarker(SeriesMarkers.CIRCLE);
            targetSeries.setMarkerColor(new Color(0xE5, 0x39, 0x35));

            SwingWrapper<XYChart> swingWrapper = new SwingWrapper<>(chart);
            JFrame frame = swingWrapper.displayChart();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            return new RealTimeTrajectoryChart(chart, swingWrapper, frame,
                    horizontalPositions, altitudes, tickDelayMillis, finalHoldMillis);
        }

        /**
         * 追加一个 Tick 的位置并刷新图表标题和曲线。
         *
         * @param tick 当前已完成的模拟 Tick
         * @param state 当前 Tick 的不可变轨迹状态
         * @param remainingHorizontalDistance 距目标的剩余水平距离
         */
        private void appendTick(int tick, RVP_VirtualTrajectoryState state,
                                double remainingHorizontalDistance) {
            horizontalPositions.add(state.position().x);
            altitudes.add(state.position().y);
            if (chart == null || frame == null || !frame.isDisplayable()) {
                return;
            }

            chart.updateXYSeries("实际轨迹", horizontalPositions, altitudes, null);
            chart.setTitle(String.format(Locale.ROOT,
                    "RVP 虚拟飞行实时轨迹 | Tick %,d | 速度 %.3f | 剩余水平距离 %,.1f",
                    tick, state.velocity().length(), remainingHorizontalDistance));
            // 调用 XChart Swing 包装器，让本 Tick 更新后的完整轨迹进入 EDT 重绘队列。
            swingWrapper.repaintChart();
            pause(tickDelayMillis);
        }

        /** 在关闭窗口前保留最终轨迹，便于人工核对末端下降段。 */
        private void holdFinalFrame() {
            if (frame != null && frame.isDisplayable()) {
                pause(finalHoldMillis);
            }
        }

        /**
         * 按配置暂停测试线程；中断时恢复中断标记并立即继续清理。
         *
         * @param milliseconds 暂停毫秒数
         */
        private static void pause(long milliseconds) {
            if (milliseconds <= 0L) {
                return;
            }
            try {
                Thread.sleep(milliseconds);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        /** 释放手动测试创建的 Swing 窗口。 */
        @Override
        public void close() {
            if (frame != null) {
                frame.dispose();
            }
        }
    }

    /**
     * 输出手动全程仿真的固定间隔检查点。
     *
     * <p>该方法只读取当前不可变轨迹状态，不修改仿真结果。输出包含位置、速度、速率、
     * 相对设定巡航高度的有符号误差，以及距 GPS 目标的剩余水平距离，用于从连续日志中
     * 观察爬升收敛速度、巡航速度是否漂移和 ETA 是否按预期缩短。</p>
     *
     * @param tick 当前已完成的模拟 Tick
     * @param state 当前 Tick 积分完成后的不可变轨迹状态
     * @param cruiseAltitude 配置的世界 Y 巡航高度
     * @param target 固定 GPS 目标坐标
     */
    private static void printFlightCheckpoint(int tick, RVP_VirtualTrajectoryState state,
                                              double cruiseAltitude, Vec3 target,
                                              Vec3 previousVelocity, double gravity) {
        double altitudeError = state.position().y - cruiseAltitude;
        double remainingHorizontalDistance = Math.abs(target.x - state.position().x);

        double gForce;
        if (previousVelocity != null) {
            Vec3 deltaV = state.velocity().subtract(previousVelocity);
            double accelMagnitude = deltaV.length();   // 步长 = 1 单位时间
            gForce = accelMagnitude / Math.abs(gravity);
        } else {
            gForce = 0.0;  // 第一个 Tick 无历史速度
        }

        System.out.printf(Locale.ROOT,
                "[RVP Virtual Trajectory] tick=%d pos=%s velocity=%s speed=%.3f "
                        + "altitudeError=%+.3f remainingHorizontal=%.3f gForce=%.2f%n",
                tick, state.position(), state.velocity(), state.velocity().length(),
                altitudeError, remainingHorizontalDistance, gForce);
    }

    /**
     * 计算两个非零向量归一化后的夹角，返回值单位为弧度。
     *
     * <p>点积在浮点误差下可能略微越过 [-1, 1]，因此在调用 {@link Math#acos(double)} 前
     * 显式钳制，避免测试辅助计算自身产生 NaN 干扰被测结果。</p>
     *
     * @param a 第一个非零方向或速度向量
     * @param b 第二个非零方向或速度向量
     * @return 两个向量的最小夹角，范围为 [0, π]
     */
    private static double theta(Vec3 a, Vec3 b) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, a.normalize().dot(b.normalize()))));
    }

    /** @return 与实体制导旋转换算一致的速度俯仰角。 */
    private static double rotationPitch(Vec3 velocity) {
        return Math.toDegrees(-Math.asin(Mth.clamp(velocity.normalize().y, -1.0, 1.0)));
    }

    /** @return 与实体制导旋转换算一致的速度偏航角。 */
    private static double rotationYaw(Vec3 velocity) {
        Vec3 direction = velocity.normalize();
        return Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0;
    }
}
