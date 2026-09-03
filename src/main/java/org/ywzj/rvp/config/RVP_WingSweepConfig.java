package org.ywzj.rvp.config;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * [RVP] 可变后掠翼参数（载具 JSON 顶层可选 {@code wing_sweep} 块）。
 *
 * <p>语义（详见 docs/plan/《研发调研_可变后掠翼手动切换与气动增强方案》审核版）：</p>
 * <ul>
 *   <li>{@code lift_to_drag_k_factor}：后掠态 {@code liftToDragK} 乘子（默认 1.0 = 不干预）；</li>
 *   <li>{@code drag_k_factor}：后掠态 {@code airDragKMin/Max} 乘子（默认 1.0）；</li>
 *   <li>{@code agility_factor}：后掠态 {@code turnRateBySpeed} 乘子（默认 1.0，机动性钝化可选）；</li>
 *   <li>{@code auto_speed_threshold}：自动后掠速度阈值（格/tick，默认 2.5，与 F-14 原控制器一致）；</li>
 *   <li>{@code auto_speed_threshold_hysteresis}：展开阈值占收拢阈值的比例（默认 0.9，
 *       即收拢 &gt;2.5、展开 &lt;2.25，中间保持现状，防阈值抖动）。</li>
 * </ul>
 *
 * <p>能力信号与参数解耦：载具只要存在 {@code wing_sweep_manual}/{@code wing_sweep_form}
 * 两个隐藏部件即被 RVP 驱动（阈值取默认），本配置块仅作调参——防止"配了部件漏配参数
 * 导致机翼卡死"（审核报告 P1）。</p>
 */
public record RVP_WingSweepConfig(
        float liftToDragKFactor,
        float dragKFactor,
        float agilityFactor,
        float autoSpeedThreshold,
        float autoSpeedHysteresisRatio
) {

    /** 缺省配置：全部乘子 1.0（不干预物理）、阈值 2.5、滞回比 0.9。 */
    public static final RVP_WingSweepConfig DEFAULT =
            new RVP_WingSweepConfig(1f, 1f, 1f, 2.5f, 0.9f);

    /**
     * 从载具 JSON 的 {@code wing_sweep} 子对象解析；非法值回落缺省，保证配置错误不致崩溃。
     */
    public static RVP_WingSweepConfig parse(@Nullable JsonObject obj) {
        if (obj == null) {
            return DEFAULT;
        }
        float lift = positiveOrDefault(GsonHelper.getAsFloat(obj, "lift_to_drag_k_factor", 1f), 1f);
        float drag = positiveOrDefault(GsonHelper.getAsFloat(obj, "drag_k_factor", 1f), 1f);
        float agility = positiveOrDefault(GsonHelper.getAsFloat(obj, "agility_factor", 1f), 1f);
        float threshold = positiveOrDefault(GsonHelper.getAsFloat(obj, "auto_speed_threshold", 2.5f), 2.5f);
        float hysteresis = GsonHelper.getAsFloat(obj, "auto_speed_threshold_hysteresis", 0.9f);
        // 目的：滞回比必须落在 (0,1) 开区间——0 会让展开阈值归零（无滞回），≥1 会让展开永不触发
        if (!Float.isFinite(hysteresis) || hysteresis <= 0f || hysteresis >= 1f) {
            hysteresis = 0.9f;
        }
        return new RVP_WingSweepConfig(lift, drag, agility, threshold, hysteresis);
    }

    /** 非法（非有限 / ≤0）时回落缺省值。 */
    private static float positiveOrDefault(float value, float fallback) {
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }
}
