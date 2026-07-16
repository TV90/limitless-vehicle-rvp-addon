package org.ywzj.rvp.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.network.S2CNuclearVisualEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone nuclear visual fallback adapted from the GPLv3 HBM NTM Rebirth Torex behavior.
 * It contains no HBM class references and performs no real nuclear damage or world mutation.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_NuclearVisualManager {

    private static final ResourceLocation CLOUD_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");
    private static final ResourceLocation FLARE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/flare.png");
    private static final RenderType CLOUD_RENDER_TYPE =
            RVP_RenderTypes.texturedTranslucentNoDepthWrite(CLOUD_TEXTURE);
    private static final RenderType FLARE_RENDER_TYPE =
            RVP_RenderTypes.texturedAdditiveNoDepthWrite(FLARE_TEXTURE);

    private static final int PARALLEL_THRESHOLD = 4_096;
    private static final int PARALLEL_CHUNK_SIZE = 2_048;
    private static final int WORKER_COUNT = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    private static final AtomicInteger WORKER_ID = new AtomicInteger();
    private static final ExecutorService UPDATE_EXECUTOR = WORKER_COUNT > 1
            ? Executors.newFixedThreadPool(WORKER_COUNT, runnable -> {
                Thread thread = new Thread(runnable,
                        "RVP-Nuclear-Cloudlet-" + WORKER_ID.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            })
            : null;

    private static final List<Effect> EFFECTS = new ArrayList<>();
    private static final List<RenderCloud> RENDER_CLOUDS = new ArrayList<>();
    private static final List<RenderCloud> RENDER_CLOUD_POOL = new ArrayList<>();
    private static final float[] COLOR_SCRATCH = new float[3];
    private static final Comparator<RenderCloud> FAR_TO_NEAR =
            Comparator.comparingDouble(RenderCloud::distanceSq).reversed();
    private static ClientLevel activeLevel;
    private static long cachedRenderGameTime = Long.MIN_VALUE;
    private static Vec3 cachedCameraPos = Vec3.ZERO;
    private static boolean renderCacheDirty = true;
    private static boolean parallelUpdatesHealthy = true;
    private static long flashTimestamp;

    private RVP_NuclearVisualManager() {}

    public static void spawn(S2CNuclearVisualEffect message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (activeLevel != level) {
            clear(level);
        }
        Effect effect = new Effect(level, message, minecraft.player);
        EFFECTS.add(effect);
        long elapsed = Math.max(0L, level.getGameTime() - message.startGameTime());
        RVP_NuclearShockwaveRenderer.spawn(message.x(), message.y(), message.z(),
                (int) Math.min(elapsed, 219L));
        renderCacheDirty = true;
        if (message.flash() && minecraft.player != null
                && minecraft.player.distanceToSqr(message.x(), message.y(), message.z()) <= effect.flashRangeSq()) {
            triggerFlash();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clear(null);
            return;
        }
        if (activeLevel != level) {
            clear(level);
        }
        if (EFFECTS.isEmpty()) {
            return;
        }
        LocalPlayer player = minecraft.player;
        for (int i = EFFECTS.size() - 1; i >= 0; i--) {
            Effect effect = EFFECTS.get(i);
            effect.tick(level, player);
            if (effect.dead()) {
                EFFECTS.remove(i);
            }
        }
        RVP_NuclearShockwaveRenderer.tick();
        renderCacheDirty = true;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || EFFECTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        render(event, minecraft);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        long remaining = flashTimestamp + 5_000L - System.currentTimeMillis();
        if (remaining <= 0L) {
            return;
        }
        float brightness = remaining / 5_000.0F;
        int alpha = Mth.clamp((int) (brightness * 255.0F), 0, 255);
        GuiGraphics graphics = event.getGuiGraphics();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        graphics.fill(0, 0, event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight(),
                (alpha << 24) | 0xFFFFFF);
        graphics.flush();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void clear(ClientLevel nextLevel) {
        EFFECTS.clear();
        RENDER_CLOUDS.clear();
        RENDER_CLOUD_POOL.clear();
        RVP_NuclearShockwaveRenderer.clear();
        activeLevel = nextLevel;
        cachedRenderGameTime = Long.MIN_VALUE;
        cachedCameraPos = Vec3.ZERO;
        renderCacheDirty = true;
        flashTimestamp = 0L;
    }

    private static void triggerFlash() {
        long now = System.currentTimeMillis();
        if (now - flashTimestamp > 1_000L) {
            flashTimestamp = now;
        }
    }

    private static void render(RenderLevelStageEvent event, Minecraft minecraft) {
        RVP_NuclearShockwaveRenderer.render(event);
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        long gameTime = minecraft.level.getGameTime();
        if (renderCacheDirty || cachedRenderGameTime != gameTime
                || cachedCameraPos.distanceToSqr(cameraPos) > 16.0D) {
            rebuildRenderCache(cameraPos, event.getPartialTick());
            cachedRenderGameTime = gameTime;
            cachedCameraPos = cameraPos;
            renderCacheDirty = false;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        Quaternionf cameraRotation = camera.rotation();
        Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(cameraRotation);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(cameraRotation);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        VertexConsumer cloudConsumer = buffers.getBuffer(CLOUD_RENDER_TYPE);
        for (RenderCloud renderCloud : RENDER_CLOUDS) {
            renderCloudBillboard(cloudConsumer, pose, normal, right, up, renderCloud, event.getPartialTick());
        }
        buffers.endBatch(CLOUD_RENDER_TYPE);

        VertexConsumer flareConsumer = buffers.getBuffer(FLARE_RENDER_TYPE);
        for (Effect effect : EFFECTS) {
            renderFlare(flareConsumer, pose, normal, right, up, effect, event.getPartialTick());
        }
        buffers.endBatch(FLARE_RENDER_TYPE);
        poseStack.popPose();
    }

    private static void rebuildRenderCache(Vec3 cameraPos, float partialTick) {
        RENDER_CLOUDS.clear();
        int poolIndex = 0;
        for (Effect effect : EFFECTS) {
            double effectDistanceSq = cameraPos.distanceToSqr(effect.center);
            float coreLod;
            float decorationLod;
            if (effectDistanceSq <= 96.0D * 96.0D) {
                coreLod = 0.68F;
                decorationLod = 0.32F;
            } else if (effectDistanceSq <= 192.0D * 192.0D) {
                coreLod = 0.78F;
                decorationLod = 0.48F;
            } else if (effectDistanceSq <= 384.0D * 384.0D) {
                coreLod = 0.86F;
                decorationLod = 0.62F;
            } else {
                coreLod = 0.72F;
                decorationLod = 0.42F;
            }
            for (Cloud cloud : effect.clouds) {
                float lod = cloud.decorative() ? decorationLod : coreLod;
                if (cloud.renderSample > lod) {
                    continue;
                }
                double x = cloud.renderX(effect, partialTick);
                double y = cloud.renderY(effect, partialTick);
                double z = cloud.renderZ(effect, partialTick);
                double dx = cameraPos.x - x;
                double dy = cameraPos.y - y;
                double dz = cameraPos.z - z;
                RenderCloud renderCloud;
                if (poolIndex < RENDER_CLOUD_POOL.size()) {
                    renderCloud = RENDER_CLOUD_POOL.get(poolIndex);
                } else {
                    renderCloud = new RenderCloud();
                    RENDER_CLOUD_POOL.add(renderCloud);
                }
                renderCloud.set(effect, cloud, dx * dx + dy * dy + dz * dz,
                        Mth.clamp((float) Math.sqrt(1.0F / lod), 1.0F, 1.35F));
                RENDER_CLOUDS.add(renderCloud);
                poolIndex++;
            }
        }
        for (int i = poolIndex; i < RENDER_CLOUD_POOL.size(); i++) {
            RENDER_CLOUD_POOL.get(i).set(null, null, 0.0D, 1.0F);
        }
        if (RENDER_CLOUDS.size() > 1) {
            RENDER_CLOUDS.sort(FAR_TO_NEAR);
        }
    }

    private static void renderCloudBillboard(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            Vector3f right, Vector3f up, RenderCloud renderCloud, float partialTick) {
        Effect effect = renderCloud.effect();
        Cloud cloud = renderCloud.cloud();
        float alpha = cloud.alpha(effect);
        if (alpha <= 0.001F) {
            return;
        }
        float scale = cloud.scale(effect) * renderCloud.scaleMultiplier;
        float x = (float) cloud.renderX(effect, partialTick);
        float y = (float) cloud.renderY(effect, partialTick);
        float z = (float) cloud.renderZ(effect, partialTick);
        cloud.writeColor(effect, partialTick, COLOR_SCRATCH);
        billboard(consumer, pose, normal, right, up, x, y, z, scale,
                COLOR_SCRATCH[0], COLOR_SCRATCH[1], COLOR_SCRATCH[2], alpha);
    }

    private static void renderFlare(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            Vector3f right, Vector3f up, Effect effect, float partialTick) {
        if (effect.age >= 100) {
            return;
        }
        float progress = (effect.age + partialTick) / 100.0F;
        float alpha = 1.0F - progress;
        float scale = (float) (25.0D * effect.rollerSize);
        for (int i = 0; i < effect.flareOffsets.length; i++) {
            float x = (float) effect.center.x + effect.flareOffsets[i][0] * (float) effect.rollerSize;
            float y = (float) (effect.center.y + effect.coreHeight)
                    + effect.flareOffsets[i][1] * (float) effect.rollerSize;
            float z = (float) effect.center.z + effect.flareOffsets[i][2] * (float) effect.rollerSize;
            billboard(consumer, pose, normal, right, up, x, y, z, scale,
                    1.0F, 1.0F, 1.0F, alpha);
        }
    }

    private static void billboard(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            Vector3f right, Vector3f up, float x, float y, float z, float halfSize,
            float red, float green, float blue, float alpha) {
        float rx = right.x() * halfSize;
        float ry = right.y() * halfSize;
        float rz = right.z() * halfSize;
        float ux = up.x() * halfSize;
        float uy = up.y() * halfSize;
        float uz = up.z() * halfSize;
        vertex(consumer, pose, normal, x - rx - ux, y - ry - uy, z - rz - uz,
                0.0F, 1.0F, red, green, blue, alpha);
        vertex(consumer, pose, normal, x + rx - ux, y + ry - uy, z + rz - uz,
                1.0F, 1.0F, red, green, blue, alpha);
        vertex(consumer, pose, normal, x + rx + ux, y + ry + uy, z + rz + uz,
                1.0F, 0.0F, red, green, blue, alpha);
        vertex(consumer, pose, normal, x - rx + ux, y - ry + uy, z - rz + uz,
                0.0F, 0.0F, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            float x, float y, float z, float u, float v,
            float red, float green, float blue, float alpha) {
        consumer.vertex(pose, x, y, z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    private static void updateClouds(Effect effect, UpdateContext context) {
        int size = effect.clouds.size();
        if (size < PARALLEL_THRESHOLD || UPDATE_EXECUTOR == null || !parallelUpdatesHealthy) {
            updateRange(effect.clouds, context, 0, size);
            return;
        }
        int taskCount = Math.min(WORKER_COUNT,
                Math.max(1, (size + PARALLEL_CHUNK_SIZE - 1) / PARALLEL_CHUNK_SIZE));
        int rangeSize = (size + taskCount - 1) / taskCount;
        List<Future<?>> futures = new ArrayList<>(taskCount);
        for (int task = 0; task < taskCount; task++) {
            int start = task * rangeSize;
            int end = Math.min(size, start + rangeSize);
            if (start >= end) {
                break;
            }
            futures.add(UPDATE_EXECUTOR.submit(() -> updateRange(effect.clouds, context, start, end)));
        }
        Throwable failure = null;
        boolean interrupted = false;
        for (Future<?> future : futures) {
            boolean complete = false;
            while (!complete) {
                try {
                    future.get();
                    complete = true;
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    failure = failure == null ? exception.getCause() : failure;
                    complete = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            parallelUpdatesHealthy = false;
        }
    }

    private static void updateRange(List<Cloud> clouds, UpdateContext context, int start, int end) {
        for (int i = start; i < end; i++) {
            clouds.get(i).update(context);
        }
    }

    private enum CloudType {
        STANDARD,
        SHOCK,
        RING,
        CONDENSATION
    }

    private static final class RenderCloud {
        private Effect effect;
        private Cloud cloud;
        private double distanceSq;
        private float scaleMultiplier;

        private void set(Effect effect, Cloud cloud, double distanceSq, float scaleMultiplier) {
            this.effect = effect;
            this.cloud = cloud;
            this.distanceSq = distanceSq;
            this.scaleMultiplier = scaleMultiplier;
        }

        private Effect effect() {
            return effect;
        }

        private Cloud cloud() {
            return cloud;
        }

        private double distanceSq() {
            return distanceSq;
        }
    }

    private record UpdateContext(
            double centerX,
            double centerY,
            double centerZ,
            double coreHeight,
            double torusWidth,
            double rollerSize,
            double convectionHeight,
            double simulationSpeed,
            double heat,
            int effectAge
    ) {}

    private static final class Effect {
        private final Vec3 center;
        private final double groundY;
        private final float effectYield;
        private final float visualScale;
        private final float visualDensity;
        private final float cloudScale;
        private final int maxAge;
        private final boolean soundEnabled;
        private final boolean shakeEnabled;
        private final int soundDelayTicks;
        private final Random random;
        private final List<Cloud> clouds = new ArrayList<>();
        private final float[][] flareOffsets = new float[3][3];
        private int age;
        private double coreHeight = 3.0D;
        private double torusWidth = 3.0D;
        private double rollerSize = 1.0D;
        private double convectionHeight = 3.0D;
        private double heat = 75.0D;
        private double lastSpawnY = Double.NaN;
        private boolean soundPlayed;

        private Effect(ClientLevel level, S2CNuclearVisualEffect message, LocalPlayer player) {
            center = new Vec3(message.x(), message.y(), message.z());
            effectYield = Math.max(1.0F, message.effectYield());
            visualScale = Math.max(0.1F, message.visualScale());
            visualDensity = Mth.clamp(message.visualDensity(), 0.1F, 1.0F);
            cloudScale = legacyCloudScale(effectYield * visualScale);
            maxAge = Math.max(1, Math.round(45.0F * 20.0F * cloudScale));
            groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(center.x), Mth.floor(center.z));
            soundEnabled = message.sound();
            shakeEnabled = message.shake();
            double distance = player == null ? 0.0D : player.position().distanceTo(center);
            soundDelayTicks = Mth.clamp((int) Math.ceil(distance / 17.15D), 0, 20 * 60);
            random = new Random(message.seed());
            Random flareRandom = new Random(message.seed() ^ 0x5DEECE66DL);
            for (float[] offset : flareOffsets) {
                offset[0] = (float) (flareRandom.nextGaussian() * 0.5D);
                offset[1] = (float) (flareRandom.nextGaussian() * 0.5D);
                offset[2] = (float) (flareRandom.nextGaussian() * 0.5D);
            }
        }

        private void tick(ClientLevel level, LocalPlayer player) {
            age++;
            double spawnTarget = groundSimulationY();
            if (Double.isNaN(lastSpawnY)) {
                lastSpawnY = spawnTarget;
            }
            if (Math.abs(spawnTarget - lastSpawnY) < 0.5D) {
                lastSpawnY = spawnTarget;
            } else {
                lastSpawnY += 0.5D * Math.signum(spawnTarget - lastSpawnY);
            }

            double simSpeed = simulationSpeed();
            spawnConvectionClouds(simSpeed);
            if (age == 1) {
                spawnStemSupportClouds(32);
            } else if (age % 2 == 0) {
                spawnStemSupportClouds(2);
            }
            spawnShockClouds(level);
            spawnRingClouds();
            spawnCondensationClouds();

            UpdateContext context = new UpdateContext(
                    center.x, center.y, center.z,
                    coreHeight, torusWidth, rollerSize, convectionHeight,
                    simSpeed, Math.max(heat, 0.001D), age);
            updateClouds(this, context);
            clouds.removeIf(cloud -> cloud.dead);

            coreHeight += 0.1D;
            torusWidth += 1.0D / 30.0D;
            rollerSize = torusWidth * 0.35D;
            convectionHeight = coreHeight + rollerSize;
            heat = Math.max(0.001D, 75.0D * (1.0D - age / (double) maxAge));
            tickSound(level, player);
        }

        private void spawnConvectionClouds(double simSpeed) {
            int count = (int) Math.ceil(10.0D * simSpeed * simSpeed);
            double range = Math.max(0.0D, (torusWidth - rollerSize) * 0.25D);
            int lifetime = Math.min(age * age + 200, maxAge - age + 200);
            for (int i = 0; i < count; i++) {
                Cloud cloud = new Cloud(
                        center.x + random.nextGaussian() * range,
                        lastSpawnY,
                        center.z + random.nextGaussian() * range,
                        randomAngle(), lifetime, CloudType.STANDARD,
                        1.0F + age * 0.0075F, 7.5F, random);
                clouds.add(cloud);
            }
        }

        private void spawnStemSupportClouds(int count) {
            double stemTopY = center.y + Math.max(1.0D, coreHeight - rollerSize * 0.65D);
            double stemBottomY = Math.min(lastSpawnY, stemTopY);
            double range = Math.max((torusWidth - rollerSize) * 0.12D, 0.2D);
            int lifetime = Math.max(40, maxAge - age + 40);
            for (int i = 0; i < count; i++) {
                double fraction = (i + random.nextDouble()) / count;
                Cloud cloud = new Cloud(
                        center.x + random.nextGaussian() * range,
                        Mth.lerp(fraction, stemBottomY, stemTopY),
                        center.z + random.nextGaussian() * range,
                        randomAngle(), lifetime, CloudType.STANDARD,
                        0.9F + age * 0.0045F, 6.0F, random);
                cloud.motionMult = 0.0D;
                clouds.add(cloud);
            }
        }

        private void spawnShockClouds(ClientLevel level) {
            int interval = visualDensity < 0.4F ? 4 : visualDensity < 0.75F ? 3 : 2;
            if (age >= 150 || age % interval != 0) {
                return;
            }
            int baseCount = Math.min(age * 4, 192);
            int count = Math.max(1, Math.round(baseCount * visualDensity));
            int lifetime = Math.max(300 - age * 20, 50);
            float compensation = Mth.clamp(
                    (float) Math.sqrt(interval / (2.0F * visualDensity)), 1.0F, 1.5F);
            for (int i = 0; i < count; i++) {
                float angle = randomAngle();
                double radial = (age * 1.5D + random.nextDouble()) * 1.5D;
                double x = center.x + radial * Mth.cos(angle);
                double z = center.z - radial * Mth.sin(angle);
                double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        Mth.floor(x), Mth.floor(z));
                Cloud cloud = new Cloud(x, y, z, angle, lifetime, CloudType.SHOCK,
                        5.5F * compensation, 1.6F * compensation, random);
                cloud.motionMult = age > 15 ? 0.75D : 0.0D;
                clouds.add(cloud);
            }
        }

        private void spawnRingClouds() {
            if (age >= 195) {
                return;
            }
            int baseLifetime = Math.min(age * age + 200, maxAge - age + 200);
            int lifetime = Math.max(1, (int) (baseLifetime * 1.5D));
            for (int i = 0; i < 2; i++) {
                clouds.add(new Cloud(center.x, center.y + coreHeight, center.z,
                        randomAngle(), lifetime, CloudType.RING,
                        1.3F + age * 0.0072F, 8.55F, random));
            }
        }

        private void spawnCondensationClouds() {
            if (age % 4 != 0 || age <= 195 || age >= Math.min(maxAge, 900)) {
                return;
            }
            int radialSamples = Math.max(4, Math.round(16.0F * visualDensity));
            spawnCondensationBand(radialSamples, false);
            if (age > 300) {
                spawnCondensationBand(radialSamples, true);
            }
        }

        private void spawnCondensationBand(int radialSamples, boolean upper) {
            float compensation = Mth.clamp((float) Math.sqrt(1.0F / visualDensity), 1.0F, 1.35F);
            for (int i = 0; i < radialSamples; i++) {
                for (int j = 0; j < 3; j++) {
                    float angle = randomAngle();
                    double radial = upper
                            ? torusWidth + rollerSize * (3.0D + random.nextDouble() * 0.5D)
                            : torusWidth + rollerSize * (5.0D + random.nextDouble());
                    float zAngle = (float) (Math.PI / 45.0D * j);
                    double rotatedRadial = radial * Mth.cos(zAngle);
                    double y = center.y + coreHeight + (upper ? 25.0D + j * 1.5D : -5.0D + j * 1.5D);
                    int lifetime = (int) ((20.0D + age / 10.0D) * (1.0D + random.nextDouble() * 0.1D));
                    clouds.add(new Cloud(
                            center.x + rotatedRadial * Mth.cos(angle),
                            y,
                            center.z - rotatedRadial * Mth.sin(angle),
                            angle, lifetime, CloudType.CONDENSATION,
                            0.1875F * compensation, 4.5F * compensation, random));
                }
            }
        }

        private double groundSimulationY() {
            return center.y + (groundY - center.y) / Math.max(cloudScale, 0.001F);
        }

        private double simulationSpeed() {
            int slow = maxAge / 4;
            int stop = maxAge / 2;
            if (age > stop) {
                return 0.0D;
            }
            if (age > slow) {
                return 1.0D - (age - slow) / (double) Math.max(1, stop - slow);
            }
            return 1.0D;
        }

        private double greying() {
            int start = maxAge * 3 / 4;
            return age > start ? 1.0D + (age - start) / (double) Math.max(1, maxAge - start) : 1.0D;
        }

        private float globalAlpha() {
            int start = maxAge * 3 / 4;
            return age > start
                    ? 1.0F - (age - start) / (float) Math.max(1, maxAge - start)
                    : 1.0F;
        }

        private static float legacyCloudScale(float radius) {
            double x = Math.max(0.0D, radius) * 0.01D;
            double squirt = Math.sqrt(x + 1.0D / ((x + 2.0D) * (x + 2.0D))) - 1.0D / (x + 2.0D);
            return Mth.clamp((float) squirt * 1.5F, 0.5F, 5.0F);
        }

        private void tickSound(ClientLevel level, LocalPlayer player) {
            if (!soundEnabled || soundPlayed || age < soundDelayTicks) {
                return;
            }
            level.playLocalSound(center.x, center.y, center.z,
                    RVP_Sounds.NUCLEAR_EXPLOSION.get(), SoundSource.BLOCKS,
                    10_000.0F, 1.0F, false);
            soundPlayed = true;
            if (shakeEnabled && player != null) {
                player.animateHurt(0.0F);
                player.hurtTime = 15;
                player.hurtDuration = 15;
            }
        }

        private float randomAngle() {
            return (float) (random.nextDouble() * Math.PI * 2.0D);
        }

        private boolean dead() {
            return age > maxAge && clouds.isEmpty();
        }

        private double flashRangeSq() {
            double range = Math.max(512.0D, effectYield * 64.0D * visualScale);
            return range * range;
        }
    }

    private static final class Cloud {
        private double x;
        private double y;
        private double z;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double motionX;
        private double motionY;
        private double motionZ;
        private final float angle;
        private final int lifetime;
        private final CloudType type;
        private final float startScale;
        private final float growScale;
        private final float rangeMod;
        private final float colorMod;
        private final float renderSample;
        private double motionMult = 1.0D;
        private double colorR = 0.25D;
        private double colorG = 0.25D;
        private double colorB = 0.25D;
        private double previousColorR = 0.25D;
        private double previousColorG = 0.25D;
        private double previousColorB = 0.25D;
        private double computedMotionX;
        private double computedMotionY;
        private double computedMotionZ;
        private int age;
        private boolean dead;

        private Cloud(double x, double y, double z, float angle, int lifetime, CloudType type,
                float startScale, float growScale, Random random) {
            this.x = x;
            this.y = y;
            this.z = z;
            previousX = x;
            previousY = y;
            previousZ = z;
            this.angle = angle;
            this.lifetime = Math.max(1, lifetime);
            this.type = type;
            this.startScale = startScale;
            this.growScale = growScale;
            rangeMod = 0.3F + random.nextFloat() * 0.7F;
            colorMod = 0.8F + random.nextFloat() * 0.2F;
            long hash = stableHash(x, y, z, angle, type.ordinal());
            renderSample = (hash & 0xFFFFFFL) / 16777216.0F;
        }

        private void update(UpdateContext context) {
            age++;
            if (age > lifetime) {
                dead = true;
                return;
            }
            previousX = x;
            previousY = y;
            previousZ = z;
            double simDeltaX = context.centerX - x;
            double simDeltaZ = context.centerZ - z;
            double simPosX = context.centerX + Math.sqrt(simDeltaX * simDeltaX + simDeltaZ * simDeltaZ);
            double simPosZ = context.centerZ;

            if (type == CloudType.STANDARD) {
                computeConvectionMotion(context, simPosX, simPosZ);
                double convectionX = computedMotionX;
                double convectionY = computedMotionY;
                double convectionZ = computedMotionZ;
                computeLiftMotion(context, simPosX);
                double liftScale = Mth.clamp(1.0D - (simPosX - (context.centerX + context.torusWidth)), 0.0D, 1.0D);
                computedMotionX *= liftScale;
                computedMotionY *= liftScale;
                computedMotionZ *= liftScale;
                double factor = Mth.clamp((y - context.centerY) / Math.max(context.coreHeight, 0.001D), 0.0D, 1.0D);
                motionX = convectionX * factor + computedMotionX * (1.0D - factor);
                motionY = convectionY * factor + computedMotionY * (1.0D - factor);
                motionZ = convectionZ * factor + computedMotionZ * (1.0D - factor);
            } else if (type == CloudType.SHOCK) {
                double factor = Mth.clamp((y - context.centerY) / Math.max(context.coreHeight, 0.001D), 0.0D, 1.0D);
                motionX = Mth.cos(angle) * factor;
                motionY = 0.0D;
                motionZ = -Mth.sin(angle) * factor;
            } else if (type == CloudType.RING) {
                computeRingMotion(context, simPosX, simPosZ);
                motionX = computedMotionX;
                motionY = computedMotionY;
                motionZ = computedMotionZ;
            } else {
                double speed = 0.00002D * context.effectAge;
                motionX = (x - context.centerX) * speed;
                motionY = 0.0D;
                motionZ = (z - context.centerZ) * speed;
            }
            double mult = motionMult * context.simulationSpeed;
            x += motionX * mult;
            y += motionY * mult;
            z += motionZ * mult;
            updateColor(context);
        }

        private void computeLiftMotion(UpdateContext context, double simPosX) {
            setNormalizedMotion(context.centerX - x,
                    context.centerY + context.convectionHeight - y,
                    context.centerZ - z);
        }

        private void computeConvectionMotion(UpdateContext context, double simPosX, double simPosZ) {
            computeTorusMotion(context, simPosX, simPosZ,
                    context.centerY + context.coreHeight, context.rollerSize * rangeMod, 1.0D);
        }

        private void computeRingMotion(UpdateContext context, double simPosX, double simPosZ) {
            if (simPosX > context.centerX + context.torusWidth * 2.0D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            computeTorusMotion(context, simPosX, simPosZ,
                    context.centerY + context.coreHeight * 0.42D,
                    context.rollerSize * rangeMod * 0.25D, 0.001D);
        }

        private void computeTorusMotion(UpdateContext context, double simPosX, double simPosZ,
                double targetY, double roller, double targetScale) {
            if (roller < 1.0E-4D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            double targetX = context.centerX + context.torusWidth;
            double deltaX = targetX - simPosX;
            double deltaY = targetY - y;
            double deltaZ = context.centerZ - simPosZ;
            double dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ) / roller - 1.0D;
            if (Math.abs(dist) < 1.0E-6D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            double func = 1.0D - Math.exp(-dist);
            float turn = (float) (func * Math.PI * 0.5D);
            double rotX = -deltaX / dist;
            double rotY = -deltaY / dist;
            double rotZ = -deltaZ / dist;
            double cos = Mth.cos(turn);
            double sin = Mth.sin(turn);
            double rotatedX = rotX * cos + rotY * sin;
            double rotatedY = rotY * cos - rotX * sin;
            setNormalizedMotion(
                    (targetX + rotatedX - simPosX) * targetScale,
                    (targetY + rotatedY - y) * targetScale,
                    (context.centerZ + rotZ - simPosZ) * targetScale);
            double outX = computedMotionX * Mth.cos(angle) + computedMotionZ * Mth.sin(angle);
            double outZ = computedMotionZ * Mth.cos(angle) - computedMotionX * Mth.sin(angle);
            computedMotionX = outX;
            computedMotionZ = outZ;
        }

        private void setNormalizedMotion(double x, double y, double z) {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0E-4D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            setComputedMotion(x / length, y / length, z / length);
        }

        private void setComputedMotion(double x, double y, double z) {
            computedMotionX = x;
            computedMotionY = y;
            computedMotionZ = z;
        }

        private void updateColor(UpdateContext context) {
            previousColorR = colorR;
            previousColorG = colorG;
            previousColorB = colorB;
            double dx = context.centerX - x;
            double dy = context.centerY + context.coreHeight - y;
            double dz = context.centerZ - z;
            double distSq = (dx * dx + dy * dy + dz * dz) / Math.max(context.heat, 0.001D);
            double col = 2.0D / Math.max(Math.sqrt(distSq), 1.0D);
            colorR = Math.max(col * 2.0D, 0.25D);
            colorG = Math.max(col * 1.5D, 0.25D);
            colorB = Math.max(col * 0.5D, 0.25D);
        }

        private boolean decorative() {
            return type == CloudType.SHOCK || type == CloudType.CONDENSATION;
        }

        private float scale(Effect effect) {
            float base = startScale + age / (float) lifetime * growScale;
            return type == CloudType.SHOCK ? base : base * effect.cloudScale;
        }

        private float alpha(Effect effect) {
            float local = 1.0F - Mth.clamp(age / (float) lifetime, 0.0F, 1.0F);
            float global = effect.age <= effect.maxAge * 0.75F
                    ? 1.0F
                    : 1.0F - (effect.age - effect.maxAge * 0.75F) / (effect.maxAge * 0.25F);
            float alpha = Mth.clamp(local * global, 0.0F, 1.0F);
            return type == CloudType.CONDENSATION ? alpha * 0.25F : alpha;
        }

        private void writeColor(Effect effect, float partialTick, float[] output) {
            if (type == CloudType.CONDENSATION) {
                output[0] = 0.9F;
                output[1] = 0.9F;
                output[2] = 0.9F;
                return;
            }
            double greying = effect.greying() + (type == CloudType.RING ? 1.0D : 0.0D);
            float brightness = 0.75F * colorMod;
            output[0] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorR, colorR) * greying) * brightness, 0.0F, 1.0F);
            output[1] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorG, colorG) * greying) * brightness, 0.0F, 1.0F);
            output[2] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorB, colorB) * greying) * brightness, 0.0F, 1.0F);
        }

        private double renderX(Effect effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousX, x);
            return type == CloudType.SHOCK ? value : (value - effect.center.x) * effect.cloudScale + effect.center.x;
        }

        private double renderY(Effect effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousY, y);
            return type == CloudType.SHOCK ? value : (value - effect.center.y) * effect.cloudScale + effect.center.y;
        }

        private double renderZ(Effect effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousZ, z);
            return type == CloudType.SHOCK ? value : (value - effect.center.z) * effect.cloudScale + effect.center.z;
        }

        private static long stableHash(double x, double y, double z, float angle, int type) {
            long hash = Double.doubleToLongBits(x);
            hash = hash * 31L + Double.doubleToLongBits(y);
            hash = hash * 31L + Double.doubleToLongBits(z);
            hash = hash * 31L + Float.floatToIntBits(angle);
            hash = hash * 31L + type;
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            return hash;
        }
    }
}
