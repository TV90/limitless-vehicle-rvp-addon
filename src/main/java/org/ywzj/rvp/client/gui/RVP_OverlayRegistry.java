package org.ywzj.rvp.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

/**
 * 注册 RVP 自定义 overlay。仅在 mod 事件总线上处理注册。
 * <p>
 * 取消原版 overlay 由 {@link RVP_OverlayCancelHandler} 在 Forge 总线上处理。
 * </p>
 * <p>
 * 全部与本体的载具 HUD 一样注册到 {@link VanillaGuiOverlay#CHAT_PANEL} 之下（即渲染于聊天框之前），
 * 与本体燃油/速度等文字处于同一渲染层：被聊天框半透明背景覆盖时能正常透过 alpha 混合显示，
 * 不会像 registerAboveAll 那样因渲染层/深度差异被聊天背景彻底遮死。相对层序仍按注册顺序保持。
 * </p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_OverlayRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onRegisterHud(RegisterGuiOverlaysEvent event) {
        LOGGER.info("[RVP-Hud] onRegisterHud fired");
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_radar", new RVP_RadarOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_scope", new RVP_ScopeOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_missile", new RVP_MissileOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_machinegun_lead", new RVP_MachinegunLeadOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_charge_bar", new RVP_ChargeBarOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_heat_hud", new RVP_HeatHudOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_aps_hud", new RVP_ApsHudOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_dircm_hud", new RVP_DircmHudOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_ecm_hud", new RVP_EcmHudOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_countermeasure_hud", new RVP_CountermeasureHudOverlay());
        // 主动ECM 伪造锁定 blip：注册在 CHAT_PANEL 之下、与其他 RVP overlay 同层。
        // Forge 禁止对其他 mod 的 overlay 排序，这里靠注册顺序（RVP 在本体之后注册）
        // 让本覆盖层在本体 vehicle_radar 之后绘制，落在 RWR 表盘之上。
        // 必须排在命中展板(rvp_hit_indicator)之前：展板最后注册=最上层，blip 不能盖住命中提示栏。
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_ecm_fake_rwr", new RVP_EcmFakeRwrOverlay());
        // 命中展板注册在最后：同锚点下按注册顺序绘制，展板最后绘制即为该层最上层。
        // 展板内显式分步 flush 固定层级：雷达/RWR 文字 < 展板底 < 标题文字 < 模型。
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_hit_indicator", new RVP_HitIndicatorOverlay());
        event.registerBelow(VanillaGuiOverlay.CHAT_PANEL.id(), "rvp_lock_warning", new RVP_LockWarningOverlay());
    }
}
