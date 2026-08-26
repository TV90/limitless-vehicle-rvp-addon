package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：RWR 伪造锁定同步（P1 占位，P4 再实现完整逻辑）。
 * 仅做网络占位，保证 P1 编译通过与包注册成功。
 */
public class S2CEcmFakeLock {

    /** 被干扰载具实体 id。 */
    private final int vehicleId;
    /** 伪造 radartype 文案列表。 */
    private final List<String> fakeRadarTypes;
    /** 剩余持续时长（tick）。 */
    private final int durationRemainTick;

    public S2CEcmFakeLock(int vehicleId, List<String> fakeRadarTypes, int durationRemainTick) {
        this.vehicleId = vehicleId;
        this.fakeRadarTypes = fakeRadarTypes == null ? List.of() : List.copyOf(fakeRadarTypes);
        this.durationRemainTick = durationRemainTick;
    }

    public static void encode(S2CEcmFakeLock msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeVarInt(msg.fakeRadarTypes.size());
        for (String s : msg.fakeRadarTypes) {
            buf.writeUtf(s, 64);
        }
        buf.writeVarInt(msg.durationRemainTick);
    }

    public static S2CEcmFakeLock decode(FriendlyByteBuf buf) {
        int vehicleId = buf.readVarInt();
        int count = buf.readVarInt();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(buf.readUtf(64));
        }
        int duration = buf.readVarInt();
        return new S2CEcmFakeLock(vehicleId, list, duration);
    }

    public static void handle(S2CEcmFakeLock msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientEcmFakeLockHandler.handle(msg.vehicleId, msg.fakeRadarTypes)));
        ctx.get().setPacketHandled(true);
    }
}
