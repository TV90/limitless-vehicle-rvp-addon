package org.ywzj.rvp.client.tuning;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** 通用参数描述与读写绑定；界面不需要认识任何具体特效、配置类或指令。 */
public final class RVP_TuningOption {
    /** 通用控件类型；开关也以严格的 0/1 保存，避免小数开关。 */
    public enum Control { NUMBER, TOGGLE }

    /** 分组内稳定的参数键，同时用于搜索和本地预设。 */
    private final String id;
    /** 用户看到的中文参数名。 */
    private final String title;
    /** 参数作用、单位及生效条件的完整说明。 */
    private final String description;
    /** 数值单位；无量纲参数可以填“倍率”或“比例”。 */
    private final String unit;
    /** 使用数值输入和滑条，或使用开关按钮。 */
    private final Control control;
    /** 允许写入的最小值。 */
    private final double min;
    /** 允许写入的最大值。 */
    private final double max;
    /** 滑条的柔和对数尺度；0 表示线性，正数方便精调跨度很大的亮度参数。 */
    private final double sliderScale;
    /** 动态默认值读取器；恢复开关时可继续尊重用户原有 TOML。 */
    private final DoubleSupplier defaultValue;
    /** 直接读取业务层的临时覆盖；命令和面板始终使用同一份状态。 */
    private final Supplier<OptionalDouble> overrideValue;
    /** 已有业务层的写入入口，仍保留其最终校验。 */
    private final DoubleConsumer writer;
    /** 清除单项覆盖的入口；恢复默认不会制造新的覆盖值。 */
    private final Runnable resetter;

    public RVP_TuningOption(String id, String title, String description, String unit, Control control,
                            double min, double max, double sliderScale, DoubleSupplier defaultValue,
                            Supplier<OptionalDouble> overrideValue, DoubleConsumer writer, Runnable resetter) {
        this.id = Objects.requireNonNull(id);
        this.title = Objects.requireNonNull(title);
        this.description = Objects.requireNonNull(description);
        this.unit = Objects.requireNonNull(unit);
        this.control = Objects.requireNonNull(control);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max
                || !Double.isFinite(sliderScale) || sliderScale < 0
                || control == Control.TOGGLE && (min != 0 || max != 1)) {
            throw new IllegalArgumentException("无效的参数范围或滑条尺度：" + id);
        }
        this.min = min;
        this.max = max;
        this.sliderScale = sliderScale;
        this.defaultValue = Objects.requireNonNull(defaultValue);
        this.overrideValue = Objects.requireNonNull(overrideValue);
        this.writer = Objects.requireNonNull(writer);
        this.resetter = Objects.requireNonNull(resetter);
    }

    public String id() { return id; }
    public String title() { return title; }
    public String description() { return description; }
    public String unit() { return unit; }
    public Control control() { return control; }
    public double min() { return min; }
    public double max() { return max; }
    public double defaultValue() { return defaultValue.getAsDouble(); }
    public OptionalDouble overrideValue() { return overrideValue.get(); }

    /** 未覆盖时动态读取默认值，不能把客户端配置的关闭状态误显示为开启。 */
    public double value() { return overrideValue.get().orElseGet(defaultValue); }

    /** 在界面、预设导入和业务写入前统一拒绝越界、NaN、无穷及小数开关。 */
    public void validate(double value) {
        if (!Double.isFinite(value) || value < min || value > max
                || control == Control.TOGGLE && value != 0 && value != 1) {
            // 调用统一数值格式，提示明确的合法范围。
            throw new IllegalArgumentException(title + "：允许 " + format(min) + "～" + format(max)
                    + (control == Control.TOGGLE ? "，开关只接受 0 或 1" : " " + unit));
        }
    }

    /** 先完成通用校验，再调用实际业务写入器；失败不吞掉错误。 */
    public void set(double value) {
        // 调用统一范围检查，避免文本输入和滑条采用不同规则。
        validate(value);
        writer.accept(value);
    }

    /** 清除覆盖，让原有代码默认值或客户端配置重新接管。 */
    public void reset() { resetter.run(); }

    /** 将实际数值换为滑条位置；宽范围参数仍给低值区保留足够的拖动空间。 */
    public double toSlider(double value) {
        double offset = Math.max(0, Math.min(max - min, value - min));
        return sliderScale == 0 ? offset / (max - min)
                : Math.log1p(offset / sliderScale) / Math.log1p((max - min) / sliderScale);
    }

    /** 将滑条位置换回实际数值；端点精确命中范围边界，数值输入不受滑条精度限制。 */
    public double fromSlider(double position) {
        if (position <= 0) { return min; }
        if (position >= 1) { return max; }
        return Math.max(min, Math.min(max, min + (sliderScale == 0 ? position * (max - min)
                : sliderScale * Math.expm1(position * Math.log1p((max - min) / sliderScale)))));
    }

    /** 用足够精度显示小数，去掉无意义的尾零；科学计数法仍可直接输入。 */
    public static String format(double value) {
        return BigDecimal.valueOf(value).round(new MathContext(7)).stripTrailingZeros().toPlainString();
    }
}
