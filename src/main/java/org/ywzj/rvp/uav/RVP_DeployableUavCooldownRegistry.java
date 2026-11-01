package org.ywzj.rvp.uav;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** 兼容旧部署调用点的冷却 facade；权威数据保存在 overworld SavedData。 */
public final class RVP_DeployableUavCooldownRegistry {
    private RVP_DeployableUavCooldownRegistry() {}

    /** 为母车 UUID 持久化冷却截止时间；清理运行态不会绕过存档冷却。 */
    public static void startCooldown(ServerLevel level, UUID parentUuid, int cooldownTick) {
        if (level == null || parentUuid == null || cooldownTick <= 0) return;
        RVP_DeployableUavStateSavedData.startCooldown(level, parentUuid, cooldownTick);
    }

    /** 读取母车剩余冷却 tick，按稳定世界时间计算。 */
    public static int getRemainingTick(ServerLevel level, UUID parentUuid) {
        if (level == null || parentUuid == null) return 0;
        return RVP_DeployableUavStateSavedData.remainingCooldown(level, parentUuid);
    }

    /** 旧清理入口保留为空操作，避免清除持久化冷却并允许重启绕过。 */
    public static void clear(UUID parentUuid) {}

    /** 只清运行态；选择、冷却与部署租约均由 SavedData 保存。 */
    public static void clearRuntime() {}
}