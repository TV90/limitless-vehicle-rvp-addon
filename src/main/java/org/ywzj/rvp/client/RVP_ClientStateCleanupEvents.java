package org.ywzj.rvp.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.rvp.client.visual.vehicle.RVP_MuzzleSmokeEmitter;
import org.ywzj.rvp.event.RVP_StateCleanupEvents;

/**
 * [RVP] 客户端运行期缓存清理入口。
 *
 * <p>只在客户端世界卸载或断线时触发；模型/贴图资源缓存由资源 reload 监听器负责，
 * 本类只清按实体或客户端会话积累的运行期数据。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientStateCleanupEvents {

    private RVP_ClientStateCleanupEvents() {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 客户端维度切换也会触发 Unload，这里只清客户端自己的实体级缓存；
            // 共享侧表（GPS/空爆测距等）留到真正断线/退出世界再清，避免误删用户设置。
            clearClientLevelCaches();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearClientLevelCaches();
        // 断线/退出世界：单人模式与集成服务端同 JVM，一并清掉共享运行期侧表。
        RVP_StateCleanupEvents.clearAll();
    }

    private static void clearClientLevelCaches() {
        // 炮口烟节流表的 value 反向引用载具 key，必须显式清空。
        RVP_MuzzleSmokeEmitter.clearAll();
        // 挂架弹药预测按实体 id 记录，断线/换世界后无恢复意义。
        RVP_CustomMountRenderLogic.clearPredictedAmmo();
        // 雷达主控快照按实体 id 记录，旧 id 不应跨世界残留。
        RVP_ClientEvents.clearRadarSnapshots();
    }
}
