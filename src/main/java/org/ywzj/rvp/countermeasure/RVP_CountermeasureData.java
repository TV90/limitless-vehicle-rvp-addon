package org.ywzj.rvp.countermeasure;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

/**
 * 载具侧干扰物总配置（RVP_CountermeasureData），载具 JSON 顶层 {@code countermeasure} 块。
 *
 * <p>flare / chaff 各为一套独立子系统（互不共享弹药、各自独立状态机）。字段说明见
 * {@code docs/plan/RVP干扰物重构数据模型/RVP 干扰物重构数据模型文档.md} 的
 * RVP_CountermeasureData 总表。</p>
 */
public final class RVP_CountermeasureData {

    /** 热焰弹子系统配置；null 或 total=0 时禁用。 */
    @SerializedName("flare")
    private RVP_CountermeasureSystemData flare;

    /** 箔条子系统配置；null 或 total=0 时禁用。 */
    @SerializedName("chaff")
    private RVP_CountermeasureSystemData chaff;

    @Nullable
    public RVP_CountermeasureSystemData getFlare() {
        return flare;
    }

    @Nullable
    public RVP_CountermeasureSystemData getChaff() {
        return chaff;
    }

    /** 按类型取子系统；无配置返回 null。 */
    @Nullable
    public RVP_CountermeasureSystemData system(RVP_EnumCountermeasureType type) {
        return type == RVP_EnumCountermeasureType.FLARE ? flare : chaff;
    }

    /** 是否存在任一启用子系统。 */
    public boolean isEnabled() {
        return (flare != null && flare.isEnabled()) || (chaff != null && chaff.isEnabled());
    }

    /**
     * 从载具 JSON 解析干扰物配置；非对象或解析失败返回 null。
     *
     * <p>支持两种入参：<b>整个载具 JSON</b>（顶层 {@code countermeasure} 键，本仓库实际用法）
     * 或<b>直接传入 countermeasure 对象</b>（含 {@code flare}/{@code chaff}）。
     * 使用与本体武器数据一致的 {@link GsonUtil#GSON}（默认值由字段初值决定）。</p>
     */
    @Nullable
    public static RVP_CountermeasureData parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonElement target = element.getAsJsonObject().get("countermeasure");
        if (target == null) {
            target = element;
        }
        if (target == null || !target.isJsonObject()) {
            return null;
        }
        try {
            RVP_CountermeasureData data = GsonUtil.GSON.fromJson(target, RVP_CountermeasureData.class);
            if (data == null || !data.isEnabled()) {
                return null;
            }
            return data;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
