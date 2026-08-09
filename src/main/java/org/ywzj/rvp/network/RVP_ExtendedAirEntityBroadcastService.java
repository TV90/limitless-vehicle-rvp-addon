package org.ywzj.rvp.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.vehicle.api.entity.RemoteTickEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerBroadcastEntities;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExtendedAirEntityBroadcastService {

    private static final int SYNC_INTERVAL_TICK = 5;
    /** 是否广播载具（飞机）超视距渲染数据；当前需求为关闭车辆、保留弹药超视距渲染。 */
    private static final boolean ENABLE_AIR_VEHICLE_RENDER = false;
    private static final double BASE_RANGE = 32.0D * 16.0D;
    private static final double UNCONDITIONAL_RANGE = 64.0D * 16.0D;
    private static final double EXTENDED_RANGE = 256.0D * 16.0D;
    private static final double BASE_RANGE_SQ = BASE_RANGE * BASE_RANGE;
    private static final double UNCONDITIONAL_RANGE_SQ = UNCONDITIONAL_RANGE * UNCONDITIONAL_RANGE;
    private static final double EXTENDED_RANGE_SQ = EXTENDED_RANGE * EXTENDED_RANGE;
    private static final double MIN_AGL = 100.0D;

    private RVP_ExtendedAirEntityBroadcastService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % SYNC_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.getVehicle() instanceof AbstractVehicle)) {
                continue;
            }
            List<Entity> eligible = collectEligible(player);
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CExtendedAirVisualSnapshot(player.serverLevel().dimension().location(),
                            eligible.stream().map(Entity::getId).collect(java.util.stream.Collectors.toSet()),
                            eligible.stream().filter(RVP_ExtendedAirEntityBroadcastService::isMotorBurning)
                                    .map(Entity::getId).collect(java.util.stream.Collectors.toSet())));
            ServerBroadcastEntities packet = buildPacket(eligible);
            if (!packet.entities.isEmpty()) {
                Channel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    private static List<Entity> collectEligible(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        AbstractVehicle playerVehicle = (AbstractVehicle) player.getVehicle();
        Set<Integer> radarDetectedIds = collectRadarDetectedIds(playerVehicle);
        RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(playerVehicle)
                .ifPresent(relay -> radarDetectedIds.addAll(collectRadarDetectedIds(relay)));

        Map<Integer, Entity> candidates = new LinkedHashMap<>();
        AABB nearBox = player.getBoundingBox().inflate(UNCONDITIONAL_RANGE);
        level.getEntities(player, nearBox, RVP_ExtendedAirEntityBroadcastService::isSupportedType)
                .forEach(entity -> candidates.put(entity.getId(), entity));

        for (Entity entity : level.getAllEntities()) {
            if (!isSupportedType(entity)) {
                continue;
            }
            if (radarDetectedIds.contains(entity.getId()) || isOwnAmmo(player, entity)) {
                candidates.put(entity.getId(), entity);
            }
        }
        candidates.values().removeIf(entity -> !isEligible(player, level, entity, radarDetectedIds));
        return new ArrayList<>(candidates.values());
    }

    private static Set<Integer> collectRadarDetectedIds(AbstractVehicle radarVehicle) {
        Set<Integer> detectedIds = new HashSet<>();
        for (PartUnit<?> partUnit : radarVehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            detectedIds.addAll(radarUnit.getDetectedEntities().keySet());
            Radar.scanTargets(radarVehicle, radarUnit.worldRadarPosition(), radarUnit.getMaxScanDistance(), pos -> {
                net.minecraft.world.phys.Vec2 aimRot = radarUnit.aimRot(pos);
                return aimRot.y >= radarUnit.getYRotMin() && aimRot.y <= radarUnit.getYRotMax();
            }).forEach(entity -> detectedIds.add(entity.getId()));
        }
        return detectedIds;
    }

    private static ServerBroadcastEntities buildPacket(List<Entity> eligible) {
        List<ServerBroadcastEntities.BroadcastEntity> entries = new ArrayList<>();
        for (Entity entity : eligible) {
            if (!isSupplementalType(entity)) {
                continue;
            }
            CompoundTag data = new CompoundTag();
            entity.saveWithoutId(data);
            if (entity instanceof RemoteTickEntity remoteTickEntity) {
                remoteTickEntity.writeData(data);
            }
            entries.add(new ServerBroadcastEntities.BroadcastEntity(
                    entity.getId(), EntityType.getKey(entity.getType()), entity.position(),
                    entity.getDeltaMovement(), data));
        }
        ServerBroadcastEntities packet = new ServerBroadcastEntities();
        packet.entities = entries;
        return packet;
    }

    private static boolean isSupplementalType(Entity entity) {
        return entity instanceof RocketEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    private static boolean isSupportedType(Entity entity) {
        return (ENABLE_AIR_VEHICLE_RENDER && entity instanceof AbstractVehicle)
                || entity instanceof MissileEntity
                || isSupplementalType(entity);
    }

    private static boolean isMotorBurning(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet) {
            return bullet.isMotorBurningNow();
        }
        if (entity instanceof MissileEntity missile) {
            int motorTick = missile.tickCount - missile.coldLaunchTimeTick;
            return motorTick >= 0 && motorTick <= missile.motorBurnTime;
        }
        if (entity instanceof RocketEntity rocket) {
            return rocket.tickCount <= rocket.motorBurnTime;
        }
        return false;
    }

    private static boolean isEligible(ServerPlayer player, ServerLevel level, Entity entity,
                                      Set<Integer> radarDetectedIds) {
        if (!entity.isAlive()) {
            return false;
        }
        double dx = entity.getX() - player.getX();
        double dz = entity.getZ() - player.getZ();
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= BASE_RANGE_SQ || distanceSq > EXTENDED_RANGE_SQ) {
            return false;
        }
        if (entity instanceof RVP_BulletEntity && distanceSq > UNCONDITIONAL_RANGE_SQ) {
            return false;
        }
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                entity.getBlockX(), entity.getBlockZ());
        if (entity.getY() - groundY < MIN_AGL) {
            return false;
        }
        return distanceSq <= UNCONDITIONAL_RANGE_SQ
                || isOwnAmmo(player, entity)
                || radarDetectedIds.contains(entity.getId());
    }

    private static boolean isOwnAmmo(ServerPlayer player, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile)) {
            return false;
        }
        if (projectile.getOwner() == player) {
            return true;
        }
        return entity instanceof RVP_BaseBullet bullet
                && player.getVehicle() instanceof AbstractVehicle playerVehicle
                && bullet.getShooterVehicle() == playerVehicle;
    }
}
