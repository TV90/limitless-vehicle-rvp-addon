package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
        // 调用独立终结提示；此时导弹控制与远程区块已释放，仍可完整覆盖返回母车的底层画面。
        if (RVP_ClientHitlState.isNoImage(mc.level)) {
            reset();
            RVP_HitlVideoLossRenderer.draw(event.getGuiGraphics(), mc.font, mc.level.getGameTime());
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
            // 调用与终结共用的现有雪花屏，保留遮挡机制与控制判定。
            RVP_HitlVideoLossRenderer.drawSnow(gg, mc.level.getGameTime());
        }
        // 调用固定居中的装饰层；与可动准星、已有制导和离轴角限制相互独立。
        if (!RVP_ClientHitlState.isHitlLinkBlocked()) {
            if (RVP_ClientHitlState.isDesignateMode()) RVP_HitlReticleRenderer.drawTvReference(gg);
            else RVP_HitlReticleRenderer.drawReference(gg);
        }
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
        if (RVP_ClientHitlState.isHitlLinkBlocked()) RVP_HitlVideoLossRenderer.drawCaption(gg, font);
        if (RVP_ClientHitlState.isMouseSteering() && !RVP_ClientHitlState.isHitlLinkBlocked()) drawSteeringCursor(gg);
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
