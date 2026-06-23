package org.ywzj.rvp.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record S2CApsFlameLink(int vehicleId, Vec3 start, Vec3 end) {

    public static void encode(S2CApsFlameLink msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeDouble(msg.start.x);
        buf.writeDouble(msg.start.y);
        buf.writeDouble(msg.start.z);
        buf.writeDouble(msg.end.x);
        buf.writeDouble(msg.end.y);
        buf.writeDouble(msg.end.z);
    }

    public static S2CApsFlameLink decode(FriendlyByteBuf buf) {
        int vehicleId = buf.readVarInt();
        Vec3 start = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 end = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new S2CApsFlameLink(vehicleId, start, end);
    }

    public static void handle(S2CApsFlameLink msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleClient(msg));
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2CApsFlameLink msg) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 s = msg.start;
        Vec3 e = msg.end;
        Vec3 dir = e.subtract(s);
        double len = dir.length();
        if (len < 1.0e-4) {
            return;
        }
        Vec3 unit = dir.scale(1.0 / len);

        int points = Math.max(8, Math.min(40, (int) Math.ceil(len * 6)));
        double step = len / points;
        double speed = 0.02;
        for (int i = 0; i <= points; i++) {
            double t = i * step;
            Vec3 p = s.add(unit.scale(t));
            double jitter = 0.05;
            double jx = (mc.level.random.nextDouble() - 0.5) * jitter;
            double jy = (mc.level.random.nextDouble() - 0.5) * jitter;
            double jz = (mc.level.random.nextDouble() - 0.5) * jitter;
            mc.level.addParticle(
                    ParticleTypes.FLAME,
                    p.x + jx,
                    p.y + jy,
                    p.z + jz,
                    unit.x * speed,
                    unit.y * speed,
                    unit.z * speed
            );
        }
    }
}

