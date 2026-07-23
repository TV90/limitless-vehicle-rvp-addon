package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标指示吊舱数据模型。JSON键 {@code targeting_pod_data}。
 * 适用于 {@code type: "rvp:targeting_pod"} 武器。
 */
public class RVP_TargetingPodData {

    /** 标记模式："entity"=仅实体标记, "block"=仅方块标记, "both"=同时执行 */
    @SerializedName("mode")
    private String mode = "entity";

    /** 实体标记扫描距离（格） */
    @SerializedName("spot_range")
    private float spotRange = 200f;

    /** 实体标记锥形半角（度） */
    @SerializedName("spot_angle")
    private float spotAngle = 15f;

    /** 标记持续时间（tick） */
    @SerializedName("mark_duration")
    private int markDuration = 600;

    /** 实体标记的目标类型过滤：vehicle/player/living */
    @SerializedName("target_filter")
    private List<String> targetFilter = new ArrayList<>(List.of("vehicle"));

    /** 方块标记射线最大距离（格），null时使用 laser_data.range */
    @SerializedName("block_range")
    private Float blockRange = null;

    /** 方块标记是否同时写入 GPS 目标点 */
    @SerializedName("write_gps_target")
    private boolean writeGpsTarget = true;

    /** 标记是否共享给同队玩家 */
    @SerializedName("team_share")
    private boolean teamShare = true;

    public String getMode() {
        return mode == null ? "entity" : mode;
    }

    public boolean isEntityMode() {
        String m = getMode();
        return "entity".equalsIgnoreCase(m) || "both".equalsIgnoreCase(m);
    }

    public boolean isBlockMode() {
        String m = getMode();
        return "block".equalsIgnoreCase(m) || "both".equalsIgnoreCase(m);
    }

    public float getSpotRange() {
        return Math.max(spotRange, 1f);
    }

    public float getSpotAngle() {
        return Math.max(spotAngle, 1f);
    }

    public int getMarkDuration() {
        return Math.max(markDuration, 1);
    }

    public List<String> getTargetFilter() {
        return targetFilter == null ? List.of("vehicle") : targetFilter;
    }

    public boolean isFilterVehicle() {
        return getTargetFilter().stream().anyMatch(s -> "vehicle".equalsIgnoreCase(s));
    }

    public boolean isFilterPlayer() {
        return getTargetFilter().stream().anyMatch(s -> "player".equalsIgnoreCase(s));
    }

    public boolean isFilterLiving() {
        return getTargetFilter().stream().anyMatch(s -> "living".equalsIgnoreCase(s));
    }

    public Float getBlockRange() {
        return blockRange;
    }

    public boolean isWriteGpsTarget() {
        return writeGpsTarget;
    }

    public boolean isTeamShare() {
        return teamShare;
    }
}
