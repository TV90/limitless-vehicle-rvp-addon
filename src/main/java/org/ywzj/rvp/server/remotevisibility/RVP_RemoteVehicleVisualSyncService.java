package org.ywzj.rvp.server.remotevisibility;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.rvp.config.LauncherDeployRuntimeManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot.LauncherDeployPhase;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot.LauncherDeployVisualState;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot.RotatablePartState;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot.SwitchablePartKind;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot.SwitchablePartState;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleChunkLeaseService.AuthorizedTarget;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.Candidate;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.VehicleCategory;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.uav.RVP_DeployableUavLinkRegistry;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.DoorUnit;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端权威的远距载具完整快照、授权收敛与租约编排入口。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteVehicleVisualSyncService {
    /** 非法远距载具元数据警告日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 按服务器实例隔离连接序号、启停边沿和最近授权并集。 */
    private static final Map<MinecraftServer, ServerState> SERVER_STATES = new IdentityHashMap<>();

    /** Forge 事件服务不允许实例化。 */
    private RVP_RemoteVehicleVisualSyncService() {
    }

    /** 在 Tick END 低频重算快照，并在每 Tick 用缓存授权刷新移动载具路径。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerState state = SERVER_STATES.computeIfAbsent(server, ignored -> new ServerState());
        boolean enabled = isVisualSyncEnabled();
        int interval = Math.max(1, RVP_CommonConfig.getRemoteVehicleSyncIntervalTicks());
        boolean syncDue = enabled && (!state.previouslyEnabled || server.getTickCount() % interval == 0);

        if (!enabled) {
            if (state.previouslyEnabled) {
                // 调用远距载具网络协议发送空完整集合，使客户端立即撤销旧授权和代理。
                sendEmptySnapshots(server, state);
            }
            state.authorizedByDimension = Map.of();
        } else if (syncDue) {
            state.authorizedByDimension = synchronizeSnapshots(server, state);
        }
        state.previouslyEnabled = enabled;
        pruneDisconnectedSequences(server, state);
        // 调用 RVP 载具租约服务；视觉授权低频刷新，实体当前位置和速度路径每 Tick 刷新。
        RVP_RemoteVehicleChunkLeaseService.tick(server, state.authorizedByDimension);
    }

    /** 玩家退出后删除本连接序号；租约服务同 Tick 会忽略已经离线的观察者。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        ServerState state = server == null ? null : SERVER_STATES.get(server);
        if (state != null) {
            state.sequences.remove(player.getUUID());
        }
    }

    /** 服务器完全停止后清除连接序号、授权缓存和租约统计。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        SERVER_STATES.remove(server);
        // 调用 RVP 载具租约服务释放同一服务器实例的静态状态。
        RVP_RemoteVehicleChunkLeaseService.clear(server);
    }

    /** 返回 common 配置是否允许服务端产生载具远距授权。 */
    private static boolean isVisualSyncEnabled() {
        return RVP_CommonConfig.isRemoteVehicleRenderingEnabled()
                && RVP_CommonConfig.getRemoteVehicleVisibilityMode() != VisibilityMode.OFF;
    }

    /** 按维度收集一次已加载载具，再逐玩家构建、发送并汇总实际条目授权。 */
    private static Map<ResourceLocation, List<AuthorizedTarget>> synchronizeSnapshots(
            MinecraftServer server,
            ServerState state) {
        Map<ResourceLocation, List<AuthorizedTarget>> result = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<AbstractVehicle> loadedVehicles = collectLoadedVehicles(level);
            Map<Integer, AbstractVehicle> vehiclesById = new LinkedHashMap<>();
            for (AbstractVehicle vehicle : loadedVehicles) {
                vehiclesById.put(vehicle.getId(), vehicle);
            }
            Map<UUID, MutableAuthorization> authorizationUnion = new LinkedHashMap<>();
            for (ServerPlayer player : level.players()) {
                List<S2CRemoteVehicleVisualSnapshot.Entry> entries = buildPlayerEntries(
                        level, player, loadedVehicles, vehiclesById, authorizationUnion);
                sendSnapshot(player, level.dimension().location(), level.getGameTime(), nextSequence(state, player), entries);
            }
            List<AuthorizedTarget> dimensionTargets = authorizationUnion.values().stream()
                    .map(MutableAuthorization::toImmutable)
                    .toList();
            if (!dimensionTargets.isEmpty()) {
                result.put(level.dimension().location(), dimensionTargets);
            }
        }
        return Map.copyOf(result);
    }

    /** 收集当前确实处于已加载区块且可归类的完整载具。 */
    private static List<AbstractVehicle> collectLoadedVehicles(ServerLevel level) {
        List<AbstractVehicle> vehicles = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || vehicle.isRemoved()
                    || !vehicle.isAlive()
                    || RVP_RemoteVehicleVisibilityPolicy.classify(vehicle).isEmpty()
                    || !level.hasChunkAt(vehicle.blockPosition())) {
                continue;
            }
            vehicles.add(vehicle);
        }
        return List.copyOf(vehicles);
    }

    /** 按服务端模式和类型矩阵为单名玩家构建完整快照条目。 */
    private static List<S2CRemoteVehicleVisualSnapshot.Entry> buildPlayerEntries(
            ServerLevel level,
            ServerPlayer player,
            List<AbstractVehicle> loadedVehicles,
            Map<Integer, AbstractVehicle> vehiclesById,
            Map<UUID, MutableAuthorization> authorizationUnion) {
        VisibilityMode mode = RVP_CommonConfig.getRemoteVehicleVisibilityMode();
        AbstractVehicle observerVehicle = resolveObserverVehicle(player);
        VehicleCategory observerCategory = RVP_RemoteVehicleVisibilityPolicy.classify(observerVehicle).orElse(null);
        if (observerCategory == null && mode != VisibilityMode.ALL_PLAYERS && mode != VisibilityMode.OFF) {
            // 调用类型矩阵，为步行玩家选择 walking_players 观察者配置。
            observerCategory = VehicleCategory.WALKING_PLAYERS;
        }
        Set<VehicleCategory> allowedTypes = observerCategory == null
                ? Set.of()
                : RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(observerCategory);
        int observerVehicleId = observerVehicle == null ? -1 : observerVehicle.getId();

        List<Candidate> candidates = new ArrayList<>(loadedVehicles.size());
        for (AbstractVehicle target : loadedVehicles) {
            VehicleCategory category = RVP_RemoteVehicleVisibilityPolicy.classify(target).orElse(null);
            if (category == null) {
                continue;
            }
            double horizontalDistanceSq = horizontalDistanceSq(player, target);
            boolean radarDetected = mode == VisibilityMode.RADAR_DETECTED
                    && observerVehicle != null
                    && horizontalDistanceSq
                    > RVP_RemoteVehicleVisibilityPolicy.RADAR_DIRECT_VISIBILITY_DISTANCE
                    * RVP_RemoteVehicleVisibilityPolicy.RADAR_DIRECT_VISIBILITY_DISTANCE
                    && isDetectedByObserverOrLinkedRadar(level, observerVehicle, target);
            if (!Double.isFinite(horizontalDistanceSq)) {
                continue;
            }
            candidates.add(new Candidate(target.getId(), category, horizontalDistanceSq, radarDetected));
        }
        // 调用纯可见性策略，在发送和租约汇总前完成服务端权威白名单、距离及数量裁剪。
        List<Candidate> selected = RVP_RemoteVehicleVisibilityPolicy.selectTargets(
                mode,
                observerCategory,
                observerVehicleId,
                allowedTypes,
                RVP_CommonConfig.getRemoteVehicleMaxDistance(),
                RVP_CommonConfig.getRemoteVehicleMaxTargetsPerPlayer(),
                candidates);

        List<S2CRemoteVehicleVisualSnapshot.Entry> entries = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            AbstractVehicle target = vehiclesById.get(candidate.entityId());
            S2CRemoteVehicleVisualSnapshot.Entry entry = createSnapshotEntry(level, target);
            if (entry == null) {
                continue;
            }
            entries.add(entry);
            authorizationUnion.computeIfAbsent(
                            target.getUUID(),
                            ignored -> new MutableAuthorization(target.getUUID(), target.getId()))
                    .observerUuids.add(player.getUUID());
        }
        return List.copyOf(entries);
    }

    /** 沿玩家当前乘坐链寻找完整载具，覆盖驾驶位、普通乘员位和嵌套座位。 */
    @Nullable
    private static AbstractVehicle resolveObserverVehicle(ServerPlayer player) {
        Entity mount = player.getVehicle();
        while (mount != null) {
            if (mount instanceof AbstractVehicle vehicle
                    && RVP_RemoteVehicleVisibilityPolicy.classify(vehicle).isPresent()) {
                return vehicle;
            }
            mount = mount.getVehicle();
        }
        return null;
    }

    /** 只读取本机和已授权链接 UAV 雷达的当前服务端探测表，不触发新扫描。 */
    private static boolean isDetectedByObserverOrLinkedRadar(
            ServerLevel level,
            AbstractVehicle observerVehicle,
            AbstractVehicle target) {
        if (vehicleRadarDetects(observerVehicle, target)) {
            return true;
        }
        UUID relayUuid = RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(observerVehicle);
        if (relayUuid == null) {
            relayUuid = RVP_DeployableUavLinkRegistry.getChildUuid(observerVehicle.getUUID());
        }
        Entity relayEntity = relayUuid == null ? null : level.getEntity(relayUuid);
        if (relayEntity instanceof AbstractVehicle relayVehicle
                && !relayVehicle.isRemoved()
                && relayVehicle.isAlive()
                && !relayVehicle.isDestroyed()
                && vehicleRadarDetects(relayVehicle, target)) {
            return true;
        }
        // 外置雷达链路中继车（如 BUKM3 挂接的 96L6 搜索雷达车）：其服务端探测表
        // 同样应计入远程可见性，否则仅被外置搜索雷达发现的超距目标不会被同步，
        // 客户端无实体 → 本车雷达也无法扫到 → 雷达框永远不显示。
        AbstractVehicle externalRelay = RVP_ExternalRadarLinkHelper
                .getLinkedRelayVehicle(observerVehicle).orElse(null);
        return externalRelay != null
                && !externalRelay.isRemoved()
                && externalRelay.isAlive()
                && !externalRelay.isDestroyed()
                && vehicleRadarDetects(externalRelay, target);
    }

    /** 查询一辆载具任一已开启雷达的当前探测表是否包含目标。 */
    private static boolean vehicleRadarDetects(AbstractVehicle radarVehicle, AbstractVehicle target) {
        for (PartUnit<?> partUnit : radarVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit
                    && radarUnit.isOn()
                    && radarUnit.getDetectedEntities().containsKey(target.getId())) {
                return true;
            }
        }
        return false;
    }

    /** 创建单个协议条目；任何非法元数据或数值都只跳过当前目标。 */
    @Nullable
    private static S2CRemoteVehicleVisualSnapshot.Entry createSnapshotEntry(
            ServerLevel level,
            @Nullable AbstractVehicle vehicle) {
        if (vehicle == null || !level.hasChunkAt(vehicle.blockPosition())) {
            return null;
        }
        ResourceLocation entityType = EntityType.getKey(vehicle.getType());
        ResourceLocation vehicleId = vehicle.getVehicleId();
        ResourceLocation displayId = vehicle.getDisplayId();
        if (entityType == null || vehicleId == null || displayId == null) {
            LOGGER.warn("远距载具缺少实体类型、车型或 display ID，已跳过：entityId={}", vehicle.getId());
            return null;
        }
        int groundY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                vehicle.blockPosition().getX(),
                vehicle.blockPosition().getZ());
        double heightAboveGround = Math.max(0.0D, vehicle.getY() - groundY);
        try {
            // 调用本服务的部件采集辅助，只提取超远距静态高模需要的最小视觉字段。
            List<RotatablePartState> rotatableParts = collectRotatableParts(vehicle);
            List<SwitchablePartState> switchableParts = collectSwitchableParts(vehicle);
            // 调用 RVP 发射架运行时管理器，携带服务端权威阶段而不让非世界代理自行推导。
            List<LauncherDeployVisualState> launcherStates = collectLauncherStates(vehicle);
            return new S2CRemoteVehicleVisualSnapshot.Entry(
                    vehicle.getId(),
                    entityType,
                    vehicleId,
                    displayId,
                    vehicle.position(),
                    vehicle.getDeltaMovement(),
                    vehicle.getXRot(),
                    vehicle.getYRot(),
                    vehicle.getZRot(),
                    heightAboveGround,
                    vehicle.isDestroyed(),
                    vehicle.isEngineOn(),
                    vehicle.getPower(),
                    vehicle.getEngineSpeed(),
                    rotatableParts,
                    switchableParts,
                    launcherStates);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("远距载具包含非法视觉数值，已跳过：entityId={}", vehicle.getId());
            return null;
        }
    }

    /** 收集炮塔、炮管、武器站以及 RVP 发射架俯仰部件的当前局部转角。 */
    private static List<RotatablePartState> collectRotatableParts(AbstractVehicle vehicle) {
        Map<Integer, RotatablePartState> states = new LinkedHashMap<>();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                states.put(partUnit.getIndex(), new RotatablePartState(
                        partUnit.getIndex(), weaponUnit.getXRot(), weaponUnit.getYRot()));
            }
        }
        // 调用发射架配置缓存，补入并非 WeaponUnit 的自定义俯仰部件。
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            PartUnit<?> pitchPart = vehicle.getPartUnit(config.pitchPartUnitId()).orElse(null);
            if (pitchPart instanceof RotatableUnit<?> rotatable) {
                states.put(pitchPart.getIndex(), new RotatablePartState(
                        pitchPart.getIndex(), rotatable.getXRot(), rotatable.getYRot()));
            }
        }
        return List.copyOf(states.values());
    }

    /** 收集起落架、舱门、武器舱及 RVP 发射架开关部件的当前终态。 */
    private static List<SwitchablePartState> collectSwitchableParts(AbstractVehicle vehicle) {
        Map<Integer, SwitchablePartState> states = new LinkedHashMap<>();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            SwitchablePartKind kind = switchableKind(partUnit);
            if (kind != null && partUnit instanceof SwitchableUnit<?> switchable) {
                states.put(partUnit.getIndex(), new SwitchablePartState(
                        partUnit.getIndex(), kind, switchable.isOn()));
            }
        }
        // 调用发射架配置缓存，补入普通 SwitchableUnit 形式的 TEL 开关部件。
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            PartUnit<?> switchPart = vehicle.getPartUnit(config.partUnitId()).orElse(null);
            if (switchPart instanceof SwitchableUnit<?> switchable) {
                states.putIfAbsent(switchPart.getIndex(), new SwitchablePartState(
                        switchPart.getIndex(), SwitchablePartKind.LAUNCHER, switchable.isOn()));
            }
        }
        return List.copyOf(states.values());
    }

    /** 将本体具体开关部件归类为网络稳定的视觉类型。 */
    @Nullable
    private static SwitchablePartKind switchableKind(PartUnit<?> partUnit) {
        if (partUnit instanceof LandingGearUnit) {
            return SwitchablePartKind.LANDING_GEAR;
        }
        if (partUnit instanceof DoorUnit) {
            return SwitchablePartKind.DOOR;
        }
        if (partUnit instanceof WeaponBayUnit) {
            return SwitchablePartKind.WEAPON_BAY;
        }
        return null;
    }

    /** 收集 RVP TEL/发射架状态机的服务端权威阶段、进度、俯仰和速度。 */
    private static List<LauncherDeployVisualState> collectLauncherStates(AbstractVehicle vehicle) {
        List<LauncherDeployVisualState> states = new ArrayList<>();
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            LauncherDeployRuntimeManager.Snapshot snapshot = LauncherDeployRuntimeManager.get(
                    vehicle.getId(), config.id(), false);
            if (snapshot == null) {
                continue;
            }
            int switchPartIndex = vehicle.getPartUnit(config.partUnitId())
                    .map(PartUnit::getIndex).orElse(-1);
            int pitchPartIndex = vehicle.getPartUnit(config.pitchPartUnitId())
                    .map(PartUnit::getIndex).orElse(-1);
            states.add(new LauncherDeployVisualState(
                    config.id(),
                    switchPartIndex,
                    pitchPartIndex,
                    LauncherDeployPhase.valueOf(snapshot.state().name()),
                    snapshot.progressTick(),
                    snapshot.currentPitch(),
                    snapshot.speedKph()));
        }
        return List.copyOf(states);
    }

    /** 计算观察者到目标的 X/Z 水平距离平方。 */
    private static double horizontalDistanceSq(ServerPlayer player, AbstractVehicle target) {
        double dx = player.getX() - target.getX();
        double dz = player.getZ() - target.getZ();
        return dx * dx + dz * dz;
    }

    /** 发送一份包含空集合在内的完整载具视觉快照。 */
    private static void sendSnapshot(
            ServerPlayer player,
            ResourceLocation dimension,
            long serverGameTime,
            long sequence,
            List<S2CRemoteVehicleVisualSnapshot.Entry> entries) {
        // 调用服务端 common 配置读取渲染策略和无 DH 高度阈值，通过完整快照统一下发。
        S2CRemoteVehicleVisualSnapshot snapshot = new S2CRemoteVehicleVisualSnapshot(
                dimension,
                serverGameTime,
                sequence,
                RVP_CommonConfig.isRemoteVehicleAggressiveLodBillboardEnabled(),
                RVP_CommonConfig.isRemoteVehicleForceAllVehicleBillboardEnabled(),
                RVP_CommonConfig.getRemoteVehicleBillboardSource(),
                RVP_CommonConfig.getRemoteVehicleDynamicSnapshotWarmupMode(),
                RVP_CommonConfig.getRemoteVehicleMinHeightAboveGroundWithoutDH(),
                entries);
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot);
    }

    /** 配置关闭边沿向所有在线玩家立即发送空集合。 */
    private static void sendEmptySnapshots(MinecraftServer server, ServerState state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            sendSnapshot(
                    player,
                    level.dimension().location(),
                    level.getGameTime(),
                    nextSequence(state, player),
                    List.of());
        }
    }

    /** 为当前玩家连接取得下一个非负单调序号，首次快照从 0 开始。 */
    private static long nextSequence(ServerState state, ServerPlayer player) {
        return state.sequences.compute(player.getUUID(), (ignored, previous) -> {
            if (previous == null) {
                return 0L;
            }
            return previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
        });
    }

    /** 删除已不在线玩家的序号，保证下次连接重新从 0 开始。 */
    private static void pruneDisconnectedSequences(MinecraftServer server, ServerState state) {
        Set<UUID> online = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
        }
        state.sequences.keySet().retainAll(online);
    }

    /** 正在构建的单目标授权并集。 */
    private static final class MutableAuthorization {
        /** 目标载具 UUID。 */
        private final UUID vehicleUuid;
        /** 目标载具当前实体 ID。 */
        private final int entityId;
        /** 最终确实收到该目标条目的观察者 UUID 集合。 */
        private final Set<UUID> observerUuids = new LinkedHashSet<>();

        private MutableAuthorization(UUID vehicleUuid, int entityId) {
            this.vehicleUuid = vehicleUuid;
            this.entityId = entityId;
        }

        /** 转换为租约服务只读输入。 */
        private AuthorizedTarget toImmutable() {
            return new AuthorizedTarget(vehicleUuid, entityId, observerUuids);
        }
    }

    /** 单个服务器的视觉同步状态。 */
    private static final class ServerState {
        /** 每名玩家当前连接内最近发送的快照序号。 */
        private final Map<UUID, Long> sequences = new LinkedHashMap<>();
        /** 上一 Tick 配置是否允许载具视觉，用于捕获关闭边沿。 */
        private boolean previouslyEnabled;
        /** 最近一次完整快照重算得到的每维度授权并集。 */
        private Map<ResourceLocation, List<AuthorizedTarget>> authorizedByDimension = Map.of();
    }
}
