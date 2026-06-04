package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 子母弹/布撒参数。JSON 键 {@code submunition_data}。
 * 母弹飞行中按 {@link #delayTick}、{@link #intervalTick} 生成子弹体（见 {@link org.ywzj.rvp.entity.projectile.RVP_BaseBullet#tickSubmunition}）。
 */
public class RVP_SubmunitionData {

    /** 待释放子弹药总数；0 表示不启用子母弹逻辑。 */
    @SerializedName("count")
    private int count = 0;

    /** 出生后延迟多少 tick 开始释放第一枚子弹药。 */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** 相邻两枚子弹药释放间隔（tick）；0 表示同一 tick 齐射剩余全部。 */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /** 子弹药初速方向散布强度（角度/随机扰动，由母弹实现解释）。 */
    @SerializedName("spread")
    private float spread = 0.1f;

    public int getCount() {
        return Math.max(count, 0);
    }

    public int getDelayTick() {
        return Math.max(delayTick, 0);
    }

    public int getIntervalTick() {
        return Math.max(intervalTick, 0);
    }

    public float getSpread() {
        return Math.max(spread, 0f);
    }
}
