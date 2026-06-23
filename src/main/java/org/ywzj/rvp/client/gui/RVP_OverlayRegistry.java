package org.ywzj.rvp.client.gui;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/**
 * 注册 RVP 自定义 overlay。仅在 mod 事件总线上处理注册。
 * <p>
 * 取消原版 overlay 由 {@link RVP_OverlayCancelHandler} 在 Forge 总线上处理。
 * </p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_OverlayRegistry {

    @SubscribeEvent
    public static void onRegisterHud(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("rvp_radar", new RVP_RadarOverlay());
        event.registerAboveAll("rvp_scope", new RVP_ScopeOverlay());
        event.registerAboveAll("rvp_missile", new RVP_MissileOverlay());
        event.registerAboveAll("rvp_machinegun_lead", new RVP_MachinegunLeadOverlay());
        event.registerAboveAll("rvp_charge_bar", new RVP_ChargeBarOverlay());
        event.registerAboveAll("rvp_aps_hud", new RVP_ApsHudOverlay());
    }
}
