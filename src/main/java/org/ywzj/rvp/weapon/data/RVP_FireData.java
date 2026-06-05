package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

/**
 * 武器开火侧参数。扩展包 JSON 中的 {@code fire_data} 字段会反序列化到这里。
 */
public class RVP_FireData {

    /**
     * 开火模式，见 {@link RVP_EnumFireMode}。
     * JSON 须写枚举名（如 {@code FULL_AUTO}）；无法识别时默认为全自动。
     */
    @SerializedName("fire_mode")
    @JsonAdapter(RVP_EnumFireModeAdapter.class)
    private RVP_EnumFireMode fireMode = RVP_EnumFireMode.FULL_AUTO;

    /**
     * 发射角度散布（度），优先于武器顶层 {@code inaccuracy}（见 {@link RVP_WeaponData#getInaccuracy()}）。
     * 未写该字段时使用顶层 {@code inaccuracy}；写了则含显式 {@code 0}。
     * 单发时叠加到每枚弹丸瞄准；多发霰弹（{@link #canisterCount} &gt; 1）时作为**每轮齐射**束心随机偏移（度），
     * 各弹丸再叠加 {@link #canisterDiff} 网格/圆盘散布。
     */
    @SerializedName("spread")
    private Float spread;

    /**
     * 仅 {@link RVP_EnumFireMode#BURST}：每一轮点射内发射的弹数（各发间隔仍受武器 {@code shoot_interval} 限制）。
     */
    @SerializedName("burst_count")
    private int burstCount = 1;

    /**
     * 仅 {@link RVP_EnumFireMode#BURST}：两轮点射之间的等待时间（毫秒）。
     */
    @SerializedName("burst_delay")
    private long burstDelay = 0L;

    /**
     * 蓄力/转速爬升时长（tick）。
     * {@link RVP_EnumFireMode#CHARGE}、{@link RVP_EnumFireMode#RAILGUN}：蓄满所需 tick；
     * {@link RVP_EnumFireMode#MINIGUN}：转速爬满 tick。
     */
    @SerializedName("charge_time")
    private int chargeTime = 0;

    /**
     * 蓄力或转速比例对伤害、初速的线性倍率；{@code 1} 表示不放大。
     * 用于 {@link RVP_EnumFireMode#CHARGE}、{@link RVP_EnumFireMode#MINIGUN}、{@link RVP_EnumFireMode#RAILGUN}。
     */
    @SerializedName("charge_power_scale")
    private float chargePowerScale = 1f;

    /**
     * 仅 {@link RVP_EnumFireMode#MINIGUN}：松开开火键后每 tick 转速衰减量（内部 spinTick 减少值）。
     * 未写或 ≤0 时默认 {@code max(charge_time / 4, 1)}。
     */
    @SerializedName("minigun_spin_decay_tick")
    private int minigunSpinDecayTick = 0;

    /**
     * 单次开火发射的弹丸数量；未写或 ≤0 时视为 1。
     * 大于 1 时使用 {@link #canisterType}、{@link #canisterDiff} 等多弹丸散布逻辑。
     */
    @SerializedName("canister_count")
    private int canisterCount = 0;

    /**
     * 多弹丸散布类型：{@code 0} 位置散布，{@code 1} 角度散布，
     * {@code 2} 角度散布并沿弹道前向错位以模拟时间散布。
     */
    @SerializedName("canister_type")
    private int canisterType = 1;

    /**
     * 霰弹散布密度，与投放器 {@code distribution} 同枚举（{@link RVP_EnumSpreadDistribution}）。
     * 圆盘散布时控制随机密度；{@code square} 时决定在矩形网格上优先占用哪些格（见 {@link org.ywzj.rvp.weapon.spread.RVP_CanisterGridLayout}）。
     */
    @SerializedName("canister_distribution")
    private String canisterDistribution = RVP_EnumSpreadDistribution.UNIFORM.getSerializedName();

    /**
     * 霰弹散布 footprint：{@code circle}（默认，随机圆盘/圆锥）或 {@code square}（矩形网格，如 16 弹为 4×4、12 弹为 4×3）。
     * 见 {@link RVP_EnumSpreadShape#forCanister(String)}、{@link org.ywzj.rvp.weapon.spread.RVP_CanisterGridLayout}。
     */
    @SerializedName("canister_shape")
    private String canisterShape = RVP_EnumSpreadShape.CIRCLE.getSerializedName();

    /**
     * 多弹丸散布强度；{@link #canisterType} 为 1 或 2 时表示角度散布幅度，推荐 &gt; 0.5。
     */
    @SerializedName("canister_diff")
    private float canisterDiff = 0.3f;

    /**
     * {@link #canisterType} 为 2 时沿弹道方向的前向弹丸间距（方块），用于模拟时间散布，非点射间隔。
     */
    @SerializedName("canister_burst_delay_time")
    private float canisterBurstDelayTime = 0f;

    /**
     * 单次 {@code shoot()} 调用内连续齐射的轮数；每轮发射 {@link #canisterCount} 枚，同 tick 完成。
     */
    @SerializedName("canister_burst_count")
    private int canisterBurstCount = 1;


    public RVP_EnumFireMode getFireMode() {
        return fireMode == null ? RVP_EnumFireMode.FULL_AUTO : fireMode;
    }

    public int getChargeTime() {
        return Math.max(chargeTime, 0);
    }

    public float getChargePowerScale() {
        return Math.max(chargePowerScale, 0f);
    }

    public int getMinigunSpinDecayTick() {
        if (minigunSpinDecayTick > 0) {
            return minigunSpinDecayTick;
        }
        int charge = getChargeTime();
        return Math.max(charge / 4, 1);
    }

    public int getCanisterCount() {
        return Math.max(canisterCount, 1);
    }

    public Float getSpreadOverride() {
        return spread;
    }

    /** {@link #canisterCount} &gt; 1 时走多弹丸散布路径（见 {@link org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon}）。 */
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
}
