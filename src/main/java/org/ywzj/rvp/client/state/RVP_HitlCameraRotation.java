package org.ywzj.rvp.client.state;

/** TV 镜头的高精度姿态插值；仅控制显示，不预测或改写弹体物理。 */
public final class RVP_HitlCameraRotation {
    /** 两个服务端姿态之间按一个逻辑刻过渡，避免原版字节角度的 1.40625 度台阶。 */
    private static final long INTERVAL_NANOS = 50_000_000L;
    /** 长时间断流后回退到既有实体镜头，不能无限保留旧姿态。 */
    private static final long STALE_NANOS = 250_000_000L;
    /** 最近快照的服务端刻与客户端到达时刻。 */
    private long tick = Long.MIN_VALUE, receivedAt;
    /** 本段起止姿态，水平角保持最短路径。 */
    private float fromYaw, fromPitch, toYaw, toPitch;

    /** 切弹、退出或世界切换时丢弃旧快照。 */
    public void clear() { tick = Long.MIN_VALUE; }

    /** 旧包和非有限姿态不进入镜头；新包从当前显示姿态平滑衔接。 */
    public void accept(long serverTick, float yaw, float pitch, long now) {
        if (serverTick <= tick || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        float previousYaw = fresh(now) ? yaw(now) : yaw;
        float previousPitch = fresh(now) ? pitch(now) : pitch;
        fromYaw = previousYaw;
        fromPitch = previousPitch;
        toYaw = previousYaw + wrap(yaw - previousYaw);
        toPitch = pitch;
        tick = serverTick;
        receivedAt = now;
    }

    /** 只在短期内使用高精度姿态。 */
    public boolean fresh(long now) {
        return tick != Long.MIN_VALUE && now >= receivedAt && now - receivedAt < STALE_NANOS;
    }

    public float yaw(long now) { return fromYaw + (toYaw - fromYaw) * alpha(now); }
    public float pitch(long now) { return fromPitch + (toPitch - fromPitch) * alpha(now); }

    private float alpha(long now) {
        return (float) Math.max(0.0, Math.min(1.0, (now - receivedAt) / (double) INTERVAL_NANOS));
    }

    private static float wrap(float value) {
        float result = value % 360f;
        return result >= 180f ? result - 360f : result < -180f ? result + 360f : result;
    }
}
