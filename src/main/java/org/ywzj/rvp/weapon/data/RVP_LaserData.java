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

    /** 光束颜色、宽度、停火残影 tick 等，见 {@link RVP_LaserVisualData}。 */
    @SerializedName("visual_data")
    private RVP_LaserVisualData visualData = new RVP_LaserVisualData();

    public float getRange() {
        return Math.max(range, 1f);
    }

    public RVP_LaserVisualData getVisualData() {
        return visualData == null ? new RVP_LaserVisualData() : visualData;
    }
}
