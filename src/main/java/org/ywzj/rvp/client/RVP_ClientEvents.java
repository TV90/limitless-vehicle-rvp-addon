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
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.RVP_GPSPanelScreen;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientTVMissileState;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.C2SSetGPSTarget;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.client.laser.RVP_ClientLaserDriver;
import org.ywzj.rvp.client.state.RVP_ClientBulletHitDebugState;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.CcipUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ClientEvents {

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
        if (player == null) {
            return;
        }

        RVP_ClientBulletHitDebugState.clientTick();

        while (RVP_Keys.WEAPON_TEST_OVERLAY.consumeClick()) {
            boolean on = org.ywzj.rvp.client.RVP_WeaponTestMode.toggle();
            player.displayClientMessage(
                    Component.translatable(on ? "message.ywzj_rvp.weapon_test.on" : "message.ywzj_rvp.weapon_test.off"),
                    true);
        }

        if (mc.screen != null) {
            return;
        }

        boolean setGPSTriggered = false;
        while (RVP_Keys.SET_GPS_TARGET.consumeClick()) {
            setGPSTriggered = true;
            if (!RVP_ClientGPSUtil.ensureGPSBombSelected(player)) {
                continue;
            }
            Vec3 target = RVP_ClientGPSUtil.raycastGPSTarget(mc);
            if (target == null) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.no_block"), true);
                continue;
            }
            RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.set(player.level().dimension().location(), target));
            RVP_ClientGPSState.set(player.level().dimension().location(), target);
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.set_target"), true);
        }
        if (!setGPSTriggered && ywzj_rvp$consumeClick(mc, RVP_Keys.SET_GPS_TARGET, ywzj_rvp$lastSetGPSDown)) {
            if (RVP_ClientGPSUtil.ensureGPSBombSelected(player)) {
                Vec3 target = RVP_ClientGPSUtil.raycastGPSTarget(mc);
                if (target == null) {
                    player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.no_block"), true);
                } else {
                    RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.set(player.level().dimension().location(), target));
                    RVP_ClientGPSState.set(player.level().dimension().location(), target);
                    player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.set_target"), true);
                }
            }
        }

        while (RVP_Keys.OPEN_GPS_PANEL.consumeClick()) {
            if (!RVP_ClientGPSUtil.ensureGPSBombSelected(player)) {
                continue;
            }
            mc.setScreen(new RVP_GPSPanelScreen());
        }

        while (RVP_Keys.CLEAR_GPS.consumeClick()) {
            RVP_Network.CHANNEL.sendToServer(org.ywzj.rvp.network.C2SSetGPSTarget.clear());
            RVP_ClientGPSState.clear();
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.clear_target"), true);
        }

        RVP_ClientTVMissileState.tick(mc, player);

        ywzj_rvp$refreshVehicleMarkers(mc, player);

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit != null) {
            weaponUnit.indexedWeapons.forEach(w -> {
                if (w instanceof RVP_WeaponBase rvp) {
                    rvp.getFireController().syncClientInput();
                }
            });
        }
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
        if (currentWeapon instanceof RVP_WeaponBase weapon
                && (weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.GPS)
                || weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.SACLOS))) {
            RVP_RocketCcipState.clear(vehicle.getId());
            ywzj_rvp$updateGPSBombCcip(vehicle, weaponUnit, weapon);
            return;
        }
        if (currentWeapon instanceof VehicleRocket) {
            return;
        }
        RVP_RocketCcipState.clear(vehicle.getId());
    }

    private static void ywzj_rvp$updateGPSBombCcip(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                    RVP_WeaponBase weapon) {
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            return;
        }
        if (RVP_ClientGPSState.isActive()) {
            LocalVehiclePlayer.instance.weaponHitPosO = null;
            LocalVehiclePlayer.instance.weaponHitPos = null;
        } else {
            Vec3 releasePos = weaponUnit.worldPivotPosition();
            float dragCoefficient = weapon.getData().getProjectileData().getDrag();
            Vec3 ccipHit = CcipUtil.computeCcipImpact(
                    vehicle.level(), releasePos, vehicle.getDeltaMovement(), dragCoefficient);
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
        if (gunner.getProfileFaction() == RVP_EnumGunnerFaction.ENEMY) {
            return 0xFFFF2B2B;
        }
        if (gunner.isOwnedBy(player)) {
            return 0xFF2B6CFF;
        }
        if (player.getTeam() != null && gunner.getTeam() != null) {
            boolean allied = gunner.getTeam().isAlliedTo(player.getTeam());
            return allied ? 0xFF2B6CFF : 0xFFFF2B2B;
        }
        if (gunner.getProfileFaction() == RVP_EnumGunnerFaction.FRIENDLY) {
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

    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        int operatorId = event.getOperator() != null ? event.getOperator().getId() : -1;
        RVP_ClientLaserDriver.pulseFromFireEvent(
                event.getVehicle(),
                event.getWeapon(),
                event.getVehicle().level().getGameTime(),
                operatorId);
    }
}
