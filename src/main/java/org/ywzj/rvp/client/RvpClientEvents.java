package org.ywzj.rvp.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.client.screen.RvpGPSPanelScreen;
import org.ywzj.rvp.client.state.RvpClientAntiRadiationState;
import org.ywzj.rvp.client.state.RvpClientGPSState;
import org.ywzj.rvp.client.state.RvpClientGPSUtil;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.rvp.client.state.RvpRocketCcipState;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.network.C2SSetGPSTarget;
import org.ywzj.rvp.network.RvpNetwork;
import org.ywzj.rvp.weapon.VehicleGPSBomb;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.CcipUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RvpClientEvents {

    private static final boolean[] ywzj_rvp$lastSetGPSDown = new boolean[1];
    private static int ywzj_rvp$markerRefreshTick;
    private static final List<VehicleMarker> ywzj_rvp$vehicleMarkers = new ArrayList<>();

    private record VehicleMarker(int vehicleId, int argb) {}

    private static boolean ywzj_rvp$consumeClick(Minecraft mc, KeyMapping mapping, boolean[] lastDown) {
        long window = mc.getWindow().getWindow();
        InputConstants.Key key = mapping.getKey();
        boolean down;
        if (key.getType() == InputConstants.Type.MOUSE) {
            down = GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        } else {
            down = InputConstants.isKeyDown(window, key.getValue());
        }
        boolean clicked = down && !lastDown[0];
        lastDown[0] = down;
        return clicked;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) {
            return;
        }

        boolean setGPSTriggered = false;
        while (RvpKeys.SET_GPS_TARGET.consumeClick()) {
            setGPSTriggered = true;
            if (!RvpClientGPSUtil.ensureGPSBombSelected(player)) {
                continue;
            }
            Vec3 target = RvpClientGPSUtil.raycastGPSTarget(mc);
            if (target == null) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.no_block"), true);
                continue;
            }
            RvpNetwork.CHANNEL.sendToServer(C2SSetGPSTarget.set(player.level().dimension().location(), target));
            RvpClientGPSState.set(player.level().dimension().location(), target);
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.set_target"), true);
        }
        if (!setGPSTriggered && ywzj_rvp$consumeClick(mc, RvpKeys.SET_GPS_TARGET, ywzj_rvp$lastSetGPSDown)) {
            if (RvpClientGPSUtil.ensureGPSBombSelected(player)) {
                Vec3 target = RvpClientGPSUtil.raycastGPSTarget(mc);
                if (target == null) {
                    player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.no_block"), true);
                } else {
                    RvpNetwork.CHANNEL.sendToServer(C2SSetGPSTarget.set(player.level().dimension().location(), target));
                    RvpClientGPSState.set(player.level().dimension().location(), target);
                    player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.set_target"), true);
                }
            }
        }

        while (RvpKeys.OPEN_GPS_PANEL.consumeClick()) {
            if (!RvpClientGPSUtil.ensureGPSBombSelected(player)) {
                continue;
            }
            mc.setScreen(new RvpGPSPanelScreen());
        }

        while (RvpKeys.CLEAR_GPS.consumeClick()) {
            RvpNetwork.CHANNEL.sendToServer(org.ywzj.rvp.network.C2SSetGPSTarget.clear());
            RvpClientGPSState.clear();
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.clear_target"), true);
        }

        while (RvpKeys.ANTI_RADIATION_SELECT_PREV.consumeClick()) {
            RvpClientAntiRadiationState.selectPrev();
        }

        while (RvpKeys.ANTI_RADIATION_SELECT_NEXT.consumeClick()) {
            RvpClientAntiRadiationState.selectNext();
        }

        RvpClientAntiRadiationState.tick(mc, player);
        RvpClientTVMissileState.tick(mc, player);

        ywzj_rvp$refreshVehicleMarkers(mc, player);

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        if (weaponUnit.getCurrentWeapon().isEmpty()) {
            return;
        }
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponUnit.getCurrentWeapon().get();
        if (currentWeapon instanceof VehicleGPSBomb) {
            RvpRocketCcipState.clear(vehicle.getId());
            ywzj_rvp$updateGPSBombCcip(player, vehicle, weaponUnit);
            return;
        }
        if (currentWeapon instanceof VehicleRocket) {
            return;
        }
        RvpRocketCcipState.clear(vehicle.getId());
    }

    private static void ywzj_rvp$updateGPSBombCcip(LocalPlayer player, AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            return;
        }
        if (RvpClientGPSState.isActive()) {
            LocalVehiclePlayer.instance.weaponHitPosO = null;
            LocalVehiclePlayer.instance.weaponHitPos = null;
        } else {
            Vec3 releasePos = weaponUnit.worldPivotPosition();
            Vec3 ccipHit = CcipUtil.computeCcipImpact(vehicle.level(), releasePos, vehicle.getDeltaMovement());
            LocalVehiclePlayer.instance.weaponHitPosO = LocalVehiclePlayer.instance.weaponHitPos;
            LocalVehiclePlayer.instance.weaponHitPos = ccipHit;
        }
    }

    private static void ywzj_rvp$refreshVehicleMarkers(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) {
            ywzj_rvp$vehicleMarkers.clear();
            return;
        }
        if ((ywzj_rvp$markerRefreshTick++ % 10) != 0) {
            return;
        }
        ywzj_rvp$vehicleMarkers.clear();
        double range = 512.0;
        AABB box = player.getBoundingBox().inflate(range);
        List<AbstractVehicle> vehicles = mc.level.getEntitiesOfClass(AbstractVehicle.class, box, v -> v.getDriver() instanceof GunnerEntity);
        for (AbstractVehicle vehicle : vehicles) {
            GunnerEntity gunner = null;
            Entity driver = vehicle.getDriver();
            if (driver instanceof GunnerEntity g) {
                gunner = g;
            }
            if (gunner == null) {
                continue;
            }
            int argb = ywzj_rvp$getGunnerVehicleMarkerColor(player, gunner);
            if ((argb >>> 24) == 0) {
                continue;
            }
            ywzj_rvp$vehicleMarkers.add(new VehicleMarker(vehicle.getId(), argb));
        }
    }

    private static int ywzj_rvp$getGunnerVehicleMarkerColor(LocalPlayer player, GunnerEntity gunner) {
        if (gunner.getProfileFaction() == GunnerFaction.ENEMY) {
            return 0xFFFF2B2B;
        }
        if (gunner.isOwnedBy(player)) {
            return 0xFF2B6CFF;
        }
        if (player.getTeam() != null && gunner.getTeam() != null) {
            boolean allied = gunner.getTeam().isAlliedTo(player.getTeam());
            return allied ? 0xFF2B6CFF : 0xFFFF2B2B;
        }
        if (gunner.getProfileFaction() == GunnerFaction.FRIENDLY) {
            return 0xFF2B6CFF;
        }
        return 0xFFFF2B2B;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (ywzj_rvp$vehicleMarkers.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (VehicleMarker marker : ywzj_rvp$vehicleMarkers) {
            Entity e = mc.level.getEntity(marker.vehicleId());
            if (!(e instanceof AbstractVehicle vehicle)) {
                continue;
            }
            Vec3 markerPos = new Vec3(
                    vehicle.getX(),
                    vehicle.getBoundingBox().maxY + 4.0,
                    vehicle.getZ()
            );
            double dist = cameraPos.distanceTo(markerPos);
            if (dist > 768.0) {
                continue;
            }
            float scale = Mth.clamp(0.06f * (64.0f / (float) Math.max(1.0, dist)), 0.03f, 0.2f);

            int argb = marker.argb();
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;

            poseStack.pushPose();
            poseStack.translate(markerPos.x - cameraPos.x, markerPos.y - cameraPos.y, markerPos.z - cameraPos.z);
            poseStack.mulPose(event.getCamera().rotation());
            poseStack.scale(scale, scale, scale);

            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            var mat = poseStack.last().pose();
            buffer.vertex(mat, 0.0f, -1.0f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, -0.8f, 0.6f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, 0.8f, 0.6f, 0.0f).color(r, g, b, a).endVertex();
            BufferUploader.drawWithShader(buffer.end());

            poseStack.popPose();
        }

        RenderSystem.disableBlend();
    }
}
