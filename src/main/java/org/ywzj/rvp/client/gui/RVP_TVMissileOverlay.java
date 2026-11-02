package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_TVMissileOverlay {

    /** 仅作视觉阻尼，不回写实际舵量。 */
    private static final RVP_HitlCursorMotion cursor = new RVP_HitlCursorMotion();
    /** 当前弹体编号，切弹清理视觉状态。 */
    private static int lastMissileId = -1;
    /** 上一绘制帧时间，单位纳秒。 */
    private static long lastFrameNanos;
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

        if (lastMissileId != missile.getId()) { reset(); lastMissileId = missile.getId(); }
        updateRate(missile);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        if (RVP_ClientHitlState.isHitlLinkBlocked()) {
            drawSnow(gg, mc.level);
        }
        // 调用固定居中的装饰层；与可动准星、已有制导和离轴角限制相互独立。
        RVP_HitlReticleRenderer.drawReference(gg);
        String video = switch (RVP_ClientHitlState.getVideoMode()) {
            case COLOR -> "VIS"; case BW -> "B/W"; case THERMAL -> "IR";
        };
        String control = switch (RVP_ClientHitlState.getControlMode()) {
            case MOUSE -> "WIRE"; case DESIGNATE -> "TV"; case VIEW -> "OBS";
        };
        // 客户端弹体只插值位置且关闭常规速度同步，deltaMovement 会停在出生值；
        // 复用每 5 tick 下发的服务端弹药快照，显示真实加速、转弯与燃尽后的当前速度。
        var snapshot = RVP_ClientRemoteAmmoState.getEntry(mc.level.dimension().location(), missile.getId());
        // 首份快照尚未到达时沿用出生速度；读数不反写实体，保持既有插值与制导行为。
        double speedKmh = snapshot != null && Double.isFinite(snapshot.speedKmh()) && snapshot.speedKmh() >= 0.0
                ? snapshot.speedKmh() : missile.getDeltaMovement().length() * 72.0;
        boolean designated = RVP_ClientHitlState.isDesignateMode()
                && RVP_ClientHitlState.getClientDesignatedPos() != null;
        // 调用既有目标/实时瞄准点，天空的虚拟射线终点不作为真实测距显示。
        var point = designated ? org.ywzj.rvp.client.state.RVP_ClientSaclosState.getLaserHudPos()
                : RVP_ClientHitlState.getClientAimPoint();
        double range = point == null ? Double.NaN : point.distanceTo(missile.position());
        if (!designated && range >= RVP_ClientHitlState.DESIGNATE_AIM_RANGE - 1) range = Double.NaN;
        String track = RVP_ClientHitlState.getClientDesignatedEntityId() >= 0 ? "ENT" : designated ? "PNT" : "--";
        // 调用统一缩放的紧凑侧栏；飞行时间复用生成包飞行刻与客户端后续刻的既有视觉时钟。
        RVP_HitlInstrumentRenderer.draw(gg, font, speedKmh, missile.getY(), range,
                missile.getDisplayFlightTickCount() / 20.0, lastRateDegPerSec, video, control, track,
                missile.rvp$isHitlRightClickDetonate());
        if (RVP_ClientHitlState.isMouseSteering()) drawSteeringCursor(gg);
        else { cursor.reset(); lastFrameNanos = 0; }
    }

    /** 指令线模式的视觉游标跟随既有舵量，并以临界阻尼跟随其原有回中过程。 */
    private static void drawSteeringCursor(GuiGraphics gg) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (!Minecraft.getInstance().isPaused()) {
            // 调用只读视觉阻尼；不更改 getHitlYaw/getHitlPitch 或发送到服务端的输入。
            cursor.update(RVP_ClientHitlState.getHitlYaw(), RVP_ClientHitlState.getHitlPitch(), dt);
        }
        float scale = RVP_HitlReticleRenderer.scale(gg);
        RVP_HitlReticleRenderer.drawAim(gg, gg.guiWidth() * 0.5 + cursor.x() * scale,
                gg.guiHeight() * 0.5 + cursor.y() * scale);
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
        cursor.reset();
        lastFrameNanos = 0;
        lastMissileId = -1;
        lastTickCount = Integer.MIN_VALUE;
        lastRateDegPerSec = 0f;
        lastYaw = 0f;
        lastPitch = 0f;
    }
}
