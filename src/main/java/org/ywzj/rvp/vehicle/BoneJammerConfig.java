package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级干扰机设备配置（{@code bone_modules} 骨块条目的 {@code jammer} 子对象）。
 *
 * @param type         干扰器类型（默认 optical）
 * @param fovDeg       前方锥形全角（度），半角用于 withinAngle 判定（默认 90）
 * @param range        检测距离（格）（默认 2000）
 * @param strength     干扰强度倍率，作用于随机错误分量幅度（默认 1.0）
 * @param facingPart   探测方向跟随的部件 id（如 {@code "turret"}，干扰锥随该部件旋转）；
 *                     为 null 或空时跟随车体朝向
 * @param facingYawDeg 在 facingPart/车体朝向基础上额外叠加的水平偏置角（度，正值向左）。
 *                     用于左右分布式干扰机：两侧锥各自偏转、覆盖不同扇区，
 *                     一侧被击毁后该扇区失去覆盖、总干扰范围变小
 * @param approachAngleDeg 导弹来袭角判定（度，半角）：导弹飞行方向与「导弹→干扰机」连线的
 *                         夹角需不超过该值才触发干扰，只干扰朝本车飞来的导弹，
 *                         避免对侧向飞过/远离的导弹超大范围误干扰（默认 60）
 * @param headingRateDeg 被干扰期间每 tick 直接旋转导弹水平速度方向的角度（度）×强度
 *                       （默认 2.0，即 40°/秒；干扰范围小、导弹停留时间短时需要更高值）
 * @param offsetAngleDeg 瞄准点横向偏移角度（度）：远距离按 tan(θ)×瞄准距离放大（默认 8）
 * @param offsetBaseBlocks 瞄准点横向偏移兜底（格）：近距离最小偏移量（默认 20）
 * @param offsetDownBlocks 瞄准点向下偏移分量（格）×强度：与横向偏移合成「左下/右下」斜向拉偏
 *                         （默认 12）
 */
public record BoneJammerConfig(
        RVP_JammingDeviceType type,
        double fovDeg,
        double range,
        double strength,
        @Nullable String facingPart,
        double facingYawDeg,
        double approachAngleDeg,
        double headingRateDeg,
        double offsetAngleDeg,
        double offsetBaseBlocks,
        double offsetDownBlocks
) {
    /** 半角（度）。 */
    public double halfAngleDeg() {
        return Math.max(0.0, fovDeg) / 2.0;
    }

    /** 来袭角判定半角（度），0 = 必须完全正对。 */
    public double approachHalfAngleDeg() {
        return Math.max(0.0, approachAngleDeg);
    }

    public static @Nullable BoneJammerConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        RVP_JammingDeviceType type = RVP_JammingDeviceType.byName(
                GsonHelper.getAsString(obj, "type", null));
        double fov = GsonHelper.getAsDouble(obj, "fov", 90.0);
        double range = GsonHelper.getAsDouble(obj, "range", 2000.0);
        double strength = GsonHelper.getAsDouble(obj, "strength", 1.0);
        String facingPart = GsonHelper.getAsString(obj, "facing_part", null);
        if (facingPart != null && facingPart.isBlank()) {
            facingPart = null;
        }
        double facingYawDeg = GsonHelper.getAsDouble(obj, "facing_yaw", 0.0);
        double approachAngleDeg = GsonHelper.getAsDouble(obj, "approach_angle", 60.0);
        double headingRateDeg = GsonHelper.getAsDouble(obj, "heading_rate", 2.0);
        double offsetAngleDeg = GsonHelper.getAsDouble(obj, "offset_angle", 8.0);
        double offsetBaseBlocks = GsonHelper.getAsDouble(obj, "offset_base", 20.0);
        double offsetDownBlocks = GsonHelper.getAsDouble(obj, "offset_down", 12.0);
        return new BoneJammerConfig(type, Math.max(0.0, fov), Math.max(0.0, range), Math.max(0.0, strength),
                facingPart, facingYawDeg, Math.max(0.0, approachAngleDeg), Math.max(0.0, headingRateDeg),
                Math.max(0.0, offsetAngleDeg), Math.max(0.0, offsetBaseBlocks), Math.max(0.0, offsetDownBlocks));
    }
}
