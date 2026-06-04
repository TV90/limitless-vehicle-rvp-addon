package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * {@code rvp:laser} 专用：射程与客户端光束外观。
 */
public class RVP_LaserData {

    @SerializedName("range")
    private float range = 512f;

    @SerializedName("visual_data")
    private RVP_LaserVisualData visualData = new RVP_LaserVisualData();

    public float getRange() {
        return Math.max(range, 1f);
    }

    public RVP_LaserVisualData getVisualData() {
        return visualData == null ? new RVP_LaserVisualData() : visualData;
    }
}
