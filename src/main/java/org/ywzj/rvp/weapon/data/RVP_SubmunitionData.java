package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 子母弹/投放物参数。用于控制载荷释放数量、延迟、间隔和散布。
 */
public class RVP_SubmunitionData {

    /** 子弹药数量，0 表示不释放子弹药。 */
    @SerializedName("count")
    private int count = 0;

    /** 出生后延迟多少 tick 开始释放子弹药。 */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** 子弹药释放间隔；0 表示一次性释放全部。 */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /** 子弹药速度散布。 */
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
