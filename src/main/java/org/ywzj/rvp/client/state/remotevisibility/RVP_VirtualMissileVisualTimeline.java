package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Entry;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 只计算显示的样本时间线；不依赖 Minecraft 实例，不修改弹道或目标代理。 */
public final class RVP_VirtualMissileVisualTimeline {
    /** 常规消息间隔对应的显示缓冲，单位刻。 */
    private static final double BUFFER_TICKS = 2;
    /** 超过此接收年龄后冻结位置与显示年龄，单位客户端动画刻。 */
    public static final int FREEZE_TICKS = 5;
    /** 超过此接收年龄停止新喷焰、灯光和尾烟。 */
    public static final int EFFECT_TIMEOUT_TICKS = 10;
    /** 超过此接收年龄删除显示对象。 */
    public static final int MODEL_TIMEOUT_TICKS = 20;
    /** 恢复等待最多十刻，等待时已经停止所有新持续效果。 */
    public static final int HANDOFF_TICKS = 10;
    /** 上一个服务端样本。 */
    private Entry previous;
    /** 最新服务端样本。 */
    private Entry latest;
    /** 上一个样本的服务端刻。 */
    private long previousServerTick;
    /** 最新样本的服务端刻。 */
    private long latestServerTick;
    /** 最近有效收包的客户端动画刻，暂停时不推进。 */
    private long receivedTick;
    /** 接收最新样本时的单调显示游标，单位服务端刻。 */
    private double cursorAtReceipt;
    /** 是否已经收到可插值的第二个样本。 */
    private boolean paired;
    /** 明确恢复后的模型等待标记。 */
    private boolean restored;
    /** 最近一次有效更新是否发生断流、跳点或暂停，通知外层同时断开两种尾烟设施。 */
    private boolean breaksTrail;

    /** 第一个样本原位起步，绝不从世界原点补线。 */
    public RVP_VirtualMissileVisualTimeline(Entry first, long serverTick, long clientTick) {
        previous = latest = first;
        previousServerTick = latestServerTick = serverTick;
        receivedTick = clientTick;
        cursorAtReceipt = serverTick;
    }

    /** 新序号由外层检查；此处仍拒绝旧服务端样本，同刻新状态原位替换而不创建零长段。 */
    public boolean accept(Entry entry, long serverTick, long clientTick) {
        if (restored || serverTick < latestServerTick || !entry.flightUuid().equals(latest.flightUuid())
                || entry.entityId() != latest.entityId() || !entry.weaponId().equals(latest.weaponId())) return false;
        double cursor = cursor(clientTick);
        boolean discontinuity = clientTick - receivedTick > FREEZE_TICKS
                || entry.position().distanceToSqr(latest.position()) > Math.pow(Math.max(64,
                latest.velocity().length() * (Math.max(0, serverTick - latestServerTick) + FREEZE_TICKS) * 2), 2);
        breaksTrail = discontinuity || paused(latest) || paused(entry);
        if (serverTick > latestServerTick && !discontinuity && !paused(latest) && !paused(entry)) {
            previous = latest;
            previousServerTick = latestServerTick;
            paired = true;
        } else if (discontinuity || paused(latest) || paused(entry)) {
            previous = entry;
            previousServerTick = serverTick;
            paired = false;
        }
        latest = entry;
        latestServerTick = serverTick;
        receivedTick = clientTick;
        cursorAtReceipt = paired ? Math.max(cursor, serverTick - BUFFER_TICKS) : serverTick;
        return true;
    }

    /** 恢复最终样本来自真实安全点，直接落位且停止一切新持续效果。 */
    public void restore(Entry finalSample, long serverTick, long clientTick) {
        previous = latest = finalSample;
        previousServerTick = latestServerTick = serverTick;
        receivedTick = clientTick;
        cursorAtReceipt = serverTick;
        paired = false;
        restored = true;
    }

    /** 查询没有副作用，多帧、多相机、HUD 与模型重复读取均不推进显示游标。 */
    public Pose sample(double clientTick) {
        double cursor = cursor(clientTick);
        Vec3 position;
        float yaw;
        float pitch;
        double flight;
        if (!paired || paused(latest) || restored) {
            position = latest.position(); yaw = latest.yaw(); pitch = latest.pitch(); flight = latest.flightTick();
        } else if (cursor <= latestServerTick && latestServerTick > previousServerTick) {
            double fraction = Mth.clamp((cursor - previousServerTick) / (latestServerTick - previousServerTick), 0, 1);
            position = previous.position().lerp(latest.position(), fraction);
            yaw = Mth.rotLerp((float) fraction, previous.yaw(), latest.yaw());
            pitch = Mth.rotLerp((float) fraction, previous.pitch(), latest.pitch());
            flight = Mth.lerp(fraction, previous.flightTick(), latest.flightTick());
        } else {
            double extrapolation = Mth.clamp(cursor - latestServerTick, 0, FREEZE_TICKS);
            position = latest.position().add(latest.velocity().scale(extrapolation));
            yaw = latest.yaw(); pitch = latest.pitch(); flight = latest.flightTick() + extrapolation;
        }
        return new Pose(position, yaw, pitch, (int) Math.min(Integer.MAX_VALUE, Math.max(0, flight)));
    }

    /** 当前发动机只消费最近权威阶段，不能预测二脉冲点火；暂停时燃烧事实和计数固定。 */
    public MotorPhase motorPhase(long clientTick) {
        if (!effectsFresh(clientTick) || latest.motorPhase() == MotorPhase.IDLE) return MotorPhase.IDLE;
        long elapsed = paused(latest) ? 0 : Math.max(0, clientTick - receivedTick);
        return elapsed <= latest.motorBurnRemainingTicks() ? latest.motorPhase() : MotorPhase.IDLE;
    }

    /** 当前燃烧窗口的亮度倒计时，独立于尾烟保持期。 */
    public int motorRemaining(long clientTick) { return remaining(latest.motorBurnRemainingTicks(), clientTick); }

    /** 原 ticksUntilMotorStopsBurning 语义的尾烟保持期，不据此推断是否点火。 */
    public int trailHoldRemaining(long clientTick) { return remaining(latest.trailHoldRemainingTicks(), clientTick); }

    /** 模型超时和恢复等待分别使用自己的明确上限。 */
    public boolean expired(long clientTick) {
        return clientTick - receivedTick > MODEL_TIMEOUT_TICKS
                || restored && clientTick - receivedTick >= HANDOFF_TICKS;
    }

    /** 是否仍允许新的持续效果；明确恢复时立即停止。 */
    public boolean effectsFresh(long clientTick) {
        return !restored && clientTick >= receivedTick && clientTick - receivedTick <= EFFECT_TIMEOUT_TICKS;
    }

    /** 暂停不在同一位置反复排烟，断流冻结后也不再追加烟段。 */
    public boolean canEmitTrail(long clientTick) {
        return !paused(latest) && clientTick - receivedTick <= FREEZE_TICKS
                && motorPhase(clientTick) != MotorPhase.IDLE;
    }

    /** 供拥有者解析身份和当前速度，不开放修改权威数据入口。 */
    public Entry latest() { return latest; }
    /** 恢复等待只保留最终模型，真实来源一旦到达即可立即删除它。 */
    public boolean restored() { return restored; }
    /** 断流后重开烟链，恢复收包不能连接过期尾烟。 */
    public boolean stale(long clientTick) { return clientTick - receivedTick > FREEZE_TICKS; }
    /** 大跳变不仅重置模型插值，还必须显式丢弃烟头和粒子待补队列。 */
    public boolean breaksTrail() { return breaksTrail; }

    /** 发射段加粗曲线只使用权威一级窗口，不能读取未初始化代理的 MAX_VALUE 默认燃尽刻。 */
    public float launchBoostFactor(float configuredBoost, double clientTick) {
        if (latest.motorPhase() != MotorPhase.PRIMARY || configuredBoost == 1) return 1;
        long endTick = Math.max(1L, (long) latest.flightTick() + latest.motorBurnRemainingTicks());
        float fade = Mth.clamp(1F - sample(clientTick).flightTick() / (float) endTick, 0, 1);
        return 1F + (configuredBoost - 1F) * fade;
    }

    /** 显示时间按收包后的动画刻推进，不以两端世界时钟相减猜网络延迟。 */
    private double cursor(double clientTick) {
        if (!paired || paused(latest) || restored) return latestServerTick;
        return Math.min(latestServerTick + FREEZE_TICKS,
                cursorAtReceipt + Mth.clamp(clientTick - receivedTick, 0, FREEZE_TICKS));
    }

    /** 只对已经开始的窗口递减，暂停时保持原样。 */
    private int remaining(int ticks, long clientTick) {
        long elapsed = paused(latest) ? 0 : Math.max(0, clientTick - receivedTick);
        return (int) Math.max(0, ticks - elapsed);
    }

    /** 恢复请求和等区块都视为暂停，速度非零也不能继续向前飞。 */
    private static boolean paused(Entry entry) { return entry.phase() != RVP_VirtualFlightPhase.VIRTUAL_CRUISE; }

    /**
     * @param position 全部视觉通道使用的同一双精度原点
     * @param yaw 最短角差插值后的机头偏航，单位度
     * @param pitch 最短角差插值后的机头俯仰，单位度
     * @param flightTick 纯显示年龄，不反写服务端或目标代理
     */
    public record Pose(Vec3 position, float yaw, float pitch, int flightTick) { }

    /** 连接内会话/序号门，不依赖客户端类型，可直接验证旧包和同刻包。 */
    public static final class ReceiptGate {
        /** 首个有效包绑定的会话，仅重连才清空。 */
        private UUID session;
        /** 各维度已应用的最高序号，换世界保留防旧包倒灌，重连清空。 */
        private final Map<ResourceLocation, Long> sequences = new HashMap<>();
        /** 各维度已接受的最新服务端采样刻，较新序号仍不能回退样本。 */
        private final Map<ResourceLocation, Long> serverTicks = new HashMap<>();

        /** 维度校验先于会话绑定，其他世界的包不能抢占当前连接。 */
        public boolean accept(S2CVirtualMissileVisualSnapshot message, ResourceLocation dimension) {
            if (!dimension.equals(message.dimension()) || session != null && !session.equals(message.sessionId())
                    || message.sequence() <= sequences.getOrDefault(dimension, -1L)
                    || message.serverGameTime() < serverTicks.getOrDefault(dimension, -1L)) return false;
            if (session == null) session = message.sessionId();
            sequences.put(dimension, message.sequence());
            serverTicks.put(dimension, message.serverGameTime());
            return true;
        }

        /** 断线或新连接才解绑定，任意不同会话包都不能主动重置当前缓存。 */
        public void disconnect() { session = null; sequences.clear(); serverTicks.clear(); }
    }
}
