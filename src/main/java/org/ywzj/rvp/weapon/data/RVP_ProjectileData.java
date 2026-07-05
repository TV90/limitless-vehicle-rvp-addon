package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/**
 * 弹体运动学参数。扩展包 JSON 中的 {@code projectile_data} 字段会反序列化到这里。
 */
public class RVP_ProjectileData {

    /**
     * 弹体初速/飞行速度（覆盖武器顶层 {@code velocity}）；为空时使用顶层 {@code velocity}。
     * 导弹、火箭、机枪等统一读此字段。
     */
    @SerializedName("velocity")
    private Float velocity;

    /**
     * 空中每 tick 竖直加速度（格/tick²），负数向下。
     * 接近地球重力约 {@code -0.0245}（{@link org.ywzj.vehicle.vehicle.PhysicsEngine#G}）；
     * 机枪常用 {@code -0.005} 以减小弹道弯曲。
     */
    @SerializedName("gravity")
    private float gravity = 0f;

    /**
     * 水中每 tick 竖直加速度；未写时推力弹道在水中用 {@code PhysicsEngine.G * 0.6}。
     */
    @SerializedName("gravity_in_water")
    private float gravityInWater = 0f;

    /**
     * 空中水平阻力（MCH {@code DragInAir}）：每 tick 从 {@code motionX/Z} 减去
     * {@code (分量/|v|)*drag}，<strong>不改变 motionY</strong>。见
     * {@link org.ywzj.rvp.entity.projectile.RVP_BaseBullet#applyMchHorizontalDrag}。
     */
    @SerializedName("drag")
    private float drag = 0f;

    /** 水中水平阻力，公式同 {@link #drag}。 */
    @SerializedName("drag_in_water")
    private float dragInWater = 0f;

    /** 发射时是否继承载具当前速度。 */
    @SerializedName("inherit_vehicle_velocity")
    private boolean inheritVehicleVelocity = false;

    /** 是否保持恒定速度，仅改变方向。适合导弹、火箭。 */
    @SerializedName("constant_speed")
    private boolean constantSpeed = false;

    /** 是否让实体朝向跟随运动方向。 */
    @SerializedName("rotate_to_motion")
    private boolean rotateToMotion = true;

    /** 最大速度限制，0 表示不限制。 */
    @SerializedName("max_speed")
    private float maxSpeed = 0f;

    /** 最小速度限制，0 表示不限制。 */
    @SerializedName("min_speed")
    private float minSpeed = 0f;

    /** 是否装备火箭发动机；为 false 时不启用推力运动学。 */
    @SerializedName("has_rocket_engine")
    private boolean hasRocketEngine = false;

    /** 弹体质量；与 {@link #thrust}、{@link #motorBurnTime} 一并有效时启用推力弹道。 */
    @SerializedName("mass")
    private float mass = 0f;

    /** 发动机推力（与质量比决定加速度）；燃烧期内每 tick 沿朝向加速。 */
    @SerializedName("thrust")
    private float thrust = 0f;

    /** 发动机燃烧时间（tick）；燃尽后仅受重力与 {@link #dragCoefficient}。 */
    @SerializedName("motor_burn_time")
    private float motorBurnTime = 0f;

    @SerializedName("dual_pulse")
    private boolean dualPulse = false;

    @SerializedName("second_pulse_trigger_speed")
    private float secondPulseTriggerSpeed = 0f;

    @SerializedName("second_pulse_trigger_distance")
    private float secondPulseTriggerDistance = 0f;

    @SerializedName("second_pulse_thrust")
    private float secondPulseThrust = 0f;

    @SerializedName("second_pulse_burn_time")
    private float secondPulseBurnTime = 0f;

    /** 点火延迟（tick）：此前不施加推力，可配合弹射/滑翔段。 */
    @SerializedName("ignition_delay_tick")
    private int ignitionDelayTick = 0;

    /**
     * 二次阻力系数：每 tick {@code Δv -= drag_coefficient * |v|²}（沿速度反方向，含 Y）。
     * 仅 {@link #hasRocketEngine} 推力弹道使用；与 {@link #drag} 线性水平阻力不同。
     */
    @SerializedName("drag_coefficient")
    private float dragCoefficient = 0f;

    /** 是否对导弹启用基于高度的空气阻力倍率。 */
    @SerializedName("altitude_drag_factor_enabled")
    private boolean altitudeDragFactorEnabled = true;

    /** 最低空气层锚点高度（默认 -64），低于该值时取 {@link #altitudeDragLowFactor}。 */
    @SerializedName("altitude_drag_low_y")
    private float altitudeDragLowY = -64f;

    /** 低空阻力倍率（默认 2.0）。 */
    @SerializedName("altitude_drag_low_factor")
    private float altitudeDragLowFactor = 2.0f;

    /** 标准空气层锚点高度（默认 384），在该高度取 1.0 倍阻力。 */
    @SerializedName("altitude_drag_base_y")
    private float altitudeDragBaseY = 384f;

    /** 标准高度阻力倍率（默认 1.0）。 */
    @SerializedName("altitude_drag_base_factor")
    private float altitudeDragBaseFactor = 1.0f;

    /** 稀薄空气锚点高度（默认 500，对应约 5000m）。 */
    @SerializedName("altitude_drag_thin_y")
    private float altitudeDragThinY = 500f;

    /** 稀薄空气层阻力倍率（默认 0.6）。 */
    @SerializedName("altitude_drag_thin_factor")
    private float altitudeDragThinFactor = 0.6f;

    /** 高空空气锚点高度（默认 1000，对应约 10000m）。 */
    @SerializedName("altitude_drag_high_y")
    private float altitudeDragHighY = 1000f;

    /** 高空阻力倍率（默认 0.34）。 */
    @SerializedName("altitude_drag_high_factor")
    private float altitudeDragHighFactor = 0.34f;

    /** GPS 炸弹巡航段开始 tick；-1 表示禁用该弹道。 */
    @SerializedName("gps_cruise_start_tick")
    private int gpsCruiseStartTick = -1;

    /** 进入末端俯冲的水平圆柱半径（米/格）；<= 0 表示禁用该弹道。 */
    @SerializedName("gps_cruise_terminal_cylinder_radius")
    private float gpsCruiseTerminalCylinderRadius = 0f;

    /** GPS 巡航段重力系数；1 = 使用原始重力，0.5 = 重力减半。 */
    @SerializedName("gps_cruise_gravity_scale")
    private float gpsCruiseGravityScale = 1.0f;

    /** GPS 巡航段自动改平强度；每 tick 将上抛竖直速度向 0 拉回的比例。 */
    @SerializedName("gps_cruise_leveling_factor")
    private float gpsCruiseLevelingFactor = 0.15f;

    /** GPS 炸弹 CEP（米/格）；定义为 50% 落点落在该半径内的圆概率误差。 */
    @SerializedName("gps_cep")
    private float gpsCep = 0f;

    /** GPS 弹药接近目标点后自动脱导的阈值距离（米/格）；0 表示不启用。 */
    @SerializedName("gps_guidance_cancel_distance")
    private float gpsGuidanceCancelDistance = 0f;

    /**
     * 信号尺寸（签名大小）：定义弹体在雷达/红外探测系统中的等效尺寸。
     * <ul>
     *   <li>{@code 0}（默认）：该弹体不可被雷达/红外探测，保持原有行为</li>
     *   <li>{@code > 0}：替代 {@code getBoundingBox().getSize()} 用于扫描/探测/锁定过滤，
     *       并作为 RCS（雷达截面积）倍率参与探测距离缩放</li>
     * </ul>
     * 典型值参考：大型导弹（AMRAAM/PL-15）1.0~2.0；格斗弹 0.5~1.0；炸弹/布撒器 0.3~0.8。
     */
    @SerializedName("signature_size")
    private float signatureSize = 0f;

    /**
     * 加载后解析推进参数：{@code projectile_data} 未写的键从武器 JSON 顶层补全。
     * {@code has_rocket_engine} 为 false 时不做合并。
     */
    public void resolvePropulsionFallback(JsonObject weaponRoot, JsonObject projectileJson) {
        if (!hasRocketEngine || weaponRoot == null) {
            return;
        }
        JsonObject proj = projectileJson != null ? projectileJson : new JsonObject();
        if (!proj.has("mass") && weaponRoot.has("mass") && weaponRoot.get("mass").isJsonPrimitive()) {
            mass = weaponRoot.get("mass").getAsFloat();
        }
        if (!proj.has("thrust") && weaponRoot.has("thrust") && weaponRoot.get("thrust").isJsonPrimitive()) {
            thrust = weaponRoot.get("thrust").getAsFloat();
        }
        if (!proj.has("motor_burn_time") && weaponRoot.has("motor_burn_time")
                && weaponRoot.get("motor_burn_time").isJsonPrimitive()) {
            motorBurnTime = weaponRoot.get("motor_burn_time").getAsFloat();
        }
        if (!proj.has("ignition_delay_tick") && weaponRoot.has("ignition_delay_tick")
                && weaponRoot.get("ignition_delay_tick").isJsonPrimitive()) {
            ignitionDelayTick = weaponRoot.get("ignition_delay_tick").getAsInt();
        }
        if (!proj.has("drag_coefficient") && weaponRoot.has("drag_coefficient")
                && weaponRoot.get("drag_coefficient").isJsonPrimitive()) {
            dragCoefficient = weaponRoot.get("drag_coefficient").getAsFloat();
        }
        if (!proj.has("dual_pulse") && weaponRoot.has("dual_pulse")
                && weaponRoot.get("dual_pulse").isJsonPrimitive()) {
            dualPulse = weaponRoot.get("dual_pulse").getAsBoolean();
        }
        if (!proj.has("second_pulse_trigger_speed") && weaponRoot.has("second_pulse_trigger_speed")
                && weaponRoot.get("second_pulse_trigger_speed").isJsonPrimitive()) {
            secondPulseTriggerSpeed = weaponRoot.get("second_pulse_trigger_speed").getAsFloat();
        }
        if (!proj.has("second_pulse_trigger_distance") && weaponRoot.has("second_pulse_trigger_distance")
                && weaponRoot.get("second_pulse_trigger_distance").isJsonPrimitive()) {
            secondPulseTriggerDistance = weaponRoot.get("second_pulse_trigger_distance").getAsFloat();
        }
        if (!proj.has("second_pulse_thrust") && weaponRoot.has("second_pulse_thrust")
                && weaponRoot.get("second_pulse_thrust").isJsonPrimitive()) {
            secondPulseThrust = weaponRoot.get("second_pulse_thrust").getAsFloat();
        }
        if (!proj.has("second_pulse_burn_time") && weaponRoot.has("second_pulse_burn_time")
                && weaponRoot.get("second_pulse_burn_time").isJsonPrimitive()) {
            secondPulseBurnTime = weaponRoot.get("second_pulse_burn_time").getAsFloat();
        }
    }

    public Float getVelocityOverride() {
        return velocity;
    }

    public boolean hasVelocityOverride() {
        return velocity != null;
    }

    public float getGravity() {
        return gravity;
    }

    public float getGravityInWater() {
        return gravityInWater;
    }

    public float getDrag() {
        return drag;
    }

    public float getDragInWater() {
        return dragInWater;
    }

    public boolean isInheritVehicleVelocity() {
        return inheritVehicleVelocity;
    }

    public boolean isConstantSpeed() {
        return constantSpeed;
    }

    public boolean isRotateToMotion() {
        return rotateToMotion;
    }

    public float getMaxSpeed() {
        return Math.max(maxSpeed, 0f);
    }

    public float getMinSpeed() {
        return Math.max(minSpeed, 0f);
    }

    public boolean hasRocketEngine() {
        return hasRocketEngine;
    }

    public float getResolvedMass() {
        return hasRocketEngine ? Math.max(mass, 0f) : 0f;
    }

    public float getResolvedThrust() {
        return hasRocketEngine ? Math.max(thrust, 0f) : 0f;
    }

    public float getResolvedMotorBurnTime() {
        return hasRocketEngine ? Math.max(motorBurnTime, 0f) : 0f;
    }

    public boolean isDualPulse() {
        return dualPulse;
    }

    public float getResolvedSecondPulseTriggerSpeed() {
        return hasRocketEngine && dualPulse ? Math.max(secondPulseTriggerSpeed, 0f) : 0f;
    }

    public float getResolvedSecondPulseTriggerDistance() {
        return hasRocketEngine && dualPulse ? Math.max(secondPulseTriggerDistance, 0f) : 0f;
    }

    public float getResolvedSecondPulseThrust() {
        return hasRocketEngine && dualPulse ? Math.max(secondPulseThrust, 0f) : 0f;
    }

    public float getResolvedSecondPulseBurnTime() {
        return hasRocketEngine && dualPulse ? Math.max(secondPulseBurnTime, 0f) : 0f;
    }

    public boolean usesSecondPulse() {
        if (!hasRocketEngine || !dualPulse) {
            return false;
        }
        if (getResolvedSecondPulseThrust() <= 0f || getResolvedSecondPulseBurnTime() <= 0f) {
            return false;
        }
        return getResolvedSecondPulseTriggerSpeed() > 0f || getResolvedSecondPulseTriggerDistance() > 0f;
    }

    public int getResolvedIgnitionDelayTick() {
        return hasRocketEngine ? Math.max(ignitionDelayTick, 0) : 0;
    }

    public float getResolvedDragCoefficient() {
        return hasRocketEngine ? Math.max(dragCoefficient, 0f) : 0f;
    }

    public boolean isAltitudeDragFactorEnabled() {
        return altitudeDragFactorEnabled;
    }

    /**
     * 导弹空气阻力高度倍率。默认锚点：
     * {@code -64 -> 2.0}, {@code 384 -> 1.0}, {@code 500 -> 0.6}, {@code 1000 -> 0.34}。
     */
    public float resolveAltitudeDragFactor(double y) {
        if (!altitudeDragFactorEnabled) {
            return 1.0f;
        }
        float lowY = altitudeDragLowY;
        float baseY = Math.max(lowY + 1.0f, altitudeDragBaseY);
        float thinY = Math.max(baseY + 1.0f, altitudeDragThinY);
        float highY = Math.max(thinY + 1.0f, altitudeDragHighY);

        float lowFactor = sanitizeFactor(altitudeDragLowFactor, 2.0f);
        float baseFactor = sanitizeFactor(altitudeDragBaseFactor, 1.0f);
        float thinFactor = sanitizeFactor(altitudeDragThinFactor, 0.6f);
        float highFactor = sanitizeFactor(altitudeDragHighFactor, 0.34f);

        if (y <= lowY) {
            return lowFactor;
        }
        if (y < baseY) {
            return smoothLerp((float) y, lowY, baseY, lowFactor, baseFactor);
        }
        if (y < thinY) {
            return smoothLerp((float) y, baseY, thinY, baseFactor, thinFactor);
        }
        if (y < highY) {
            return smoothLerp((float) y, thinY, highY, thinFactor, highFactor);
        }
        return highFactor;
    }

    public int getGpsCruiseStartTick() {
        return gpsCruiseStartTick < 0 ? -1 : gpsCruiseStartTick;
    }

    public float getGpsCruiseTerminalCylinderRadius() {
        return Math.max(gpsCruiseTerminalCylinderRadius, 0f);
    }

    public float getGpsCruiseGravityScale() {
        if (Float.isNaN(gpsCruiseGravityScale) || Float.isInfinite(gpsCruiseGravityScale) || gpsCruiseGravityScale < 0f) {
            return 1.0f;
        }
        return gpsCruiseGravityScale;
    }

    public float getGpsCruiseLevelingFactor() {
        if (Float.isNaN(gpsCruiseLevelingFactor) || Float.isInfinite(gpsCruiseLevelingFactor)) {
            return 0.15f;
        }
        return Math.max(0f, Math.min(1f, gpsCruiseLevelingFactor));
    }

    public boolean usesGpsCruiseProfile() {
        return getGpsCruiseStartTick() >= 0 && getGpsCruiseTerminalCylinderRadius() > 0f;
    }

    public float getGpsCep() {
        if (Float.isNaN(gpsCep) || Float.isInfinite(gpsCep)) {
            return 0f;
        }
        return Math.max(gpsCep, 0f);
    }

    public float getGpsGuidanceCancelDistance() {
        if (Float.isNaN(gpsGuidanceCancelDistance) || Float.isInfinite(gpsGuidanceCancelDistance)) {
            return 0f;
        }
        return Math.max(gpsGuidanceCancelDistance, 0f);
    }

    /** 获取信号尺寸；0 表示不可被雷达/红外探测。 */
    public float getSignatureSize() {
        if (Float.isNaN(signatureSize) || Float.isInfinite(signatureSize)) {
            return 0f;
        }
        return Math.max(signatureSize, 0f);
    }

    private static float sanitizeFactor(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0f) {
            return fallback;
        }
        return value;
    }

    private static float smoothLerp(float y, float y0, float y1, float f0, float f1) {
        float t = (y - y0) / (y1 - y0);
        t = Math.max(0.0f, Math.min(1.0f, t));
        float s = t * t * (3.0f - 2.0f * t);
        return f0 + (f1 - f0) * s;
    }

    /** 是否启用与本体 {@link org.ywzj.vehicle.entity.weapon.MissileEntity} 一致的推力运动学。 */
    public boolean usesPropulsion() {
        if (!hasRocketEngine) {
            return false;
        }
        return getResolvedMass() > 1.0E-6f
                && getResolvedThrust() > 0f
                && getResolvedMotorBurnTime() > 0f;
    }

    /** {@code has_rocket_engine} 为 true 但质量/推力/燃烧时间未凑齐，会静默退回简化弹道。 */
    public boolean isRocketEngineMisconfigured() {
        return hasRocketEngine && !usesPropulsion();
    }
}
