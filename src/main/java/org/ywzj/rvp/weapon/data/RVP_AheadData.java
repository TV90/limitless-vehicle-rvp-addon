package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

/**
 * `rvp:machinegun` 的 AHEAD 自动编程配置。JSON 键 `ahead_data`。
 *
 * <p>字段使用包装类型以区分“未配置”和“明确写值”，便于兼容旧顶层 `ahead_*` 键。</p>
 */
public class RVP_AheadData {

    @SerializedName("enabled")
    private Boolean enabled;

    @SerializedName("burst_offset_meters")
    private Float burstOffsetMeters;

    @SerializedName("require_lock")
    private Boolean requireLock;

    /** 低于该离地高度时不执行 AHEAD 空爆，避免对地过强。单位：米。 */
    @SerializedName("min_ground_clearance")
    private Float minGroundClearance;

    @Nullable
    public Boolean getEnabledOverride() {
        return enabled;
    }

    @Nullable
    public Float getBurstOffsetMetersOverride() {
        return burstOffsetMeters;
    }

    @Nullable
    public Boolean getRequireLockOverride() {
        return requireLock;
    }

    @Nullable
    public Float getMinGroundClearanceOverride() {
        return minGroundClearance;
    }
}
