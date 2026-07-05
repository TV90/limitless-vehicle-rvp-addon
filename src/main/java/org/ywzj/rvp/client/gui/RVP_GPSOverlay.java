package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_GPSOverlay {

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (mc.options.hideGui) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();

        if (RVP_ClientGPSUtil.isGPSBombSelected()) {
            Vec3 aimPos = RVP_ClientGPSUtil.raycastGPSTarget(mc);
            if (aimPos != null) {
                Vec3 screenAimPos = VectorUtil.worldToScreen(aimPos);
                if (screenAimPos.z > 0) {
                    PoseStack pose = gg.pose();
                    pose.pushPose();
                    pose.translate(screenAimPos.x, screenAimPos.y, 0);
                    GuiHelper.drawCircle(pose, 0, 0, 5, Color.WHITE, 0.05f, 0f, 1f);
                    pose.popPose();
                }
            }
        }

        if (!RVP_ClientGPSUtil.isGPSBombSelected()) {
            return;
        }
        if (!RVP_ClientGPSState.isActive()) {
            return;
        }
        ResourceLocation currentDim = player.level().dimension().location();
        List<RVP_ClientGPSState.Point> points = RVP_ClientGPSState.getPointsForDimension(currentDim);
        if (points.isEmpty()) {
            return;
        }
        RVP_ClientGPSState.Point armedPoint = RVP_ClientGPSState.getArmedPoint();
        List<Vec3> inFlightTargets = collectInFlightBombTargets(mc, player);

        Vec3 armedWorldPos = null;
        Vec3 armedScreenPos = null;
        for (RVP_ClientGPSState.Point point : points) {
            Vec3 targetPos = point.pos();
            Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
            if (screenPos.z <= 0) {
                continue;
            }

            boolean isNext = armedPoint != null && point.equals(armedPoint);
            int color = Color.WHITE;
            int radius = 6;
            if (isNext) {
                color = Color.GREEN;
                radius = 7;
                armedWorldPos = targetPos;
                armedScreenPos = screenPos;
            } else if (isTargetedByInFlight(inFlightTargets, targetPos)) {
                color = Color.AMMO_WARNING;
                radius = 6;
            }

            PoseStack pose = gg.pose();
            pose.pushPose();
            pose.translate(screenPos.x, screenPos.y, 0);
            GuiHelper.drawCircle(pose, 0, 0, radius, color, 0.05f, 0f, 1f);
            pose.popPose();
        }

        if (armedWorldPos == null || armedScreenPos == null) {
            return;
        }

        double dist = player.position().distanceTo(armedWorldPos);
        int bx = (int) Math.floor(armedWorldPos.x);
        int by = (int) Math.floor(armedWorldPos.y);
        int bz = (int) Math.floor(armedWorldPos.z);
        Component line1 = Component.translatable("overlay.ywzj_rvp.gps.target", RVP_ClientGPSUtil.currentPointTag(), (int) dist);
        Component line2 = Component.translatable("overlay.ywzj_rvp.gps.coords", bx, by, bz);
        Component line3 = Component.translatable("overlay.ywzj_rvp.gps.mode", RVP_ClientGPSUtil.currentModeTag(), RVP_ClientGPSState.getPointCount());

        Font font = mc.font;
        int w1 = font.width(line1);
        int w2 = font.width(line2);
        int w3 = font.width(line3);
        gg.drawString(font, line1, (int) (armedScreenPos.x - w1 / 2f), (int) (armedScreenPos.y + 12), Color.GREEN, true);
        gg.drawString(font, line2, (int) (armedScreenPos.x - w2 / 2f), (int) (armedScreenPos.y + 22), Color.GREEN, true);
        gg.drawString(font, line3, (int) (armedScreenPos.x - w3 / 2f), (int) (armedScreenPos.y + 32), Color.GREEN, true);
    }

    private static List<Vec3> collectInFlightBombTargets(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) {
            return List.of();
        }
        List<Vec3> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (bullet.getWeaponKind() != RVP_EnumWeaponKind.BOMB) {
                continue;
            }
            if (bullet.getOwner() != player) {
                continue;
            }
            if (!isGpsAmmo(bullet)) {
                continue;
            }
            Vec3 target = bullet.getTargetPos();
            if (target == null) {
                target = bullet.getLastGuidancePos();
            }
            if (target == null) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    private static boolean isTargetedByInFlight(List<Vec3> targets, Vec3 point) {
        if (targets.isEmpty()) {
            return false;
        }
        for (Vec3 target : targets) {
            if (target.distanceToSqr(point) <= 9.0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGpsAmmo(RVP_BaseBullet bullet) {
        ResourceLocation weaponId = bullet.getWeaponId();
        if (weaponId == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof RVP_WeaponData weaponData
                        && weaponData.usesGuidanceType(RVP_EnumGuidanceType.GPS))
                .orElse(false);
    }
}
