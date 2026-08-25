package org.ywzj.rvp.ecm;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;

/**
 * 单台装备 ECM_PASSIVE 载具的被动电子战运行状态（服务端运行期持有，不入存档）。
 *
 * <p>一次性脉冲模型（无激活期）：</p>
 * <pre>
 * 空闲 --被敌对雷达照射(距离≥烧穿)--> 撒一波假目标 --> 充能(cooldownTicks) --> 空闲
 * 空闲/任意 --照射距离&lt;烧穿--> 清除现存假目标 + 进入/刷新充能
 * </pre>
 *
 * <p>假目标是普通实体，带独立寿命（≈一个充能周期）：撒出后自主漂移，
 * 到期或被击落即消失；期间不做任何补货——下一波要等充能结束后再次被照射。</p>
 */
public final class RVP_EcmPassiveState {

    /** 剩余充能 tick：>0 期间再次被照射不生成。 */
    private int cooldownTicks;
    /** 最近一次脉冲的距离档（供导引头欺骗概率查询）。 */
    @Nullable
    private BoneEcmPassiveConfig.Band appliedBand;
    /** 最后一次被敌对雷达照射的游戏时间（空闲清理用）。 */
    private long lastIlluminatedGameTime;
    /** 最后一次触发照射源的载具实体 id（调试用）。 */
    private int lastSourceVehicleId = -1;

    public int getCooldownTicks() {
        return cooldownTicks;
    }

    public void setCooldownTicks(int cooldownTicks) {
        this.cooldownTicks = Math.max(0, cooldownTicks);
    }

    public @Nullable BoneEcmPassiveConfig.Band getAppliedBand() {
        return appliedBand;
    }

    public void setAppliedBand(@Nullable BoneEcmPassiveConfig.Band appliedBand) {
        this.appliedBand = appliedBand;
    }

    public long getLastIlluminatedGameTime() {
        return lastIlluminatedGameTime;
    }

    public void setLastIlluminatedGameTime(long lastIlluminatedGameTime) {
        this.lastIlluminatedGameTime = lastIlluminatedGameTime;
    }

    public int getLastSourceVehicleId() {
        return lastSourceVehicleId;
    }

    public void setLastSourceVehicleId(int lastSourceVehicleId) {
        this.lastSourceVehicleId = lastSourceVehicleId;
    }
}
