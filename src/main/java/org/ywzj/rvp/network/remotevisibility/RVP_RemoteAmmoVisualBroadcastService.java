package org.ywzj.rvp.network.remotevisibility;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
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
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.vehicle.api.entity.RemoteTickEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerBroadcastEntities;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 负责弹药超视距视觉授权、燃烧状态同步和本体远程弹药克隆补充。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteAmmoVisualBroadcastService {
    /** 弹药视觉完整集合的同步周期，单位 tick。 */
    private static final int SYNC_INTERVAL_TICK = 5;
    /** 原生实体追踪接管边界，单位格。 */
    private static final double BASE_RANGE = 32.0D * 16.0D;
    /** 无条件弹药可视边界，单位格。 */
    private static final double UNCONDITIONAL_RANGE = 64.0D * 16.0D;
    /** 弹药超视距视觉最远边界，单位格。 */
    private static final double EXTENDED_RANGE = 256.0D * 16.0D;
    /** 原生实体追踪接管边界平方。 */
    private static final double BASE_RANGE_SQ = BASE_RANGE * BASE_RANGE;
    /** 无条件弹药可视边界平方。 */
    private static final double UNCONDITIONAL_RANGE_SQ = UNCONDITIONAL_RANGE * UNCONDITIONAL_RANGE;
    /** 弹药超视距视觉最远边界平方。 */
    private static final double EXTENDED_RANGE_SQ = EXTENDED_RANGE * EXTENDED_RANGE;
    /** 弹药进入超视距视觉所需的最低离地高度，单位格。 */
    private static final double MIN_AGL = 100.0D;

    private RVP_RemoteAmmoVisualBroadcastService() {
    }

    /** 在服务端 Tick 末尾向乘载具玩家发送弹药视觉完整集合。 */
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
            // 调用 RVP 网络通道，向当前玩家发送服务端权威的弹药视觉完整集合。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CRemoteAmmoVisualSnapshot(player.serverLevel().dimension().location(),
                            eligible.stream().map(Entity::getId).collect(java.util.stream.Collectors.toSet()),
                            collectMotorBurnRemainingTicks(eligible)));
            ServerBroadcastEntities packet = buildPacket(eligible);
            if (!packet.entities.isEmpty()) {
                // 调用本体网络通道，补充创建客户端尚未追踪的远程弹药克隆。
                Channel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /** 收集保留既有授权语义的远程弹药候选。 */
    private static List<Entity> collectEligible(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        AbstractVehicle playerVehicle = (AbstractVehicle) player.getVehicle();
        Set<Integer> radarDetectedIds = collectRadarDetectedIds(playerVehicle);
        // 调用 RVP 外部雷达链路，把授权中继载具的雷达发现并入当前观察者。
        RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(playerVehicle)
                .ifPresent(relay -> radarDetectedIds.addAll(collectRadarDetectedIds(relay)));

        Map<Integer, Entity> candidates = new LinkedHashMap<>();

        // 不再用大立方体查询；在已加载实体循环中保留 1024 格内无条件候选的既有语义。
        net.minecraft.world.phys.AABB nearBox = player.getBoundingBox().inflate(UNCONDITIONAL_RANGE);
        for (Entity entity : level.getAllEntities()) {
            if (!isSupportedType(entity)) {
                continue;
            }
            if (radarDetectedIds.contains(entity.getId())
                    || isOwnAmmo(player, entity)
                    || entity.getBoundingBox().intersects(nearBox)) {
                candidates.put(entity.getId(), entity);
            }
        }
        candidates.values().removeIf(entity -> !isEligible(player, level, entity, radarDetectedIds));
        return new ArrayList<>(candidates.values());
    }

    /** 收集指定载具所有已开启雷达当前发现的实体 ID。 */
    private static Set<Integer> collectRadarDetectedIds(AbstractVehicle radarVehicle) {
        Set<Integer> detectedIds = new HashSet<>();
        for (PartUnit<?> partUnit : radarVehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            detectedIds.addAll(radarUnit.getDetectedEntities().keySet());
            // 调用本体雷达扫描，补齐雷达当前扇区内尚未写入缓存的弹药目标。
            Radar.scanTargets(radarVehicle, radarUnit.worldRadarPosition(), radarUnit.getMaxScanDistance(), pos -> {
                net.minecraft.world.phys.Vec2 aimRot = radarUnit.aimRot(pos);
                return aimRot.y >= radarUnit.getYRotMin() && aimRot.y <= radarUnit.getYRotMax();
            }).forEach(entity -> detectedIds.add(entity.getId()));
        }
        return detectedIds;
    }

    /** 构造本体远程实体接收器所需的弹药克隆数据。 */
    private static ServerBroadcastEntities buildPacket(List<Entity> eligible) {
        List<ServerBroadcastEntities.BroadcastEntity> entries = new ArrayList<>();
        for (Entity entity : eligible) {
            if (!isSupplementalType(entity)) {
                continue;
            }
            CompoundTag data = new CompoundTag();
            entity.saveWithoutId(data);
            if (entity instanceof RemoteTickEntity remoteTickEntity) {
                // 调用本体远程 Tick 数据接口，保留弹药客户端外推所需的扩展状态。
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

    /** 判断实体是否需要通过本体远程接收器补充克隆。 */
    private static boolean isSupplementalType(Entity entity) {
        return entity instanceof RocketEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    /** 判断实体是否属于弹药超视距视觉支持类型。 */
    private static boolean isSupportedType(Entity entity) {
        return entity instanceof MissileEntity || isSupplementalType(entity);
    }

    /** 判断导弹或火箭发动机在当前服务端 tick 是否仍在燃烧。 */
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

    /**
     * 收集燃烧中弹药的剩余燃烧时间，供超视距客户端让固体发动机凝结云保持期绑定真实燃尽时刻。
     * 映射存在即代表仍在燃烧；值为 0 表示当前正处于燃尽边界 Tick。
     */
    private static Map<Integer, Integer> collectMotorBurnRemainingTicks(List<Entity> eligible) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (Entity entity : eligible) {
            if (!isMotorBurning(entity)) {
                continue;
            }
            result.put(entity.getId(), remainingMotorBurnTicks(entity));
        }
        return result;
    }

    /** 解析一枚受支持弹药距最后燃尽的剩余 Tick，并钳制到快照协议允许范围。 */
    private static int remainingMotorBurnTicks(Entity entity) {
        int remainingTicks;
        if (entity instanceof RVP_BaseBullet bullet) {
            // 调用 RVP 弹体燃烧窗口出口，使远程凝结云与一级/二脉冲的最晚燃尽时刻一致。
            remainingTicks = bullet.ticksUntilMotorStopsBurning();
        } else if (entity instanceof MissileEntity missile) {
            int motorTick = missile.tickCount - missile.coldLaunchTimeTick;
            remainingTicks = (int) Math.ceil(missile.motorBurnTime - motorTick);
        } else if (entity instanceof RocketEntity rocket) {
            remainingTicks = (int) Math.ceil(rocket.motorBurnTime - rocket.tickCount);
        } else {
            remainingTicks = 0;
        }
        return Math.max(0, Math.min(remainingTicks,
                S2CRemoteAmmoVisualSnapshot.MAX_MOTOR_BURN_REMAINING_TICKS));
    }

    /** 应用既有弹药距离、离地高度、所有权和雷达授权规则。 */
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

    /** 判断弹药是否由当前玩家或其所乘载具发射。 */
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
