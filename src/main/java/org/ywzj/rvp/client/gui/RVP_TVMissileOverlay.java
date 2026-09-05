package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_TVMissileOverlay {

    private static int lastTickCount = Integer.MIN_VALUE;
    private static float lastYaw;
    private static float lastPitch;
    private static float lastRateDegPerSec;

    /** 雪花噪点纹理缓存：屏幕尺寸一张，按需重绘。 */
    private static DynamicTexture snowTexture;
    private static ResourceLocation snowTextureLocation;
    private static int snowWidth = -1;
    private static int snowHeight = -1;
    private static int snowRegenTick = -1;
    /** 噪点纹理重绘间隔（tick）：每 4 tick 重绘一次模拟雪花闪动，避免逐帧重绘。 */
    private static final int SNOW_REGEN_INTERVAL = 4;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (!RVP_ClientHitlState.isActive()) {
            reset();
            return;
        }
        Entity e = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (!(e instanceof RVP_MissileEntity missile) || e.isRemoved()) {
            reset();
            return;
        }

        updateRate(missile);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        if (RVP_ClientHitlState.isHitlLinkBlocked()) {
            drawSnow(gg, mc.level);
        }
        int x = 6;
        int y = 6;

        Component mode = switch (RVP_ClientHitlState.getVideoMode()) {
            case COLOR -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.color");
            case BW -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.bw");
            case THERMAL -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.thermal");
        };
        Component control = switch (RVP_ClientHitlState.getControlMode()) {
            case MOUSE -> Component.translatable("overlay.ywzj_rvp.hitl.control.mouse");
            case DESIGNATE -> Component.translatable("overlay.ywzj_rvp.hitl.control.designate");
            case VIEW -> Component.translatable("overlay.ywzj_rvp.hitl.control.view");
        };
        Component header = Component.translatable("overlay.ywzj_rvp.tv_missile.header", mode);
        // 转向率显式格式化保留一位小数（避免翻译占位符浮点精度差异），并追加当前速度（km/h）
        String turnRateText = String.format("%.1f", lastRateDegPerSec);
        double speedKmh = missile.getDeltaMovement().length() * 72.0;
        String speedText = String.format("%.0f", speedKmh);
        Component turnRate = Component.translatable("overlay.ywzj_rvp.tv_missile.turn_rate", turnRateText, speedText);
        gg.drawString(font, header, x, y, Color.GREEN, true);
        gg.drawString(font, control, x, y + 10, Color.GREEN, true);
        gg.drawString(font, turnRate, x, y + 20, Color.GREEN, true);

        if (RVP_ClientHitlState.getControlMode() == RVP_EnumHitlControlMode.DESIGNATE) {
            int targetId = RVP_ClientHitlState.getClientDesignatedEntityId();
            if (targetId >= 0) {
                gg.drawString(font,
                        Component.translatable("overlay.ywzj_rvp.hitl.designate.intercept"),
                        x, y + 30, Color.GREEN, true);
            }
        }

        // 指令线人在回路（MOUSE 驾控）：准星固定在屏幕中央，样式与电视人在回路（CRT 武器准星）一致
        if (RVP_ClientHitlState.getControlMode() == RVP_EnumHitlControlMode.MOUSE) {
            drawCenterCrosshair(gg, font);
        }
        // 右键空爆弹头提示
        if (missile.rvp$isHitlRightClickDetonate()) {
            Component hint = Component.translatable("overlay.ywzj_rvp.hitl.airburst_hint");
            gg.drawString(font, hint, gg.guiWidth() - font.width(hint) - 6, 6, Color.RED, true);
        }
    }

    /** 屏幕中央 CRT 样式准星（指令线弹：弹头指向即准星，固定屏幕中央）。 */
    private static void drawCenterCrosshair(GuiGraphics gg, Font font) {
        int cx = gg.guiWidth() / 2;
        int cy = gg.guiHeight() / 2;
        int color = Color.GREEN;
        // 中央空心方块（5px，与电视弹 CRT 准星一致）
        // 上下左右延伸线（样式对齐 RVP_ScopeOverlay CRT 准星分支）
        gg.fill(cx - 1, cy - 32, cx + 1, cy - 8, color);
        gg.fill(cx - 1, cy + 8, cx + 1, cy + 32, color);
        gg.fill(cx - 32, cy - 1, cx - 8, cy + 1, color);
        gg.fill(cx + 8, cy - 1, cx + 32, cy + 1, color);
        // 距离
        double dist = LocalVehiclePlayer.instance.aimLocationDistance;
        gg.drawCenteredString(font, Component.literal((int) dist + " m"), cx, cy + 40, color);
    }

    /** 链路被阻时的干扰滤镜：黑底 + 整屏贴一张预生成雪花噪点纹理。
     * 由原来的「每帧循环数千次 fill 小矩形」改为「黑底一次 draw + 噪点纹理一次 blit」，
     * 消除逐像素顶点提交导致的客户端 FPS 骤降。 */
    private static void drawSnow(GuiGraphics gg, Level level) {
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        gg.fill(0, 0, w, h, 0xFF000000); // 黑底（单次 draw）
        ResourceLocation loc = ensureSnowTexture(level, w, h);
        if (loc != null) {
            // 整屏贴噪点纹理：单次 draw（替代原来数千次 2×2 fill）
            gg.blit(loc, 0, 0, 0, 0, w, h, w, h);
        }
    }

    /** 复用/生成一张屏幕尺寸的雪花噪点纹理；每 {@code SNOW_REGEN_INTERVAL} tick 重绘一次模拟闪动。
     * 重绘是批量写像素 + 一次 upload，不产生逐像素 draw 提交。 */
    private static ResourceLocation ensureSnowTexture(Level level, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int gameTick = (int) (level.getGameTime() % 1000000L);
        boolean sizeChanged = snowWidth != w || snowHeight != h;
        if (snowTexture != null && !sizeChanged && Math.abs(gameTick - snowRegenTick) < SNOW_REGEN_INTERVAL) {
            return snowTextureLocation;
        }
        if (sizeChanged || snowTexture == null) {
            if (snowTexture != null) {
                snowTexture.close();
            }
            NativeImage img = new NativeImage(w, h, false);
            snowTexture = new DynamicTexture(img);
            snowTextureLocation = mc.getTextureManager()
                    .register("rvp_tv_noise_" + w + "_" + h, snowTexture);
            snowWidth = w;
            snowHeight = h;
        }
        // 批量重绘噪点：约 90% 像素为暗色、少量亮灰点模拟雪花（写纹理，无 draw 开销）
        NativeImage img = snowTexture.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = level.random.nextInt(100);
                int gray = r < 90 ? 0 : 60 + level.random.nextInt(160);
                img.setPixelRGBA(x, y, (0xFF << 24) | (gray << 16) | (gray << 8) | gray);
            }
        }
        snowTexture.upload();
        snowRegenTick = gameTick;
        return snowTextureLocation;
    }

    private static void updateRate(Entity missile) {
        int tc = missile.tickCount;
        if (tc == lastTickCount) {
            return;
        }
        if (lastTickCount == Integer.MIN_VALUE) {
            lastTickCount = tc;
            lastYaw = missile.getYRot();
            lastPitch = missile.getXRot();
            lastRateDegPerSec = 0f;
            return;
        }
        int dt = Math.max(1, tc - lastTickCount);
        float yaw = missile.getYRot();
        float pitch = missile.getXRot();
        float dyaw = Mth.wrapDegrees(yaw - lastYaw);
        float dpitch = pitch - lastPitch;
        float delta = (float) Math.sqrt((double) (dyaw * dyaw + dpitch * dpitch));
        float degPerSec = delta * (20f / (float) dt);
        lastRateDegPerSec = Mth.lerp(0.35f, lastRateDegPerSec, degPerSec);
        lastTickCount = tc;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    private static void reset() {
        lastTickCount = Integer.MIN_VALUE;
        lastRateDegPerSec = 0f;
        lastYaw = 0f;
        lastPitch = 0f;
    }
}
