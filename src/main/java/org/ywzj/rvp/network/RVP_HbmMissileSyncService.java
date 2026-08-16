package org.ywzj.rvp.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HbmMissileSyncService {

    private static final String HBM_MOD_ID = "hbm_ntm_rebirth";
    private static final double SYNC_RANGE = 6144.0;
    private static final int SYNC_INTERVAL_TICKS = 5;

    private RVP_HbmMissileSyncService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ModList.get().isLoaded(HBM_MOD_ID)) {
            return;
        }
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.serverLevel() == null) {
                continue;
            }
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
        }
    }

    private static S2CHbmMissileSnapshot buildSnapshot(ServerPlayer player) {
        S2CHbmMissileSnapshot msg = new S2CHbmMissileSnapshot();
        net.minecraft.server.level.ServerLevel serverLevel = player.serverLevel();
        msg.dimension = serverLevel.dimension().location();
        List<S2CHbmMissileSnapshot.Entry> entries = new ArrayList<>();
        Vec3 playerPos = player.position();
        double rangeSqr = SYNC_RANGE * SYNC_RANGE;
        // O(实体) 遍历已加载实体，替代 ±SYNC_RANGE(6144) 立方体 getEntities（12288³，
        // 每玩家每 tick 的 section 索引遍历灾难级 TPS）
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (entity.isRemoved() || !entity.isAlive() || !RVP_RadarContactHelper.isHbmMissile(entity)) {
                continue;
            }
            if (entity.position().distanceToSqr(playerPos) > rangeSqr) {
                continue;
            }
            entries.add(toEntry(player, entity));
        }
        msg.entries = entries;
        return msg;
    }

    private static S2CHbmMissileSnapshot.Entry toEntry(ServerPlayer player, Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        return new S2CHbmMissileSnapshot.Entry(
                entity.getId(),
                classify(player, entity),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                velocity.x,
                velocity.y,
                velocity.z,
                entity.getYRot(),
                RVP_RadarContactHelper.resolveSpecialTargetPos(entity)
        );
    }

    private static S2CHbmMissileSnapshot.Affiliation classify(ServerPlayer player, Entity entity) {
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        if (entity == player || entity.getVehicle() == player || (playerVehicle != null && entity.getVehicle() == playerVehicle)) {
            return S2CHbmMissileSnapshot.Affiliation.OWN;
        }
        if (isAllied(player, entity.getTeam())) {
            return S2CHbmMissileSnapshot.Affiliation.FRIEND;
        }
        return S2CHbmMissileSnapshot.Affiliation.HOSTILE;
    }

    private static boolean isAllied(Player player, @Nullable net.minecraft.world.scores.Team team) {
        return player.getTeam() != null && team != null && team.isAlliedTo(player.getTeam());
    }
}
