package org.ywzj.rvp.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * 服务端：向命中点附近玩家发送载具命中调试 HUD 数据。
 */
public final class RVP_BulletHitDebugNetworking {

    private static final double NOTIFY_RADIUS = 128.0;

    private RVP_BulletHitDebugNetworking() {}

    public static void notifyVehicleHit(ServerLevel level, Vec3 hitPos,
                                        float incidenceAngleDeg, float distanceM,
                                        float totalMultiplier, float distanceMultiplier,
                                        float incidenceMultiplier, float penetrationMultiplier,
                                        float vehicleTypeMultiplier,
                                        float hitboxMultiplier, String hitboxBoneName) {
        S2CBulletVehicleHitDebug msg = S2CBulletVehicleHitDebug.create(
                incidenceAngleDeg, distanceM, totalMultiplier,
                distanceMultiplier, incidenceMultiplier, penetrationMultiplier, vehicleTypeMultiplier,
                hitboxMultiplier, hitboxBoneName);
        RVP_Network.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                        hitPos.x, hitPos.y, hitPos.z, NOTIFY_RADIUS, level.dimension())),
                msg);
    }
}
