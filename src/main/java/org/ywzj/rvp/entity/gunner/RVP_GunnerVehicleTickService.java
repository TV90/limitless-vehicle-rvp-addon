package org.ywzj.rvp.entity.gunner;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CGunnerVehicleSync;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Gunner 载具服务端 tick：区块加载 + 阵营/航向同步。
 *
 * <p>替代被删的两个公共数组 mixin：</p>
 * <ul>
 *   <li>{@code AbstractVehicleGunnerChunkLoadMixin}：Gunner 驾驶的载具保持区块加载
 *       （{@code EntityUtil.keepChunkLoaded} + 前方预加载），距离所有玩家超过
 *       {@value #MAX_CHUNK_DISTANCE} 区块时失效；</li>
 *   <li>{@code AbstractVehicleGunnerDataMixin} 的服务端侧：向客户端推送 Gunner 阵营与航向
 *       （客户端侧表 {@code RVP_ClientGunnerVehicleState} 存储）。</li>
 * </ul>
 *
 * <p>Forge 1.20.1 无 {@code TickEvent.EntityTickEvent}，故用 {@code ServerTickEvent} +
 * 低频扫描替代原 tick TAIL 注入。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GunnerVehicleTickService {

    private static final int TICK_INTERVAL = 5;
    /** 距离玩家超过此区块数后，区块强载器失效。96 区块 = 1536 格。 */
    private static final int MAX_CHUNK_DISTANCE = 96;
    /** 前方预加载距离（格），与本体 UAV 一致。 */
    private static final double LOOK_AHEAD_DISTANCE = 16.0;
    /** 阵营/航向同步距离（与本体远程实体广播的扩展距离一致）。 */
    private static final double SYNC_RANGE = 256.0D * 16.0D;
    private static final double SYNC_RANGE_SQ = SYNC_RANGE * SYNC_RANGE;

    private RVP_GunnerVehicleTickService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % TICK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<AbstractVehicle> gunnerVehicles = collectGunnerVehicles(level);
            for (AbstractVehicle vehicle : gunnerVehicles) {
                tickChunkLoad(level, vehicle);
            }
            syncFactionToPlayers(event.getServer(), level, gunnerVehicles);
        }
    }

    private static List<AbstractVehicle> collectGunnerVehicles(ServerLevel level) {
        List<AbstractVehicle> out = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof AbstractVehicle vehicle && isGunnerDriven(vehicle)) {
                out.add(vehicle);
            }
        }
        return out;
    }

    private static boolean isGunnerDriven(AbstractVehicle vehicle) {
        if (!vehicle.isAlive() || vehicle.uav || vehicle.isDestroyed()) {
            return false;
        }
        return vehicle.getDriver() instanceof GunnerEntity;
    }

    private static void tickChunkLoad(ServerLevel level, AbstractVehicle vehicle) {
        if (isTooFarFromAnyPlayer(level, vehicle)) {
            return;
        }
        EntityUtil.keepChunkLoaded(vehicle, vehicle.position());
        EntityUtil.keepChunkLoaded(vehicle, vehicle.position().add(
                vehicle.getLookAngle().normalize().scale(LOOK_AHEAD_DISTANCE)));
    }

    private static boolean isTooFarFromAnyPlayer(ServerLevel level, AbstractVehicle vehicle) {
        int vehicleChunkX = vehicle.blockPosition().getX() >> 4;
        int vehicleChunkZ = vehicle.blockPosition().getZ() >> 4;
        for (ServerPlayer player : level.players()) {
            int dx = Math.abs(vehicleChunkX - (player.blockPosition().getX() >> 4));
            int dz = Math.abs(vehicleChunkZ - (player.blockPosition().getZ() >> 4));
            if (dx <= MAX_CHUNK_DISTANCE && dz <= MAX_CHUNK_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    private static void syncFactionToPlayers(MinecraftServer server, ServerLevel level,
                                             List<AbstractVehicle> gunnerVehicles) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.serverLevel() != level) {
                continue;
            }
            if (!(player.getVehicle() instanceof AbstractVehicle)) {
                continue;
            }
            List<S2CGunnerVehicleSync.Entry> entries = new ArrayList<>();
            for (AbstractVehicle vehicle : gunnerVehicles) {
                if (vehicle == player.getVehicle()) {
                    continue;
                }
                if (player.distanceToSqr(vehicle) > SYNC_RANGE_SQ) {
                    continue;
                }
                LivingEntity driver = vehicle.getDriver();
                if (!(driver instanceof GunnerEntity gunner)) {
                    continue;
                }
                RVP_EnumGunnerFaction faction = gunner.getProfileFaction();
                if (faction == null) {
                    continue;
                }
                entries.add(new S2CGunnerVehicleSync.Entry(
                        vehicle.getId(), vehicle.getYRot(), vehicle.getXRot(), faction));
            }
            // 空快照也发送，用于清除客户端残留的过期阵营/航向
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CGunnerVehicleSync(level.dimension().location(), entries));
        }
    }
}
