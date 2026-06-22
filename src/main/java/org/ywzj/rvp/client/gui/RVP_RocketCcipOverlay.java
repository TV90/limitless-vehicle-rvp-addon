package org.ywzj.rvp.client.gui;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.renderer.texture.TextureManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_RocketCcipState;
import org.ywzj.rvp.ext.VehicleRocketWeaponDataExt;
import org.ywzj.rvp.weapon.RVP_RocketBallistics;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RocketCcipOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("rvp", "textures/ui/ccip.png");
    private static final ResourceLocation REGISTERED_TEXTURE = RVP_MOD.modLocation("dynamic/ccip");
    private static final int CCIP_SIZE = 32;
    private static final int CCIP_TEXTURE_SIZE = 128;
    private static final boolean ywzj_rvp$debugForceCenter = false;
    private static final boolean ywzj_rvp$enableForgeOverlayEvent = false;
    private static long ywzj_rvp$lastTextureCheckMs;
    private static long ywzj_rvp$loadedTextureMtime = Long.MIN_VALUE;
    private static DynamicTexture ywzj_rvp$dynamicTexture;
    private static ResourceLocation ywzj_rvp$activeTexture = FALLBACK_TEXTURE;
    private static boolean ywzj_rvp$hasTexture;
    private static String ywzj_rvp$lastTextureDebugState = "";

    private RVP_RocketCcipOverlay() {}

    private record ActiveRocketContext(AbstractVehicle vehicle, WeaponUnit weaponUnit, AbstractVehicleWeapon<?> weapon) {}

    public static boolean isBallisticRocketWeapon(AbstractVehicleWeapon<?> weapon, WeaponUnit activeWeaponUnit) {
        if (weapon == null || activeWeaponUnit == null) {
            return false;
        }
        if (activeWeaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.CCIP) {
            return false;
        }
        if (weapon instanceof VehicleRocket rocket) {
            return rocket.getData() instanceof VehicleRocketWeaponDataExt ext && ext.ywzj_rvp$isBallisticEnabled();
        }
        if (weapon instanceof RVP_ProjectileWeapon rvpWeapon) {
            return rvpWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET;
        }
        return false;
    }

    private static ActiveRocketContext getActiveRocketContext() {
        if (LocalVehiclePlayer.instance == null) {
            return null;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return null;
        }
        if (weaponUnit.getCurrentWeapon().isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().get();
        if (!isBallisticRocketWeapon(weapon, weaponUnit)) {
            return null;
        }
        WeaponUnit rocketWeaponUnit = weapon.getWeaponUnit();
        if (rocketWeaponUnit == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return null;
        }
        return new ActiveRocketContext(vehicle, rocketWeaponUnit, weapon);
    }

    public static boolean isBallisticRocketActive() {
        return getActiveRocketContext() != null;
    }

    public static boolean shouldReplaceReticle() {
        return isBallisticRocketActive() && ensureTexture();
    }

    public static void draw(GuiGraphics guiGraphics, float partialTick) {
        draw(guiGraphics, partialTick, CCIP_SIZE);
    }

    public static void draw(GuiGraphics guiGraphics, float partialTick, int size) {
        if (!ensureTexture()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float roll = 0.0f;
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            roll = Mth.lerp(partialTick, vehicle.zRotO, vehicle.getZRot());
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(roll));
        guiGraphics.blit(ywzj_rvp$activeTexture, -size / 2, -size / 2, 0, 0, size, size, CCIP_TEXTURE_SIZE, CCIP_TEXTURE_SIZE);
        guiGraphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    public static void drawAtScreen(GuiGraphics guiGraphics, double x, double y, float partialTick, int size) {
        if (!ensureTexture()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float roll = 0.0f;
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            roll = Mth.lerp(partialTick, vehicle.zRotO, vehicle.getZRot());
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(roll));
        guiGraphics.blit(ywzj_rvp$activeTexture, -size / 2, -size / 2, 0, 0, size, size, CCIP_TEXTURE_SIZE, CCIP_TEXTURE_SIZE);
        guiGraphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    public static Vec3 getCurrentScreenHitPos() {
        ActiveRocketContext context = getActiveRocketContext();
        if (context == null) {
            return null;
        }
        Vec3 rawHit = ywzj_rvp$computeImpact(context);
        if (rawHit == null) {
            return null;
        }
        Vec3 hitPos = RVP_RocketCcipState.smooth(
                context.vehicle().getId(),
                ywzj_rvp$getWeaponId(context.weapon()),
                context.vehicle().tickCount,
                rawHit
        );
        if (hitPos == null) {
            return null;
        }
        return VectorUtil.worldToScreen(hitPos);
    }

    private static Vec3 ywzj_rvp$computeImpact(ActiveRocketContext context) {
        AbstractVehicleWeapon<?> weapon = context.weapon();
        if (weapon instanceof VehicleRocket rocket) {
            return RVP_RocketBallistics.computeWeaponImpact(
                    context.vehicle().level(),
                    context.weaponUnit(),
                    context.vehicle().getDeltaMovement(),
                    rocket.getData(),
                    context.vehicle()
            );
        }
        if (weapon instanceof RVP_ProjectileWeapon rvpWeapon
                && rvpWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET) {
            return RVP_RocketBallistics.computeWeaponImpact(
                    context.vehicle().level(),
                    context.weaponUnit(),
                    context.vehicle().getDeltaMovement(),
                    rvpWeapon.getData(),
                    context.vehicle()
            );
        }
        return null;
    }

    private static ResourceLocation ywzj_rvp$getWeaponId(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleRocket rocket) {
            return rocket.getData().getWeaponId();
        }
        if (weapon instanceof RVP_ProjectileWeapon rvpWeapon) {
            return rvpWeapon.getData().getWeaponId();
        }
        return null;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!ywzj_rvp$enableForgeOverlayEvent) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ActiveRocketContext context = getActiveRocketContext();
        if (player == null || mc.options.hideGui || context == null || !ensureTexture()) {
            return;
        }
        Vec3 screenHitPos = getCurrentScreenHitPos();
        if (screenHitPos == null || screenHitPos.z < 0) {
            return;
        }
        if (!Double.isFinite(screenHitPos.x) || !Double.isFinite(screenHitPos.y) || !Double.isFinite(screenHitPos.z)) {
            return;
        }
        float partialTick = mc.getFrameTime();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double x = ywzj_rvp$clamp(screenHitPos.x, 0.0, sw);
        double y = ywzj_rvp$clamp(screenHitPos.y, 0.0, sh);
        drawAtScreen(event.getGuiGraphics(), x, y, partialTick, CCIP_SIZE);
    }

    private static double ywzj_rvp$clamp(double v, double min, double max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static boolean ensureTexture() {
        long now = System.currentTimeMillis();
        if (now - ywzj_rvp$lastTextureCheckMs < 1000L) {
            return ywzj_rvp$hasTexture;
        }
        ywzj_rvp$lastTextureCheckMs = now;

        Minecraft mc = Minecraft.getInstance();
        TextureManager textureManager = mc.getTextureManager();
        try {
            Path texturePath = resolvePackTexturePath();
            if (texturePath != null && Files.isRegularFile(texturePath)) {
                long mtime = Files.getLastModifiedTime(texturePath).toMillis();
                if (mtime != ywzj_rvp$loadedTextureMtime) {
                    try (InputStream inputStream = Files.newInputStream(texturePath)) {
                        NativeImage image = NativeImage.read(inputStream);
                        if (ywzj_rvp$dynamicTexture != null) {
                            ywzj_rvp$dynamicTexture.close();
                        }
                        ywzj_rvp$dynamicTexture = new DynamicTexture(image);
                        textureManager.register(REGISTERED_TEXTURE, ywzj_rvp$dynamicTexture);
                        ywzj_rvp$loadedTextureMtime = mtime;
                    }
                }
                ywzj_rvp$activeTexture = REGISTERED_TEXTURE;
                ywzj_rvp$hasTexture = true;
                ywzj_rvp$debugTextureState("external:" + texturePath + " -> " + REGISTERED_TEXTURE);
                return true;
            }
        } catch (IOException ignored) {
            // Fall through to the standard resource lookup.
        }

        ywzj_rvp$hasTexture = mc.getResourceManager().getResource(FALLBACK_TEXTURE).isPresent();
        ywzj_rvp$activeTexture = FALLBACK_TEXTURE;
        ywzj_rvp$debugTextureState("fallback:" + FALLBACK_TEXTURE + ", present=" + ywzj_rvp$hasTexture);
        if (!ywzj_rvp$hasTexture) {
            ywzj_rvp$loadedTextureMtime = Long.MIN_VALUE;
        }
        return ywzj_rvp$hasTexture;
    }

    private static void ywzj_rvp$debugTextureState(String source) {
        String state = source + ", active=" + ywzj_rvp$activeTexture + ", hasTexture=" + ywzj_rvp$hasTexture;
        if (!state.equals(ywzj_rvp$lastTextureDebugState)) {
            ywzj_rvp$lastTextureDebugState = state;
            LOGGER.info("[RVP][RocketCCIP] {}", state);
        }
    }

    private static Path resolvePackTexturePath() {
        List<Path> candidates = new ArrayList<>();
        Path gameDir = FMLPaths.GAMEDIR.get();
        addCandidate(candidates, gameDir);
        Path parent = gameDir;
        for (int i = 0; i < 4 && parent != null; i++) {
            parent = parent.getParent();
            addCandidate(candidates, parent);
        }
        candidates.add(Paths.get("F:\\载具包临时\\ywzj\\.minecraft\\versions\\Optimized fps\\limitless_vehicle\\rvp\\assets\\rvp\\textures\\ui\\ccip.png"));
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void addCandidate(List<Path> candidates, Path base) {
        if (base == null) {
            return;
        }
        candidates.add(base.resolve("limitless_vehicle").resolve("rvp").resolve("assets").resolve("rvp").resolve("textures").resolve("ui").resolve("ccip.png"));
        candidates.add(base.resolve("versions").resolve("Optimized fps").resolve("limitless_vehicle").resolve("rvp").resolve("assets").resolve("rvp").resolve("textures").resolve("ui").resolve("ccip.png"));
    }
}
