package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager;
import org.ywzj.rvp.client.nuclear.RVP_NuclearVisualManager;

import java.util.function.Supplier;

public record S2CNuclearVisualEffect(
        String preset,
        double x,
        double y,
        double z,
        int groundY,
        float effectYield,
        float visualScale,
        float visualDensity,
        long seed,
        long startGameTime,
        boolean sound,
        boolean flash,
        boolean shake
) {

    public static void encode(S2CNuclearVisualEffect msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.preset, 16);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeInt(msg.groundY);
        buf.writeFloat(msg.effectYield);
        buf.writeFloat(msg.visualScale);
        buf.writeFloat(msg.visualDensity);
        buf.writeLong(msg.seed);
        buf.writeLong(msg.startGameTime);
        buf.writeBoolean(msg.sound);
        buf.writeBoolean(msg.flash);
        buf.writeBoolean(msg.shake);
    }

    public static S2CNuclearVisualEffect decode(FriendlyByteBuf buf) {
        return new S2CNuclearVisualEffect(
                buf.readUtf(16),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readLong(), buf.readLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(S2CNuclearVisualEffect msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    if ("nuclear".equalsIgnoreCase(msg.preset()) || "nuke".equalsIgnoreCase(msg.preset())) {
                        RVP_NuclearVisualManager.spawn(msg);
                    } else {
                        RVP_ExplosionVisualManager.spawn(msg);
                    }
                }));
    }
}
