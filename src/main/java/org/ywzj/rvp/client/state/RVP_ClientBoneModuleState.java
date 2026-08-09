package org.ywzj.rvp.client.state;

import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 客户端骨骼模块状态侧表：骨块失效模块集合按载具 entityId 存储。
 *
 * <p>数据经 {@link S2CBoneModuleState} 推送后在此按 entityId 存表，供动画脚本经
 * {@code context.rvp_isModuleActive(bone, type)} 查询并隐藏失效骨块。</p>
 */
public final class RVP_ClientBoneModuleState {

    private static final Map<Integer, Map<String, Set<BoneModuleType>>> INACTIVE_MODULES = new HashMap<>();

    private RVP_ClientBoneModuleState() {
    }

    public static void apply(S2CBoneModuleState msg) {
        if (msg.inactiveModules.isEmpty()) {
            INACTIVE_MODULES.remove(msg.entityId);
        } else {
            INACTIVE_MODULES.put(msg.entityId, msg.inactiveModules);
        }
    }

    /** 骨块的某模块是否仍处于激活（未被消耗）。 */
    public static boolean isModuleActive(int entityId, String boneName, BoneModuleType type) {
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.get(entityId);
        if (boneMap == null) {
            return true;
        }
        Set<BoneModuleType> types = boneMap.get(boneName);
        return types == null || !types.contains(type);
    }

    /** 兼容旧调用：骨块的 ERA 模块是否仍激活。 */
    public static boolean isEraActive(int entityId, String boneName) {
        return isModuleActive(entityId, boneName, BoneModuleType.ERA);
    }

    public static void clear() {
        INACTIVE_MODULES.clear();
    }
}
