package org.ywzj.rvp.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 原版 overlay cancel handler。
 * {@code RenderGuiOverlayEvent.Pre} 在 Forge 事件总线上触发，
 * 必须用 {@link MinecraftForge#EVENT_BUS} 注册。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_OverlayCancelHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastProbeLog = Long.MIN_VALUE;
    private static final ResourceLocation VEHICLE_RADAR = ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "vehicle_radar");
    private static final ResourceLocation VEHICLE_SCOPE = ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "vehicle_scope");

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
        // 探针：确认 ForgeGui 每帧是否在渲染 overlay、rvp_hit_indicator 是否在渲染列表。
        // [RVP] 探针（含 overlay 列表遍历与 StringBuilder 拼接）仅在 HUD 调试开关开启时执行，
        // 避免正常游戏时每 2 秒白做一次全列表遍历（开关：/rvpdebug flags hud）
        if (RVP_DebugFlags.HUD.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastProbeLog > 2000) {
                lastProbeLog = now;
                boolean contains = false;
                StringBuilder sb = new StringBuilder();
                for (var entry : GuiOverlayManager.getOverlays()) {
                    if (entry.id().getPath().equals("rvp_hit_indicator")) {
                        contains = true;
                    }
                    sb.append(entry.id()).append(' ');
                }
                LOGGER.info("[RVP-Hud] overlay probe: size={} current={} containsHitIndicator={} list={}",
                        GuiOverlayManager.getOverlays().size(), event.getOverlay().id(), contains, sb);
            }
        }
        ResourceLocation id = event.getOverlay().id();
        if (!id.equals(VEHICLE_RADAR) && !id.equals(VEHICLE_SCOPE)) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null || vehicle.getVehicleId() == null) {
            if (id.equals(VEHICLE_RADAR)) {
                event.setCanceled(true);
            }
            return;
        }
        if (id.equals(VEHICLE_RADAR)) {
            event.setCanceled(true);
            return;
        }
        if (RVP_ClientHitlState.isActive()) {
            event.setCanceled(true);
            return;
        }
        String preset = VehicleUIPresetCache.get(vehicle.getVehicleId());
        if (preset != null && !preset.isEmpty()) {
            event.setCanceled(true);
        }
    }
}
