package org.ywzj.rvp.client.compat.distanthorizons;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/** 换维度与退出连接时清理 RVP 自有 DH 兼容资源。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DhCompatLifecycle {
    private RVP_DhCompatLifecycle() {
    }

    /** 客户端世界卸载时调用兼容入口释放旧尺寸/旧世界资源。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            RVP_DistantHorizonsCompatBootstrap.clearClientResources();
        }
    }

    /** 客户端退出连接时再次幂等清理，覆盖没有完整世界卸载事件的路径。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RVP_DistantHorizonsCompatBootstrap.clearClientResources();
    }
}
