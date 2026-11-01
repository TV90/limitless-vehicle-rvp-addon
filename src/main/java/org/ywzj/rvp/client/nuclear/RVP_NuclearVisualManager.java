package org.ywzj.rvp.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
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
import org.joml.Matrix4f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.network.S2CNuclearVisualEffect;

import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveCloudField.*;
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
 *
 * <p>来源与许可：移植自 HBM's Nuclear Tech Mod: Rebirth（LGPL-3.0），版权归其原作者及
 * 贡献者所有；并入本项目后随项目以 GPL-3.0 再分发（LGPLv3 §3 许可的合并方式，
 * 详见项目根 {@code THIRD_PARTY_NOTICES.md}）。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_NuclearVisualManager {

    private static final ResourceLocation CLOUD_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");
    private static final ResourceLocation FLARE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/flare.png");
    private static final double FAR_PLANE = 10000.0D;

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
        // 调用核爆专用球壳，保留原参数与迟到事件的 219 tick 年龄上限。
        RVP_NuclearShockwaveRenderer.spawn(message.x(), message.y(), message.z(),
                (int) Math.min(elapsed, 219L), message.seed());
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
        // 调用核爆自身的更新入口，保留原来的推进时机。
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
        // 只清理核爆球壳，威尔逊云持有独立生命周期。
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
        // 维持原顺序：先画核爆球壳，再画核爆云团和闪光。
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
        if (RENDER_CLOUDS.isEmpty() && EFFECTS.isEmpty()) {
            return;
        }

        // Extend projection matrix far plane so effects are visible from thousands of blocks away.
        // FOV 从当前投影矩阵提取（含玩家缩放视角/瞄准镜的动态 FOV），保证特效随视角缩放正确变化。
        Matrix4f currentProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.backupProjectionMatrix();
        float f = currentProjection.m11(); // 透视矩阵 m11 = 1/tan(fovY/2)
        if (f <= 1.0E-4F) {
            f = (float) (1.0D / Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2.0D));
        }
        float fovY = 2.0F * (float) Math.atan(1.0F / f);
        float aspect = f / currentProjection.m00();
        Matrix4f extendedProjection = new Matrix4f().perspective(
                fovY,
                aspect,
                0.05F,
                (float) FAR_PLANE
        );
        RenderSystem.setProjectionMatrix(extendedProjection, VertexSorting.DISTANCE_TO_ORIGIN);

        // Model-view with camera rotation only (matching RVP_NuclearShockwaveRenderer approach)
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        modelView.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();

        // Clouds (translucent)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderTexture(0, CLOUD_TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // 调用共用烟雾受光记录器，核爆云也接受炮口/发动机等其它光源，不改变核爆自身颜色动画。
        com.mojang.blaze3d.vertex.VertexConsumer smokeBuffer =
                org.ywzj.rvp.client.light.RVP_SmokeLightPass.capture(builder, CLOUD_TEXTURE);
        for (RenderCloud renderCloud : RENDER_CLOUDS) {
            renderCloudTess(smokeBuffer, renderCloud, cameraPos, event.getPartialTick());
        }
        BufferUploader.drawWithShader(builder.end());

        // Flares (additive)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, FLARE_TEXTURE);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Effect effect : EFFECTS) {
            renderFlareTess(builder, effect, cameraPos, event.getPartialTick());
        }
        BufferUploader.drawWithShader(builder.end());

        // Restore state
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
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

    private static void renderCloudTess(com.mojang.blaze3d.vertex.VertexConsumer builder, RenderCloud renderCloud,
            Vec3 cameraPos, float partialTick) {
        Effect effect = renderCloud.effect();
        Cloud cloud = renderCloud.cloud();
        float alpha = cloud.alpha(effect);
        if (alpha <= 0.001F) {
            return;
        }
        float scale = cloud.scale(effect) * renderCloud.scaleMultiplier;
        float wx = (float) cloud.renderX(effect, partialTick);
        float wy = (float) cloud.renderY(effect, partialTick);
        float wz = (float) cloud.renderZ(effect, partialTick);
        // Convert to camera-relative coordinates; model-view matrix handles rotation
        float x = wx - (float) cameraPos.x;
        float y = wy - (float) cameraPos.y;
        float z = wz - (float) cameraPos.z;
        cloud.writeColor(effect, partialTick, COLOR_SCRATCH);
        billboardTess(builder, x, y, z, scale,
                COLOR_SCRATCH[0], COLOR_SCRATCH[1], COLOR_SCRATCH[2], alpha);
    }

    private static void renderFlareTess(BufferBuilder builder, Effect effect,
            Vec3 cameraPos, float partialTick) {
        if (effect.age >= 100) {
            return;
        }
        float progress = (effect.age + partialTick) / 100.0F;
        float alpha = 1.0F - progress;
        float scale = (float) (25.0D * effect.rollerSize);
        for (int i = 0; i < effect.flareOffsets.length; i++) {
            float wx = (float) effect.center.x + effect.flareOffsets[i][0] * (float) effect.rollerSize;
            float wy = (float) (effect.center.y + effect.coreHeight)
                    + effect.flareOffsets[i][1] * (float) effect.rollerSize;
            float wz = (float) effect.center.z + effect.flareOffsets[i][2] * (float) effect.rollerSize;
            // Convert to camera-relative coordinates
            float x = wx - (float) cameraPos.x;
            float y = wy - (float) cameraPos.y;
            float z = wz - (float) cameraPos.z;
            billboardTess(builder, x, y, z, scale, 1.0F, 1.0F, 1.0F, alpha);
        }
    }

    /**
     * True camera-facing billboard in camera-relative pre-rotation space.
     * Each billboard faces the camera individually by computing its orientation
     * from the vector directed toward the camera (origin in camera-relative space).
     * This prevents the flat-looking artifacts that occur when a fixed XY-plane
     * quad is viewed from oblique angles.
     */
    private static void billboardTess(com.mojang.blaze3d.vertex.VertexConsumer builder,
            float x, float y, float z, float halfSize,
            float red, float green, float blue, float alpha) {
        // Look direction from billboard center toward camera (origin in camera-relative space).
        float lx = -x;
        float ly = -y;
        float lz = -z;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1.0E-4F) {
            builder.vertex(x - halfSize, y - halfSize, z).uv(0, 1).color(red, green, blue, alpha).endVertex();
            builder.vertex(x + halfSize, y - halfSize, z).uv(1, 1).color(red, green, blue, alpha).endVertex();
            builder.vertex(x + halfSize, y + halfSize, z).uv(1, 0).color(red, green, blue, alpha).endVertex();
            builder.vertex(x - halfSize, y + halfSize, z).uv(0, 0).color(red, green, blue, alpha).endVertex();
            return;
        }
        lx /= len;
        ly /= len;
        lz /= len;

        // Right = cross(look, world_up) where world_up = (0, 1, 0)
        float rx = -lz;
        float rz = lx;
        float rLen = (float) Math.sqrt(rx * rx + rz * rz);
        if (rLen < 1.0E-4F) {
            // Looking straight up/down, right degenerates to zero; use world right
            rx = 1.0F;
            rz = 0.0F;
            rLen = 1.0F;
        }
        rx /= rLen;
        rz /= rLen;

        // Up = cross(right, look)
        // right = (rx, 0, rz), look = (lx, ly, lz)
        // cross((rx,0,rz), (lx,ly,lz)) = (0*lz - rz*ly, rz*lx - rx*lz, rx*ly - 0*lx)
        float ux = -rz * ly;
        float uy = rz * lx - rx * lz;
        float uz = rx * ly;

        // Scale right and up by halfSize
        float rhx = rx * halfSize;
        float rhz = rz * halfSize;
        float uhx = ux * halfSize;
        float uhy = uy * halfSize;
        float uhz = uz * halfSize;

        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        int r = Mth.clamp((int) (red * 255.0F), 0, 255);
        int g = Mth.clamp((int) (green * 255.0F), 0, 255);
        int b = Mth.clamp((int) (blue * 255.0F), 0, 255);

        // CW winding: BL→TL→TR→BR so that normal = cross(up, right) faces the camera.
        builder.vertex(x - rhx - uhx, y - uhy, z - rhz - uhz).uv(0, 1).color(r, g, b, a).endVertex();
        builder.vertex(x - rhx + uhx, y + uhy, z - rhz + uhz).uv(0, 0).color(r, g, b, a).endVertex();
        builder.vertex(x + rhx + uhx, y + uhy, z + rhz + uhz).uv(1, 0).color(r, g, b, a).endVertex();
        builder.vertex(x + rhx - uhx, y - uhy, z + rhz - uhz).uv(1, 1).color(r, g, b, a).endVertex();
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
            // 调用共享云团运动算法，核爆与威尔逊云共用同一套翻滚和抬升计算。
            clouds.get(i).update(context);
        }
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

    private static final class Effect implements Appearance {
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
            // legacyCloudScale 对低半径有 0.5 下界且动态范围小，直接乘 visual_scale 会被 clamp 抹平。
            // 改为：yield 决定基准缩放，visual_scale 作为整体线性乘数，默认(1.0)时与原行为一致。
            cloudScale = Mth.clamp(legacyCloudScale(effectYield), 0.5F, 5.0F) * Mth.clamp(visualScale, 0.1F, 2.0F);
            maxAge = Math.max(1, Math.round(45.0F * 20.0F * cloudScale));
            groundY = message.groundY();
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
                int blockX = Mth.floor(x);
                int blockZ = Mth.floor(z);
                double y = level.hasChunkAt(new BlockPos(blockX, Mth.floor(center.y), blockZ))
                        ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ)
                        : groundY;
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

        /** 向共享云团算法提供核爆外观参数，保持原缩放与淡出。 */
        public Vec3 center() { return center; }
        public float cloudScale() { return cloudScale; }
        public int age() { return age; }
        public int maxAge() { return maxAge; }

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

        public double greying() {
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


}
