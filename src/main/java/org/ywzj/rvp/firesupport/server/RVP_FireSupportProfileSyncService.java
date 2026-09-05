package org.ywzj.rvp.firesupport.server;

import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.firesupport.RVP_FireSupportProfileManager;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.S2CFireSupportProfileSnapshot;

/** 在玩家登录和成功 reload 同步服务端权威 profile revision 与规范化内容。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportProfileSyncService {
    private RVP_FireSupportProfileSyncService() {}

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // 调用本项目 profile 管理器和网络快照编码器：同步同一次原子发布的 revision 与规范化内容。
        S2CFireSupportProfileSnapshot packet = S2CFireSupportProfileSnapshot.from(
                RVP_FireSupportProfileManager.INSTANCE.snapshot());
        if (event.getPlayer() != null) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
        } else {
            RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
        }
    }
}
