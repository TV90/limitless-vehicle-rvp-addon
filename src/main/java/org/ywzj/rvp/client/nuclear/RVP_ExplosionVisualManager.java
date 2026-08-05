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
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.network.S2CNuclearVisualEffect;

import java.util.ArrayList;
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

        // Extend projection matrix far plane so effects are visible from thousands of blocks away
        RenderSystem.backupProjectionMatrix();
        double fov = minecraft.options.fov().get();
        Matrix4f extendedProjection = new Matrix4f().perspective(
                (float) (fov * Math.PI / 180.0),
                (float) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight(),
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
        private final Random random;
        private final List<BlastCloud> clouds = new ArrayList<>();
        private int age;
        private boolean soundPlayed;

        private ExplosionEffect(S2CNuclearVisualEffect message, Preset preset, LocalPlayer player) {
            center = new Vec3(message.x(), message.y(), message.z());
            this.preset = preset;
            scale = Math.max(0.1F, message.visualScale());
            density = Mth.clamp(message.visualDensity(), 0.1F, 1.0F);
            soundEnabled = message.sound();
            random = new Random(message.seed());
            soundRange = preset == Preset.BOMB
                    ? Mth.clamp(350.0F * (float) Math.sqrt(scale), 80.0F, 1_200.0F)
                    : Mth.clamp(200.0F * (float) Math.sqrt(scale), 60.0F, 600.0F);
            double distance = player == null ? 0.0D : player.position().distanceTo(center);
            soundDelayTicks = Math.max(0, (int) (distance / LEGACY_SPEED_OF_SOUND));
            soundPlayed = player != null && distance > soundRange;
            waveScale = preset == Preset.BOMB ? Mth.clamp(65.0F * scale, 8.0F, 220.0F) : 0.0F;
            waveLifetime = preset == Preset.BOMB
                    ? Math.max(1, Math.round(25.0F * waveScale / 45.0F)) : 1;
            debrisCount = preset == Preset.BOMB
                    ? Mth.clamp(Math.round(25.0F * scale * density), 2, 160)
                    : Mth.clamp(Math.round(15.0F * scale * density), 0, 120);
        }

        private void spawn(ClientLevel level) {
            level.addParticle(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 0.0D, 0.0D, 0.0D);
            int baseCloudCount = preset == Preset.BOMB
                    ? Mth.clamp(Math.round(30.0F * scale), 8, 180)
                    : Mth.clamp(Math.round(10.0F * scale), 4, 80);
            int cloudCount = Math.max(1, Math.round(baseCloudCount * density));
            float densityScale = Mth.clamp((float) Math.sqrt(1.0F / density), 1.0F, 1.35F);
            float cloudScale = preset == Preset.BOMB
                    ? Mth.clamp(6.5F * scale, 1.0F, 32.0F)
                    : Mth.clamp(2.0F * scale, 0.4F, 16.0F);
            float speed = preset == Preset.BOMB
                    ? Mth.clamp(2.0F * (float) Math.sqrt(scale), 0.35F, 6.0F)
                    : Mth.clamp(0.5F * (float) Math.sqrt(scale), 0.15F, 4.0F);
            for (int i = 0; i < cloudCount; i++) {
                double motionX = random.nextGaussian() * (preset == Preset.BOMB ? 0.5D : 1.0D) * speed;
                double motionY = preset == Preset.BOMB ? random.nextDouble() * 3.0D * speed : 0.0D;
                double motionZ = random.nextGaussian() * (preset == Preset.BOMB ? 0.5D : 1.0D) * speed;
                int lifetime = preset == Preset.BOMB ? 70 + random.nextInt(20) : 25 + random.nextInt(10);
                clouds.add(new BlastCloud(center, motionX, motionY, motionZ,
                        cloudScale * densityScale, lifetime, preset, random));
            }
            spawnDebris(level);
        }

        private void spawnDebris(ClientLevel level) {
            BlockPos samplePos = BlockPos.containing(center.x, center.y - 0.1D, center.z);
            BlockState state = level.getBlockState(samplePos);
            for (int i = 0; i < 6 && state.isAir(); i++) {
                samplePos = samplePos.below();
                state = level.getBlockState(samplePos);
            }
            if (state.isAir()) {
                state = Blocks.DIRT.defaultBlockState();
            }
            int particlesPerChunk = preset == Preset.BOMB ? 4 : 1;
            for (int i = 0; i < debrisCount * particlesPerChunk; i++) {
                double horizontal = preset == Preset.BOMB ? 0.55D : 0.2D;
                double vertical = preset == Preset.BOMB
                        ? 0.8D + random.nextDouble() * 1.8D
                        : 0.5D + random.nextDouble() * 0.7D;
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        center.x, center.y + 0.1D, center.z,
                        random.nextGaussian() * horizontal, vertical,
                        random.nextGaussian() * horizontal);
            }
        }

        private void tick(ClientLevel level, LocalPlayer player) {
            age++;
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
            return clouds.isEmpty() && age > Math.max(waveLifetime, soundDelayTicks + 1);
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
}
