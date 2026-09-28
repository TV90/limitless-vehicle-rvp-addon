package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级炮管部件配置（{@code bone_modules} 骨块条目的 {@code barrel} 子对象）。
 *
 * <p>炮管模块的失效语义（用户 2026-09-28 定版，可选单档/两档累计）：不由单发 {@code min_damage}
 * 直毁，而由 {@code RVP_BarrelDamageTable} 的<b>累计直击伤害</b>驱动，且为<b>永久状态、无时间
 * 衰减</b>（与引擎无衰减定版同构），唯一恢复途径是快修/焊枪（清炮管累计）。</p>
 *
 * <p><b>单档</b>（未写 {@code threshold_light}）：累计 ≥ {@link #threshold} → 彻底损坏
 * （BARREL 进失效表，整个炮管所在武器站禁止射击）。<b>两档</b>（配置 {@code threshold_light}）：
 * 累计 ≥ {@code threshold_light} → <b>炮管受损</b>——此后每次射击三选一：①1/3 正常射击但
 * 散布 ×10；②1/3 哑火（不出弹）；③1/3 <b>炸膛</b>（炮管处爆炸特效 + 直接进入彻底损坏档）。
 * 继续累计 ≥ {@link #threshold} 也直接彻底损坏。</p>
 *
 * @param threshold      炮管彻底损坏累计伤害阈值（实际到骨伤害，默认 300）
 * @param thresholdLight 炮管受损档累计伤害阈值（≤0 = 单档，无受损档；两档须 ≤ threshold）
 */
public record BoneBarrelConfig(
        float threshold,
        float thresholdLight
) {
    /** Java 缺省配置（JSON 未写 {@code barrel} 子对象但 modules 含 BARREL 时使用）：单档。 */
    public static BoneBarrelConfig defaults() {
        return new BoneBarrelConfig(300f, 0f);
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
        float thresholdLight = GsonHelper.getAsFloat(obj, "threshold_light", 0f);
        if (!Float.isFinite(thresholdLight) || thresholdLight < 0f) {
            thresholdLight = 0f;
        }
        if (thresholdLight > threshold) {
            thresholdLight = threshold; // 单调性：受损阈不超过损坏阈
        }
        return new BoneBarrelConfig(threshold, thresholdLight);
    }

    /** 是否两档（配置了受损阈且小于损坏阈）。 */
    public boolean hasDamagedStage() {
        return thresholdLight > 0f && thresholdLight < threshold;
    }
}
