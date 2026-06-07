package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 制导阶段专用导引头参数。写在 {@link RVP_GuidanceStageData#getSeeker()}，不再使用顶层 {@code seeker_data}。
 */
public class RVP_GuidanceSeekerData {

    @SerializedName("fov")
    private Float fov;

    @SerializedName("range")
    private Float range;

    @SerializedName("scan_interval_tick")
    private Integer scanIntervalTick;

    @SerializedName("lock_min_height")
    private Float lockMinHeight;

    @SerializedName("jam_resistance")
    private Float jamResistance;

    @SerializedName("ignore_flares")
    private Boolean ignoreFlares;

    @SerializedName("ignore_chaff")
    private Boolean ignoreChaff;

    @SerializedName("dircm_resistance")
    private Float dircmResistance;

    @SerializedName("home_on_jam")
    private Boolean homeOnJam;

    @SerializedName("decoy_filter")
    private Float decoyFilter;

    public float getFov() {
        return Math.max(fov != null ? fov : 30f, 1f);
    }

    public float getRange() {
        return Math.max(range != null ? range : 512f, 1f);
    }

    public int getScanIntervalTick() {
        return Math.max(scanIntervalTick != null ? scanIntervalTick : 2, 1);
    }

    public float getLockMinHeight() {
        return lockMinHeight != null ? lockMinHeight : 4f;
    }

    public float getJamResistance() {
        return Math.max(jamResistance != null ? jamResistance : 0f, 0f);
    }

    public boolean isIgnoreFlares() {
        return ignoreFlares != null && ignoreFlares;
    }

    public boolean isIgnoreChaff() {
        return ignoreChaff != null && ignoreChaff;
    }

    public float getDircmResistance() {
        return Math.max(dircmResistance != null ? dircmResistance : 0f, 0f);
    }

    public boolean isHomeOnJam() {
        return homeOnJam != null && homeOnJam;
    }

    public float getDecoyFilter() {
        return Math.max(decoyFilter != null ? decoyFilter : 0f, 0f);
    }

    public boolean isEmpty() {
        return fov == null && range == null && scanIntervalTick == null && lockMinHeight == null
                && jamResistance == null && ignoreFlares == null && ignoreChaff == null
                && dircmResistance == null && homeOnJam == null && decoyFilter == null;
    }

    public RVP_GuidanceSeekerData copy() {
        RVP_GuidanceSeekerData copy = new RVP_GuidanceSeekerData();
        copy.fov = this.fov;
        copy.range = this.range;
        copy.scanIntervalTick = this.scanIntervalTick;
        copy.lockMinHeight = this.lockMinHeight;
        copy.jamResistance = this.jamResistance;
        copy.ignoreFlares = this.ignoreFlares;
        copy.ignoreChaff = this.ignoreChaff;
        copy.dircmResistance = this.dircmResistance;
        copy.homeOnJam = this.homeOnJam;
        copy.decoyFilter = this.decoyFilter;
        return copy;
    }

    public void applyOverride(RVP_GuidanceSeekerData override) {
        if (override == null || override.isEmpty()) {
            return;
        }
        if (override.fov != null) {
            this.fov = override.fov;
        }
        if (override.range != null) {
            this.range = override.range;
        }
        if (override.scanIntervalTick != null) {
            this.scanIntervalTick = override.scanIntervalTick;
        }
        if (override.lockMinHeight != null) {
            this.lockMinHeight = override.lockMinHeight;
        }
        if (override.jamResistance != null) {
            this.jamResistance = override.jamResistance;
        }
        if (override.ignoreFlares != null) {
            this.ignoreFlares = override.ignoreFlares;
        }
        if (override.ignoreChaff != null) {
            this.ignoreChaff = override.ignoreChaff;
        }
        if (override.dircmResistance != null) {
            this.dircmResistance = override.dircmResistance;
        }
        if (override.homeOnJam != null) {
            this.homeOnJam = override.homeOnJam;
        }
        if (override.decoyFilter != null) {
            this.decoyFilter = override.decoyFilter;
        }
    }
}
