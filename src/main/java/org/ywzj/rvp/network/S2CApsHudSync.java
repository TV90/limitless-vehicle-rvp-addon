package org.ywzj.rvp.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ApsHudState;

import java.util.function.Supplier;

public final class S2CApsHudSync {

    private final int vehicleEntityId;
    private final int ammoCurrent;
    private final int ammoMax;
    private final int reloadOneTick;
    private final int reloadProgressTick;

    public S2CApsHudSync(int vehicleEntityId, int ammoCurrent, int ammoMax, int reloadOneTick, int reloadProgressTick) {
        this.vehicleEntityId = vehicleEntityId;
        this.ammoCurrent = ammoCurrent;
        this.ammoMax = ammoMax;
        this.reloadOneTick = reloadOneTick;
        this.reloadProgressTick = reloadProgressTick;
    }

    public static void encode(S2CApsHudSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeVarInt(msg.ammoCurrent);
        buf.writeVarInt(msg.ammoMax);
        buf.writeVarInt(msg.reloadOneTick);
        buf.writeVarInt(msg.reloadProgressTick);
    }

    public static S2CApsHudSync decode(FriendlyByteBuf buf) {
        return new S2CApsHudSync(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    public static void handle(S2CApsHudSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (Minecraft.getInstance().level == null) {
                return;
            }
            RVP_ApsHudState.update(
                    msg.vehicleEntityId,
                    msg.ammoCurrent,
                    msg.ammoMax,
                    msg.reloadOneTick,
                    msg.reloadProgressTick
            );
        });
        ctx.get().setPacketHandled(true);
    }
}

