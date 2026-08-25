package org.ywzj.rvp.ecm;

import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;

/**
 * 单台装备 ECM_PASSIVE 载具的被动电子战运行状态（服务端运行期持有，不入存档）。
 *
 * <p>状态流转：空闲 --敌对照射--> 激活 --激活时长耗尽--> 充能 --充能归零--> 空闲；
 * 照射距离低于烧穿阈值时任意状态直接转入充能并清场。</p>
 */
public final class RVP_EcmPassiveState {

    private boolean active;
    private int activeTicks;
    private int cooldownTicks;
    private boolean burnThrough;
    /** 当前生效的距离档配置（激活期间档位随照射源距离变化而重排假目标）。 */
    private BoneEcmPassiveConfig.Band appliedBand;
    /** 最后一次被敌对雷达照射的游戏时间。 */
    private long lastIlluminatedGameTime;
    /** 最后一次触发照射源的载具实体 id（调试用）。 */
    private int lastSourceVehicleId = -1;

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getActiveTicks() {
        return activeTicks;
    }

    public void setActiveTicks(int activeTicks) {
        this.activeTicks = activeTicks;
    }

    public int getCooldownTicks() {
        return cooldownTicks;
    }

    public void setCooldownTicks(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    public boolean isBurnThrough() {
        return burnThrough;
    }

    public void setBurnThrough(boolean burnThrough) {
        this.burnThrough = burnThrough;
    }

    public BoneEcmPassiveConfig.Band getAppliedBand() {
        return appliedBand;
    }

    public void setAppliedBand(BoneEcmPassiveConfig.Band appliedBand) {
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
