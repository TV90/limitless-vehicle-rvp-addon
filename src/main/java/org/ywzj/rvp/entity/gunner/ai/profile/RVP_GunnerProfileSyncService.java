package org.ywzj.rvp.entity.gunner.ai.profile;

import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.gunner.S2CGunnerProfileSnapshot;

import java.util.Comparator;

/** 将服务端成功发布的 Profile ID 同步给客户端数据驱动物品变体。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GunnerProfileSyncService {
    private RVP_GunnerProfileSyncService() {}
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        GunnerProfileManager manager = GunnerProfileManager.INSTANCE;
        S2CGunnerProfileSnapshot packet = new S2CGunnerProfileSnapshot(manager.getGeneration(),
                manager.getProfileIds().stream().sorted(Comparator.comparing(Object::toString)).toList());
        if (event.getPlayer() != null) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
        } else {
            RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
        }
    }
}
