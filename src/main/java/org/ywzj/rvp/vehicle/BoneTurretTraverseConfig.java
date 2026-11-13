package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 方向机配置。绑定本载具的武器站 ID，按实际到骨累计伤害分档。
 *
 * <p><b>单档</b>（{@code threshold_heavy} 为 0）：只有一级受损，不存在二级损坏；
 * 面板虚拟血量分母取 {@code threshold_light}。用于体积过大的炮塔式方向机——
 * 用户 2026-11-14 定版：步战/防空未配方向机者直接把炮塔当方向机，450 血一级、无二级。</p>
 *
 * @param partId 本体 parts 条目中的武器站 ID
 * @param thresholdLight 一级受损阈值，默认 200
 * @param thresholdHeavy 二级损坏阈值，默认 600；0 表示不设二级损伤
 */
public record BoneTurretTraverseConfig(String partId, float thresholdLight, float thresholdHeavy) {
    public static @Nullable BoneTurretTraverseConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        var object = element.getAsJsonObject();
        try {
            String partId = GsonHelper.getAsString(object, "part_id", "").trim();
            float light = GsonHelper.getAsFloat(object, "threshold_light", 200f);
            float heavy = GsonHelper.getAsFloat(object, "threshold_heavy", 600f);
            if (partId.isEmpty() || !Float.isFinite(light) || !Float.isFinite(heavy) || light <= 0
                    || heavy < 0 || (heavy > 0 && heavy <= light)) {
                return null;
            }
            return new BoneTurretTraverseConfig(partId, light, heavy);
        } catch (JsonParseException invalidConfig) {
            return null;
        }
    }

    public int stage(float accumulated) {
        if (thresholdHeavy > 0 && accumulated >= thresholdHeavy) return 2;
        return accumulated >= thresholdLight ? 1 : 0;
    }
}
