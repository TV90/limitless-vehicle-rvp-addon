package org.ywzj.rvp.flight;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.ywzj.rvp.flight.RVP_FlightTypes.Command;
import org.ywzj.rvp.flight.RVP_FlightTypes.Switch;

/** 通用实际执行边界：校验绑定，尽量修正可执行部分，再通过固定翼端口写一次完整输入。 */
public final class RVP_FlightExecutor {
    /** 每架载具最后已写入的代次与物理刻；每个服务器独立创建执行器。 */
    private final Map<UUID, Applied> applied = new HashMap<>();

    /** 执行端口由服务端载具适配器实现；测试可直接验证真实输入翻译合同。 */
    public interface Port {
        /** 当前载具UUID。 */ UUID vehicleId();
        /** 当前维度。 */ String dimension();
        /** 当前服务器物理刻。 */ long tick();
        /** 当前载具是否有效。 */ boolean available();
        /** 原授权来源和会话代次是否仍有效。 */ boolean owns(long generation);
        /** 是否处于本体地面转向分支。 */ boolean onGround();
        /** 当前实际俯仰输入步长。 */ double pitchStep();
        /** 当前实际偏航输入步长。 */ double yawStep();
        /** 当前实际滚转输入步长。 */ double rollStep();
        /** 本机油门硬上限。 */ double maximumThrottle();
        /** 是否有起落架部件。 */ boolean hasLandingGear();
        /** 是否有减速板部件。 */ boolean hasAirbrake();
        /** 写入本体字段；不得操作武器/功能字段。 */ void write(Frame frame);
        /** 清理本组件的运动残留，保持功能操作和当前油门。 */ void clear();
    }

    /** 已翻译成本体输入约定的数据；三轴setter值已预扣本体将叠加的键盘增量。 */
    public record Frame(
            /** 写入setPitchInput的值。 */ double pitchSetter,
            /** 写入setYawInput的值。 */ double yawSetter,
            /** 写入setRollInput的值。 */ double rollSetter,
            /** 本体左偏航键。 */ boolean leftYaw,
            /** 本体右偏航键。 */ boolean rightYaw,
            /** 明确油门百分比。 */ double throttle,
            /** 起落架指令。 */ Switch landingGear,
            /** 减速板指令。 */ Switch airbrake) {}

    /** 真实执行结果，不与轨迹到位/业务成功混淆。 */
    public enum Result {
        /** 输入已写入。 */ APPLIED,
        /** 已写入运动输入；时间、油门或缺失可选部件已按实际条件修正。 */ APPLIED_ADJUSTED,
        /** 请求已保存，内部等待控制权；业务不必重新申请或重复提交。 */ WAITING_FOR_CONTROL,
        /** 本刻端口暂时异常；请求保留，公共调度下一刻自动继续。 */ CONTINUING,
        /** 已无控制权。 */ NOT_OWNER,
        /** 本刻已经执行，重复调用视为已接受，禁止重复补偿。 */ ALREADY_APPLIED,
        /** 本体输入步长会写出非有限数值，不能继续污染实体。 */ INVALID_INPUT,
        /** 载具已终止或不存在。 */ VEHICLE_UNAVAILABLE;

        /** 是否在本次调用真实写入；修正执行同样属于已执行。 */
        public boolean applied() { return this == APPLIED || this == APPLIED_ADJUSTED; }
        /** 是否已接受且无需业务重试；等待/重复调用均由公共层自己处理。 */
        public boolean accepted() { return applied() || this == WAITING_FOR_CONTROL || this == CONTINUING || this == ALREADY_APPLIED; }
    }

    /** 仅记录执行身份，不能反过来当成当前授权。 */
    private record Applied(/** 控制代次。 */ long generation, /** 已执行刻。 */ long tick) {}

    /** 校验并翻译一次指令；真实运动仍由本体物理产生。 */
    public Result execute(Port port, Command command) {
        if (port == null || command == null || !port.available()) return Result.VEHICLE_UNAVAILABLE;
        if (!port.vehicleId().equals(command.vehicleId()) || !port.dimension().equals(command.dimension())
                || !port.owns(command.generation())) return Result.NOT_OWNER;
        long tick = port.tick();
        Applied last = applied.get(command.vehicleId());
        if (last != null && last.generation() == command.generation() && last.tick() >= tick)
            return Result.ALREADY_APPLIED;
        double pitchStep = port.pitchStep(), yawStep = port.yawStep(), rollStep = port.rollStep();
        if (!validStep(pitchStep) || !validStep(yawStep) || !validStep(rollStep)) return Result.INVALID_INPUT;
        // 调用公共修正补齐油门上限；缺少可选部件只跳过该部件，不拒绝运动输入。
        double maximumThrottle = RVP_FlightMath.bounded(port.maximumThrottle(), 100, 0, 1000);
        Switch landingGear = port.hasLandingGear() ? command.landingGear() : Switch.KEEP;
        Switch airbrake = port.hasAirbrake() ? command.airbrake() : Switch.KEEP;
        double throttle = Math.min(maximumThrottle, command.throttlePercent());
        boolean adjusted = tick != command.tick() || landingGear != command.landingGear()
                || airbrake != command.airbrake() || throttle != command.throttlePercent();

        boolean ground = port.onGround();
        boolean leftYaw = !ground || command.yaw() < -1.0E-6;
        boolean rightYaw = ground && command.yaw() > 1.0E-6;
        double signedYawStep = leftYaw ? yawStep : rightYaw ? -yawStep : 0;
        // 调用公共补偿：本体俯仰/偏航正值分别低头/左转，滚转正值则与公共右倾一致。
        double pitchSetter = compensated(-command.pitch(), -pitchStep);
        double yawSetter = ground && !leftYaw && !rightYaw ? 0 : compensated(-command.yaw(), signedYawStep);
        double rollSetter = compensated(ground ? 0 : command.roll(), -rollStep);
        Frame frame = new Frame(pitchSetter, yawSetter, rollSetter, leftYaw, rightYaw,
                throttle, landingGear, airbrake);
        // 在最终写入前再次核对代次，避免交接后旧请求清写新持有者。
        if (!port.owns(command.generation())) return Result.NOT_OWNER;
        port.write(frame);
        applied.put(command.vehicleId(), new Applied(command.generation(), tick));
        return adjusted ? Result.APPLIED_ADJUSTED : Result.APPLIED;
    }

    /** 只有仍持有此代次的调用者才可清运动输入；延迟释放不得影响新会话。 */
    public boolean release(Port port, long generation) {
        if (port == null || !port.owns(generation)) return false;
        port.clear();
        Applied last = applied.get(port.vehicleId());
        if (last != null && last.generation() == generation) applied.remove(port.vehicleId());
        return true;
    }

    /** 会话结束时清本代次消费记录；没有当前实体也可回收，不触碰任何运动字段。 */
    public void forget(UUID vehicleId, long generation) {
        Applied last = applied.get(vehicleId);
        if (last != null && last.generation() == generation) applied.remove(vehicleId);
    }

    /** 预扣本体下一次tickMove叠加的步长；不在setter前错误限幅。 */
    public static double compensated(double target, double signedStep) {
        // 调用公共修正，业务杆量越界取最近边界，缺失杆量取中立。
        target = RVP_FlightMath.bounded(target, 0, -1, 1);
        RVP_FlightMath.finite(signedStep, "signedStep");
        return target - signedStep;
    }

    /** 大/负步长也可按实际值预扣；只拦截会在本体float字段中变成无穷的步长。 */
    private static boolean validStep(double step) { return Double.isFinite(step) && Math.abs(step) <= Float.MAX_VALUE / 2.0; }
}
