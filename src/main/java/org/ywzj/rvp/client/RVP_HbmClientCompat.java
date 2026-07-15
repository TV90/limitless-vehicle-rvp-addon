package org.ywzj.rvp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class RVP_HbmClientCompat {

    private static final String HBM_MOD_ID = "hbm_ntm_rebirth";
    private static final String NUKE_TOREX_RENDERER_CLASS = "com.hbm.ntm.client.renderer.NukeTorexRenderer";
    private static final String NUKE_TOREX_ENTITY_CLASS = "com.hbm.ntm.entity.effect.NukeTorexEntity";

    private static boolean resolved;
    private static boolean available;
    @Nullable
    private static Class<?> nukeTorexEntityClass;
    @Nullable
    private static Field coreHeightField;
    @Nullable
    private static Field lastSpawnYField;
    @Nullable
    private static Field cloudletsField;
    @Nullable
    private static Field cloudletPosYField;
    @Nullable
    private static Field cloudletPrevPosYField;
    @Nullable
    private static Field cloudletTypeField;
    @Nullable
    private static Method renderCloudletsAfterLevelMethod;
    private static final Map<Integer, Double> appliedCapLiftByEntityId = new HashMap<>();

    private RVP_HbmClientCompat() {}

    public static void renderLateTorexCloudlets(RenderLevelStageEvent event) {
        if (event == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        renderLateTorexCloudlets(event.getCamera(), event.getPartialTick());
    }

    public static void renderLateTorexCloudlets(@Nullable Camera camera, float partialTick) {
        if (camera == null || !shouldRenderLateTorexCloudlets()) {
            return;
        }
        ensureResolved();
        if (!available) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        try {
            adjustTorexCapLift(level);
            renderLateTorexFlare(level, partialTick, camera.getPosition(), bufferSource);
            renderCloudletsAfterLevelMethod.invoke(null, level, camera, partialTick,
                    poseStack, bufferSource);
            bufferSource.endBatch();
        } catch (ReflectiveOperationException ignored) {
            available = false;
        }
    }

    public static boolean shouldRenderLateTorexCloudlets() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() != null;
    }

    private static void renderLateTorexFlare(ClientLevel level, float partialTick, net.minecraft.world.phys.Vec3 cameraPos,
                                             MultiBufferSource.BufferSource bufferSource) {
        if (nukeTorexEntityClass == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PoseStack poseStack = new PoseStack();
        for (Entity entity : level.entitiesForRendering()) {
            if (!nukeTorexEntityClass.isInstance(entity)) {
                continue;
            }
            double capLift = appliedCapLiftByEntityId.getOrDefault(entity.getId(), 0.0D);
            dispatcher.render(entity,
                    entity.getX() - cameraPos.x,
                    entity.getY() + capLift - cameraPos.y,
                    entity.getZ() - cameraPos.z,
                    entity.getYRot(),
                    partialTick,
                    poseStack,
                    bufferSource,
                    15728880);
        }
    }

    private static void adjustTorexCapLift(ClientLevel level) throws ReflectiveOperationException {
        if (nukeTorexEntityClass == null
                || coreHeightField == null
                || lastSpawnYField == null
                || cloudletsField == null
                || cloudletPosYField == null
                || cloudletPrevPosYField == null
                || cloudletTypeField == null) {
            return;
        }
        HashMap<Integer, Boolean> alive = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!nukeTorexEntityClass.isInstance(entity)) {
                continue;
            }
            alive.put(entity.getId(), Boolean.TRUE);
            double desiredLift = computeDesiredCapLift(entity);
            double appliedLift = appliedCapLiftByEntityId.getOrDefault(entity.getId(), 0.0D);
            double delta = desiredLift - appliedLift;
            if (Math.abs(delta) < 1.0E-4D) {
                continue;
            }
            applyCapLiftDelta(entity, delta);
            appliedCapLiftByEntityId.put(entity.getId(), desiredLift);
        }
        Iterator<Integer> iterator = appliedCapLiftByEntityId.keySet().iterator();
        while (iterator.hasNext()) {
            Integer id = iterator.next();
            if (!alive.containsKey(id)) {
                iterator.remove();
            }
        }
    }

    private static double computeDesiredCapLift(Entity entity) throws IllegalAccessException {
        double lastSpawnY = lastSpawnYField.getDouble(entity);
        double coreHeight = coreHeightField.getDouble(entity);
        if (lastSpawnY < 0.0D || coreHeight <= 0.0D) {
            return 0.0D;
        }
        double stemGap = entity.getY() - lastSpawnY;
        double lift = (stemGap - coreHeight * 0.5D) * 0.75D;
        return clamp(lift, 0.0D, 8.0D);
    }

    @SuppressWarnings("unchecked")
    private static void applyCapLiftDelta(Entity entity, double delta) throws IllegalAccessException {
        Object rawCloudlets = cloudletsField.get(entity);
        if (!(rawCloudlets instanceof List<?> cloudlets)) {
            return;
        }
        double coreHeight = coreHeightField.getDouble(entity);
        double capThresholdY = entity.getY() + coreHeight * 0.55D;
        double stemFloorY = lastSpawnYField.getDouble(entity) + coreHeight * 0.35D;
        for (Object cloudlet : cloudlets) {
            if (cloudlet == null) {
                continue;
            }
            Object type = cloudletTypeField.get(cloudlet);
            if (type == null) {
                continue;
            }
            String typeName = type.toString();
            double posY = cloudletPosYField.getDouble(cloudlet);
            boolean shouldLift = "RING".equals(typeName)
                    || "CONDENSATION".equals(typeName)
                    || ("STANDARD".equals(typeName) && posY >= capThresholdY);
            if (!shouldLift || posY < stemFloorY) {
                continue;
            }
            cloudletPosYField.setDouble(cloudlet, posY + delta);
            cloudletPrevPosYField.setDouble(cloudlet, cloudletPrevPosYField.getDouble(cloudlet) + delta);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void ensureResolved() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!ModList.get().isLoaded(HBM_MOD_ID)) {
            available = false;
            return;
        }
        try {
            Class<?> rendererClass = Class.forName(NUKE_TOREX_RENDERER_CLASS);
            nukeTorexEntityClass = Class.forName(NUKE_TOREX_ENTITY_CLASS);
            coreHeightField = nukeTorexEntityClass.getField("coreHeight");
            lastSpawnYField = nukeTorexEntityClass.getField("lastSpawnY");
            cloudletsField = nukeTorexEntityClass.getField("cloudlets");
            Class<?> levelClass = Class.forName("net.minecraft.client.multiplayer.ClientLevel");
            Class<?> cameraClass = Class.forName("net.minecraft.client.Camera");
            Class<?> poseStackClass = Class.forName("com.mojang.blaze3d.vertex.PoseStack");
            Class<?> bufferSourceClass = Class.forName("net.minecraft.client.renderer.MultiBufferSource$BufferSource");
            renderCloudletsAfterLevelMethod = rendererClass.getMethod(
                    "renderCloudletsAfterLevel", levelClass, cameraClass, float.class, poseStackClass, bufferSourceClass);
            Class<?> cloudletClass = Class.forName(NUKE_TOREX_ENTITY_CLASS + "$Cloudlet");
            cloudletPosYField = cloudletClass.getField("posY");
            cloudletPrevPosYField = cloudletClass.getField("prevPosY");
            cloudletTypeField = cloudletClass.getField("type");
            available = true;
        } catch (ReflectiveOperationException ignored) {
            available = false;
        }
    }
}
