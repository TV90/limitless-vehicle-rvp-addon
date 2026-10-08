package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;

import java.util.Map;

public class RVP_ProjectileData {

    /** 默认大气表的世界 Y 锚点；低于首锚点取首锚点倍率，高于末锚点取末锚点倍率。 */
    private static final float[] DEFAULT_ALTITUDE_DRAG_HEIGHTS = {
            -64f, 64f, 320f, 550f, 1000f
    };

    /** 默认大气表各锚点的空气密度相对海平面倍率；按相邻锚点线性插值。 */
    private static final float[] DEFAULT_ALTITUDE_DRAG_FACTORS = {
            2.0f, 1.5f, 0.5f, 0.25f, 0.014f
    };

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

    /**
     * 是否启用气动转向统一求解，默认 false；仅实体与虚拟制导转向生效。阶段 S2 保持
     * false 以保证旧武器配置静默兼容；显式启用后，转角按动压减载并结算诱导阻力。
     */
    @SerializedName("rvp_aero_steering")
    private boolean rvpAeroSteering = false;

    /**
     * 弹轴与相对气流速度方向的总攻角控制上限，单位度，默认 null（关闭）；
     * 仅 RVP 导弹、{@code rvp_aero_steering=true} 且值为有限 (0, 90) 时生效。
     * 首版沿用无环境风速的近似，气流速度取弹体速度；不单独模拟侧滑和滚转。
     * 开启后姿态独立于速度，优先于 {@code rotate_to_motion}；未配置有限
     * {@code rvp_maxg} 的 {@code turning_factor>=1} 瞬转弹仍豁免。非法值关闭功能。
     */
    @SerializedName("rvp_attack_angle_limit_deg")
    private Double rvpAttackAngleLimitDeg;

    /**
     * 诱导阻力系数，无量纲，默认 null；仅 {@code rvp_aero_steering=true} 时生效。
     * 未配置时复用有效 {@code drag_coefficient}，0 表示显式关闭诱导阻力。
     */
    @SerializedName("rvp_induced_drag")
    private Float rvpInducedDrag;

    /**
     * 动压参考速度，单位格/Tick，默认 null；仅 {@code rvp_aero_steering=true} 时生效。
     * 未配置时依次回退到 {@code max_speed}、武器初速和 3.0；显式 0 关闭动压减载。
     */
    @SerializedName("rvp_ref_speed")
    private Float rvpRefSpeed;

    /**
     * 单 Tick 绝对转角上限，单位度/Tick，默认 0；仅 {@code rvp_aero_steering=true}
     * 且值为正有限数时生效，0 表示不额外限制。
     */
    @SerializedName("rvp_turn_rate_limit")
    private float rvpTurnRateLimit = 0f;

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

    /** 弹体质量，默认 0；推力计算沿用此数值，阻力仅在质量小于 1 时按吨换算为千克；仅火箭发动机启用时生效。 */
    @SerializedName("mass")
    private float mass = 0f;

    /** 发动机推力，单位沿用本体动力学，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("thrust")
    private float thrust = 0f;

    /**
     * 燃料质量（发射总质量 {@link #mass} 的一部分），单位与 {@link #mass} 一致，默认 0（禁用变质量）。
     * 生效条件：{@code has_rocket_engine=true} 且 {@code fuel_mass>0} 且 {@code mass>0}；
     * 主燃烧期间弹体质量由发射总质量线性递减至干质量 {@code mass - fuel_mass}，
     * 使发动机后半程加速度随质量下降而增大（对齐真实火箭燃耗）；第二脉冲按干质量工作。
     */
    @SerializedName("fuel_mass")
    private float fuelMass = 0f;

    /** 主发动机燃烧时间，单位 Tick，默认 0；仅火箭发动机启用时生效。 */
    @SerializedName("motor_burn_time")
    private float motorBurnTime = 0f;

    /**
     * 按点火后 Tick 分段的推力表，单位与 {@link #thrust} 一致，默认 null；未配置时使用标量
     * {@link #thrust}。键为 {@code "[[起始,结束]]"} 形式的 Tick 区间（含端点，{@code inf} 表示无穷），
     * 值为该区间的推力。配置后曲线为权威：区间内取匹配值、区间外推力为 0；燃烧总窗口仍由
     * {@link #motorBurnTime} 决定，曲线只塑造窗口内的推力形状（实现助推/续航分级推力）。
     */
    @SerializedName("thrust_curve")
    private Map<RVP_Range<Integer>, Float> thrustCurve;

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

    /** 速度平方阻力系数，默认 0；推进弹体每 Tick 按系数×速度平方÷阻力质量×高度倍率扣速，质量小于 1 时阻力质量为其千倍。 */
    @SerializedName("drag_coefficient")
    private float dragCoefficient = 0f;

    /** 按世界 Y 高度配置的阻力倍率，字段默认 null；未配置时使用默认大气密度表，显式空表、未命中或非法值按 1.0 处理。 */
    @SerializedName("altitude_drag_factor")
    private Map<RVP_Range<Float>, Float> altitudeDragFactor;

    /**
     * 弹体碰撞箱边长（宽=高，单位格），默认 null → {@link #COLLISION_BOX_SIZE_DEFAULT}（1/16 格，
     * 与实体注册尺寸一致，历史行为）；有限值钳 {@code [0.0625, 16]}。调大后直击命中（弹幕拦弹）
     * 的几何窗口随之放大——命中结算、方块碰撞与弹间碰撞均按新碰撞箱判定；渲染与近炸触发
     * 半径不受影响（近炸探测盒按发射方弹体自身膨胀）。
     */
    @SerializedName("collision_box_size")
    private Double collisionBoxSize;

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

    /**
     * 自毁距离，单位米（=格，沿弹道累计飞行路程），字段缺省 {@code -1}=未配置；
     * 生效条件：弹体每 Tick 累计飞行路程达到该值即自毁（消失或爆炸，见
     * {@link #selfDestructExplode}）。未配置时按武器类别取默认：机枪/机炮
     * （{@code MACHINEGUN}）默认 1024 米，其它类别默认 0（不自毁）；显式配置优先——
     * 显式 {@code 0} 表示关闭（含显式关闭机枪默认）。
     */
    @SerializedName("self_destruct_distance")
    private float selfDestructDistance = -1f;

    /**
     * 自毁行为，默认 {@code false}=消失（弹体直接移除）；{@code true}=爆炸——按弹药自身
     * 引信语义引爆（{@code detonateFuseAt}，继承 ON_FUSE 子母弹等引信配置）；弹药无爆炸
     * 配置时爆炸退化为消失。仅 {@link #selfDestructDistance} 生效时本字段有意义。
     */
    @SerializedName("self_destruct_explode")
    private boolean selfDestructExplode = false;

    /** 自毁距离解析：显式配置（≥0，含显式 0=关闭）优先；未配置按类别默认——机枪 1024 米，其它 0。 */
    public float resolveSelfDestructDistance(RVP_EnumWeaponKind weaponKind) {
        if (selfDestructDistance >= 0f) {
            return selfDestructDistance;
        }
        return weaponKind == RVP_EnumWeaponKind.MACHINEGUN ? 1024f : 0f;
    }

    /** 自毁行为：true=爆炸（无爆炸配置退化为消失），false=消失。 */
    public boolean isSelfDestructExplode() {
        return selfDestructExplode;
    }

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
        if (!proj.has("fuel_mass") && weaponRoot.has("fuel_mass") && weaponRoot.get("fuel_mass").isJsonPrimitive()) {
            fuelMass = weaponRoot.get("fuel_mass").getAsFloat();
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

    /** @return 是否启用气动转向统一求解；阶段 S2 默认 false。 */
    public boolean isRvpAeroSteering() {
        return rvpAeroSteering;
    }

    /** RVP 弹体碰撞箱边长下限（格）：与实体注册尺寸一致，兼作默认值。 */
    public static final float COLLISION_BOX_SIZE_DEFAULT = 0.0625F;

    /** @return 弹体碰撞箱边长（格）；缺省/非法回退 {@link #COLLISION_BOX_SIZE_DEFAULT}，有效配置钳 [0.0625, 16]。 */
    public float getCollisionBoxSize() {
        if (collisionBoxSize == null || !Double.isFinite(collisionBoxSize) || collisionBoxSize < COLLISION_BOX_SIZE_DEFAULT) {
            return COLLISION_BOX_SIZE_DEFAULT;
        }
        return (float) Math.min(collisionBoxSize, 16.0D);
    }

    /** @return 有限 (0, 90) 度攻角上限；缺省、null 和非法配置返回 0（关闭）。 */
    public double getRvpAttackAngleLimitDeg() {
        return rvpAttackAngleLimitDeg != null && Double.isFinite(rvpAttackAngleLimitDeg)
                && rvpAttackAngleLimitDeg > 0.0 && rvpAttackAngleLimitDeg < 90.0
                ? rvpAttackAngleLimitDeg : 0.0;
    }

    /**
     * @return 原始诱导阻力配置；null 表示运行时复用有效 {@code drag_coefficient}
     */
    @Nullable
    public Float getRvpInducedDrag() {
        return rvpInducedDrag;
    }

    /**
     * @return 原始动压参考速度配置；null 表示按最高速率、武器初速和常量依次推导
     */
    @Nullable
    public Float getRvpRefSpeed() {
        return rvpRefSpeed;
    }

    /** @return 非负有限的绝对转角上限，单位度/Tick；非法值按 0 处理。 */
    public float getRvpTurnRateLimit() {
        return Float.isFinite(rvpTurnRateLimit) ? Math.max(rvpTurnRateLimit, 0f) : 0f;
    }

    /**
     * 解析气动转向限制，turningFactor 未显式传入时使用运行时默认值 0.5。
     *
     * @param fallbackSpeed 武器初速兜底，单位格/Tick
     * @param altitude 当前世界 Y 高度，用于复用高度阻力倍率作为密度倍率
     * @return 不持有数据对象引用的单 Tick 气动转向限制快照
     */
    public RVP_AeroSteeringLimits resolveAeroSteeringLimits(double fallbackSpeed,
                                                             double altitude) {
        return resolveAeroSteeringLimits(fallbackSpeed, altitude, 0.5F);
    }

    /**
     * 解析实体态与虚拟态共用的气动转向限制快照。
     *
     * @param fallbackSpeed 武器初速兜底，单位格/Tick；仅显式参考速度与最高速率均缺失时使用
     * @param altitude 当前世界 Y 高度，用于复用高度阻力倍率作为密度倍率
     * @param resolvedTurningFactor 当前飞行 Tick 已解析的方向插值强度，运行时钳制到 0～1
     * @return 不持有数据对象引用的单 Tick 气动转向限制快照
     */
    public RVP_AeroSteeringLimits resolveAeroSteeringLimits(double fallbackSpeed,
                                                             double altitude,
                                                             float resolvedTurningFactor) {
        double referenceSpeed = resolveAeroReferenceSpeed(fallbackSpeed);
        double inducedDrag = resolveInducedDragCoefficient();
        // 调用本项目高度阻力解析器，复用同一倍率作为气动转向的空气密度因子。
        double densityFactor = resolveAltitudeDragFactor(altitude);
        float turning = Float.isFinite(resolvedTurningFactor)
                ? Math.max(0f, Math.min(1f, resolvedTurningFactor))
                : 0.5F;
        return new RVP_AeroSteeringLimits(
                getRvpMaxG(), turning, referenceSpeed, densityFactor,
                inducedDrag, getRvpTurnRateLimit(), rvpAeroSteering,
                // 调用本项目攻角解析器，将可选配置冻结到实体/虚拟共用的限制中。
                getRvpAttackAngleLimitDeg());
    }

    /**
     * @return 有效诱导阻力系数；显式值优先，未配置时复用火箭动力学的速度平方阻力系数
     */
    public float resolveInducedDragCoefficient() {
        if (rvpInducedDrag != null) {
            return Float.isFinite(rvpInducedDrag) ? Math.max(rvpInducedDrag, 0f) : 0f;
        }
        // 调用本项目阻力解析器，保持 has_rocket_engine 对 drag_coefficient 的既有门控。
        return getResolvedDragCoefficient();
    }

    /**
     * 解析动压设计参考速度。
     *
     * @param fallbackSpeed 武器初速兜底，单位格/Tick
     * @return 非负有限参考速度；显式 0 保留为“关闭动压减载”，无可用来源时返回 3.0
     */
    private double resolveAeroReferenceSpeed(double fallbackSpeed) {
        if (rvpRefSpeed != null) {
            return Float.isFinite(rvpRefSpeed) ? Math.max(rvpRefSpeed, 0f) : 0.0;
        }
        float configuredMaxSpeed = getMaxSpeed();
        if (configuredMaxSpeed > 0f) {
            return configuredMaxSpeed;
        }
        if (Double.isFinite(fallbackSpeed) && fallbackSpeed > 0.0) {
            return fallbackSpeed;
        }
        return 3.0;
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

    /** @return 是否配置了非空推力曲线 {@code thrust_curve}。 */
    public boolean hasThrustCurve() {
        return thrustCurve != null && !thrustCurve.isEmpty();
    }

    /** @return 燃料质量原始值（未启用火箭发动机时返回 0），供调试与外部读取。 */
    public float getFuelMass() {
        return hasRocketEngine ? Math.max(fuelMass, 0f) : 0f;
    }

    /**
     * 解析点火后第 {@code motorTick} 个 Tick 的主发动机推力（RVP 游戏单位，与 {@link #thrust} 一致）。
     * 未配置 {@code thrust_curve} 时恒返回标量 {@link #getResolvedThrust()}；配置后曲线为权威：
     * 命中区间返回非负匹配值，未命中任何区间返回 0（区间外视为无推力）。
     */
    public float resolveThrustAt(int motorTick) {
        if (!hasThrustCurve()) {
            return getResolvedThrust();
        }
        for (Map.Entry<RVP_Range<Integer>, Float> entry : thrustCurve.entrySet()) {
            RVP_Range<Integer> range = entry.getKey();
            Float value = entry.getValue();
            if (range == null || value == null || !Float.isFinite(value) || !range.contains(motorTick)) {
                continue;
            }
            return Math.max(value, 0f);
        }
        return 0f;
    }

    /**
     * 解析点火后第 {@code motorTick} 个 Tick 的弹体质量（RVP 游戏单位，与 {@link #mass} 一致）。
     * 未启用变质量（{@code fuel_mass<=0} 或未启用火箭发动机）时恒返回发射总质量
     * {@link #getResolvedMass()}；启用后按主燃烧窗口线性递减：
     * {@code mass(motorTick) = (mass - fuelMass) + fuelMass * (1 - motorTick/motorBurnTime)}。
     * {@code motorTick} 负值按 0 处理，超过燃烧窗口按干质量处理。
     */
    public float resolveMassAt(int motorTick, float motorBurnTime) {
        float totalMass = getResolvedMass();
        if (!hasRocketEngine || fuelMass <= 0f || totalMass <= 0f) {
            return totalMass;
        }
        float burn = Math.max(motorBurnTime, 1f);
        float dryMass = Math.max(totalMass - fuelMass, 0f);
        float progress = Math.max(0f, Math.min(1f, motorTick / burn));
        return dryMass + fuelMass * (1f - progress);
    }

    /** @return 是否存在有效推力（标量推力或推力曲线任一即可），供推进判定使用。 */
    public boolean hasEffectiveThrust() {
        return getResolvedThrust() > 0f || hasThrustCurve();
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
        float sample = normalizeAltitudeSample(y);
        if (altitudeDragFactor == null) {
            return resolveDefaultAltitudeDragFactor(sample);
        }
        if (altitudeDragFactor.isEmpty()) {
            return 1.0f;
        }
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

    /** 未配置 JSON 倍率表时，在压缩后的现实大气密度锚点间平滑插值。 */
    private static float resolveDefaultAltitudeDragFactor(float y) {
        if (y <= DEFAULT_ALTITUDE_DRAG_HEIGHTS[0]) {
            return DEFAULT_ALTITUDE_DRAG_FACTORS[0];
        }
        for (int index = 1; index < DEFAULT_ALTITUDE_DRAG_HEIGHTS.length; index++) {
            float upperHeight = DEFAULT_ALTITUDE_DRAG_HEIGHTS[index];
            if (y <= upperHeight) {
                float lowerHeight = DEFAULT_ALTITUDE_DRAG_HEIGHTS[index - 1];
                float fraction = (y - lowerHeight) / (upperHeight - lowerHeight);
                float lowerFactor = DEFAULT_ALTITUDE_DRAG_FACTORS[index - 1];
                float upperFactor = DEFAULT_ALTITUDE_DRAG_FACTORS[index];
                return lowerFactor + (upperFactor - lowerFactor) * fraction;
            }
        }
        return DEFAULT_ALTITUDE_DRAG_FACTORS[DEFAULT_ALTITUDE_DRAG_FACTORS.length - 1];
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
                && hasEffectiveThrust()
                && getResolvedMotorBurnTime() > 0f;
    }

    public boolean isRocketEngineMisconfigured() {
        return hasRocketEngine && !usesPropulsion();
    }
}
