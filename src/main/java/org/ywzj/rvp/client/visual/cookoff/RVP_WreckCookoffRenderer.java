package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/**
 * 殉燃客户端生命周期清理入口。
 *
 * <p>旧版本类在 {@code AFTER_WEATHER} 中绘制 {@code hot_core_16x64.png} 横条火柱；
 * 当前车顶火焰和炮口灰烟均由客户端粒子引擎渲染，本类只保留资源重载和世界卸载时的控制器清理职责。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckCookoffRenderer implements ResourceManagerReloadListener {

    /** 客户端资源重载入口，仅由客户端注册类加载。 */
    public static final RVP_WreckCookoffRenderer INSTANCE = new RVP_WreckCookoffRenderer();

    private RVP_WreckCookoffRenderer() {
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // 调用本项目殉燃控制器清理入口：资源重载后重新解析车辆挂点，避免保留旧姿态引用。
        RVP_WreckCookoffController.clear();
    }

    /** 世界退出立即清理，不等下一次进入游戏。 */
    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 调用本项目殉燃控制器清理入口：换维度时释放旧世界和预览车辆引用。
            RVP_WreckCookoffController.clear();
        }
    }
}
