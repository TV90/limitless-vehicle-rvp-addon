package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 DIRCM HUD 状态：每载具的左右通道照射/充能快照。
 * 由 {@link org.ywzj.rvp.network.S2CDircmHudSync} 驱动更新。
 */
public final class RVP_DircmHudState {

    /** 单通道快照。 */
    public record ChannelSnapshot(String boneName, int targetId, int chargeRemainTick) {
        public boolean irradiating() {
            return targetId > 0;
        }

        public boolean charging() {
            return chargeRemainTick > 0;
        }
    }

    /** 单载具快照（通道按骨块名）。 */
    public record Snapshot(List<ChannelSnapshot> channels) {
        @Nullable
        public ChannelSnapshot byBone(String boneName) {
            if (channels == null) {
                return null;
            }
            for (ChannelSnapshot c : channels) {
                if (c.boneName().equals(boneName)) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();

    public static void update(int vehicleEntityId, List<String> boneNames,
                              List<Integer> targetIds, List<Integer> chargeRemains) {
        if (vehicleEntityId <= 0 || boneNames == null) {
            return;
        }
        java.util.ArrayList<ChannelSnapshot> list = new java.util.ArrayList<>();
        for (int i = 0; i < boneNames.size(); i++) {
            String bone = boneNames.get(i);
            int targetId = i < targetIds.size() ? targetIds.get(i) : -1;
            int charge = i < chargeRemains.size() ? chargeRemains.get(i) : 0;
            list.add(new ChannelSnapshot(bone, targetId, charge));
        }
        STATES.put(vehicleEntityId, new Snapshot(list));
    }

    @Nullable
    public static Snapshot get(int vehicleEntityId) {
        return STATES.get(vehicleEntityId);
    }

    /** 所有已收到 DIRCM 通道状态的载具 id（供光束渲染遍历，含旁观视角）。 */
    public static java.util.Set<Integer> vehicleIds() {
        return java.util.Collections.unmodifiableSet(STATES.keySet());
    }

    public static void remove(int vehicleEntityId) {
        STATES.remove(vehicleEntityId);
    }

    private RVP_DircmHudState() {
    }
}