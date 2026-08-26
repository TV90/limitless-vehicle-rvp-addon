package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：主动ECM 调试快照（单客户端/集成服务端亦可接收，走回环网络）。
 * 携带玩家自身载具 ECM 状态、本人被干扰的导弹数、附近正在放主动ECM 的载具清单，
 * 客户端由 F10 调试覆盖层渲染（{@link org.ywzj.rvp.client.gui.RVP_EcmDebugOverlay}）。
 */
public class S2CEcmDebug {

    /** 玩家自身载具是否装备主动ECM 骨块。 */
    private final boolean ownEquipped;
    /** 玩家自身载具主动ECM 是否正在释放。 */
    private final boolean ownActive;
    /** 自身主动ECM 剩余时长（tick）。 */
    private final int ownActiveRemain;
    /** 自身主动ECM 冷却剩余（tick）。 */
    private final int ownCooldownRemain;
    /** 本人发射、当前正被主动ECM 干扰的导弹数。 */
    private final int myMissilesJammed;
    /** 附近正在放主动ECM 的载具清单（截断到 24 条）。 */
    private final List<Entry> entries;

    /** 单条附近主动ECM 载具信息。 */
    public static final class Entry {
        /** 载具实体 id。 */
        public final int vehicleId;
        /** 距玩家距离（米，取整）。 */
        public final int dist;
        /** 弹药干扰半径。 */
        public final int ammoRadius;
        /** 载具干扰半径。 */
        public final int vehicleRadius;
        /** 主动释放剩余（tick）。 */
        public final int activeRemain;

        public Entry(int vehicleId, int dist, int ammoRadius, int vehicleRadius, int activeRemain) {
            this.vehicleId = vehicleId;
            this.dist = dist;
            this.ammoRadius = ammoRadius;
            this.vehicleRadius = vehicleRadius;
            this.activeRemain = activeRemain;
        }
    }

    public S2CEcmDebug(boolean ownEquipped, boolean ownActive, int ownActiveRemain, int ownCooldownRemain,
                       int myMissilesJammed, List<Entry> entries) {
        this.ownEquipped = ownEquipped;
        this.ownActive = ownActive;
        this.ownActiveRemain = ownActiveRemain;
        this.ownCooldownRemain = ownCooldownRemain;
        this.myMissilesJammed = myMissilesJammed;
        this.entries = entries;
    }

    public boolean ownEquipped() {
        return ownEquipped;
    }

    public boolean ownActive() {
        return ownActive;
    }

    public int ownActiveRemain() {
        return ownActiveRemain;
    }

    public int ownCooldownRemain() {
        return ownCooldownRemain;
    }

    public int myMissilesJammed() {
        return myMissilesJammed;
    }

    public List<Entry> entries() {
        return entries;
    }

    public static void encode(S2CEcmDebug msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.ownEquipped);
        buf.writeBoolean(msg.ownActive);
        buf.writeVarInt(msg.ownActiveRemain);
        buf.writeVarInt(msg.ownCooldownRemain);
        buf.writeVarInt(msg.myMissilesJammed);
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeVarInt(e.vehicleId);
            buf.writeVarInt(e.dist);
            buf.writeVarInt(e.ammoRadius);
            buf.writeVarInt(e.vehicleRadius);
            buf.writeVarInt(e.activeRemain);
        }
    }

    public static S2CEcmDebug decode(FriendlyByteBuf buf) {
        boolean ownEquipped = buf.readBoolean();
        boolean ownActive = buf.readBoolean();
        int ownActiveRemain = buf.readVarInt();
        int ownCooldownRemain = buf.readVarInt();
        int myMissilesJammed = buf.readVarInt();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new S2CEcmDebug(ownEquipped, ownActive, ownActiveRemain, ownCooldownRemain,
                myMissilesJammed, entries);
    }

    public static void handle(S2CEcmDebug msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.update(msg)));
        ctx.get().setPacketHandled(true);
    }
}
