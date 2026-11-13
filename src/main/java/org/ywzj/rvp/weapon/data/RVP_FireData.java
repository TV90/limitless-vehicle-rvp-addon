package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

/**
 * Runtime model for {@code fire_data}.
 */
public class RVP_FireData {

    @SerializedName("require_lock")
    private boolean requireLock = true;

    @SerializedName("fire_mode")
    @JsonAdapter(RVP_EnumFireModeAdapter.class)
    private RVP_EnumFireMode fireMode = RVP_EnumFireMode.FULL_AUTO;

    /**
     * Optional spread override in degrees. When omitted, the weapon-level spread is used.
     */
    @SerializedName("spread")
    private Float spread;

    @SerializedName("burst_count")
    private int burstCount = 1;

    @SerializedName("burst_delay")
    private long burstDelay = 0L;

    /**
     * Charge-up / spool-up duration in ticks.
     */
    @SerializedName("charge_tick")
    private int chargeTick = 10;

    /**
     * Total decay duration from full charge/spin back to zero.
     */
    @SerializedName("charge_decay_tick")
    private int chargeDecayTick = 2;

    /**
     * Linear damage / muzzle-speed multiplier scale for charge-based fire modes.
     */
    @SerializedName("charge_power_scale")
    private float chargePowerScale = 1f;

    @SerializedName("heat_count")
    private int heatCount = 0;

    @SerializedName("max_heat_count")
    private int maxHeatCount = 0;

    @SerializedName("overheat_extra_heat")
    private int overheatExtraHeat = 30;

    /** 过热文案的翻译键（如 overlay.ywzj_rvp.heat.loader）；空 = 默认“过热”。 */
    @SerializedName("heat_label")
    private String heatLabel;

    /** 过热文案中文翻译键/文本；空时回退 heat_label。 */
    @SerializedName("heat_label_CN")
    private String heatLabelCn;

    /**
     * 停火后的过热恢复加速：每停火 1 秒，冷却速度额外增加该值（热/tick）。
     * 0 = 保持固定 1 热/tick 冷却；默认只在部分机炮上启用。
     */
    @SerializedName("heat_decay_accel")
    private double heatDecayAccel = 0.0;

    /**
     * Maximum off-axis launch angle in degrees.
     * {@code null} keeps legacy unrestricted firing.
     */
    @SerializedName("max_off_axis_shoot_angle")
    private Integer maxOffAxisShootAngle;

    @SerializedName("canister_count")
    private int canisterCount = 0;

    @SerializedName("canister_type")
    private int canisterType = 1;

    @SerializedName("canister_distribution")
    private String canisterDistribution = RVP_EnumSpreadDistribution.UNIFORM.getSerializedName();

    @SerializedName("canister_shape")
    private String canisterShape = RVP_EnumSpreadShape.CIRCLE.getSerializedName();

    @SerializedName("canister_diff")
    private float canisterDiff = 0.3f;

    @SerializedName("canister_burst_delay_time")
    private float canisterBurstDelayTime = 0f;

    @SerializedName("canister_burst_count")
    private int canisterBurstCount = 1;

    /**
     * 载具原地自爆开关，无单位，默认 false；仅 rvp:bomb 生效。
     * true 时消耗一枚弹药并使用该武器战斗部终结当前载具；RVP 部署实例先回传真玩家，
     * 普通有人载具不保护乘员。回传失败时不消耗弹药、不启动冷却、不起爆。
     */
    @SerializedName("self_destruct_vehicle")
    private boolean selfDestructVehicle = false;

    public RVP_EnumFireMode getFireMode() {
        return fireMode == null ? RVP_EnumFireMode.FULL_AUTO : fireMode;
    }

    public boolean isRequireLock() {
        return requireLock;
    }

    public int getChargeTick() {
        return Math.max(chargeTick, 0);
    }

    public float getChargePowerScale() {
        return Math.max(chargePowerScale, 0f);
    }

    public int getChargeDecayTick() {
        return Math.max(chargeDecayTick, 1);
    }

    public int getHeatCount() {
        return Math.max(heatCount, 0);
    }

    public int getMaxHeatCount() {
        return Math.max(maxHeatCount, 0);
    }

    public int getOverheatExtraHeat() {
        return Math.max(overheatExtraHeat, 0);
    }

    public String getHeatLabel() {
        return heatLabel == null ? "" : heatLabel.trim();
    }

    public String getHeatLabelCn() {
        return heatLabelCn == null ? "" : heatLabelCn.trim();
    }

    public double getHeatDecayAccel() {
        return Math.max(heatDecayAccel, 0.0);
    }

    public Integer getMaxOffAxisShootAngle() {
        if (maxOffAxisShootAngle == null) {
            return null;
        }
        return Math.max(maxOffAxisShootAngle, 0);
    }

    public int getCanisterCount() {
        return Math.max(canisterCount, 1);
    }

    public Float getSpreadOverride() {
        return spread;
    }

    public boolean hasSpreadOverride() {
        return spread != null;
    }

    public boolean isCanister() {
        return canisterCount > 1;
    }

    public int getCanisterType() {
        return Math.max(0, Math.min(canisterType, 2));
    }

    public RVP_EnumSpreadDistribution getCanisterDistribution() {
        return RVP_EnumSpreadDistribution.fromString(canisterDistribution);
    }

    public RVP_EnumSpreadShape getCanisterShape() {
        return RVP_EnumSpreadShape.forCanister(canisterShape);
    }

    public float getCanisterDiff() {
        return Math.max(canisterDiff, 0f);
    }

    public float getCanisterBurstDelayTime() {
        return Math.max(canisterBurstDelayTime, 0f);
    }

    public int getCanisterBurstCount() {
        return Math.max(canisterBurstCount, 1);
    }

    public int getBurstCount() {
        return Math.max(burstCount, 1);
    }

    public long getBurstDelay() {
        return Math.max(burstDelay, 0L);
    }

    /** 是否请求通过 rvp:bomb 终结当前部署载具。 */
    public boolean isSelfDestructVehicle() {
        return selfDestructVehicle;
    }
}
