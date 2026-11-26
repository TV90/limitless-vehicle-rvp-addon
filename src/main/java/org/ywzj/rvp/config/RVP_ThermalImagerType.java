package org.ywzj.rvp.config;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** 武器站 thermal_imager_type 的数据值。编号对应热像输出着色器的调色模式。 */
public enum RVP_ThermalImagerType {
    /** 默认白热，保留原有灰阶响应。 */
    @SerializedName("white_hot") WHITE_HOT(0),
    /** 低分辨率传感器的柔化白热。 */
    @SerializedName("low_res_white_hot") LOW_RES_WHITE_HOT(1),
    /** 深绿荧光背景、黄色热源。 */
    @SerializedName("green_low_light") GREEN_LOW_LIGHT(2),
    /** 青蓝微光背景、浅青蓝热源。 */
    @SerializedName("blue_low_light") BLUE_LOW_LIGHT(3),
    /** 热源越热越黑。 */
    @SerializedName("black_hot") BLACK_HOT(4),
    /** 深紫、红橙至金黄的连续铁红色带。 */
    @SerializedName("yellow_purple") YELLOW_PURPLE(5),
    /** 低清酒红画面，热源偏浅红。 */
    @SerializedName("low_res_red_hot") LOW_RES_RED_HOT(6),
    /** 极低分辨率传感器的白热，热像中的轮廓和光晕一起软化。 */
    @SerializedName("very_low_res_white_hot") VERY_LOW_RES_WHITE_HOT(7);

    /** 省略类型或没有有效条目时使用原白热。 */
    public static final List<RVP_ThermalImagerType> DEFAULT_TYPES = List.of(WHITE_HOT);

    /** 传给 Palette 的固定编号，枚举的声明顺序不影响载具显示。 */
    public final int shaderMode;

    RVP_ThermalImagerType(int shaderMode) {
        this.shaderMode = shaderMode;
    }

    /** 武器站兼容旧字符串和有序数组；忽略未知值与重复项，保证至少有一种热像。 */
    public static final class ListAdapter implements JsonDeserializer<List<RVP_ThermalImagerType>> {
        @Override
        public List<RVP_ThermalImagerType> deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            Iterable<JsonElement> entries = json.isJsonArray() ? json.getAsJsonArray() : List.of(json);
            List<RVP_ThermalImagerType> result = new ArrayList<>();
            for (JsonElement entry : entries) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
                RVP_ThermalImagerType mode = context.deserialize(entry, RVP_ThermalImagerType.class);
                if (mode != null && !result.contains(mode)) result.add(mode);
            }
            return result.isEmpty() ? DEFAULT_TYPES : List.copyOf(result);
        }
    }
}
