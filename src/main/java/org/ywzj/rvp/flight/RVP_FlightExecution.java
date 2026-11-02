package org.ywzj.rvp.flight;

import java.util.function.Supplier;
import org.ywzj.rvp.flight.RVP_FlightExecutor.Result;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;

/** 单会话的尽量执行流程；不依赖Minecraft，故障恢复也能用真实控制器和执行器验证。 */
final class RVP_FlightExecution {
    /** 所有会话可共享的无状态计算器。 */
    private static final RVP_FixedWingFlightController CONTROLLER = new RVP_FixedWingFlightController();
    /** 仅在真实写入后提交的控制状态。 */ private State state = State.empty();
    /** 最近真实观察，用于角速度；不会拿旧位置冒充新观察。 */ private Observation observation;
    /** 最近可用机体估算，读取暂时失败时继续使用。 */ private Airframe airframe;
    /** 最近执行反馈。 */ private Feedback feedback;
    /** 最近实际动作结果。 */ private Result result;
    /** 本刻异常摘要，供游戏层差分记录，不在纯公共层刷日志。 */ private String failure = "";

    /** true请求完整地形观察，false只读真实位置/速度/姿态；两者都不能伪造身份。 */
    @FunctionalInterface
    interface ObservationSource {
        /** 按本刻重新观察；previous只用于推导角速度。 */
        Observation read(Observation previous, boolean sampleTerrain);
    }

    /** 当前控制者尚未交还时只保存目标并反馈等待，不写别人的输入。 */
    void waiting(RVP_FlightRequest request, long tick) {
        result = Result.WAITING_FOR_CONTROL;
        feedback = new Feedback(Status.WAITING, "WAITING_FOR_CONTROL", request == null ? "stabilize" : request.goalId(),
                request == null ? 0 : request.revision(), tick, 0, 0, 0, 0, 0);
    }

    /** 每刻自动尝试原请求，失败时先降级观察/参数/稳飞；不把重试责任退回业务。 */
    void execute(RVP_FlightExecutor executor, RVP_FlightExecutor.Port port, long generation,
                 ObservationSource observations, Supplier<Airframe> airframes,
                 RVP_FlightRequest request, RVP_FlightTuning tuning, boolean stale) {
        failure = "";
        String adjustment = "";
        Observation fresh;
        try {
            try { fresh = observations.read(observation, true); }
            catch (RuntimeException exception) {
                // 调用只读姿态兜底，地形查询失败也能在同一刻稳飞并继续导航。
                failure = describe(exception); fresh = observations.read(observation, false);
                adjustment = "OBSERVATION_ESTIMATED";
            }
            if (fresh == null) throw new IllegalStateException("观察暂不可用");
        } catch (IllegalArgumentException exception) {
            // 真实坐标/姿态含NaN属于致命数值边界，继续写入会污染实体。
            failure = describe(exception); result = Result.INVALID_INPUT; feedback = null; return;
        } catch (RuntimeException exception) {
            // 调用内部继续流程，本刻无法读取实体时仍保留原请求，下一刻自动重试。
            continuing(port, generation, request, exception); return;
        }
        Airframe usable = airframe;
        try {
            Airframe current = airframes.get();
            if (current != null) { usable = current; airframe = current; }
            else adjustment = "AIRFRAME_ESTIMATED";
        } catch (RuntimeException exception) {
            failure = describe(exception); adjustment = "AIRFRAME_ESTIMATED";
        }
        // 调用公共保守估算，仅首次缺数据使用；后续仍每刻尝试真实机体参数。
        if (usable == null) { usable = Airframe.conservative(); adjustment = "AIRFRAME_ESTIMATED"; }
        try {
            Solution solution;
            boolean stabilizing = false;
            try {
                // 调用生产计算器持续推进原业务请求。
                solution = CONTROLLER.solve(fresh, usable, request, tuning, state);
            } catch (RuntimeException exception) {
                failure = describe(exception); stabilizing = true; adjustment = "CONTROL_FALLBACK_CONTINUING";
                // 调用缺省稳飞重新捕获；原请求仍由会话保存，下一刻首先尝试原目标。
                RVP_FlightRequest fallback = new RVP_FlightRequest(request == null ? "stabilize" : request.goalId(),
                        request == null ? 0 : request.revision(), null, null, request == null ? null : request.speed(),
                        request == null ? null : request.limits(), request == null ? null : request.landingGear(),
                        request == null ? null : request.airbrake());
                solution = CONTROLLER.solve(fresh, usable, fallback, RVP_FlightTuning.smoothCruise(), State.empty());
            }
            // 调用唯一写入边界；真实执行后才推进状态，不能把计算结果冒充飞机已执行。
            result = executor.execute(port, solution.command());
            if (result.applied()) {
                state = solution.nextState(); observation = fresh; feedback = solution.feedback();
                Status status = stabilizing ? Status.RECOVERING
                        : (!adjustment.isEmpty() || stale) && feedback.status() != Status.RECOVERING ? Status.LIMITED : feedback.status();
                String reason = feedback.reason();
                if (!adjustment.isEmpty()) reason += (reason.isEmpty() ? "" : ";") + adjustment;
                if (stale) reason += (reason.isEmpty() ? "" : ";") + "REQUEST_STALE_CONTINUING";
                if (result == Result.APPLIED_ADJUSTED) reason += (reason.isEmpty() ? "" : ";") + "EXECUTION_ADJUSTED";
                feedback = new Feedback(status, reason, feedback.goalId(), feedback.revision(), feedback.tick(),
                        feedback.altitudeError(), feedback.speedError(), feedback.headingError(),
                        feedback.requestedRadius(), feedback.effectiveRadius());
            } else if (result != Result.ALREADY_APPLIED) feedback = null;
        } catch (RuntimeException exception) {
            // 调用内部继续流程隔离端口暂时异常，不能要求业务重新申请会话。
            continuing(port, generation, request, exception);
        }
    }

    /** 暂停本刻残留操纵，保留目标和资格，由统一调度自动恢复。 */
    private void continuing(RVP_FlightExecutor.Port port, long generation, RVP_FlightRequest request, RuntimeException exception) {
        failure = describe(exception); result = Result.CONTINUING;
        try { if (port.owns(generation)) port.clear(); }
        catch (RuntimeException ignored) { /* 端口本身暂不可用，下一刻重新观察并写完整指令。 */ }
        feedback = new Feedback(Status.RECOVERING, "CONTROL_CONTINUING", request == null ? "stabilize" : request.goalId(),
                request == null ? 0 : request.revision(), port.tick(), 0, 0, 0, 0, 0);
    }

    /** 统一异常摘要，避免持有异常栈与游戏对象引用。 */
    private static String describe(RuntimeException exception) { return exception.getClass().getSimpleName() + ":" + exception.getMessage(); }
    /** 最近反馈，只读。 */ Feedback feedback() { return feedback; }
    /** 最近结果，只读。 */ Result result() { return result; }
    /** 本刻异常摘要，只读。 */ String failure() { return failure; }
}
