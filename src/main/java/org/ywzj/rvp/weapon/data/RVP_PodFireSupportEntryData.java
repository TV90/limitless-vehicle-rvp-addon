package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

/**
 * 坐标指示吊舱内联火力支援条目（{@code targeting_pod_data.rvp_fire_support[]}，2026-10-13）。
 *
 * <p>每条目在数据加载期被 {@code RVP_PodFireSupportRegistry} 转换为一份运行时火力支援
 * profile（id 形如 {@code rvp:pod/<武器path>/<条目id>}），从而完整复用任务生态
 * （呼叫引导/排程/投送/冷却/幂等）。语义为精简单点打击：单武器/单投送/单点落区，
 * 不含终端 profile 的 patterns/fire_modes/multi-munition。</p>
 */
public class RVP_PodFireSupportEntryData {

    /** 条目 ID；同一武器内必须唯一（进入运行时 profile ID 与玩家选择持久化键）。 */
    @SerializedName("id")
    private String id;

    /** UI 显示名（中文）；吊舱地图工具直接显示本字符串。 */
    @SerializedName("display_CN")
    private String displayCn;

    /** 呼叫引导时长（Tick）：接受后进入 CALLING 阶段，期间可被玩家死亡/断线取消。 */
    @SerializedName("call_duration_ticks")
    private int callDurationTicks = 200;

    /** 请求冷却（Tick）：成功接受后对该玩家的下一次请求生效。 */
    @SerializedName("cooldown_ticks")
    private int cooldownTicks = 600;

    /** 目标点距玩家最大水平距离（格）；最小距离固定 0。 */
    @SerializedName("max_target_distance_m")
    private double maxTargetDistanceM = 2048.0;

    /** 单次呼叫最大弹数上限（保护值；实际弹数由 rounds_per_call 决定）。 */
    @SerializedName("max_rounds")
    private int maxRounds = 8;

    /** 使用的真实 RVP 武器资源 ID（如 {@code rvp:f14d_mk84}）。 */
    @SerializedName("weapon")
    private String weapon;

    /** 单次呼叫投送的弹数。 */
    @SerializedName("rounds_per_call")
    private int roundsPerCall = 1;

    /** 多发时相邻两弹的间隔（Tick）。 */
    @SerializedName("interval_ticks")
    private Integer intervalTicks = 40;

    /** 命中点散布半径（米）；point 几何参数 radius_m 的默认值。 */
    @SerializedName("scatter_radius_m")
    private double scatterRadiusM = 8.0;

    /** 独立入场方向（度）：弹体从发射点飞向目标的航向；空射投送建议配置（默认 0=北）。 */
    @SerializedName("inbound_heading_degrees")
    private Double inboundHeadingDegrees = 0.0;

    /** 投送定义：{@code type} = 投送工厂类型 ID，{@code data} = 投送参数（形态与终端 profile 的 delivery 完全一致）。 */
    @SerializedName("delivery")
    private JsonObject delivery;

    public String getId() {
        return id == null ? "" : id.trim();
    }

    public String getDisplayCn() {
        return displayCn == null || displayCn.isBlank() ? getId() : displayCn.trim();
    }

    public int getCallDurationTicks() {
        return Math.max(callDurationTicks, 20);
    }

    public int getCooldownTicks() {
        return Math.max(cooldownTicks, 0);
    }

    public double getMaxTargetDistanceM() {
        return Math.max(maxTargetDistanceM, 0.0);
    }

    public int getMaxRounds() {
        return Math.max(maxRounds, 1);
    }

    /** 解析武器 ID；格式非法返回 null（Registry 加载期记录问题并跳过）。 */
    public ResourceLocation getWeaponId() {
        if (weapon == null || weapon.isBlank()) {
            return null;
        }
        try {
            return ResourceLocation.parse(weapon.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    public int getRoundsPerCall() {
        return Math.max(roundsPerCall, 1);
    }

    public int getIntervalTicks() {
        return intervalTicks == null ? 40 : Math.max(intervalTicks, 1);
    }

    public double getScatterRadiusM() {
        return Math.max(scatterRadiusM, 0.0);
    }

    public double getInboundHeadingDegrees() {
        return inboundHeadingDegrees == null ? 0.0 : inboundHeadingDegrees;
    }

    /** 投送定义原始 JSON（含 type/data）；null 或缺 type 时条目无效。 */
    public JsonObject getDelivery() {
        return delivery;
    }

    /** @return 条目是否具备触发所需的最小字段。 */
    public boolean isValid() {
        return !getId().isEmpty() && getWeaponId() != null && delivery != null
                && delivery.has("type") && delivery.get("type").isJsonPrimitive();
    }
}
