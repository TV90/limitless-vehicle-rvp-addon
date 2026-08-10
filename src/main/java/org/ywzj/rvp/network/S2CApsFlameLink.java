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
        var random = mc.level.random;

        int points = Math.max(8, Math.min(40, (int) Math.ceil(len * 6)));
        double step = len / points;
        double speed = 0.02;
        for (int i = 0; i <= points; i++) {
            double t = i * step;
            Vec3 p = s.add(unit.scale(t));
            double jitter = 0.05;
            double jx = (random.nextDouble() - 0.5) * jitter;
            double jy = (random.nextDouble() - 0.5) * jitter;
            double jz = (random.nextDouble() - 0.5) * jitter;
            // force=true 对齐 effect_data 的 trajectory_particle：绕过 LevelRenderer 对
            // 非强制粒子的 32 格（1024²）距离裁剪，保证远处玩家视角下也能渲染出连线粒子
            mc.level.addParticle(
                    ParticleTypes.FLAME,
                    true,
                    p.x + jx,
                    p.y + jy,
                    p.z + jz,
                    unit.x * speed,
                    unit.y * speed,
                    unit.z * speed
            );
            // 远距离可见性：连线每隔 5 点穿插一个发光的膨胀爆炸点
            if (i % 5 == 0) {
                mc.level.addParticle(
                        ParticleTypes.EXPLOSION,
                        true,
                        p.x,
                        p.y,
                        p.z,
                        0.0D, 0.0D, 0.0D
                );
            }
        }
        // 拦截点小爆炸：发光环 + 少量火花，保证远距离也能看清拦截位置
        mc.level.addParticle(ParticleTypes.EXPLOSION, true, e.x, e.y, e.z, 0.0D, 0.0D, 0.0D);
        mc.level.addParticle(ParticleTypes.EXPLOSION, true, e.x, e.y, e.z + 0.1, 0.0D, 0.0D, 0.0D);
        for (int i = 0; i < 6; i++) {
            mc.level.addParticle(
                    ParticleTypes.FLAME,
                    true,
                    e.x, e.y, e.z,
                    (random.nextDouble() - 0.5) * 0.15,
                    random.nextDouble() * 0.12,
                    (random.nextDouble() - 0.5) * 0.15
            );
        }
    }
}

