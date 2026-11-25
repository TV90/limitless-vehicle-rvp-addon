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
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
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
    private static final ResourceLocation VEHICLE_RADAR = new ResourceLocation("ywzj_vehicle", "vehicle_radar");
    private static final ResourceLocation VEHICLE_SCOPE = new ResourceLocation("ywzj_vehicle", "vehicle_scope");
    private static final ResourceLocation VEHICLE_STATUS = new ResourceLocation("ywzj_vehicle", "vehicle_status");
    private static final ResourceLocation VEHICLE_WEAPON = new ResourceLocation("ywzj_vehicle", "vehicle_weapon");

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
        boolean inMissile = RVP_ClientHitlState.isActive();
        boolean opticalView = inMissile && org.ywzj.rvp.client.state.RVP_ClientOpticalState
                .isViewing(RVP_ClientHitlState.getActiveMissileId());
        boolean opticalScope = !inMissile && org.ywzj.rvp.client.state.RVP_ClientOpticalState.isOpticalSelected();
        if ((opticalView || opticalScope) && (id.equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CROSSHAIR.id())
                || event.getOverlay().overlay() instanceof org.ywzj.vehicle.client.gui.VehicleAimAtOverlay
                || id.equals(VEHICLE_SCOPE))
                || inMissile && (id.equals(VEHICLE_STATUS) || id.equals(VEHICLE_WEAPON))) {
            event.setCanceled(true);
            return;
        }
        if (opticalScope && id.getNamespace().equals(RVP_MOD.MOD_ID) && id.getPath().equals("rvp_hmd")
                || opticalView && id.getNamespace().equals(RVP_MOD.MOD_ID) && !isOpticalVideoOverlay(id.getPath())) {
            event.setCanceled(true);
            return;
        }
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
        } else if (LocalVehiclePlayer.instance.onVehicle()
                && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE
                && event.getOverlay().overlay() instanceof VehicleScopeOverlay scopeOverlay) {
            // 无 UI 预设时，复用本体准心、射界和机身图，只把目标绘制交给本项目平滑入口。
            // 通过现有 Forge 事件取消原整层，避免本体随后再画一套未经平滑的雷达框。
            event.setCanceled(true);
            var graphics = event.getGuiGraphics();
            float partialTick = event.getPartialTick();
            // 调用本项目兼容入口，保留本体样式并让无预设 CRT 焦点准星也按本帧世界落点投影。
            RVP_ScopeOverlay.renderVanillaCrosshair(scopeOverlay, graphics, partialTick, vehicle);
            // 调用本项目炮兵诸元补画，对齐本体 0.6.3 从射界绘制中拆出的独立读数。
            RVP_ScopeOverlay.renderArtilleryReadout(graphics, vehicle);
            // 调用本体射界绘制，保留原始布局及武器角度限制。
            scopeOverlay.renderWeaponEngagementEnvelope(graphics, partialTick,
                    graphics.guiWidth(), graphics.guiHeight(), vehicle);
            // 调用本项目目标绘制，让无预设载具的扫描框、硬锁框和外置雷达框也统一平滑。
            RVP_ScopeOverlay.renderAimLockTarget(graphics, partialTick);
            // 本体 27552d7 将机身朝向图迁入 VehicleStatusOverlay 新 HUD 面板自绘（renderVehicleHeading
            // 自 VehicleScopeOverlay 移除），此处不再补画，随本体状态面板正常展示。
        }
    }
    private static boolean isOpticalVideoOverlay(String path) {
        return switch (path) {
            case "rvp_hitl_video_background", "rvp_hit_indicator", "rvp_tv_missile", "rvp_optical_targeting" -> true;
            default -> false;
        };
    }

}
