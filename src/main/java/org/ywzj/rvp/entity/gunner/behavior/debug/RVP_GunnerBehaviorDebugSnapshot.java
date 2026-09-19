package org.ywzj.rvp.entity.gunner.behavior.debug;

import java.util.List;
import java.util.Map;

/** 单 tick 行为候选、通道胜者、拒绝原因与动作结果的不可变调试快照。 */
public record RVP_GunnerBehaviorDebugSnapshot(
        /** 快照对应的游戏时间。 */ long gameTime,
        /** 当前 Profile ID。 */ String profileId,
        /** 所有已提交候选的稳定描述。 */ List<String> candidates,
        /** 以“通道/资源键”为键的胜者描述。 */ Map<String, String> winners,
        /** 被仲裁拒绝的候选及原因。 */ List<String> rejections,
        /** 已执行胜者及动作层结果。 */ Map<String, String> results) {

    /** 创建完全空的初始快照。 */
    public static RVP_GunnerBehaviorDebugSnapshot empty() {
        return new RVP_GunnerBehaviorDebugSnapshot(-1L, "", List.of(), Map.of(), List.of(), Map.of());
    }
}
