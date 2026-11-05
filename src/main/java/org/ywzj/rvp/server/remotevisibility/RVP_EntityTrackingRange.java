package org.ywzj.rvp.server.remotevisibility;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_Config;
import org.ywzj.rvp.util.RVP_RemoteVehicleViewPolicy;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** 综合原版实体同步范围和每名玩家的客户端视距，计算载具与弹药的远距交接半径。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EntityTrackingRange {
    /** 在每玩家近远交接边界前提前准备显示数据的距离，单位格；保持原有 128 格重叠区。 */
    private static final double HANDOVER_OVERLAP = 128.0D;
    /** 按服务器实例隔离玩家上报的视距；退出和停服时移除，单机重开不会沿用旧连接。 */
    private static final Map<MinecraftServer, ClientViewDistances> CLIENT_VIEW_DISTANCES = new IdentityHashMap<>();

    private RVP_EntityTrackingRange() {
    }

    /**
     * 返回当前玩家对目标使用的水平近远交接半径，单位格。
     * <p>与 ChunkMap 的实体跟踪器一致：乘员可扩大载具的类型半径，随后应用服务器实体广播倍率，
     * 最后受服务端视距及该玩家客户端视距共同限制。每次读取当前值，运行中调整后自动生效。</p>
     */
    public static double resolve(ServerPlayer observer, Entity target) {
        MinecraftServer server = observer.serverLevel().getServer();
        int trackingRange = target.getType().clientTrackingRange() * 16;
        for (Entity passenger : target.getIndirectPassengers()) {
            trackingRange = Math.max(trackingRange, passenger.getType().clientTrackingRange() * 16);
        }
        // 调用原版服务端倍率入口，同时兼容专服的广播百分比与单机的实体距离倍率。
        int scaledRange = server.getScaledTrackingDistance(trackingRange);
        int serverViewDistance = server.getPlayerList().getViewDistance();
        ClientViewDistances clientDistances = CLIENT_VIEW_DISTANCES.get(server);
        // 调用每连接玩家的范围表，尚未收到首次上报时沿用服务器范围。
        int clientViewDistance = clientDistances == null ? serverViewDistance
                : clientDistances.get(observer.getUUID(), serverViewDistance);
        // 调用统一上限计算，客户端设置只能提前交接，不能扩大服务端实际实体同步范围。
        return limitToViewDistance(scaledRange, serverViewDistance, clientViewDistance);
    }

    /** 接受发送者自己的客户端视距，供下一次载具和弹药快照分别计算备用范围。 */
    public static void updateClientViewDistance(ServerPlayer player, int chunks, boolean allowBeyond) {
        // 调用范围表校验和写入，隔离同服不同玩家及不同服务器的设置。
        MinecraftServer server = player.serverLevel().getServer();
        ClientViewDistances distances = CLIENT_VIEW_DISTANCES.computeIfAbsent(server, ignored -> new ClientViewDistances());
        int previousChunks = distances.get(player.getUUID(), -1);
        boolean previousRequest = distances.requestsBeyond(player.getUUID());
        distances.update(player.getUUID(), chunks, allowBeyond);
        if (previousChunks != chunks || previousRequest != allowBeyond) {
            // 调用现有视觉同步服务，下一 Tick 撤销旧范围，不等待可能被配置为十秒的同步周期。
            RVP_RemoteVehicleVisualSyncService.requestPolicyRefresh(server);
        }
    }

    /** 读取此玩家的显示偏好，区分客户端全关闭与服务端仅限制低空。 */
    static boolean clientRequestsBeyond(ServerPlayer player) {
        ClientViewDistances distances = CLIENT_VIEW_DISTANCES.get(player.serverLevel().getServer());
        return distances != null && distances.requestsBeyond(player.getUUID());
    }

    /** 计算本连接的实际视距与多人许可，发送和客户端绘制使用同一份策略。 */
    public static RVP_RemoteVehicleViewPolicy visualPolicy(ServerPlayer player) {
        MinecraftServer server = player.serverLevel().getServer();
        int serverChunks = server.getPlayerList().getViewDistance();
        ClientViewDistances distances = CLIENT_VIEW_DISTANCES.get(server);
        int clientChunks = distances == null ? serverChunks : distances.get(player.getUUID(), serverChunks);
        boolean clientRequests = distances != null && distances.requestsBeyond(player.getUUID());
        // 调用公共策略，专服和已开放 LAN 的内置服都执行服务端默认禁用。
        return RVP_RemoteVehicleViewPolicy.forConnection(
                server.isDedicatedServer(), server.isPublished(),
                RVP_Config.allowsVehiclesBeyondViewDistance(), clientRequests, serverChunks, clientChunks);
    }

    /** 离服即清除上报，重连必须重新取得客户端当前设置。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ClientViewDistances distances = CLIENT_VIEW_DISTANCES.get(player.serverLevel().getServer());
            if (distances != null) {
                // 调用范围表移除旧连接数据，避免相同玩家重连继承旧视距。
                distances.remove(player.getUUID());
            }
        }
    }

    /** 服务器结束后释放该实例的全部玩家视距。 */
    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        CLIENT_VIEW_DISTANCES.remove(event.getServer());
    }

    /** 以服务端实际视距限制已应用倍率的实体半径；区块边长为 16 格。 */
    static double limitToViewDistance(int scaledRange, int viewDistance) {
        return Math.min(scaledRange, Mth.clamp(viewDistance, 2, 32) * 16);
    }

    /** 每玩家的交接半径同时受服务端实体同步范围和其客户端渲染视距约束。 */
    static double limitToViewDistance(int scaledRange, int serverViewDistance, int clientViewDistance) {
        // 调用原生范围上限计算，客户端只能缩短显示交接半径。
        return Math.min(limitToViewDistance(scaledRange, serverViewDistance),
                Mth.clamp(clientViewDistance, 2, 32) * 16.0D);
    }

    /** 返回备用数据开始同步的水平距离平方；低视距时重叠区最早从玩家位置开始。 */
    public static double preparationDistanceSquared(double handoverDistance) {
        double preparationDistance = Math.max(0.0D, handoverDistance - HANDOVER_OVERLAP);
        return preparationDistance * preparationDistance;
    }

    /** 一台服务器上的每玩家视距表；独立于世界实体，便于验证玩家之间互不覆盖。 */
    static final class ClientViewDistances {
        /** 玩家身份到最新客户端视距，单位区块。 */
        private final Map<UUID, Integer> distances = new HashMap<>();
        /** 每连接的客户端扩展请求；首包前默认不授予扩展权限。 */
        private final Map<UUID, Boolean> beyondRequests = new HashMap<>();

        /** 保存合法视距，非法请求不覆盖当前有效值。 */
        void update(UUID player, int chunks, boolean allowBeyond) {
            if (chunks < 2 || chunks > 32) {
                throw new IllegalArgumentException("Remote view distance must be within 2..32 chunks");
            }
            distances.put(player, chunks);
            beyondRequests.put(player, allowBeyond);
        }

        /** 返回指定玩家的上报值，首次上报前使用服务器视距。 */
        int get(UUID player, int fallback) {
            return distances.getOrDefault(player, fallback);
        }

        /** 返回已校验连接的扩展请求，不读取其他玩家或客户端本地 common 配置。 */
        boolean requestsBeyond(UUID player) {
            return beyondRequests.getOrDefault(player, false);
        }

        /** 结束连接时删除该玩家的值。 */
        void remove(UUID player) {
            distances.remove(player);
            beyondRequests.remove(player);
        }
    }
}
