package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 子弹药释放云的椭球半径配置。
 */
public class RVP_SubmunitionReleaseCloudRadiusData {

    /**
     * 释放云 X/Z 水平轴半径，单位格，默认 {@code 4.0}；仅所属 release 的
     * {@code release_cloud_enabled=true} 时生效。负值按 {@code 0}，NaN 或 Infinity 回退默认值。
     */
    @SerializedName("horizontal")
    private float horizontal = 4.0f;

    /**
     * 释放云 Y 竖直轴半径，单位格，默认 {@code 4.0}；仅所属 release 的
     * {@code release_cloud_enabled=true} 时生效。负值按 {@code 0}，NaN 或 Infinity 回退默认值。
     */
    @SerializedName("vertical")
    private float vertical = 4.0f;

    public float getHorizontal() {
        return normalizeRadius(horizontal);
    }

    public float getVertical() {
        return normalizeRadius(vertical);
    }

    /**
     * 把单轴半径归一化为可安全参与服务端位置采样的有限非负数。
     */
    private static float normalizeRadius(float radius) {
        if (!Float.isFinite(radius)) {
            return 4.0f;
        }
        return Math.max(radius, 0.0f);
    }
}
