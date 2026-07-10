package org.ywzj.rvp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.gui.RVP_RocketCcipOverlay;
import org.ywzj.rvp.client.gui.RVP_HmdOverlay;
import org.ywzj.rvp.client.debug.RVP_DebugStateLogs;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.client.shader.RVP_CrtUiLiteHandler;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.rvp.client.state.RVP_BombCcipUtil;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.client.laser.RVP_ClientLaserDriver;
import org.ywzj.rvp.client.state.RVP_ClientBulletHitDebugState;
import org.ywzj.rvp.network.C2SDeployDeployableUav;
import org.ywzj.rvp.network.C2SSwitchDeployableUav;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.client.shader.CrtHandler;
import org.ywzj.vehicle.client.shader.ThermalHandler;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_ClientEvents {

    private static int ywzj_rvp$markerRefreshTick;
    private static final List<VehicleMarker> ywzj_rvp$vehicleMarkers = new ArrayList<>();
    private static String ywzj_rvp$lastBombCcipDebugState = "";

    private record VehicleMarker(int vehicleId, int argb) {}

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
        RVP_ClientRemoteAmmoState.clientTick();
        RVP_ClientExternalRadarState.clientTick();

        if (mc.level != null) {
            RVP_TacticalMapCache.processChunkUpdates(mc.level, player.getX(), player.getZ(), 6);
            RVP_TacticalMapCache.uploadDirtyTextures();
        }

        while (RVP_Keys.WEAPON_TEST_OVERLAY.consumeClick()) {
            boolean on = org.ywzj.rvp.client.RVP_WeaponTestMode.toggle();
            player.displayClientMessage(
                    Component.translatable(on ? "message.ywzj_rvp.weapon_test.on" : "message.ywzj_rvp.weapon_test.off"),
                    true);
        }

        // HMD 模式切换：STT 状态下按 5 键先取消 STT 再进入 HMD
        while (RVP_Keys.HMD_TOGGLE.consumeClick()) {
            if (LocalVehiclePlayer.instance == null) continue;
            RVP_ClientHmdState hmd = RVP_ClientHmdState.getInstance();
            if (hmd.isHmdMode()) {
                hmd.disable();
                player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.off"), true);
            } else {
                // 检查是否有 STT 锁定
                WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
                if (weaponUnit != null) {
                    RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(weaponUnit);
                    if (radar != null && radar.getLockedEntity() != null) {
                        RVP_RadarRoleHelper.clearAllRadarLocks(weaponUnit);
                        weaponUnit.setLockedEntity(null);
                    }
                    if (RVP_ExternalRadarLinkHelper.hasClientExternalLockState(LocalVehiclePlayer.instance.getVehicle(),
                            mc.level != null ? mc.level.dimension().location() : null)) {
                        RVP_ExternalRadarLinkHelper.clearClientLockRequest(weaponUnit);
                    }
                }
                boolean on = hmd.toggle();
                player.displayClientMessage(
                        Component.translatable(on ? "message.ywzj_rvp.hmd.on" : "message.ywzj_rvp.hmd.off"),
                        true);
            }
        }

        ywzj_rvp$applyScopeOverrides();

        if (mc.screen != null) {
            return;
        }

        while (RVP_Keys.OPEN_GPS_PANEL.consumeClick()) {
            mc.setScreen(new RVP_TacticalMapScreen());
        }
        while (RVP_Keys.DEPLOY_DEPLOYABLE_UAV.consumeClick()) {
            RVP_Network.CHANNEL.sendToServer(new C2SDeployDeployableUav());
        }
        while (RVP_Keys.SWITCH_DEPLOYABLE_UAV.consumeClick()) {
            RVP_Network.CHANNEL.sendToServer(new C2SSwitchDeployableUav());
        }
        while (RVP_Keys.TOGGLE_SACLOS_LASER.consumeClick()) {
            if (RVP_ClientSaclosState.isGuiding() && !RVP_ClientHitlState.isDesignateMode()) {
                RVP_ClientSaclosState.toggleLaser();
            }
        }

        RVP_ClientHitlState.tick(mc, player);
        // IR HMD 自动检测（必须在雷达 HMD 逻辑之前）
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        hmdState.checkIrHmd();
        hmdState.tick();

        ywzj_rvp$refreshVehicleMarkers(mc, player);

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit != null) {
            weaponUnit.indexedWeapons.forEach(w -> {
                if (w instanceof RVP_WeaponBase rvp) {
                    rvp.getFireController().syncClientInput();
                }
            });
            AbstractVehicleWeapon<?> selectedWeapon = RVP_LaserWeapons.unwrap(weaponUnit.getCurrentWeapon().orElse(null));
            if (selectedWeapon instanceof RVP_WeaponBase selectedRvp) {
                selectedRvp.getFireController().syncClientInput();
            }
        }
        if (weaponUnit != null
                && !weaponUnit.getCurrentWeapon().isEmpty()
                && player.getVehicle() instanceof AbstractVehicle vehicle) {
            AbstractVehicleWeapon<?> currentWeapon = RVP_LaserWeapons.unwrap(weaponUnit.getCurrentWeapon().get());
            if (currentWeapon instanceof RVP_WeaponBase weapon
                    && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.BOMB) {
                RVP_RocketCcipState.clear(vehicle.getId());
                ywzj_rvp$updateRvpBombCcip(vehicle, weaponUnit, weapon);
            } else if (!RVP_RocketCcipOverlay.isBallisticRocketWeapon(currentWeapon, weaponUnit)) {
                RVP_RocketCcipState.clear(vehicle.getId());
            }
        }

        if (LocalVehiclePlayer.instance.onVehicle() || RVP_ClientHitlState.isDesignateMode()) {
            RVP_ClientSaclosState.tick(mc, player);
        }
    }

    private static void ywzj_rvp$applyScopeOverrides() {
        if (LocalVehiclePlayer.instance == null || LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.CRT) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        if (!(weaponUnit.getData() instanceof WeaponUnitDataExt ext) || !ext.ywzj_rvp$disableCrtEffect()) {
            RVP_CrtUiLiteHandler.setActive(false);
            return;
        }
        if (CrtHandler.isActive()) {
            CrtHandler.setActive(false);
        }
        if (!RVP_CrtUiLiteHandler.isActive()) {
            RVP_CrtUiLiteHandler.setActive(true);
        }
        if (weaponUnit.withThermalImager() && LocalVehiclePlayer.instance.thermalImaging) {
            if (!ThermalHandler.isActive()) {
                ThermalHandler.setActive(true);
            }
        } else if (ThermalHandler.isActive()) {
            ThermalHandler.setActive(false);
        }
    }

    private static void ywzj_rvp$updateRvpBombCcip(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                   RVP_WeaponBase weapon) {
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            ywzj_rvp$logBombCcipState("skip sensor=" + weaponUnit.getFireControlSensorType()
                    + " view=" + LocalVehiclePlayer.instance.viewType
                    + " weapon=" + weapon.getData().getWeaponId());
            return;
        }
        if (weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.GPS) && RVP_ClientGPSState.isActive()) {
            weaponUnit.weaponHitPosO = null;
            weaponUnit.weaponHitPos = null;
            ywzj_rvp$logBombCcipState("gps-active blank weapon=" + weapon.getData().getWeaponId());
        } else {
            Vec3 rawHit = RVP_BombCcipUtil.computeImpact(vehicle, weaponUnit, weapon.getData());
            Vec3 ccipHit = RVP_RocketCcipState.smooth(
                    vehicle.getId(),
                    weapon.getData().getWeaponId(),
                    vehicle.tickCount,
                    rawHit
            );
            weaponUnit.weaponHitPosO = weaponUnit.weaponHitPos;
            weaponUnit.weaponHitPos = ccipHit;
            ywzj_rvp$logBombCcipState("update weapon=" + weapon.getData().getWeaponId()
                    + " rawHit=" + (rawHit != null)
                    + " smoothed=" + (ccipHit != null)
                    + " style=" + (weapon.getWeaponUnit() == null ? "null" : weapon.getWeaponUnit().crosshairStyle));
        }
    }

    private static void ywzj_rvp$logBombCcipState(String state) {
        if (!state.equals(ywzj_rvp$lastBombCcipDebugState)) {
            ywzj_rvp$lastBombCcipDebugState = state;
            RVP_DebugStateLogs.logCcip(state);
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
        if (RVP_ClientSaclosState.isSaclosWeapon(event.getWeapon())) {
            RVP_ClientSaclosState.onSaclosWeaponFired();
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (RVP_ClientHmdState.getInstance().isHmdMode()) {
            RVP_HmdOverlay.render(event.getGuiGraphics());
        }
    }
}
