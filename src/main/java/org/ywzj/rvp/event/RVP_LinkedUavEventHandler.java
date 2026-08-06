package org.ywzj.rvp.event;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 联动 UAV 运行时行为的事件驱动（替代被删 {@code AbstractVehicleLinkedUavMixin} 的注入）。
 *
 * <p>承接原 mixin 的：</p>
 * <ul>
 *   <li>{@code tick}：残骸 10 秒提前清除、部署实例强载区块、母车位置快照同步、母车被毁联动判定；</li>
 *   <li>{@code onRemovedFromWorld}：清理座位锁/位置快照/联动注册，并传送操作员回母车；</li>
 *   <li>{@code onEnterVehicle}/{@code onLeaveVehicle}：非 UAV 模板部署实例的假操作员位置记录与
 *       离机回传 + 自动上车（经 {@link EntityMountEvent}）；</li>
 *   <li>座位锁强制（每 tick 轮询，替代 onEnterVehicle TAIL / changeSeat 注入）。</li>
 * </ul>
 *
 * <p>联动状态与父/子/角色等数据存于 {@link RVP_LinkedUavStateTable} 侧表（UUID → 状态）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_LinkedUavEventHandler {

    /** 残骸遗留时间（毫秒）：本体硬编码 60 秒，RVP 缩短为 10 秒。 */
    private static final long WRECK_EXPIRE_MS = 10_000L;
    /** 母车距无人机超过此区块数时不再强载母车区块（避免无限制远距离强载）。96 区块 = 1536 格。 */
    private static final int MAX_PARENT_CHUNK_DISTANCE = 96;

    /** 载具被击毁时间戳（key = 实体 UUID），替代本体私有字段 {@code destroyedTime}。 */
    private static final Map<UUID, Long> DESTROYED_SINCE = new HashMap<>();

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickVehicles(level);
            RVP_DeployableUavService.enforceSeatLocks(level);
        }
    }

    private static void tickVehicles(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            expireWreckEarly(vehicle);
            if (RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
                EntityUtil.keepChunkLoaded(vehicle, vehicle.position());
                EntityUtil.keepChunkLoaded(vehicle, vehicle.position().add(vehicle.getLookAngle().normalize().scale(16)));
                Vec3 fakePos = RVP_LinkedUavStateTable.getFakeOperatorPosition(vehicle);
                if (fakePos != null) {
                    EntityUtil.keepChunkLoaded(vehicle, fakePos);
                }
                refreshParentPosition(vehicle);
                checkParentDestroyed(vehicle);
            }
        }
    }

    /** 残骸提前清除：被击毁超过 {@link #WRECK_EXPIRE_MS} 的载具直接移除。 */
    private static void expireWreckEarly(AbstractVehicle vehicle) {
        if (vehicle.isDestroyed()) {
            Long since = DESTROYED_SINCE.computeIfAbsent(vehicle.getUUID(), ignored -> System.currentTimeMillis());
            if (System.currentTimeMillis() - since > WRECK_EXPIRE_MS) {
                DESTROYED_SINCE.remove(vehicle.getUUID());
                vehicle.discard();
            }
        } else {
            DESTROYED_SINCE.remove(vehicle.getUUID());
        }
    }

    /** 母车（父车）被击毁时，联动无人机也判定被击毁（自毁成残骸）。 */
    private static void checkParentDestroyed(AbstractVehicle uav) {
        if (uav.isDestroyed()) {
            return;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid == null || !(uav.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity parent = serverLevel.getEntity(parentUuid);
        if (parent instanceof AbstractVehicle parentVehicle && parentVehicle.isDestroyed()) {
            destroyVehicle(uav);
        }
    }

    /** 仿照本体 hurt() 的击毁逻辑：踢出乘客、置 DESTROYED、回满血、记录残骸计时。 */
    private static void destroyVehicle(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        vehicle.getPassengers().forEach(Entity::stopRiding);
        vehicle.getEntityData().set(AbstractVehicle.DESTROYED, true);
        vehicle.setHealth(vehicle.getMaxHealth());
        DESTROYED_SINCE.put(vehicle.getUUID(), System.currentTimeMillis());
    }

    /**
     * 每 tick 同步母车（父车）最新位置并（在距离上限内）强载母车区块。
     * 母车离开无人机视距后仍可能被服务端卸载，此时实时位置取不到；
     * 同步到静态注册表的位置用于被击毁传送回母车旁的兜底。
     */
    private static void refreshParentPosition(AbstractVehicle uav) {
        if (!(uav.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid == null) {
            return;
        }
        Entity parent = serverLevel.getEntity(parentUuid);
        if (parent instanceof AbstractVehicle parentVehicle) {
            RVP_DeployableUavService.setLinkedParentLastPosition(uav.getUUID(), parentVehicle.position());
            if (isWithinChunkDistance(uav, parentVehicle.position())) {
                EntityUtil.keepChunkLoaded(uav, parentVehicle.position());
            }
            if (uav.tickCount % 100 == 0) {
                LOGGER.info("[RVP-UAV] refreshParent: uav={} parent={} 母车实体存在，强载@{}",
                        uav.getVehicleId(), parentVehicle.getVehicleId(), parentVehicle.blockPosition());
            }
            return;
        }
        // 母车实体已被服务端卸载（离开视距）：按最近同步到的位置强载区块，
        // 等待区块重新加载后母车实体恢复，switchBackToParent / tryAutoRideParent 才能成功。
        // 注意：addRegionTicket 每 tick 调用会让 DistanceManager 反复重排远处区块（开销大，
        // 曾导致"切换后一切冻结"），这里每 40 tick 刷新一次（POST_TELEPORT ticket 有效期 300 tick 足够）。
        Vec3 lastParentPos = RVP_DeployableUavService.getLinkedParentLastPosition(uav.getUUID());
        if (lastParentPos != null && isWithinChunkDistance(uav, lastParentPos) && (uav.tickCount & 39) == 0) {
            EntityUtil.keepChunkLoaded(uav, lastParentPos);
        }
        if (uav.tickCount % 100 == 0) {
            LOGGER.info("[RVP-UAV] refreshParent: uav={} parentUuid={} 母车实体不可用！lastPos={} 距{}区块强载",
                    uav.getVehicleId(), parentUuid,
                    lastParentPos == null ? "null" : lastParentPos.toString(),
                    lastParentPos == null ? "-" :
                            Math.max(Math.abs(uav.blockPosition().getX() - (int) Math.floor(lastParentPos.x)) >> 4,
                                    Math.abs(uav.blockPosition().getZ() - (int) Math.floor(lastParentPos.z)) >> 4));
        }
    }

    private static boolean isWithinChunkDistance(AbstractVehicle a, Vec3 pos) {
        int dx = Math.abs(a.blockPosition().getX() - (int) Math.floor(pos.x)) >> 4;
        int dz = Math.abs(a.blockPosition().getZ() - (int) Math.floor(pos.z)) >> 4;
        return dx <= MAX_PARENT_CHUNK_DISTANCE && dz <= MAX_PARENT_CHUNK_DISTANCE;
    }

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle vehicle) || vehicle.level().isClientSide()) {
            return;
        }
        DESTROYED_SINCE.remove(vehicle.getUUID());
        RVP_DeployableUavService.cleanupSeatLock(vehicle);
        RVP_DeployableUavService.clearLinkedParentLastPosition(vehicle.getUUID());
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
            RVP_LinkedUavStateTable.remove(vehicle);
            return;
        }
        RVP_DeployableUavService.handleDeployableUavRemoved(vehicle);
        if (vehicle.getDriver() instanceof ServerPlayer serverPlayer) {
            vehicle.onLeaveVehicle(serverPlayer);
            serverPlayer.stopRiding();
            Vec3 target = resolveReturnPosition(vehicle, serverPlayer);
            if (target != null) {
                serverPlayer.teleportTo(target.x, target.y, target.z);
            }
            RVP_DeployableUavService.tryAutoRideParent(serverPlayer, vehicle);
        }
        RVP_LinkedUavStateTable.remove(vehicle);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!(event.getEntityBeingMounted() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!(event.getEntityMounting() instanceof LivingEntity passenger)) {
            return;
        }
        if (event.isMounting()) {
            handleMount(vehicle, passenger);
        } else if (event.isDismounting()) {
            handleDismount(vehicle, passenger);
        }
    }

    /** 非 UAV 模板部署实例（deployableUavInstance && !uav）：上车时记录操作员原位置并传送到无人机处。 */
    private static void handleMount(AbstractVehicle vehicle, LivingEntity passenger) {
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle) || vehicle.uav) {
            return;
        }
        if (passenger instanceof ServerPlayer serverPlayer && vehicle.tickCount != 0) {
            // 只保存玩家原位置，不生成假玩家实体（避免母车旁出现玩家模型）
            RVP_LinkedUavStateTable.setFakeOperatorPosition(vehicle, passenger.position());
            serverPlayer.teleportTo(vehicle.getX(), vehicle.getY(), vehicle.getZ());
        }
    }

    /** 部署实例离机（被击毁踢下 / 主动下车 / 实体移除）：传送回母车旁并自动坐上母车。 */
    private static void handleDismount(AbstractVehicle vehicle, LivingEntity passenger) {
        if (!RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle)) {
            return;
        }
        if (!(passenger instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Vec3 target = resolveReturnPosition(vehicle, serverPlayer);
        if (target != null) {
            serverPlayer.teleportTo(target.x, target.y, target.z);
        }
        LOGGER.info("[RVP-UAV] {} 离机: uav={} 回传位置={} parentUuid={}",
                serverPlayer.getName().getString(), vehicle.getVehicleId(), target,
                RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(vehicle));
        RVP_DeployableUavService.tryAutoRideParent(serverPlayer, vehicle);
    }

    /**
     * 解析操作员回传位置：优先母车当前实体位置（母车右侧外沿，避免落在车体内），
     * 母车已卸载时用最近同步到的母车位置兜底，最后回退上车时记录的位置。
     */
    private static Vec3 resolveReturnPosition(AbstractVehicle uav, ServerPlayer player) {
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid != null && uav.level() instanceof ServerLevel serverLevel) {
            Entity parent = serverLevel.getEntity(parentUuid);
            if (parent instanceof AbstractVehicle parentVehicle) {
                return parentVehicle.relativeRotPos(
                        parentVehicle.position().add(parentVehicle.getMainCubeOBB().obb().extents().x + 1, 1, 0),
                        false);
            }
            Vec3 lastParentPos = RVP_DeployableUavService.getLinkedParentLastPosition(uav.getUUID());
            if (lastParentPos != null) {
                return lastParentPos.add(0, 1, 0);
            }
        }
        return RVP_LinkedUavStateTable.getFakeOperatorPosition(uav);
    }
}
