package org.ywzj.rvp.client.state;

import org.ywzj.rvp.network.S2CVehicleEraState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 客户端 ERA 状态侧表：失效骨块集合按载具 entityId 存储。
 *
 * <p>替代被删 {@code AbstractVehicleEraStateMixin}（实体上的 {@code rvp$inactiveEraBones}
 * 字段不再存在）：数据经 {@link S2CVehicleEraState} 推送后在此按 entityId 存表。</p>
 */
public final class RVP_ClientVehicleEraState {

    private static final Map<Integer, Set<String>> INACTIVE_BONES = new HashMap<>();

    private RVP_ClientVehicleEraState() {
    }

    public static void apply(S2CVehicleEraState msg) {
        Set<String> bones = msg.inactiveBones.isEmpty() ? Set.of() : Set.copyOf(msg.inactiveBones);
        if (bones.isEmpty()) {
            INACTIVE_BONES.remove(msg.entityId);
        } else {
            INACTIVE_BONES.put(msg.entityId, bones);
        }
    }

    /** 骨块是否仍处于激活（未被消耗）。 */
    public static boolean isEraActive(int entityId, String boneName) {
        Set<String> bones = INACTIVE_BONES.get(entityId);
        return bones == null || !bones.contains(boneName);
    }

    public static void clear() {
        INACTIVE_BONES.clear();
    }
}
