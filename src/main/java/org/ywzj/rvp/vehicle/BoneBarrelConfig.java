package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级炮管部件配置（{@code bone_modules} 骨块条目的 {@code barrel} 子对象）。
 *
 * <p>炮管模块的失效语义（用户 2026-09-28 定版：单档累计）：不由单发 {@code min_damage}
 * 直毁，而由 {@code RVP_BarrelDamageTable} 的<b>累计直击伤害</b>驱动——累计超过
 * {@link #threshold} 触发 BARREL 模块失效（进失效表，整个炮管所在武器站禁止射击）。
 * <b>无时间衰减、永久状态</b>（与引擎无衰减定版同构），唯一恢复途径是快修（清炮管累计）。
 * 默认阈值 300（实际到骨伤害口径，与引擎累计同源）。</p>
 *
 * @param threshold 炮管损坏累计伤害阈值（实际到骨伤害，默认 300）
 */
public record BoneBarrelConfig(
        float threshold
) {
    /** Java 缺省配置（JSON 未写 {@code barrel} 子对象但 modules 含 BARREL 时使用）。 */
    public static BoneBarrelConfig defaults() {
        return new BoneBarrelConfig(300f);
    }

    public static BoneBarrelConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return defaults();
        }
        JsonObject obj = element.getAsJsonObject();
        float threshold = GsonHelper.getAsFloat(obj, "threshold", 300f);
        if (!Float.isFinite(threshold) || threshold < 1f) {
            threshold = 300f;
        }
        return new BoneBarrelConfig(threshold);
    }
}
