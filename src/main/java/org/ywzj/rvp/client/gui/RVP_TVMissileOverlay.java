package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientOpticalState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;

/**
 * 人在回路电视 HUD。与本体载具 HUD 一样注册在 {@code CHAT_PANEL} 之下，
 * 半透明聊天框叠上来时仍按同一图层透出，不再被挡在对话框后面。
 */
public class RVP_TVMissileOverlay implements IGuiOverlay {

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

    @Override
    public void render(ForgeGui gui, GuiGraphics gg, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            reset();
            return;
        }
        // 调用独立终结提示；此时导弹控制与远程区块已释放，仍可完整覆盖返回母车的底层画面。
        if (RVP_ClientHitlState.isNoImage(mc.level)) {
            reset();
            RVP_HitlVideoLossRenderer.drawCaption(gg, mc.font);
            return;
        }
        if (!RVP_ClientHitlState.isActive()) {
            reset();
            return;
        }
        // 短暂阻断期间弹体也可能尚未同步，失图提示不依赖弹体存在。
        if (RVP_ClientHitlState.isHitlLinkBlocked()) RVP_HitlVideoLossRenderer.drawCaption(gg, mc.font);
        // 调用既有会话的统一显示入口，导弹虚拟化期间保留镜头/操控显示，不新增目标权限。
        Entity e = RVP_ClientHitlState.resolveActiveMissile(mc);
        if (!(e instanceof RVP_MissileEntity missile) || e.isRemoved()) {
            reset();
            return;
        }

        if (missile.isOpticalMissile() || RVP_ClientOpticalState.isViewing(missile.getId())) {
            RVP_VehicleActionHudOverlay.drawOpticalObserverRow(gg);
            return;
        }

        if (lastMissileId != missile.getId()) { reset(); lastMissileId = missile.getId(); }
        updateRate(missile);

        Font font = mc.font;
        // 人在回路 TV 保留动态锁点，仅指令线等其他模式绘制固定中央参照。
        if (!RVP_ClientHitlState.isHitlLinkBlocked() && !RVP_ClientHitlState.isDesignateMode()) {
            RVP_HitlReticleRenderer.drawReference(gg);
        }
        String video = switch (RVP_ClientHitlState.getVideoMode()) {
            case COLOR -> "VIS"; case BW -> "B/W"; case THERMAL -> "IR";
        };
        String control = switch (RVP_ClientHitlState.getControlMode()) {
            case MOUSE -> "WIRE"; case DESIGNATE -> "TV"; case VIEW -> "OBS";
        };
        boolean designated = RVP_ClientHitlState.isDesignateMode()
                && RVP_ClientHitlState.getClientDesignatedPos() != null;
        // 调用既有目标/实时瞄准点，天空的虚拟射线终点不作为真实测距显示。
        var point = designated ? org.ywzj.rvp.client.state.RVP_ClientSaclosState.getLaserHudPos()
                : RVP_ClientHitlState.getClientAimPoint();
        double range = point == null ? Double.NaN : point.distanceTo(missile.position());
        if (!designated && range >= RVP_ClientHitlState.DESIGNATE_AIM_RANGE - 1) range = Double.NaN;
        String track = RVP_ClientHitlState.getLockStatusLabel();
        boolean airburst = missile.rvp$isHitlRightClickDetonate();
        // 调用统一缩放的紧凑侧栏；飞行时间复用生成包飞行刻与客户端后续刻的既有视觉时钟。
        RVP_HitlInstrumentRenderer.draw(gg, font, missile.getY(), range,
                missile.getDisplayFlightTickCount() / 20.0, lastRateDegPerSec, video, control, track,
                airburst, RVP_ClientHitlState.getHitlZoom());
        boolean airburstNearby = airburst && hasNonSelfEntityWithin(mc, missile, 10.0D);
        RVP_HitlInstrumentRenderer.drawActionHints(gg,
                RVP_ClientHitlState.isDesignateMode(), airburst,
                RVP_ClientHitlState.shouldPromptDesignateKey(), airburstNearby);
        if (RVP_ClientHitlState.isMouseSteering() && !RVP_ClientHitlState.isHitlLinkBlocked()) drawSteeringCursor(gg);
        else { cursor.reset(); lastFrameNanos = 0; }
        // 指令制导的动态瞄准/目标斜十字并回同一 HUD 层，和仪表一起走 CHAT_PANEL 之下的绘制顺序。
        if (RVP_ClientHitlState.getSnowOpacity(mc.level) <= 0) RVP_HitlDesignateOverlay.render(gg);
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

    /** 空爆提示变红条件：导弹周围 10 格内存在非自身、非发射者的存活实体。 */
    private static boolean hasNonSelfEntityWithin(Minecraft mc, RVP_MissileEntity missile, double radius) {
        if (mc.level == null) {
            return false;
        }
        Entity owner = missile.getOwner();
        for (Entity entity : mc.level.getEntities(missile, missile.getBoundingBox().inflate(radius))) {
            if (entity != missile && entity != owner && entity.isAlive() && !entity.isRemoved()) {
                return true;
            }
        }
        return false;
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
