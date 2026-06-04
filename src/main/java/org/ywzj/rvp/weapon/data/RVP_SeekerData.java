package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 导引头/传感器参数。制导源和反制判定都会读取这里。
 */
public class RVP_SeekerData {

    /** 搜索/锁定视场角，单位为度。 */
    @SerializedName("fov")
    private float fov = 30f;

    /** 搜索/锁定距离。 */
    @SerializedName("range")
    private float range = 512f;

    /** 搜索间隔 tick 数，越小越频繁。 */
    @SerializedName("scan_interval_tick")
    private int scanIntervalTick = 2;

    /** 雷达地杂波高度门限，低于该高度的目标更容易被过滤。 */
    @SerializedName("lock_min_height")
    private float lockMinHeight = 4f;

    /** 抗干扰能力预留值，后续可用于 ECM 概率或强度判定。 */
    @SerializedName("jam_resistance")
    private float jamResistance = 0f;

    /** 是否忽略热焰弹等红外假目标。 */
    @SerializedName("ignore_flares")
    private boolean ignoreFlares = false;

    /** 是否忽略箔条等雷达假目标。 */
    @SerializedName("ignore_chaff")
    private boolean ignoreChaff = false;

    /** 抗 DIRCM 能力预留值，供红外/激光反制判定使用。 */
    @SerializedName("dircm_resistance")
    private float dircmResistance = 0f;

    /** 雷达弹是否具备干扰源归向能力。 */
    @SerializedName("home_on_jam")
    private boolean homeOnJam = false;

    /** 假目标过滤能力预留值，数值越高越不容易被 decoy 欺骗。 */
    @SerializedName("decoy_filter")
    private float decoyFilter = 0f;

    public float getFov() {
        return Math.max(fov, 1f);
    }

    public float getRange() {
        return Math.max(range, 1f);
    }

    public int getScanIntervalTick() {
        return Math.max(scanIntervalTick, 1);
    }

    public float getLockMinHeight() {
        return lockMinHeight;
    }

    public float getJamResistance() {
        return Math.max(jamResistance, 0f);
    }

    public boolean isIgnoreFlares() {
        return ignoreFlares;
    }

    public boolean isIgnoreChaff() {
        return ignoreChaff;
    }

    public float getDircmResistance() {
        return Math.max(dircmResistance, 0f);
    }

    public boolean isHomeOnJam() {
        return homeOnJam;
    }

    public float getDecoyFilter() {
        return Math.max(decoyFilter, 0f);
    }
}
