package org.ywzj.rvp.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.resource.RVP_CustomMountReloadListener;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_ClientReloadListeners {

    private RVP_ClientReloadListeners() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(RVP_DisplayTransparentModeManager.INSTANCE);
        event.registerReloadListener(RVP_CustomMountReloadListener.INSTANCE);
        // rvp 载具包在客户端以资源包（CLIENT_RESOURCES）注册，AddReloadListenerEvent（数据仓库）扫不到
        // data/rvp/vehicles，故额外注册到客户端资源重载，保证单机 / 联机客户端的隐藏乘员、
        // 命中箱系数等配置可用。
        event.registerReloadListener(RVP_VehicleExtendedConfigManager.INSTANCE);
        event.registerReloadListener(RVP_VehicleHitboxFactorManager.INSTANCE);
    }
}
