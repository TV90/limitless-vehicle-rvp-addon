package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 ECM 被动电子战 HUD 状态：每载具的各 ECM 骨块干扰/充能快照。
 * 由 {@link org.ywzj.rvp.network.S2CEcmHudSync} 驱动更新。
 */
public final class RVP_EcmHudState {

    /** 单通道快照（对应一个 ECM 骨块）。 */
    public record ChannelSnapshot(String boneName, String displayName, int decoyCount, int chargeRemainTick) {
        /** 是否正在干扰（有存活假目标）。 */
        public boolean jamming() {
            return decoyCount > 0;
        }

        /** 是否正在充能。 */
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

    public static void update(int vehicleEntityId, List<String> boneNames, List<String> displayNames,
                              List<Integer> decoyCounts, List<Integer> chargeRemains) {
        if (vehicleEntityId <= 0 || boneNames == null) {
            return;
        }
        java.util.ArrayList<ChannelSnapshot> list = new java.util.ArrayList<>();
        for (int i = 0; i < boneNames.size(); i++) {
            String bone = boneNames.get(i);
            String display = i < displayNames.size() ? displayNames.get(i) : bone;
            int count = i < decoyCounts.size() ? decoyCounts.get(i) : 0;
            int charge = i < chargeRemains.size() ? chargeRemains.get(i) : 0;
            list.add(new ChannelSnapshot(bone, display, count, charge));
        }
        STATES.put(vehicleEntityId, new Snapshot(list));
    }

    @Nullable
    public static Snapshot get(int vehicleEntityId) {
        return STATES.get(vehicleEntityId);
    }

    public static void remove(int vehicleEntityId) {
        STATES.remove(vehicleEntityId);
    }

    private RVP_EcmHudState() {
    }
}
