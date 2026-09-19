package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【已封印参数】{@code signal_intensity_factor_on_radar}（均匀雷达信号倍率，距离分区 Map）：
 * 2026-09-17 由 {@code ammo_radar_rcs_factor}（分角度）取代并<b>全量删除，刻意不再支持</b>。
 * 该参数极易与分角度因子、探测半径平方公式纠缠出单位/语义错误——2026-09-19 中继链
 * 探测半径塌缩为 √maxScan 的 BVR 失效事故即源于对它的重写（开发者 dtc10 与其奋战一晚，
 * 最终采取土办法封印：即使武器 JSON 里写了该键，Gson 也按未知键静默忽略，等效恒 1）。
 * 请勿在解析器或任何探测链中恢复此参数；放大探测距离请直接调大 ammo_radar_rcs_factor。
 */
public class RVP_MiscData {

    @SerializedName("missile_name_on_hud")
    private Map<RVP_Range<Float>, String> missileNameOnHud = createDefaultHudNames();

    @SerializedName("missile_name_on_radar")
    private Map<RVP_Range<Float>, String> missileNameOnRadar = createDefaultRadarNames();

    /**
     * 弹药分角度雷达信号因子 [迎头, 侧向, 尾向]（2026-09-17 取代旧
     * signal_intensity_factor_on_radar 均匀倍率）：雷达探测该弹药的距程 =
     * 雷达 max_scan_distance × 按弹体速度方向插值出的方向因子（与战机
     * rvp_radar_rcs_factor 同款 sin³ 曲线，字段名加 ammo 与载具区分）。
     * null = 未配置（等效 [1,1,1]）；单档钳 [0.01, 10]，可大于 1 增透。
     */
    @SerializedName("ammo_radar_rcs_factor")
    private float[] ammoRadarRcsFactor;

    @SerializedName("artillery_map")
    private boolean artilleryMap;

    public Map<RVP_Range<Float>, String> getMissileNameOnHud() {
        return missileNameOnHud == null ? createDefaultHudNames() : missileNameOnHud;
    }

    public Map<RVP_Range<Float>, String> getMissileNameOnRadar() {
        return missileNameOnRadar == null ? createDefaultRadarNames() : missileNameOnRadar;
    }

    /** 弹药分角度雷达信号因子；未配置返回 [1,1,1]，单档钳 [0.01, 10]，非法回退 1。 */
    public float[] getAmmoRadarRcsFactor() {
        float[] resolved = clampAmmoRadarRcsFactor(ammoRadarRcsFactor);
        return resolved != null ? resolved : new float[]{1.0f, 1.0f, 1.0f};
    }

    /** 弹药是否配置了分角度雷达信号因子（未配置走 [1,1,1] 中性路径）。 */
    public boolean hasAmmoRadarRcsFactor() {
        return clampAmmoRadarRcsFactor(ammoRadarRcsFactor) != null;
    }

    @Nullable
    private static float[] clampAmmoRadarRcsFactor(float[] factor) {
        if (factor == null || factor.length < 3) {
            return null;
        }
        float[] clamped = new float[3];
        for (int i = 0; i < 3; i++) {
            float value = factor[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return null;
            }
            clamped[i] = Math.max(0.01f, Math.min(10.0f, value));
        }
        return clamped;
    }

    public boolean isArtilleryMap() {
        return artilleryMap;
    }

    @Nullable
    public String resolveMissileNameOnHud(float distance) {
        return resolveByRange(getMissileNameOnHud(), distance, null);
    }

    /**
     * 返回导弹在 HUD 上显示的名称，超出配置范围时回退至上一个非 null 区间的值。
     * 用于自己最晚发射的导弹超距离跟踪时保持文案不断。
     */
    @Nullable
    public String resolveMissileNameOnHudWithFallback(float distance) {
        String resolved = resolveMissileNameOnHud(distance);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        // 超出所有配置范围或当前区间值为 null，往前找最后一个非 null 的区间值
        float sample = normalizeDistance(distance);
        String lastNonNull = null;
        for (Map.Entry<RVP_Range<Float>, String> entry : getMissileNameOnHud().entrySet()) {
            RVP_Range<Float> range = entry.getKey();
            if (range == null) continue;
            // 只看上界 <= 当前距离的区间（即该范围完全在当前距离之前）
            Float upperBound = resolveRangeUpperBound(range);
            if (upperBound != null && upperBound <= sample) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    lastNonNull = entry.getValue();
                }
            }
        }
        return lastNonNull;
    }

    /**
     * 返回范围的上界（所有区间上界的最大值），若任一区间上界为 null（正无穷）则返回 null。
     */
    @Nullable
    private static Float resolveRangeUpperBound(RVP_Range<Float> range) {
        if (range == null) return null;
        Float maxUpper = null;
        for (RVP_Range.Interval<Float> interval : range.intervals()) {
            if (interval == null) continue;
            Float upper = interval.upper();
            if (upper == null) return null;
            if (maxUpper == null || upper > maxUpper) {
                maxUpper = upper;
            }
        }
        return maxUpper;
    }

    @Nullable
    public String resolveMissileNameOnRadar(float distance) {
        return resolveByRange(getMissileNameOnRadar(), distance, null);
    }

    @Nullable
    private static <T> T resolveByRange(
            Map<RVP_Range<Float>, T> values,
            float distance,
            @Nullable T fallback
    ) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        float sample = normalizeDistance(distance);
        for (Map.Entry<RVP_Range<Float>, T> entry : values.entrySet()) {
            RVP_Range<Float> range = entry.getKey();
            if (range == null || !range.contains(sample)) {
                continue;
            }
            return entry.getValue();
        }
        return fallback;
    }

    private static float normalizeDistance(float distance) {
        if (Float.isNaN(distance)) {
            return 0f;
        }
        if (Float.isInfinite(distance)) {
            return distance > 0f ? Float.MAX_VALUE : 0f;
        }
        return Math.max(distance, 0f);
    }

    private static Map<RVP_Range<Float>, String> createDefaultHudNames() {
        Map<RVP_Range<Float>, String> values = new LinkedHashMap<>();
        values.put(RVP_Range.closed(0f, 500f), "MSL");
        values.put(RVP_Range.of(new RVP_Range.Interval<>(500f, null)), null);
        return values;
    }

    private static Map<RVP_Range<Float>, String> createDefaultRadarNames() {
        Map<RVP_Range<Float>, String> values = new LinkedHashMap<>();
        values.put(RVP_Range.of(new RVP_Range.Interval<>(20f, null)), "MSL");
        return values;
    }
}
