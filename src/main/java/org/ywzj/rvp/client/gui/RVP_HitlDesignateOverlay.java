package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlCamera;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientHitlUtil;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * TV SACLOS (DESIGNATE): crosshair projected from the same 3D aim point as designation.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitlDesignateOverlay {

    private RVP_HitlDesignateOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_ClientHitlState.isDesignateMode() || RVP_ClientHitlState.isHitlLinkBlocked()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        Entity entity = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (!(entity instanceof RVP_MissileEntity missile)) {
            return;
        }

        Vec3 aimPoint = RVP_ClientHitlUtil.resolveAimPoint(
                mc, missile,
                RVP_ClientHitlCamera.getAimYaw(),
                RVP_ClientHitlCamera.getAimPitch(),
                RVP_ClientHitlState.DESIGNATE_AIM_RANGE,
                false);
        if (aimPoint == null) {
            return;
        }

        Vec3 crosshair = VectorUtil.worldToScreen(aimPoint);
        if (crosshair.z <= 0.0D) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        // 调用 TV 独有的四角框和断开短十字，继续使用真实瞄准点投影及既有小范围离轴。
        RVP_HitlReticleRenderer.drawTvAim(gg, crosshair.x, crosshair.y);

        Vec3 designated = RVP_ClientSaclosState.getLaserHudPos();
        if (designated == null) {
            return;
        }

        Vec3 lockScreen = VectorUtil.worldToScreen(designated);
        if (lockScreen.z <= 0.0D) {
            return;
        }

        int width = gg.guiWidth();
        int height = gg.guiHeight();
        if (lockScreen.x < 0 || lockScreen.y < 0 || lockScreen.x > width || lockScreen.y > height) {
            return;
        }
        double separation = 16.0D * RVP_HitlReticleRenderer.scale(gg);
        if (lockScreen.distanceToSqr(crosshair.x, crosshair.y, 0.0D) < separation * separation) {
            return;
        }

        // 调用独立目标角框，继续沿用既有指定目标与屏幕可见性判定。
        RVP_HitlReticleRenderer.drawDesignation(gg, lockScreen.x, lockScreen.y);
    }
}
