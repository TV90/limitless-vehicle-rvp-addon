package org.ywzj.rvp.uav;

import java.util.UUID;

/** 当前乘坐实例的权威辅助载具身份，不以客户端车型配置或 uav 属性猜测父子关系。 */
public record RVP_AuxiliaryHudSnapshot(UUID vehicleUuid, Role role, int cooldownTicks,
                                       boolean deployed, boolean deployAllowed, boolean switchAllowed) {
    public enum Role { ORDINARY, PARENT, CHILD }

    /** 实际部署实例优先，哪怕该子机本身也有部署配置仍不能显示母车部署入口。 */
    public static Role classify(boolean deploymentInstance, boolean configured) {
        return deploymentInstance ? Role.CHILD : configured ? Role.PARENT : Role.ORDINARY;
    }

    /** 快照间只扣已流逝的游戏 tick；服务端暂停/时间回退不让计时增加，也不减成负数。 */
    public static int remainingTicks(int total, long receivedTick, long currentTick) {
        long elapsed = Math.max(0L, currentTick - receivedTick);
        return (int) Math.max(0L, Math.max(0, total) - elapsed);
    }

    /** 仅为展示更新倒计时，不把计时归零当成服务端已经允许部署。 */
    public RVP_AuxiliaryHudSnapshot elapsed(long receivedTick, long currentTick) {
        return new RVP_AuxiliaryHudSnapshot(vehicleUuid, role, remainingTicks(cooldownTicks, receivedTick, currentTick),
                deployed, deployAllowed, switchAllowed);
    }
}
