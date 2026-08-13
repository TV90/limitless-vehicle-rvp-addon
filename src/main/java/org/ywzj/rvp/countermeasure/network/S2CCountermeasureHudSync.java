package org.ywzj.rvp.countermeasure.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：干扰物 HUD 状态同步（剩余 / 总数 / 装填剩余），节流推送，对齐 S2CApsHudSync。
 */
public final class S2CCountermeasureHudSync {

    private final int vehicleEntityId;
    private final int flareRemain;
    private final int flareTotal;
    private final int flareReloadRemain;
    private final int chaffRemain;
    private final int chaffTotal;
    private final int chaffReloadRemain;

    public S2CCountermeasureHudSync(int vehicleEntityId,
                                    int flareRemain, int flareTotal, int flareReloadRemain,
                                    int chaffRemain, int chaffTotal, int chaffReloadRemain) {
        this.vehicleEntityId = vehicleEntityId;
        this.flareRemain = flareRemain;
        this.flareTotal = flareTotal;
        this.flareReloadRemain = flareReloadRemain;
        this.chaffRemain = chaffRemain;
        this.chaffTotal = chaffTotal;
        this.chaffReloadRemain = chaffReloadRemain;
    }

    public static void encode(S2CCountermeasureHudSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeVarInt(msg.flareRemain);
        buf.writeVarInt(msg.flareTotal);
        buf.writeVarInt(msg.flareReloadRemain);
        buf.writeVarInt(msg.chaffRemain);
        buf.writeVarInt(msg.chaffTotal);
        buf.writeVarInt(msg.chaffReloadRemain);
    }

    public static S2CCountermeasureHudSync decode(FriendlyByteBuf buf) {
        return new S2CCountermeasureHudSync(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    public static void handle(S2CCountermeasureHudSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (Minecraft.getInstance().level == null) {
                return;
            }
            RVP_CountermeasureHudState.update(
                    msg.vehicleEntityId,
                    msg.flareRemain, msg.flareTotal, msg.flareReloadRemain,
                    msg.chaffRemain, msg.chaffTotal, msg.chaffReloadRemain
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
