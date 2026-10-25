package org.ywzj.rvp.client.visual.shake;

import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * 每个调用方独立持有的震动参数调度器，只能在客户端主线程使用。
 * 负责事件去重、距离衰减、起止时间及取消；执行器负责实际的三轴计算与相机写入。
 * 同一调度器内先叠加、再乘实时倍率、最后限制角度；不同调度器分别限幅后相加。
 * 调用方应长期复用一个实例，不要为每次爆炸新建实例，否则无法共享去重和叠加上限。
 */
public final class RVP_ScreenShakeScheduler {
    /** 当前调用方尚未结束的脉冲；键由调用方提供，仅在本调度器内去重。 */
    private final Map<Object, Pulse> pulses = new HashMap<>();
    /** 每帧读取的强度倍率来源，可连接调用方自己的客户端设置。 */
    private final DoubleSupplier intensity;
    /** 本调用方所有脉冲叠加后，每轴允许的最大角度，单位度。 */
    private final float maxCombinedDegrees;

    public RVP_ScreenShakeScheduler(float maxCombinedDegrees, DoubleSupplier intensity) {
        if (!Float.isFinite(maxCombinedDegrees) || maxCombinedDegrees < 0.0F) {
            throw new IllegalArgumentException("震动叠加上限必须为有限非负角度");
        }
        this.maxCombinedDegrees = maxCombinedDegrees;
        this.intensity = Objects.requireNonNull(intensity, "震动强度来源不能为空");
    }

    /**
     * 提交一次震动，返回是否成功加入；同键活动事件不会被重启或替换。
     *
     * @param key 调用方的稳定事件键；同键可在旧脉冲结束后再次使用
     * @param now 当前世界时间，单位游戏刻
     * @param startGameTime 起始世界时间，单位游戏刻；可传未来时间延迟播放，或过去时间补帧
     * @param seed 稳定随机种子，用于确定三轴初始相位，即开始时的摆动位置
     * @param distance 接收者到震源的距离，单位格；负值或非有限值沿用原温压规则，视为零
     * @param range 影响距离，单位格；到达此距离时衰减为零，无效范围不创建震动
     * @param parameters 本次震动的持续时间、三轴振幅与频率
     */
    public boolean schedule(Object key, double now, double startGameTime, long seed,
            double distance, double range, RVP_ScreenShakeParameters parameters) {
        Objects.requireNonNull(key, "震动事件键不能为空");
        Objects.requireNonNull(parameters, "震动参数不能为空");
        if (!Double.isFinite(now) || !Double.isFinite(startGameTime)) {
            return false;
        }
        // 清除已结束的键，保证即使没有渲染帧也能重新提交同键事件。
        purgeExpired(now);
        // 复用线性距离衰减，温压调用方仍传入原来的影响范围。
        float distanceFactor = resolveDistanceFactor(distance, range);
        if (distanceFactor <= 0.0F || now - startGameTime >= parameters.durationTicks()) {
            return false;
        }
        if (pulses.putIfAbsent(key, new Pulse(startGameTime, seed, distanceFactor, parameters)) != null) {
            return false;
        }
        // 将有任务的调度器交给统一执行器，调用方无需自行订阅相机事件。
        RVP_ScreenShakeExecutor.activate(this);
        return true;
    }

    /** 撤销本调用方的一个活动事件，不影响其他调用方的同名键。 */
    public void cancel(Object key) {
        pulses.remove(key);
    }

    /** 清空本调用方的全部任务；执行器在下一帧移除空调度器。 */
    public void clear() {
        pulses.clear();
    }

    /** 按指定连续世界时间获取结果；由执行器调用，也用于脱离游戏画面的回归验证。 */
    public RVP_ScreenShakeExecutor.ShakeOffset sample(double now) {
        // 先清理到期任务，再让执行器按当前实时倍率计算叠加结果。
        purgeExpired(now);
        float scale = pulses.isEmpty() ? 0.0F : (float) intensity.getAsDouble();
        return RVP_ScreenShakeExecutor.execute(pulses.values(), now,
                Float.isFinite(scale) ? Math.max(0.0F, scale) : 0.0F, maxCombinedDegrees);
    }

    /** 判断是否仍有等待或执行中的任务，供执行器释放空调度器引用。 */
    boolean isEmpty() {
        return pulses.isEmpty();
    }

    /** 移除寿命已结束的脉冲，未来才开始的脉冲继续保留。 */
    private void purgeExpired(double now) {
        pulses.values().removeIf(pulse -> now - pulse.startGameTime() >= pulse.parameters().durationTicks());
    }

    /** 返回线性距离衰减；规则与抽取前的温压实现一致。 */
    public static float resolveDistanceFactor(double distance, double range) {
        if (!Double.isFinite(range) || range <= 0.0D) {
            return 0.0F;
        }
        double normalizedDistance = Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
        return (float) Mth.clamp(1.0D - normalizedDistance / range, 0.0D, 1.0D);
    }

    /**
     * 调度器交给执行器的一次震动任务。
     * @param startGameTime 开始的世界时间，单位游戏刻
     * @param seed 决定三轴初始相位的种子
     * @param distanceFactor 距离衰减倍率，范围 0 至 1
     * @param parameters 本次任务的不可变震动参数
     */
    record Pulse(double startGameTime, long seed, float distanceFactor,
            RVP_ScreenShakeParameters parameters) {
    }
}
