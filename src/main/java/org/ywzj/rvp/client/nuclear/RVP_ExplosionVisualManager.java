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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.network.S2CNuclearVisualEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Standalone shell and bomb visual fallback adapted from HBM NTM Rebirth's
 * explosionSmall/explosionLarge behavior. It performs no damage or world mutation.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExplosionVisualManager {

    private static final ResourceLocation CLOUD_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");
    private static final ResourceLocation WAVE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/shockwave.png");
    private static final double LEGACY_SPEED_OF_SOUND = 17.15D * 0.5D;
    private static final double FAR_PLANE = 10000.0D;
    private static final double LOD_DISTANCE_SQ = 512.0D * 512.0D;

    // 可选：烟团寿命按 sqrt(scale) 缩放（小爆炸消散更快）。默认关闭——寿命不缩也能保持等比，
    // 只是动画节奏偏"慢动作"（文档 7.1 末行）。HBM 后端无寿命参数，此开关仅对自研回退生效。
    private static final boolean SCALE_CLOUD_LIFETIME_BY_SQRT = false;

    // ---- 立体碎块（对齐原版 ParticleDebris / WorldInAJar，文档 7.3）----
    /** 原版 LARGE 档固定簇填充重试数：控制簇内部填充密度，不是尺寸量，不随 scale 缩。 */
    private static final int DEBRIS_RETRY_ATTEMPTS = 50;
    private static final int DEBRIS_LIFETIME_TICKS = 100;
    private static final float DEBRIS_GRAVITY = 0.15F;
    /** 初速放大倍数：原版 ParticleDebris 构造器 motion*3。 */
    private static final double DEBRIS_INITIAL_SPEED_MULT = 3.0D;
    /** 前 5 tick 无碰撞（原版 noClip），让碎块先飞出爆心再开始落地判定。 */
    private static final int DEBRIS_COLLISION_DELAY_TICKS = 5;
    /** 每 tick 恒定翻滚角上限（度），原版 rng.nextFloat()*10。 */
    private static final float DEBRIS_TUMBLE_DEGREES = 10.0F;
    /** 每 3 个碎块 1 个沿路拖烟尾（原版 entityId%3==0）。 */
    private static final int DEBRIS_TRAIL_EVERY = 3;
    private static final int DEBRIS_TRAIL_LIFETIME = 50;
    /** 采样中心相对爆心的垂直偏移，原版 debrisVerticalOffset=-2。 */
    private static final double DEBRIS_VERTICAL_OFFSET = -2.0D;
    /** 单次爆炸碎块总体积上限 = 原版 LARGE 满配 25×16³，超出则削减碎块数。 */
    private static final long MAX_DEBRIS_CELLS_PER_EXPLOSION = 25L * 16 * 16 * 16;
    /** 全局同屏立体碎块上限（跨爆炸累计），防止连环爆炸刷爆顶点吞吐。 */
    private static final int MAX_ACTIVE_DEBRIS_CHUNKS = 64;

    private static final List<ExplosionEffect> EFFECTS = new ArrayList<>();
    private static final List<RenderCloud> RENDER_CLOUDS = new ArrayList<>();
    private static final Comparator<RenderCloud> FAR_TO_NEAR =
            Comparator.comparingDouble(RenderCloud::distanceSq).reversed();
    private static ClientLevel activeLevel;

    private RVP_ExplosionVisualManager() {}

    public static void spawn(S2CNuclearVisualEffect message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Preset preset = Preset.parse(message.preset());
        if (preset == null) {
            return;
        }
        if (activeLevel != level) {
            clear(level);
        }
        ExplosionEffect effect = new ExplosionEffect(message, preset, minecraft.player);
        effect.spawn(level);
        EFFECTS.add(effect);
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
        LocalPlayer player = minecraft.player;
        for (int i = EFFECTS.size() - 1; i >= 0; i--) {
            ExplosionEffect effect = EFFECTS.get(i);
            effect.tick(level, player);
            if (effect.dead()) {
                EFFECTS.remove(i);
            }
        }
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

    private static void clear(ClientLevel nextLevel) {
        EFFECTS.clear();
        RENDER_CLOUDS.clear();
        activeLevel = nextLevel;
    }

    private static void render(RenderLevelStageEvent event, Minecraft minecraft) {
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();

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

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();

        // Debris (opaque): TERRAIN_SHEET block clusters with face culling, drawn before
        // the translucent clouds so smoke blends over the flying terrain chunks.
        renderDebris(builder, cameraPos, event.getPartialTick(), minecraft.level);

        // Clouds (translucent)
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        rebuildRenderCache(cameraPos);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderTexture(0, CLOUD_TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (RenderCloud renderCloud : RENDER_CLOUDS) {
            renderCloudTess(builder, renderCloud.cloud(), cameraPos, event.getPartialTick(), renderCloud.distanceSq());
        }
        BufferUploader.drawWithShader(builder.end());

        // Waves (additive) - only for BOMB preset
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, WAVE_TEXTURE);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (ExplosionEffect effect : EFFECTS) {
            renderWaveTess(builder, effect, cameraPos, event.getPartialTick());
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

    private static void rebuildRenderCache(Vec3 cameraPos) {
        RENDER_CLOUDS.clear();
        for (ExplosionEffect effect : EFFECTS) {
            for (BlastCloud cloud : effect.clouds) {
                double dx = cameraPos.x - cloud.x;
                double dy = cameraPos.y - cloud.y;
                double dz = cameraPos.z - cloud.z;
                RENDER_CLOUDS.add(new RenderCloud(cloud, dx * dx + dy * dy + dz * dz));
            }
        }
        if (RENDER_CLOUDS.size() > 1) {
            RENDER_CLOUDS.sort(FAR_TO_NEAR);
        }
    }

    /**
     * 立体碎块通道：方块图集上的不透明面剔除立方体簇（等价重生的 TERRAIN_SHEET 做法，
     * 但完全自绘，不依赖 hbm_ntm_rebirth）。先于半透明烟云绘制，让烟盖在碎块上方。
     */
    private static void renderDebris(BufferBuilder builder, Vec3 cameraPos, float partialTick,
            ClientLevel level) {
        boolean any = false;
        for (ExplosionEffect effect : EFFECTS) {
            if (!effect.debrisChunks.isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (ExplosionEffect effect : EFFECTS) {
            for (DebrisChunk chunk : effect.debrisChunks) {
                chunk.render(builder, cameraPos, partialTick, level);
            }
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static void renderCloudTess(BufferBuilder builder, BlastCloud cloud,
            Vec3 cameraPos, float partialTick, double distanceSq) {
        float progress = cloud.progress(partialTick);
        if (progress >= 1.0F) {
            return;
        }
        double wx = Mth.lerp(partialTick, cloud.previousX, cloud.x);
        double wy = Mth.lerp(partialTick, cloud.previousY, cloud.y);
        double wz = Mth.lerp(partialTick, cloud.previousZ, cloud.z);
        float x = (float) (wx - cameraPos.x);
        float y = (float) (wy - cameraPos.y);
        float z = (float) (wz - cameraPos.z);
        boolean lod = distanceSq > LOD_DISTANCE_SQ;
        if (cloud.preset == Preset.SHELL) {
            int rgb = Mth.hsvToRgb(cloud.hue / 255.0F,
                    Math.max(1.0F - progress * 2.0F, 0.0F),
                    Mth.clamp(1.25F - progress * 2.0F, cloud.hue * 0.01F - 0.1F, 1.0F));
            float red = ((rgb >> 16) & 0xFF) / 255.0F;
            float green = ((rgb >> 8) & 0xFF) / 255.0F;
            float blue = (rgb & 0xFF) / 255.0F;
            float alpha = (float) Math.pow(1.0F - progress, 0.25D) * 0.5F;
            float size = (float) (0.25D + 1.0D - Math.pow(1.0D - progress, 4.0D)
                    + progress * cloud.lifetime * 0.02D) * cloud.baseScale;
            if (lod) size *= 1.5F;
            billboardTess(builder, x, y, z, size, red, green, blue, alpha);
            return;
        }

        float dark = 1.0F - Math.min(progress * 4.0F, 1.0F);
        float alpha = (float) Math.pow(1.0F - progress, 0.5D) * 0.75F;
        float spread = ((float) Math.pow(progress * 4.0F, 1.5D) + 1.0F) * cloud.baseScale;
        int layerStep = lod ? 3 : 1;
        float lodSizeBoost = lod ? 2.0F : 1.0F;
        for (int i = 0; i < cloud.layerAdd.length; i += layerStep) {
            float add = cloud.layerAdd[i];
            float red = Mth.clamp(dark + add, 0.0F, 1.0F);
            float green = Mth.clamp(0.6F * dark + add, 0.0F, 1.0F);
            float blue = Mth.clamp(add, 0.0F, 1.0F);
            float size = (cloud.layerScale[i] * 0.5F + 0.1F + progress * 2.0F) * cloud.baseScale * lodSizeBoost;
            float bx = x + cloud.layerOffsetX[i] * spread;
            float by = y + cloud.layerOffsetY[i] * spread;
            float bz = z + cloud.layerOffsetZ[i] * spread;
            billboardTess(builder, bx, by, bz, size, red, green, blue, alpha);
        }
    }

    private static void renderWaveTess(BufferBuilder builder, ExplosionEffect effect,
            Vec3 cameraPos, float partialTick) {
        if (effect.preset != Preset.BOMB) {
            return;
        }
        float visualAge = effect.age + partialTick;
        float alpha = 1.0F - visualAge / effect.waveLifetime;
        if (alpha <= 0.0F) {
            return;
        }
        float scale = (float) (1.0D - Math.exp(visualAge * -0.125D)) * effect.waveScale;
        float cx = (float) effect.center.x - (float) cameraPos.x;
        float cy = (float) effect.center.y + 1.75F - (float) cameraPos.y;
        float cz = (float) effect.center.z - (float) cameraPos.z;
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        builder.vertex(cx - scale, cy, cz - scale).uv(1.0F, 1.0F).color(255, 255, 255, a).endVertex();
        builder.vertex(cx - scale, cy, cz + scale).uv(1.0F, 0.0F).color(255, 255, 255, a).endVertex();
        builder.vertex(cx + scale, cy, cz + scale).uv(0.0F, 0.0F).color(255, 255, 255, a).endVertex();
        builder.vertex(cx + scale, cy, cz - scale).uv(0.0F, 1.0F).color(255, 255, 255, a).endVertex();
    }

    /**
     * True camera-facing billboard in camera-relative pre-rotation space.
     * Each billboard faces the camera individually by computing its orientation
     * from the vector directed toward the camera (origin in camera-relative space).
     * This prevents the flat-looking artifacts that occur when a fixed XY-plane
     * quad is viewed from oblique angles.
     */
    private static void billboardTess(BufferBuilder builder,
            float x, float y, float z, float halfSize,
            float red, float green, float blue, float alpha) {
        // Look direction from billboard center toward camera (origin in camera-relative space).
        float lx = -x;
        float ly = -y;
        float lz = -z;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1.0E-4F) {
            int a0 = Mth.clamp((int) (alpha * 255.0F), 0, 255);
            int r0 = Mth.clamp((int) (red * 255.0F), 0, 255);
            int g0 = Mth.clamp((int) (green * 255.0F), 0, 255);
            int b0 = Mth.clamp((int) (blue * 255.0F), 0, 255);
            builder.vertex(x - halfSize, y - halfSize, z).uv(0, 1).color(r0, g0, b0, a0).endVertex();
            builder.vertex(x + halfSize, y - halfSize, z).uv(1, 1).color(r0, g0, b0, a0).endVertex();
            builder.vertex(x + halfSize, y + halfSize, z).uv(1, 0).color(r0, g0, b0, a0).endVertex();
            builder.vertex(x - halfSize, y + halfSize, z).uv(0, 0).color(r0, g0, b0, a0).endVertex();
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
            rx = 1.0F;
            rz = 0.0F;
            rLen = 1.0F;
        }
        rx /= rLen;
        rz /= rLen;

        // Up = cross(right, look)
        float ux = -rz * ly;
        float uy = rz * lx - rx * lz;
        float uz = rx * ly;

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

    private enum Preset {
        SHELL,
        BOMB;

        private static Preset parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "shell" -> SHELL;
                case "bomb" -> BOMB;
                default -> null;
            };
        }
    }

    private record RenderCloud(BlastCloud cloud, double distanceSq) {}

    private static final class ExplosionEffect {
        private final Vec3 center;
        private final Preset preset;
        private final float scale;
        private final float density;
        private final boolean soundEnabled;
        private final int soundDelayTicks;
        private final float soundRange;
        private final float waveScale;
        private final int waveLifetime;
        private final int debrisCount;
        private final int debrisSize;
        private final float debrisVelocity;
        private final float debrisHorizontalDeviation;
        private final Random random;
        private final List<BlastCloud> clouds = new ArrayList<>();
        private final List<DebrisChunk> debrisChunks = new ArrayList<>();
        private int age;
        private boolean soundPlayed;

        private ExplosionEffect(S2CNuclearVisualEffect message, Preset preset, LocalPlayer player) {
            center = new Vec3(message.x(), message.y(), message.z());
            this.preset = preset;
            scale = Math.max(0.1F, message.visualScale());
            density = Mth.clamp(message.visualDensity(), 0.1F, 1.0F);
            soundEnabled = message.sound();
            random = new Random(message.seed());
            // 音域线性缩放，与桥接层 bombSoundRange（350×scale）同式同夹取（文档 7.1）
            soundRange = preset == Preset.BOMB
                    ? Mth.clamp(350.0F * scale, 80.0F, 800.0F)
                    : Mth.clamp(200.0F * scale, 60.0F, 600.0F);
            double distance = player == null ? 0.0D : player.position().distanceTo(center);
            soundDelayTicks = Math.max(0, (int) (distance / LEGACY_SPEED_OF_SOUND));
            soundPlayed = player != null && distance > soundRange;
            // 冲击波半径放大 1.5 倍（97.5×scale，钳制 12..330，与桥接层 bombWaveScale 同式同步，2026-09-03）
            waveScale = preset == Preset.BOMB ? Mth.clamp(97.5F * scale, 12.0F, 330.0F) : 0.0F;
            waveLifetime = preset == Preset.BOMB
                    ? Math.max(1, Math.round(25.0F * waveScale / 45.0F)) : 1;
            // bomb 档碎块数同时受 scale×density 钳制（任务2，与桥接层 bombDebrisCount 同式同步）：
            // round(25×scale×density)，下限 2 避免为 0；烟团数 30×density、shell 碎屑 15×density 仍只乘 density。
            debrisCount = preset == Preset.BOMB
                    ? Mth.clamp(Math.round(25.0F * scale * density), 2, 160)
                    : Mth.clamp(Math.round(15.0F * density), 0, 120);
            if (preset == Preset.BOMB) {
                debrisSize = Mth.clamp(Math.round(16.0F * scale), 4, 64);
                // 保留 sqrt：碎块为固定重力弹道，射程 ∝ v²/g，sqrt 才能等比缩小抛物线
                debrisVelocity = Mth.clamp(1.25F * (float) Math.sqrt(scale), 0.2F, 4.0F);
                debrisHorizontalDeviation = Mth.clamp(3.0F * scale, 0.5F, 12.0F);
            } else {
                debrisSize = 0;
                debrisVelocity = 0.0F;
                debrisHorizontalDeviation = 0.0F;
            }
        }

        private void spawn(ClientLevel level) {
            // vanilla EXPLOSION 闪光只保留给 shell 档（对齐原版 explosionSmall）；
            // bomb 档的这发闪光是 RVP 特有偏差，按文档 7.4 移除。
            if (preset == Preset.SHELL) {
                level.addParticle(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 0.0D, 0.0D, 0.0D);
            }
            int cloudCount = preset == Preset.BOMB
                    ? Mth.clamp(Math.round(30.0F * density), 8, 180)
                    : Mth.clamp(Math.round(10.0F * density), 4, 80);
            float densityScale = Mth.clamp((float) Math.sqrt(1.0F / density), 1.0F, 1.35F);
            float cloudScale = preset == Preset.BOMB
                    ? Mth.clamp(6.5F * scale, 1.0F, 32.0F)
                    : Mth.clamp(2.0F * scale, 0.4F, 16.0F);
            // 速度线性缩放：与 cloudScale 同步缩，烟柱高宽比恒 ≈4.4（原 sqrt 公式会拉细烟柱，文档 7.1）
            float speed = preset == Preset.BOMB
                    ? Mth.clamp(2.0F * scale, 0.35F, 6.0F)
                    : Mth.clamp(0.5F * scale, 0.15F, 4.0F);
            for (int i = 0; i < cloudCount; i++) {
                double motionX = random.nextGaussian() * (preset == Preset.BOMB ? 0.5D : 1.0D) * speed;
                double motionY = preset == Preset.BOMB ? random.nextDouble() * 3.0D * speed : 0.0D;
                double motionZ = random.nextGaussian() * (preset == Preset.BOMB ? 0.5D : 1.0D) * speed;
                int lifetime = preset == Preset.BOMB ? 70 + random.nextInt(20) : 25 + random.nextInt(10);
                if (SCALE_CLOUD_LIFETIME_BY_SQRT) {
                    lifetime = Math.max(10, Math.round(lifetime * (float) Math.sqrt(scale)));
                }
                clouds.add(new BlastCloud(center, motionX, motionY, motionZ,
                        cloudScale * densityScale, lifetime, preset, random));
            }
            spawnDebris(level);
        }

        private void spawnDebris(ClientLevel level) {
            if (preset != Preset.BOMB) {
                spawnShellDebris(level);
            } else {
                spawnBombDebris(level);
            }
        }

        /** shell 档保持原样的 vanilla 单方块碎屑（原版 explosionSmall 即 BlockDust，文档 §5）。 */
        private void spawnShellDebris(ClientLevel level) {
            BlockPos samplePos = BlockPos.containing(center.x, center.y - 0.1D, center.z);
            BlockState state = level.getBlockState(samplePos);
            for (int i = 0; i < 6 && state.isAir(); i++) {
                samplePos = samplePos.below();
                state = level.getBlockState(samplePos);
            }
            if (state.isAir()) {
                state = Blocks.DIRT.defaultBlockState();
            }
            for (int i = 0; i < debrisCount; i++) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        center.x, center.y + 0.1D, center.z,
                        random.nextGaussian() * 0.2D,
                        0.5D + random.nextDouble() * 0.7D,
                        random.nextGaussian() * 0.2D);
            }
        }

        /** bomb 档立体碎块：debrisSize³ 真实方块簇高抛翻滚（文档 7.3）。 */
        private void spawnBombDebris(ClientLevel level) {
            if (debrisSize <= 0 || debrisCount <= 0) {
                return;
            }
            // 性能护栏：单爆总体积上限 + 全局同屏碎块数上限（文档 7.3）
            long cellsPerChunk = (long) debrisSize * debrisSize * debrisSize;
            int count = (int) Math.min(debrisCount, MAX_DEBRIS_CELLS_PER_EXPLOSION / cellsPerChunk);
            count = Math.min(count, MAX_ACTIVE_DEBRIS_CHUNKS - countActiveDebrisChunks());
            if (count <= 0) {
                return;
            }
            LocalPlayer player = Minecraft.getInstance().player;
            double distanceSq = player == null ? 0.0D
                    : player.distanceToSqr(center.x, center.y, center.z);
            // 远景降级：超出烟云同款 LOD 距离后不再做簇采样，仅渲染占位立方体
            boolean placeholder = distanceSq > LOD_DISTANCE_SQ;
            for (int i = 0; i < count; i++) {
                double offsetX = random.nextGaussian() * debrisHorizontalDeviation;
                double offsetZ = random.nextGaussian() * debrisHorizontalDeviation;
                double sampleX = center.x + offsetX;
                double sampleY = center.y + DEBRIS_VERTICAL_OFFSET;
                double sampleZ = center.z + offsetZ;
                boolean trail = i % DEBRIS_TRAIL_EVERY == 0;
                DebrisChunk chunk = placeholder
                        ? DebrisChunk.placeholder(center, debrisSize,
                                samplePlaceholderState(level, sampleX, sampleY, sampleZ),
                                debrisVelocity, random, trail)
                        : DebrisChunk.cluster(center, debrisSize,
                                DebrisChunk.sampleClusterStates(level, sampleX, sampleY, sampleZ,
                                        debrisSize, random),
                                debrisVelocity, random, trail);
                debrisChunks.add(chunk);
            }
        }

        private static BlockState samplePlaceholderState(ClientLevel level, double x, double y, double z) {
            BlockPos pos = BlockPos.containing(x, y, z);
            BlockState state = level.getBlockState(pos);
            for (int i = 0; i < 6 && state.isAir(); i++) {
                pos = pos.below();
                state = level.getBlockState(pos);
            }
            return state.isAir() ? Blocks.DIRT.defaultBlockState() : state;
        }

        private static int countActiveDebrisChunks() {
            int total = 0;
            for (ExplosionEffect effect : EFFECTS) {
                total += effect.debrisChunks.size();
            }
            return total;
        }

        private void tick(ClientLevel level, LocalPlayer player) {
            age++;
            // 碎块先 tick：它会给 clouds 追加烟尾，必须在下方 clouds 迭代前完成，避免 CME
            for (DebrisChunk chunk : debrisChunks) {
                chunk.tick(level, this, random);
            }
            debrisChunks.removeIf(DebrisChunk::isDead);
            for (BlastCloud cloud : clouds) {
                cloud.tick();
            }
            clouds.removeIf(BlastCloud::dead);
            if (!soundEnabled || soundPlayed || age < soundDelayTicks) {
                return;
            }
            double distance = player == null ? 0.0D : player.position().distanceTo(center);
            if (distance <= soundRange) {
                boolean near = distance <= soundRange * 0.4D;
                SoundEvent sound = preset == Preset.BOMB
                        ? (near ? RVP_Sounds.EXPLOSION_LARGE_NEAR.get() : RVP_Sounds.EXPLOSION_LARGE_FAR.get())
                        : (near ? RVP_Sounds.EXPLOSION_SMALL_NEAR.get() : RVP_Sounds.EXPLOSION_SMALL_FAR.get());
                level.playLocalSound(center.x, center.y, center.z, sound, SoundSource.BLOCKS,
                        preset == Preset.BOMB ? 1_000.0F : 100.0F,
                        0.9F + random.nextFloat() * 0.2F, false);
            }
            soundPlayed = true;
        }

        private boolean dead() {
            return clouds.isEmpty() && debrisChunks.isEmpty() && age > Math.max(waveLifetime, soundDelayTicks + 1);
        }
    }

    private static final class BlastCloud {
        private final Preset preset;
        private final float baseScale;
        private final int lifetime;
        private final float hue;
        private final float rise;
        private final float[] layerAdd;
        private final float[] layerScale;
        private final float[] layerOffsetX;
        private final float[] layerOffsetY;
        private final float[] layerOffsetZ;
        private double x;
        private double y;
        private double z;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double motionX;
        private double motionY;
        private double motionZ;
        private int age;

        private BlastCloud(Vec3 center, double motionX, double motionY, double motionZ,
                float baseScale, int lifetime, Preset preset, Random random) {
            x = previousX = center.x;
            y = previousY = center.y;
            z = previousZ = center.z;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.baseScale = baseScale;
            this.lifetime = lifetime;
            this.preset = preset;
            hue = 20.0F + random.nextFloat() * 20.0F;
            rise = random.nextFloat() * 0.01F;
            int layers = preset == Preset.BOMB ? 10 : 0;
            layerAdd = new float[layers];
            layerScale = new float[layers];
            layerOffsetX = new float[layers];
            layerOffsetY = new float[layers];
            layerOffsetZ = new float[layers];
            for (int i = 0; i < layers; i++) {
                layerAdd[i] = random.nextFloat() * 0.3F;
                layerScale[i] = random.nextFloat();
                layerOffsetX[i] = (float) ((random.nextGaussian() - 1.0D) * 0.2D);
                layerOffsetY[i] = (float) ((random.nextGaussian() - 1.0D) * 0.5D);
                layerOffsetZ[i] = (float) ((random.nextGaussian() - 1.0D) * 0.2D);
            }
        }

        private void tick() {
            previousX = x;
            previousY = y;
            previousZ = z;
            age++;
            if (preset == Preset.SHELL) {
                motionX *= 0.65D;
                motionZ *= 0.65D;
                motionY += rise;
            } else {
                motionX *= 0.91D;
                motionY *= 0.91D;
                motionZ *= 0.91D;
            }
            x += motionX;
            y += motionY;
            z += motionZ;
        }

        private float progress(float partialTick) {
            return Mth.clamp((age + partialTick) / lifetime, 0.0F, 1.0F);
        }

        private boolean dead() {
            return age >= lifetime;
        }
    }

    private record DebrisCell(int x, int y, int z, TextureAtlasSprite sprite, int visibleFaceMask) {}

    /**
     * 立体碎块：复刻原版 WorldInAJar 簇采样 + ParticleDebris 物理的自研实现。
     * 物理对齐原版：初速 ×3（45°–70° 高抛）、重力 0.15/tick、寿命 100 tick、
     * 每 tick 恒定 ±10° 双轴翻滚、前 5 tick 无碰撞、落地即消失、每 3 个 1 个沿路烟尾。
     * 渲染为方块图集立方体 + 六向面剔除（结构对齐重生 LegacyDebrisParticle，但零依赖）。
     */
    private static final class DebrisChunk {
        private static final int FACE_NEG_X = 1;
        private static final int FACE_POS_X = 1 << 1;
        private static final int FACE_NEG_Y = 1 << 2;
        private static final int FACE_POS_Y = 1 << 3;
        private static final int FACE_NEG_Z = 1 << 4;
        private static final int FACE_POS_Z = 1 << 5;
        private static final int ALL_FACES = FACE_NEG_X | FACE_POS_X | FACE_NEG_Y
                | FACE_POS_Y | FACE_NEG_Z | FACE_POS_Z;
        // 六面亮度近似（原版方块模型着色：顶 1.0 / 南北 0.8 / 东西 0.6 / 底 0.5）
        private static final float SHADE_X = 0.6F;
        private static final float SHADE_Z = 0.8F;
        private static final float SHADE_UP = 1.0F;
        private static final float SHADE_DOWN = 0.5F;

        private final int size;
        private final float half;
        private final DebrisCell[] cells;
        private final TextureAtlasSprite placeholderSprite;
        private final boolean trail;
        private final float trailScale;
        private final float pitchStep;
        private final float yawStep;
        private double x;
        private double y;
        private double z;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double motionX;
        private double motionY;
        private double motionZ;
        private float pitch;
        private float previousPitch;
        private float yaw;
        private float previousYaw;
        private int age;
        private boolean dead;

        private DebrisChunk(Vec3 origin, int size, DebrisCell[] cells, TextureAtlasSprite placeholderSprite,
                double debrisVelocity, Random random, boolean trail) {
            this.size = Math.max(1, size);
            this.half = this.size * 0.5F;
            this.cells = cells;
            this.placeholderSprite = placeholderSprite;
            this.trail = trail;
            // 原版烟尾尺度 setScale(1F * max(sizeY, 6) / 16F)
            this.trailScale = Math.max(this.size, 6) / 16.0F;
            this.pitchStep = random.nextFloat() * DEBRIS_TUMBLE_DEGREES;
            this.yawStep = random.nextFloat() * DEBRIS_TUMBLE_DEGREES;
            x = previousX = origin.x;
            y = previousY = origin.y;
            z = previousZ = origin.z;
            // 原版：从 +X 轴绕 Z 抬升 45°–70° 再绕 Y 均匀散布方位（高抛），最后初速 ×3
            double elevation = Math.toRadians(45.0D + random.nextFloat() * 25.0D);
            double azimuth = random.nextDouble() * Math.PI * 2.0D;
            double horizontal = debrisVelocity * Math.cos(elevation);
            motionX = horizontal * Math.cos(azimuth) * DEBRIS_INITIAL_SPEED_MULT;
            motionZ = -horizontal * Math.sin(azimuth) * DEBRIS_INITIAL_SPEED_MULT;
            motionY = debrisVelocity * Math.sin(elevation) * DEBRIS_INITIAL_SPEED_MULT;
        }

        private static DebrisChunk cluster(Vec3 origin, int size, BlockState[] states,
                double debrisVelocity, Random random, boolean trail) {
            return new DebrisChunk(origin, size, makeCells(states, size), null, debrisVelocity, random, trail);
        }

        private static DebrisChunk placeholder(Vec3 origin, int size, BlockState state,
                double debrisVelocity, Random random, boolean trail) {
            return new DebrisChunk(origin, size, new DebrisCell[0], particleIcon(state),
                    debrisVelocity, random, trail);
        }

        /**
         * 复刻原版 WorldInAJar 簇采样（与重生 sampleLegacyDebrisStates 同构）：
         * 2×2×2 种子起步 → 逐层固定 50 次随机尝试 → 六向邻居被占用才写入。
         * 保留原版"抓到空气也填入"的行为：空气照样写入簇中（渲染时跳过），但不算占用，
         * 簇不会穿过空气生长——与原版 jar 判定完全一致。
         */
        private static BlockState[] sampleClusterStates(ClientLevel level, double sampleX, double sampleY,
                double sampleZ, int size, Random random) {
            BlockState[] states = new BlockState[size * size * size];
            boolean[] occupied = new boolean[size * size * size];
            int centerX = Mth.floor(sampleX + 0.5D);
            int centerY = Mth.floor(sampleY + 0.5D);
            int centerZ = Mth.floor(sampleZ + 0.5D);
            int middle = size / 2 - 1;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        BlockState state = level.getBlockState(pos.set(centerX + ix, centerY + iy, centerZ + iz));
                        int index = debrisIndex(size, middle + ix, middle + iy, middle + iz);
                        states[index] = state;
                        occupied[index] = !state.isAir();
                    }
                }
            }

            for (int layer = 2; layer <= size / 2; layer++) {
                for (int attempt = 0; attempt < DEBRIS_RETRY_ATTEMPTS; attempt++) {
                    int offsetX = -layer + random.nextInt(layer * 2 + 1);
                    int offsetY = -layer + random.nextInt(layer * 2 + 1);
                    int offsetZ = -layer + random.nextInt(layer * 2 + 1);
                    int localX = middle + offsetX;
                    int localY = middle + offsetY;
                    int localZ = middle + offsetZ;
                    if (!debrisInBounds(size, localX, localY, localZ)
                            || !hasOccupiedNeighbor(occupied, size, localX, localY, localZ)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(
                            pos.set(centerX + offsetX, centerY + offsetY, centerZ + offsetZ));
                    int index = debrisIndex(size, localX, localY, localZ);
                    states[index] = state;
                    occupied[index] = !state.isAir();
                }
            }
            return states;
        }

        private static DebrisCell[] makeCells(BlockState[] states, int size) {
            int capacity = Math.min(states.length, size * size * size);
            DebrisCell[] cells = new DebrisCell[capacity];
            int count = 0;
            for (int localX = 0; localX < size; localX++) {
                for (int localY = 0; localY < size; localY++) {
                    for (int localZ = 0; localZ < size; localZ++) {
                        int index = debrisIndex(size, localX, localY, localZ);
                        if (index < 0 || index >= states.length) {
                            continue;
                        }
                        BlockState state = states[index];
                        if (state == null || state.isAir()) {
                            continue;
                        }
                        cells[count++] = new DebrisCell(localX, localY, localZ, particleIcon(state),
                                visibleFaceMask(states, size, localX, localY, localZ, state));
                    }
                }
            }
            return count == cells.length ? cells : Arrays.copyOf(cells, count);
        }

        private static TextureAtlasSprite particleIcon(BlockState state) {
            return Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        }

        private static int visibleFaceMask(BlockState[] states, int size, int x, int y, int z, BlockState state) {
            if (!state.canOcclude()) {
                return ALL_FACES;
            }
            int mask = ALL_FACES;
            if (isOccludingCell(states, size, x - 1, y, z)) mask &= ~FACE_NEG_X;
            if (isOccludingCell(states, size, x + 1, y, z)) mask &= ~FACE_POS_X;
            if (isOccludingCell(states, size, x, y - 1, z)) mask &= ~FACE_NEG_Y;
            if (isOccludingCell(states, size, x, y + 1, z)) mask &= ~FACE_POS_Y;
            if (isOccludingCell(states, size, x, y, z - 1)) mask &= ~FACE_NEG_Z;
            if (isOccludingCell(states, size, x, y, z + 1)) mask &= ~FACE_POS_Z;
            return mask;
        }

        private static boolean isOccludingCell(BlockState[] states, int size, int x, int y, int z) {
            if (!debrisInBounds(size, x, y, z)) {
                return false;
            }
            int index = debrisIndex(size, x, y, z);
            if (index < 0 || index >= states.length) {
                return false;
            }
            BlockState neighbor = states[index];
            return neighbor != null && !neighbor.isAir() && neighbor.canOcclude();
        }

        private static boolean hasOccupiedNeighbor(boolean[] occupied, int size, int x, int y, int z) {
            return isOccupied(occupied, size, x + 1, y, z)
                    || isOccupied(occupied, size, x - 1, y, z)
                    || isOccupied(occupied, size, x, y + 1, z)
                    || isOccupied(occupied, size, x, y - 1, z)
                    || isOccupied(occupied, size, x, y, z + 1)
                    || isOccupied(occupied, size, x, y, z - 1);
        }

        private static boolean isOccupied(boolean[] occupied, int size, int x, int y, int z) {
            return debrisInBounds(size, x, y, z) && occupied[debrisIndex(size, x, y, z)];
        }

        private static boolean debrisInBounds(int size, int x, int y, int z) {
            return x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size;
        }

        private static int debrisIndex(int size, int x, int y, int z) {
            return (x * size + y) * size + z;
        }

        private void tick(ClientLevel level, ExplosionEffect effect, Random random) {
            previousX = x;
            previousY = y;
            previousZ = z;
            previousPitch = pitch;
            previousYaw = yaw;
            pitch += pitchStep;
            yaw += yawStep;
            if (trail) {
                effect.clouds.add(new BlastCloud(new Vec3(x, y, z), 0.0D, 0.0D, 0.0D,
                        trailScale, DEBRIS_TRAIL_LIFETIME, Preset.BOMB, random));
            }
            motionY -= DEBRIS_GRAVITY;
            double nextX = x + motionX;
            double nextY = y + motionY;
            double nextZ = z + motionZ;
            if (age >= DEBRIS_COLLISION_DELAY_TICKS && collidesWithTerrain(level, nextX, nextY, nextZ)) {
                dead = true;
                return;
            }
            x = nextX;
            y = nextY;
            z = nextZ;
            age++;
            if (age >= DEBRIS_LIFETIME_TICKS) {
                dead = true;
            }
        }

        private static boolean collidesWithTerrain(ClientLevel level, double x, double y, double z) {
            BlockPos pos = BlockPos.containing(x, y, z);
            return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        }

        private boolean isDead() {
            return dead;
        }

        private void render(BufferBuilder builder, Vec3 cameraPos, float partialTick, ClientLevel level) {
            float originX = (float) (Mth.lerp(partialTick, previousX, x) - cameraPos.x);
            float originY = (float) (Mth.lerp(partialTick, previousY, y) - cameraPos.y);
            float originZ = (float) (Mth.lerp(partialTick, previousZ, z) - cameraPos.z);
            // 原版 glRotated(pitch, 0,1,0) + glRotated(yaw, 0,0,1)
            float pitchNow = Mth.lerp(partialTick, previousPitch, pitch) * Mth.DEG_TO_RAD;
            float yawNow = Mth.lerp(partialTick, previousYaw, yaw) * Mth.DEG_TO_RAD;
            Quaternionf rotation = new Quaternionf().rotateY(pitchNow).rotateZ(yawNow);
            float brightness = debrisBrightness(level);
            if (placeholderSprite != null) {
                writeCube(builder, rotation, brightness, originX, originY, originZ,
                        -half, -half, -half, half, half, half, placeholderSprite, ALL_FACES);
                return;
            }
            for (DebrisCell cell : cells) {
                float x0 = cell.x() - half;
                float y0 = cell.y() - half;
                float z0 = cell.z() - half;
                writeCube(builder, rotation, brightness, originX, originY, originZ,
                        x0, y0, z0, x0 + 1.0F, y0 + 1.0F, z0 + 1.0F, cell.sprite(), cell.visibleFaceMask());
            }
        }

        /**
         * 光照近似：取簇中心的世界 packed light 做整体明暗调制（本管线无光照贴图通道），
         * 叠加六面方向着色近似原版 RenderBlocks AO 后的体积感。
         */
        private float debrisBrightness(ClientLevel level) {
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
            int blockLight = (light >> 4) & 0xF;
            int skyLight = (light >> 20) & 0xF;
            return Mth.clamp(Math.max(blockLight, skyLight) / 15.0F, 0.35F, 1.0F);
        }

        private void writeCube(BufferBuilder builder, Quaternionf rotation, float brightness,
                float originX, float originY, float originZ,
                float x0, float y0, float z0, float x1, float y1, float z1,
                TextureAtlasSprite sprite, int faceMask) {
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            // 四角逆时针（从面外侧看）绕序，法线朝外，配合背面剔除
            if ((faceMask & FACE_NEG_X) != 0) {
                writeFace(builder, rotation, brightness, SHADE_X, originX, originY, originZ, u0, u1, v0, v1,
                        x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            }
            if ((faceMask & FACE_POS_X) != 0) {
                writeFace(builder, rotation, brightness, SHADE_X, originX, originY, originZ, u0, u1, v0, v1,
                        x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
            }
            if ((faceMask & FACE_NEG_Y) != 0) {
                writeFace(builder, rotation, brightness, SHADE_DOWN, originX, originY, originZ, u0, u1, v0, v1,
                        x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
            }
            if ((faceMask & FACE_POS_Y) != 0) {
                writeFace(builder, rotation, brightness, SHADE_UP, originX, originY, originZ, u0, u1, v0, v1,
                        x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            }
            if ((faceMask & FACE_NEG_Z) != 0) {
                writeFace(builder, rotation, brightness, SHADE_Z, originX, originY, originZ, u0, u1, v0, v1,
                        x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            }
            if ((faceMask & FACE_POS_Z) != 0) {
                writeFace(builder, rotation, brightness, SHADE_Z, originX, originY, originZ, u0, u1, v0, v1,
                        x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            }
        }

        private static void writeFace(BufferBuilder builder, Quaternionf rotation, float brightness,
                float shade, float originX, float originY, float originZ,
                float u0, float u1, float v0, float v1,
                float ax, float ay, float az,
                float bx, float by, float bz,
                float cx, float cy, float cz,
                float dx, float dy, float dz) {
            // 底边两顶点取 v1（贴图底部），顶边两顶点取 v0，u 沿水平轴展开
            debrisVertex(builder, rotation, brightness, shade, originX, originY, originZ, ax, ay, az, u0, v1);
            debrisVertex(builder, rotation, brightness, shade, originX, originY, originZ, bx, by, bz, u1, v1);
            debrisVertex(builder, rotation, brightness, shade, originX, originY, originZ, cx, cy, cz, u1, v0);
            debrisVertex(builder, rotation, brightness, shade, originX, originY, originZ, dx, dy, dz, u0, v0);
        }

        private static void debrisVertex(BufferBuilder builder, Quaternionf rotation, float brightness,
                float shade, float originX, float originY, float originZ,
                float localX, float localY, float localZ, float u, float v) {
            Vector3f corner = new Vector3f(localX, localY, localZ).rotate(rotation);
            int color = Mth.clamp((int) (brightness * shade * 255.0F), 0, 255);
            builder.vertex(originX + corner.x, originY + corner.y, originZ + corner.z)
                    .uv(u, v)
                    .color(color, color, color, 255)
                    .endVertex();
        }
    }
}
