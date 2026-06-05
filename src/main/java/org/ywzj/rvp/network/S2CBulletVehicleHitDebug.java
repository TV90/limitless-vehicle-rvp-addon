package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 载具命中调试：在客户端 HUD 显示入射角、距离与伤害倍率。 */
public class S2CBulletVehicleHitDebug {

    public float incidenceAngleDeg;
    public float distanceM;
    public float totalMultiplier;
    public float distanceMultiplier;
    public float incidenceMultiplier;
    public float penetrationMultiplier;
    public float vehicleTypeMultiplier;

    public static S2CBulletVehicleHitDebug create(float incidenceAngleDeg, float distanceM,
                                                   float totalMultiplier, float distanceMultiplier,
                                                   float incidenceMultiplier, float penetrationMultiplier,
                                                   float vehicleTypeMultiplier) {
        S2CBulletVehicleHitDebug msg = new S2CBulletVehicleHitDebug();
        msg.incidenceAngleDeg = incidenceAngleDeg;
        msg.distanceM = distanceM;
        msg.totalMultiplier = totalMultiplier;
        msg.distanceMultiplier = distanceMultiplier;
        msg.incidenceMultiplier = incidenceMultiplier;
        msg.penetrationMultiplier = penetrationMultiplier;
        msg.vehicleTypeMultiplier = vehicleTypeMultiplier;
        return msg;
    }

    public static void encode(S2CBulletVehicleHitDebug msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.incidenceAngleDeg);
        buf.writeFloat(msg.distanceM);
        buf.writeFloat(msg.totalMultiplier);
        buf.writeFloat(msg.distanceMultiplier);
        buf.writeFloat(msg.incidenceMultiplier);
        buf.writeFloat(msg.penetrationMultiplier);
        buf.writeFloat(msg.vehicleTypeMultiplier);
    }

    public static S2CBulletVehicleHitDebug decode(FriendlyByteBuf buf) {
        S2CBulletVehicleHitDebug msg = new S2CBulletVehicleHitDebug();
        msg.incidenceAngleDeg = buf.readFloat();
        msg.distanceM = buf.readFloat();
        msg.totalMultiplier = buf.readFloat();
        msg.distanceMultiplier = buf.readFloat();
        msg.incidenceMultiplier = buf.readFloat();
        msg.penetrationMultiplier = buf.readFloat();
        msg.vehicleTypeMultiplier = buf.readFloat();
        return msg;
    }

    public static void handle(S2CBulletVehicleHitDebug msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientBulletHitDebugState.push(msg)));
    }
}
