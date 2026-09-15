package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class RVP_ProjectileData {

    /** 可选弹体初速覆盖，单位格/Tick，默认 null；未配置时继承武器顶层速度。 */
    @SerializedName("velocity")
    private Float velocity;

    /** 空中每 Tick 的 Y 轴速度增量，单位格/Tick²，默认 0；实体和虚拟弹道均生效。 */
    @SerializedName("gravity")
    private float gravity = 0f;

    /** 水中每 Tick 的 Y 轴速度增量，单位格/Tick²，默认 0；仅水中实体弹道生效。 */
    @SerializedName("gravity_in_water")
    private float gravityInWater = 0f;

    /** 空中线性阻力系数，默认 0；仅未启用火箭发动机动力学的实体弹道生效。 */
    @SerializedName("drag")
    private float drag = 0f;

    /** 水中线性阻力系数，默认 0；仅水中实体弹道生效。 */
    @SerializedName("drag_in_water")
    private float dragInWater = 0f;

    /** 是否在发射初速中叠加载具速度，默认 false；仅弹体生成时生效。 */
    @SerializedName("inherit_vehicle_velocity")
    private boolean inheritVehicleVelocity = false;

    /** 是否在运动更新后恢复配置速率，默认 false；实体弹道和相关预测均生效。 */
    @SerializedName("constant_speed")
    private boolean constantSpeed = false;

    /** 是否让弹体姿态跟随速度方向，默认 true；实体态及虚拟态恢复姿态时生效。 */
    @SerializedName("rotate_to_motion")
    private boolean rotateToMotion = true;

    /** 最高速率，单位格/Tick，默认 0；非正值表示不限制。 */
    @SerializedName("max_speed")
    private float maxSpeed = 0f;

    /** 最低速率，单位格/Tick，默认 0；非正值表示不限制。 */
    @SerializedName("min_speed")
    private float minSpeed = 0f;

    /**
     * 按飞行 Tick 配置的方向插值强度，范围 0～1，默认 null；未配置 {@code rvp_maxg}
     * 时约束实体与虚拟制导转向，区间未命中时使用运行时默认值 0.5。
     */
    @SerializedName("turning_factor")
    private Map<RVP_Range<Integer>, Float> turningFactor;

    /**
     * RVP 最大法向过载，单位 G，默认 null；显式配置时以非负值约束实体与虚拟制导的
     * 单 Tick 转向，并优先于 {@code turning_factor}，0 表示不允许转向。
     */
    @SerializedName("rvp_maxg")
    private Double rvpMaxG;

    /** 是否启用火箭发动机动力学，默认 false；启用后质量、推力和燃烧时间参与运动计算。 */
    @SerializedName("has_rocket_engine")
    private boolean hasRocketEngine = false;

    /**
     * 尾焰喷口偏移（实体空间，[x,y,z]，格；Z- 为弹尾）。默认 {@code [0,0,-0.5]}。
     * 生效条件：{@code has_rocket_engine=true} 且发动机燃烧中，用于客户端尾焰渲染位置
     * （复用本体火箭尾焰模型/动画/贴图三件套）。
     */
    @SerializedName("engine_nozzle_offset")
    private float[] engineNozzleOffset;

    /**
     * 尾焰渲染缩放，默认 {@code 0.3}（2026-09-15 由 0.2 上调）。语义同本体
     * {@code caliber/1000}，即数值 ≈ 弹体直径（格）；尾焰模型自身直径约 3.09 格，
     * 缩放后约为弹径的 3 倍。配置示例：9M723（弹径 0.92 格）取 {@code 1.15} 以显更粗。
     * 生效条件：同 {@code engine_nozzle_offset}。
     */
    @SerializedName("flame_scale")
    private Float flameScale;

    /** 弹体质量，单位沿用本体动力学，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("mass")
    private float mass = 0f;

    /** 发动机推力，单位沿用本体动力学，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("thrust")
    private float thrust = 0f;

    /** 主发动机燃烧时间，单位 Tick，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("motor_burn_time")
    private float motorBurnTime = 0f;

    /** 是否启用第二脉冲发动机，默认 false；仅火箭发动机启用时生效。 */
    @SerializedName("second_pulse")
    private boolean secondPulse = false;

    /** 第二脉冲速度触发阈值，单位格/Tick，默认 0；低于正阈值时允许点火。 */
    @SerializedName("second_pulse_trigger_speed")
    private float secondPulseTriggerSpeed = 0f;

    /** 第二脉冲目标距离触发阈值，单位格，默认 0；低于正阈值时允许点火。 */
    @SerializedName("second_pulse_trigger_distance")
    private float secondPulseTriggerDistance = 0f;

    /** 第二脉冲推力，单位沿用本体动力学，默认 0；第二脉冲启用后生效。 */
    @SerializedName("second_pulse_thrust")
    private float secondPulseThrust = 0f;

    /** 第二脉冲燃烧时间，单位 Tick，默认 0；第二脉冲启用后生效。 */
    @SerializedName("second_pulse_burn_time")
    private float secondPulseBurnTime = 0f;

    /** 发射后的点火延迟，单位 Tick，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("ignition_delay_tick")
    private int ignitionDelayTick = 0;

    /** 速度平方阻力系数，默认 0；火箭发动机动力学按 Tick 施加。 */
    @SerializedName("drag_coefficient")
    private float dragCoefficient = 0f;

    /** 按世界 Y 高度配置的阻力倍率，默认 null；未命中或非法值按 1.0 处理。 */
    @SerializedName("altitude_drag_factor")
    private Map<RVP_Range<Float>, Float> altitudeDragFactor;

    /** 服务器权威风漂配置，默认使用禁用配置；仅配置 {@code wind_data.enabled=true} 时生效。 */
    @SerializedName("wind_data")
    private RVP_WindData windData = new RVP_WindData();

    /**
     * 子弹药部署水平速度的半衰期，单位 Tick，默认 0（禁用）；仅由本项目子弹药生成器
     * 显式初始化的 X/Z 部署分量生效，正有限值按指数曲线衰减，Y、风偏和其他外力不参与该衰减。
     */
    @SerializedName("deployment_horizontal_half_life_ticks")
    private float deploymentHorizontalHalfLifeTicks = 0f;

    /**
     * 子弹药部署纵向速度的半衰期，单位 Tick，默认 0（禁用）；仅由本项目子弹药生成器
     * 显式初始化的散布 Y 分量生效，正有限值按指数曲线衰减，重力、显式附加速度和其他外力不参与该衰减。
     */
    @SerializedName("deployment_vertical_half_life_ticks")
    private float deploymentVerticalHalfLifeTicks = 0f;

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
        if (!proj.has("second_pulse") && weaponRoot.has("second_pulse")
                && weaponRoot.get("second_pulse").isJsonPrimitive()) {
            secondPulse = weaponRoot.get("second_pulse").getAsBoolean();
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

    public boolean hasTurningFactor() {
        return turningFactor != null && !turningFactor.isEmpty();
    }

    public Float resolveTurningFactor(int flightTick) {
        if (!hasTurningFactor()) {
            return null;
        }
        int tick = Math.max(flightTick, 0);
        for (Map.Entry<RVP_Range<Integer>, Float> entry : turningFactor.entrySet()) {
            RVP_Range<Integer> range = entry.getKey();
            Float value = entry.getValue();
            if (range == null || value == null || !range.contains(tick) || !Float.isFinite(value)) {
                continue;
            }
            return Math.max(0f, Math.min(1f, value));
        }
        return null;
    }

    /** @return 是否显式配置了 {@code rvp_maxg}；配置 0 仍视为已配置。 */
    public boolean hasRvpMaxG() {
        return rvpMaxG != null;
    }

    /**
     * @return 未配置时返回 null；已配置时返回非负有限 G 值，非法数值按 0 G 安全处理
     */
    public Double getRvpMaxG() {
        if (rvpMaxG == null) {
            return null;
        }
        return Double.isFinite(rvpMaxG) ? Math.max(rvpMaxG, 0.0) : 0.0;
    }

    public boolean hasRocketEngine() {
        return hasRocketEngine;
    }

    /**
     * 尾焰喷口偏移（实体空间）；未配置返回 {@code null}，调用方使用默认值 {@code [0,0,-0.5]}。
     */
    @Nullable
    public float[] getEngineNozzleOffset() {
        return engineNozzleOffset != null && engineNozzleOffset.length == 3 ? engineNozzleOffset : null;
    }

    /** 尾焰渲染缩放；未配置返回 {@code null}，调用方使用默认值 {@code 0.3}。 */
    @Nullable
    public Float getFlameScale() {
        return flameScale;
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

    public boolean isSecondPulse() {
        return secondPulse;
    }

    public float getResolvedSecondPulseTriggerSpeed() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseTriggerSpeed, 0f) : 0f;
    }

    public float getResolvedSecondPulseTriggerDistance() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseTriggerDistance, 0f) : 0f;
    }

    public float getResolvedSecondPulseThrust() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseThrust, 0f) : 0f;
    }

    public float getResolvedSecondPulseBurnTime() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseBurnTime, 0f) : 0f;
    }

    public boolean usesSecondPulse() {
        if (!hasRocketEngine || !secondPulse) {
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

    public Map<RVP_Range<Float>, Float> getAltitudeDragFactor() {
        return altitudeDragFactor;
    }

    public RVP_WindData getWindData() {
        return windData == null ? new RVP_WindData() : windData;
    }

    public float getDeploymentHorizontalHalfLifeTicks() {
        return Float.isFinite(deploymentHorizontalHalfLifeTicks)
                ? Math.max(deploymentHorizontalHalfLifeTicks, 0f)
                : 0f;
    }

    public float getDeploymentVerticalHalfLifeTicks() {
        return Float.isFinite(deploymentVerticalHalfLifeTicks)
                ? Math.max(deploymentVerticalHalfLifeTicks, 0f)
                : 0f;
    }

    public float resolveAltitudeDragFactor(double y) {
        if (altitudeDragFactor == null || altitudeDragFactor.isEmpty()) {
            return 1.0f;
        }
        float sample = normalizeAltitudeSample(y);
        for (Map.Entry<RVP_Range<Float>, Float> entry : altitudeDragFactor.entrySet()) {
            RVP_Range<Float> range = entry.getKey();
            if (range == null || !range.contains(sample)) {
                continue;
            }
            Float factor = entry.getValue();
            if (factor == null || !Float.isFinite(factor) || factor <= 0f) {
                return 1.0f;
            }
            return factor;
        }
        return 1.0f;
    }

    private static float normalizeAltitudeSample(double y) {
        if (Double.isNaN(y)) {
            return 0f;
        }
        if (Double.isInfinite(y)) {
            return y > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
        }
        if (y > Float.MAX_VALUE) {
            return Float.MAX_VALUE;
        }
        if (y < -Float.MAX_VALUE) {
            return -Float.MAX_VALUE;
        }
        return (float) y;
    }

    public boolean usesPropulsion() {
        if (!hasRocketEngine) {
            return false;
        }
        return getResolvedMass() > 1.0E-6f
                && getResolvedThrust() > 0f
                && getResolvedMotorBurnTime() > 0f;
    }

    public boolean isRocketEngineMisconfigured() {
        return hasRocketEngine && !usesPropulsion();
    }
}
