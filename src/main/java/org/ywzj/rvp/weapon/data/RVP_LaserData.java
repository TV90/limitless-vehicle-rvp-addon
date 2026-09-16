package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * {@code rvp:laser} 专用参数。JSON 键 {@code laser_data}。
 * 伤害在服务端射线检测；光束绘制见 {@link RVP_LaserVisualData}（仅客户端）。
 */
public class RVP_LaserData {

    /** 射线最大射程（格）；亦用于瞄准吊舱射线长度。 */
    @SerializedName("range")
    private float range = 512f;

    /** 激光致盲：触发致盲所需命中次数；**默认 0 = 关闭致盲功能**（仅显式配置 >0 的激光生效）。 */
    @SerializedName("blind_hit_count")
    private int blindHitCount = 0;

    /** 激光致盲：命中累计窗口（tick，20tick=1秒），窗口内攒满次数才触发，超窗清零重计。 */
    @SerializedName("blind_hit_window_tick")
    private int blindHitWindowTick = 100;

    /** 激光致盲：致盲时长（tick）；0 = 关闭。触发时命中计数清零重新累计。 */
    @SerializedName("blind_duration_tick")
    private int blindDurationTick = 200;

    /** 光束颜色、宽度、停火残影 tick 等，见 {@link RVP_LaserVisualData}。 */
    @SerializedName("visual_data")
    private RVP_LaserVisualData visualData = new RVP_LaserVisualData();

    public float getRange() {
        return Math.max(range, 1f);
    }

    /** 激光致盲触发所需命中次数；默认 0 = 功能关闭。 */
    public int getBlindHitCount() {
        return blindHitCount;
    }

    /** 激光致盲命中累计窗口（tick）。 */
    public int getBlindHitWindowTick() {
        return Math.max(blindHitWindowTick, 1);
    }

    /** 激光致盲时长（tick）；0 = 关闭。 */
    public int getBlindDurationTick() {
        return Math.max(blindDurationTick, 0);
    }

    public RVP_LaserVisualData getVisualData() {
        return visualData == null ? new RVP_LaserVisualData() : visualData;
    }
}
