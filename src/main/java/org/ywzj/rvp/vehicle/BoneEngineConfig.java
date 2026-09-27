package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级引擎部件配置（{@code bone_modules} 骨块条目的 {@code engine} 子对象）。
 *
 * <p>引擎模块的失效语义（用户 2026-09-27 最终定版，无衰减）与其它模块不同：不由单发
 * {@code min_damage} 直毁，而由 {@code RVP_EngineDamageTable} 的<b>累计直击伤害</b>分级驱动——
 * 累计超过 {@link #thresholdLight} 进入受损档（极速/转向上限 ×{@link #powerMultiplierDamaged}，
 * 动力/加速度恒 ×0.75——×0.5 连地面摩擦都克服不了，2026-09-28 用户定版）；
 * 超过 {@link #thresholdHeavy} 触发 ENGINE 模块失效（瘫痪档，全字段 ×0.0001，进失效表可维修恢复）。
 * <b>重创与瘫痪均为永久状态，无时间衰减</b>——受损不修复会一直保持，继续挨打升级为瘫痪，
 * 唯一恢复途径是快修（旧版 45 秒衰减窗已按用户定版移除）。全程不关发动机、不压 POWER——
 * 方向机/高低机（{@code hasPower()} 门）照常。</p>
 *
 * @param thresholdLight         受损档累计伤害阈值（窗口内累计直击伤害，默认 60）
 * @param thresholdHeavy         瘫痪档累计伤害阈值（跨过即 ENGINE 模块失效，默认 150）
 * @param powerMultiplierDamaged 受损档极速/转向上限倍率（默认 0.5，50→25 KPH）；动力/加速度
 *                               路不随此字段（恒 ≥0.75）；瘫痪档恒 0.0001
 */
public record BoneEngineConfig(
        float thresholdLight,
        float thresholdHeavy,
        float powerMultiplierDamaged
) {
    /** Java 缺省配置（JSON 未写 {@code engine} 子对象但 modules 含 ENGINE 时使用）。 */
    public static BoneEngineConfig defaults() {
        return new BoneEngineConfig(60f, 150f, 0.5f);
    }

    public static BoneEngineConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return defaults();
        }
        JsonObject obj = element.getAsJsonObject();
        float thresholdLight = GsonHelper.getAsFloat(obj, "threshold_light", 60f);
        float thresholdHeavy = GsonHelper.getAsFloat(obj, "threshold_heavy", 150f);
        float powerMultiplierDamaged = GsonHelper.getAsFloat(obj, "power_multiplier_damaged", 0.5f);
        if (thresholdHeavy < thresholdLight) {
            // 目的：保证"重损阈值 ≥ 受损阈值"的档位单调性，写反时自动抬升重损阈值
            thresholdHeavy = thresholdLight;
        }
        return new BoneEngineConfig(
                Math.max(0.1f, thresholdLight),
                Math.max(0.1f, thresholdHeavy),
                // 倍率下限 1e-4：与瘫痪档同款除零保护（0 会让本体公式 0/0=NaN 污染整车坐标）
                Math.max(1.0E-4f, Math.min(1f, powerMultiplierDamaged)));
    }
}
