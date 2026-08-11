package org.ywzj.rvp.server.visual;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.visual.S2CVisualEffectEvent;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEventPublisher;

/** 使用同维度、指定范围分发的服务端网络发布适配器。 */
public final class RVP_NetworkVisualEventPublisher implements RVP_VisualEventPublisher {
    @Override
    public void publish(ServerLevel level, RVP_VisualEffectEvent event) {
        PacketDistributor.TargetPoint targetPoint = new PacketDistributor.TargetPoint(
                event.position().x,
                event.position().y,
                event.position().z,
                event.broadcastRange(),
                level.dimension());
        // 调用 RVP 公共网络通道，仅向同维度且位于广播范围内的玩家发送一次领域事件。
        RVP_Network.CHANNEL.send(
                PacketDistributor.NEAR.with(() -> targetPoint),
                new S2CVisualEffectEvent(event));
    }
}
