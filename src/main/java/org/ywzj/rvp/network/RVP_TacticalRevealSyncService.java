package org.ywzj.rvp.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_TacticalRevealSyncService {

    private static final int SYNC_INTERVAL_TICKS = 5;
    private static final int FIRE_REVEAL_DURATION_TICKS = 100;
    private static final double FIRE_REVEAL_RANGE = 1000.0;
    private static final double FIRE_REVEAL_RANGE_SQR = FIRE_REVEAL_RANGE * FIRE_REVEAL_RANGE;
    private static final Map<UUID, Long> FIRED_GROUND_VEHICLES = new LinkedHashMap<>();

    private RVP_TacticalRevealSyncService() {}

    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (event.isClientSide()) {
            return;
        }
        AbstractVehicle vehicle = event.getVehicle();
        if (!isGroundVehicle(vehicle)) {
            return;
        }
        FIRED_GROUND_VEHICLES.put(vehicle.getUUID(), vehicle.level().getGameTime() + FIRE_REVEAL_DURATION_TICKS);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long gameTime = event.getServer().overworld().getGameTime();
        pruneExpired(gameTime);
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

    private static S2CTacticalRevealSnapshot buildSnapshot(ServerPlayer player) {
        S2CTacticalRevealSnapshot msg = new S2CTacticalRevealSnapshot();
        msg.dimension = player.serverLevel().dimension().location();
        if (!(player.getVehicle() instanceof AbstractVehicle viewerVehicle)) {
            msg.fireRevealIds = List.of();
            msg.markedRevealIds = List.of();
            return msg;
        }
        AABB rangeBox = viewerVehicle.getBoundingBox().inflate(FIRE_REVEAL_RANGE);
        List<Integer> fireRevealIds = new ArrayList<>();
        for (AbstractVehicle vehicle : player.serverLevel().getEntitiesOfClass(AbstractVehicle.class, rangeBox,
                vehicle -> vehicle != null && vehicle.isAlive() && isGroundVehicle(vehicle))) {
            if (vehicle == viewerVehicle) {
                continue;
            }
            Long expireTick = FIRED_GROUND_VEHICLES.get(vehicle.getUUID());
            if (expireTick == null || expireTick <= player.serverLevel().getGameTime()) {
                continue;
            }
            if (viewerVehicle.distanceToSqr(vehicle) > FIRE_REVEAL_RANGE_SQR) {
                continue;
            }
            fireRevealIds.add(vehicle.getId());
        }
        msg.fireRevealIds = fireRevealIds;
        msg.markedRevealIds = List.of();
        return msg;
    }

    private static void pruneExpired(long gameTime) {
        Iterator<Map.Entry<UUID, Long>> iterator = FIRED_GROUND_VEHICLES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() == null || entry.getValue() <= gameTime) {
                iterator.remove();
            }
        }
    }

    private static boolean isGroundVehicle(Entity entity) {
        return entity instanceof WheeledVehicle || entity instanceof TrackedVehicle;
    }
}
