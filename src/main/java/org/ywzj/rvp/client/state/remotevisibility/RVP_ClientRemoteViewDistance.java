package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteAmmoVisualRenderer;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.remotevisibility.C2SRemoteViewDistance;
import org.ywzj.rvp.util.RVP_RemoteVehicleViewPolicy;

import java.util.function.DoubleSupplier;

/** 上报本连接的客户端视距，并为已加载实体的近远绘制交接提供统一水平距离判断。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientRemoteViewDistance {
    /** 最近发送视距的连接；重连即使数值相同也必须重新发送。 */
    private static ClientPacketListener connection;
    /** 本连接最近发送的客户端视距，单位区块；-1 表示尚未发送。 */
    private static int lastSentChunks = -1;
    /** 本连接最近发送的超视距显示请求，与视距一并更新。 */
    private static boolean lastSentAllowBeyond;

    private RVP_ClientRemoteViewDistance() {
    }

    /** 首次进入世界或视距数值变化时发送，正常游戏不逐刻重复上报。 */
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener current = minecraft.getConnection();
        if (current == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        int chunks = Mth.clamp(minecraft.options.renderDistance().get(), 2, 32);
        boolean allowBeyond = RVP_ClientConfig.allowsRemoteVehiclesBeyondViewDistance();
        if (current != connection || chunks != lastSentChunks || allowBeyond != lastSentAllowBeyond) {
            // 调用 RVP 上行通道，只发送当前玩家自己的显示视距。
            RVP_Network.CHANNEL.sendToServer(new C2SRemoteViewDistance(chunks, allowBeyond));
            connection = current;
            lastSentChunks = chunks;
            lastSentAllowBeyond = allowBeyond;
        }
    }

    /** 以玩家位置而非第三人称/观瞄相机作为距离中心，与服务端准备范围一致。 */
    public static boolean isBeyondClientRange(Vec3 targetPosition) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        Vec3 observer = minecraft.player.position();
        double dx = targetPosition.x - observer.x;
        double dz = targetPosition.z - observer.z;
        // 调用同一纯数值边界判断，垂直高度不改变水平视距的交接位置。
        return isBeyondClientRange(dx * dx + dz * dz, minecraft.options.renderDistance().get());
    }

    /** 严格超过客户端视距时才交给远距绘制，等于边界仍保留原生显示。 */
    static boolean isBeyondClientRange(double horizontalDistanceSquared, int chunks) {
        double range = Mth.clamp(chunks, 2, 32) * 16.0D;
        return Double.isFinite(horizontalDistanceSquared) && horizontalDistanceSquared > range * range;
    }

    /** 代理与 DH 绘制前统一检查范围，缩小视距无需等待服务端下一份快照。 */
    public static boolean allowsVehiclePosition(Vec3 targetPosition, DoubleSupplier heightAboveGround) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        Vec3 observer = minecraft.player.position();
        // 合法 TV 会话的真实实体来自既有远程窗口；按镜头附近视距显示，不能误用发射点距离。
        if (RVP_ClientHitlState.isActive() && minecraft.options.getCameraType().isFirstPerson()
                && RVP_ClientHitlState.resolveActiveMissile(minecraft) != null) {
            observer = minecraft.gameRenderer.getMainCamera().getPosition();
        }
        double dx = targetPosition.x - observer.x;
        double dz = targetPosition.z - observer.z;
        // 调用公共策略，同时约束服务端下发范围和当前客户端偏好，不因 DH 开启而放宽。
        return RVP_ClientRemoteVehicleVisualState.renderPolicy().viewPolicy().allowsDistance(
                dx * dx + dz * dz, minecraft.options.renderDistance().get(),
                RVP_ClientConfig.allowsRemoteVehiclesBeyondViewDistance(), heightAboveGround);
    }

    /** 真实世界载具也遵守同一范围；本机、界面模型和非世界代理由各自通道处理。 */
    public static boolean allowsNativeVehicle(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || entity.level() != minecraft.level
                || minecraft.level.getEntity(entity.getId()) != entity || isLocalVehicle(entity)) {
            return true;
        }
        // 调用代理共用的显示门，防止客户端保留的远处真实实体绕过新规则。
        return allowsVehiclePosition(entity.position(),
                () -> RVP_RemoteVehicleViewPolicy.heightAboveGround(entity));
    }

    /** 本机所乘完整载具不能随远距策略隐藏。 */
    public static boolean isLocalVehicle(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getRootVehicle() == entity;
    }

    /** 退出连接后释放发送状态和上一帧的真实实体引用。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        connection = null;
        lastSentChunks = -1;
        // 调用两个渲染器的交接清理，旧连接不能隐藏重连后同编号的新实体。
        RVP_RemoteVehicleVisualRenderer.clearHandover();
        RVP_RemoteAmmoVisualRenderer.clearHandover();
    }

    /** 换维度即释放旧世界的帧引用；同一连接的上报值在服务端继续有效。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 调用交接清理，不让旧世界的真实实体引用留到新世界首帧。
            RVP_RemoteVehicleVisualRenderer.clearHandover();
            RVP_RemoteAmmoVisualRenderer.clearHandover();
        }
    }
}
