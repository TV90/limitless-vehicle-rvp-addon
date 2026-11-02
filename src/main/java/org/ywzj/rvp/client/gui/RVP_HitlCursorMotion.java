package org.ywzj.rvp.client.gui;

/** 指令线准星的纯视觉临界阻尼；输入只读，不向弹体或网络回写舵量。 */
public final class RVP_HitlCursorMotion {
    /** 达到最大视觉偏移的输入角，单位度。 */
    private static final double INPUT_RANGE = 12.0;
    /** 参考 1920×1080 画布上的最大视觉位移。 */
    private static final double MAX_OFFSET = 150.0;
    /** 临界阻尼角频率，单位秒的倒数。 */
    private static final double OMEGA = 14.0;
    /** 水平位移与速度，参考像素及参考像素/秒。 */
    private double x, vx;
    /** 垂直位移与速度，参考像素及参考像素/秒。 */
    private double y, vy;

    public double x() { return x; }
    public double y() { return y; }
    /** 切弹或退出时清除上一发的视觉惯性。 */
    public void reset() { x = y = vx = vy = 0; }
    /** 精确推进恒定输入下的临界阻尼，帧率改变不会改变回中速度。 */
    public void update(double yaw, double pitch, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return;
        double tx = target(yaw), ty = target(pitch), decay = Math.exp(-OMEGA * seconds);
        double cx = vx + OMEGA * (x - tx), cy = vy + OMEGA * (y - ty);
        x = tx + (x - tx + cx * seconds) * decay;
        y = ty + (y - ty + cy * seconds) * decay;
        vx = (vx - OMEGA * cx * seconds) * decay;
        vy = (vy - OMEGA * cy * seconds) * decay;
    }
    /** 防止非法输入或极端舵量把视觉准星推到屏幕外。 */
    private static double target(double angle) {
        return Double.isFinite(angle) ? Math.max(-1, Math.min(1, angle / INPUT_RANGE)) * MAX_OFFSET : 0;
    }
}
