package org.ywzj.rvp.client.handler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.RVP_AuiVariantScreen;
import org.ywzj.rvp.mixin.accessor.VehicleModdingToolScreenAccessor;
import org.ywzj.vehicle.client.screen.VehicleModdingToolScreen;

/** 改装工具的同一个开屏事件同时覆盖本体载具与扩展包载具。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ModdingToolOverlay {
    private RVP_ModdingToolOverlay() {}

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof VehicleModdingToolScreen tool) {
            // 沿用现有访问器读取本体实际交互的实体；在旧文档初始化前替换，关闭后直接回到游戏。
            var vehicle = ((VehicleModdingToolScreenAccessor) tool).rvp$getVehicle();
            if (vehicle != null) event.setNewScreen(new RVP_AuiVariantScreen(vehicle, null));
        }
    }
}
