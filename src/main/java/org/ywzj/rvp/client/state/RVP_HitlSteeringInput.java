package org.ywzj.rvp.client.state;

/** 鼠标虚拟摇杆按实际经过时间回中，避免每个逻辑刻把刚输入的舵量突然削掉 40%。 */
public final class RVP_HitlSteeringInput {
    /** 沿用每 50 毫秒保留 60% 的原回中速度，单位为秒的倒数。 */
    private static final double DECAY_RATE = -Math.log(0.6) / 0.05;
    /** 本体鼠标角度换算比例。 */
    private static final double SENSITIVITY = 0.15;
    /** 水平、垂直舵量，单位度。 */
    private float yaw, pitch;
    /** 上次推进时间；单独记录初始化，System.nanoTime() 允许零或负数。 */
    private long lastNanos;
    private boolean initialized;

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public void reset() { yaw = pitch = 0; initialized = false; }

    /** 输入和发送前均推进到同一时间，渲染帧数不会改变回中速度。 */
    public void advance(long now) {
        if (initialized && now > lastNanos) {
            float decay = (float) Math.exp(-DECAY_RATE * ((now - lastNanos) / 1.0e9));
            yaw *= decay;
            pitch *= decay;
        }
        lastNanos = now;
        initialized = true;
    }

    /** 累加完整鼠标位移，只限制总舵量；不能按每帧 4 度裁切，否则低帧率时会丢输入。 */
    public void add(double dx, double dy, long now) {
        advance(now);
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
        yaw = (float) Math.max(-45, Math.min(45, yaw + dx * SENSITIVITY));
        pitch = (float) Math.max(-45, Math.min(45, pitch + dy * SENSITIVITY));
    }
}
