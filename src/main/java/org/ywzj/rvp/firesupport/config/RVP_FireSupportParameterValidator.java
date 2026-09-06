package org.ywzj.rvp.firesupport.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;

/** 对客户端提交的动态几何参数进行精确、有限且步长对齐的纯逻辑校验。 */
public final class RVP_FireSupportParameterValidator {
    private static final double STEP_EPSILON = 1.0e-7;

    private RVP_FireSupportParameterValidator() {}

    /**
     * 校验请求键集合必须与预设完全一致，并返回不可变规范化参数。
     * 数值不做静默夹取，避免客户端预览与服务端落区不一致。
     */
    public static Map<String, Double> validate(RVP_FireSupportProfile.PatternPreset preset,
                                                Map<String, Double> requested, int maxParameterCount) {
        if (requested.size() > maxParameterCount || !requested.keySet().equals(preset.parameters().keySet())) {
            throw new IllegalArgumentException("动态参数键必须与预设完全一致且不超过 profile 上限");
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        preset.parameters().forEach((key, spec) -> {
            Double value = requested.get(key);
            if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("参数 " + key + " 必须是有限数值");
            if (value < spec.min() - STEP_EPSILON || value > spec.max() + STEP_EPSILON) {
                throw new IllegalArgumentException("参数 " + key + " 超出允许范围");
            }
            double steps = (value - spec.min()) / spec.step();
            if (Math.abs(steps - Math.rint(steps)) > STEP_EPSILON) {
                throw new IllegalArgumentException("参数 " + key + " 未按步长对齐");
            }
            normalized.put(key, Math.max(spec.min(), Math.min(spec.max(), value)));
        });
        return Map.copyOf(normalized);
    }
}
