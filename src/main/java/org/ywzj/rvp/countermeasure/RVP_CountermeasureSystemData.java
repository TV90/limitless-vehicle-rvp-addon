package org.ywzj.rvp.countermeasure;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 单套干扰物子系统配置（RVP_CountermeasureSystemData）。
 *
 * <p>字段说明见 {@code docs/plan/RVP干扰物重构数据模型/RVP 干扰物重构数据模型文档.md}
 * 的 RVP_CountermeasureSystemData 表。</p>
 */
public final class RVP_CountermeasureSystemData {

    /** 干扰物类型 FLARE/CHAFF，大小写不敏感；缺省由所属键决定。 */
    @SerializedName("type")
    private String type = "";

    /** 发射装置部件 id 列表（本体 WeaponUnit 部件），仅作为出膛点与发射动画锚点。 */
    @SerializedName("launcher_parts")
    private List<String> launcherParts = new ArrayList<>();

    /** 干扰物总数（弹舱容量），0 = 禁用该系统。 */
    @SerializedName("total")
    private int total = 32;

    /** 一轮发射数 m。 */
    @SerializedName("per_round")
    private int perRound = 4;

    /** 总发射轮数 n（一次按键最多发射的轮数）。 */
    @SerializedName("burst_rounds")
    private int burstRounds = 8;

    /** 轮间发射间隔（tick）。 */
    @SerializedName("launch_interval_tick")
    private int launchIntervalTick = 4;

    /** 装填时间（tick），从 0 装填到 total。 */
    @SerializedName("reload_tick")
    private int reloadTick = 200;

    /** 干扰物实体属性。 */
    @SerializedName("decoy")
    private RVP_CountermeasureDecoyData decoy = new RVP_CountermeasureDecoyData();

    /** 烟雾云属性（仅 SMOKE 生效）。 */
    @SerializedName("smoke")
    private RVP_CountermeasureSmokeData smoke = new RVP_CountermeasureSmokeData();

    /** 箔条对雷达锁定的干扰判定半径（格），仅对 CHAFF 生效。 */
    @SerializedName("radar_jam_radius")
    private float radarJamRadius = 8F;

    /** 被锁定目标周围箔条数 ≥ 该值时雷达脱锁，仅对 CHAFF 生效。 */
    @SerializedName("radar_jam_count")
    private int radarJamCount = 3;

    /** 脱锁后目标短时间内不能被雷达选中/锁定（仍可被扫描）的时长（tick），仅对 CHAFF 生效。 */
    @SerializedName("radar_jam_cooldown_tick")
    private int radarJamCooldownTick = 60;

    /**
     * 关联的 {@code bone_modules} 骨块名列表：非空时，对应骨块的 COUNTERMEASURE 模块<b>全部被击毁</b>
     * 则本系统失去抛洒功能；为空时不联动骨块（始终可用）。骨块需在载具 JSON 的
     * {@code bone_modules.<bone>.modules} 中声明 {@code "countermeasure"}。
     */
    @SerializedName("bone_modules")
    private List<String> boneModules = new ArrayList<>();

    /**
     * 允许使用本系统的座位索引列表（0 = 一号位/驾驶位，按部件声明顺序编号）：
     * 空/缺省时仅一号位可用；配置多个索引（如 {@code [0,1]}）时列出的座位均可用。
     * 非授权座位按键静默拒绝（服务端权威校验 + 客户端按键/UI 同步隐藏）。
     */
    @SerializedName("allowed_seat_indexes")
    private List<Integer> allowedSeatIndexes = new ArrayList<>();

    public String getRawType() {
        return type;
    }

    /** 解析类型；非法时回退到调用方提供的默认类型。 */
    public RVP_EnumCountermeasureType resolveType(RVP_EnumCountermeasureType fallback) {
        RVP_EnumCountermeasureType parsed = RVP_EnumCountermeasureType.byName(type);
        return parsed != null ? parsed : fallback;
    }

    public List<String> getLauncherParts() {
        return launcherParts == null ? List.of() : List.copyOf(launcherParts);
    }

    public int getTotal() {
        return Math.max(0, total);
    }

    public int getPerRound() {
        return Math.max(1, perRound);
    }

    public int getBurstRounds() {
        return Math.max(1, burstRounds);
    }

    public int getLaunchIntervalTick() {
        return Math.max(1, launchIntervalTick);
    }

    public int getReloadTick() {
        return Math.max(1, reloadTick);
    }

    public RVP_CountermeasureDecoyData getDecoy() {
        return decoy == null ? new RVP_CountermeasureDecoyData() : decoy;
    }

    public RVP_CountermeasureSmokeData getSmokeData() {
        return smoke == null ? new RVP_CountermeasureSmokeData() : smoke;
    }

    public float getRadarJamRadius() {
        return Float.isFinite(radarJamRadius) ? Math.max(0.0F, radarJamRadius) : 8F;
    }

    public int getRadarJamCount() {
        return Math.max(1, radarJamCount);
    }

    public int getRadarJamCooldownTick() {
        return Math.max(1, radarJamCooldownTick);
    }

    public List<String> getBoneModules() {
        return boneModules == null ? List.of() : List.copyOf(boneModules);
    }

    public List<Integer> getAllowedSeatIndexes() {
        return allowedSeatIndexes == null ? List.of() : List.copyOf(allowedSeatIndexes);
    }

    /** 当前座位是否允许使用本系统：未配置 {@code allowed_seat_indexes} 时仅一号位（0）可用。 */
    public boolean isSeatAllowed(int seatIndex) {
        return org.ywzj.rvp.util.RVP_SeatAccessHelper.isSeatAllowed(getAllowedSeatIndexes(), seatIndex);
    }

    /** 系统是否启用：总数为正且至少有一个发射装置部件。 */
    public boolean isEnabled() {
        return total > 0 && !getLauncherParts().isEmpty();
    }
}
