package org.ywzj.rvp.client.nuclear;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
    private static final RenderType CLOUD_RENDER_TYPE =
            RVP_RenderTypes.texturedTranslucentNoDepthWrite(CLOUD_TEXTURE);
    private static final RenderType WAVE_RENDER_TYPE =
            RVP_RenderTypes.texturedAdditiveNoDepthWrite(WAVE_TEXTURE);
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
        PoseStack poseStack = event.getPoseStack();

        // Extend projection matrix far plane so clouds are visible from thousands of blocks away
        RenderSystem.backupProjectionMatrix();
        double fov = minecraft.options.fov().get();
        Matrix4f extendedProjection = new Matrix4f().perspective(
                (float) (fov * Math.PI / 180.0),
                (float) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight(),
                0.05F,
                (float) FAR_PLANE
        );
        RenderSystem.setProjectionMatrix(extendedProjection, VertexSorting.DISTANCE_TO_ORIGIN);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        Quaternionf cameraRotation = camera.rotation();
        Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(cameraRotation);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(cameraRotation);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        rebuildRenderCache(cameraPos);
        VertexConsumer cloudConsumer = buffers.getBuffer(CLOUD_RENDER_TYPE);
        for (RenderCloud renderCloud : RENDER_CLOUDS) {
            renderCloud(cloudConsumer, pose, normal, right, up,
                    renderCloud.cloud(), event.getPartialTick(), renderCloud.distanceSq());
        }
        buffers.endBatch(CLOUD_RENDER_TYPE);

        VertexConsumer waveConsumer = buffers.getBuffer(WAVE_RENDER_TYPE);
        for (ExplosionEffect effect : EFFECTS) {
            renderWave(waveConsumer, pose, normal, effect, event.getPartialTick());
        }
        buffers.endBatch(WAVE_RENDER_TYPE);
        poseStack.popPose();

        // Restore original projection matrix
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

    private static void renderCloud(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            Vector3f right, Vector3f up, BlastCloud cloud, float partialTick, double distanceSq) {
        float progress = cloud.progress(partialTick);
        if (progress >= 1.0F) {
            return;
        }
        double x = Mth.lerp(partialTick, cloud.previousX, cloud.x);
        double y = Mth.lerp(partialTick, cloud.previousY, cloud.y);
        double z = Mth.lerp(partialTick, cloud.previousZ, cloud.z);
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
            billboard(consumer, pose, normal, right, up, (float) x, (float) y, (float) z,
                    size, red, green, blue, alpha);
            return;
        }

        float dark = 1.0F - Math.min(progress * 4.0F, 1.0F);
        float alpha = (float) Math.pow(1.0F - progress, 0.5D) * 0.75F;
        float spread = ((float) Math.pow(progress * 4.0F, 1.5D) + 1.0F) * cloud.baseScale;
        // LOD: at long distance, only render every 3rd layer with enlarged size
        int layerStep = lod ? 3 : 1;
        float lodSizeBoost = lod ? 2.0F : 1.0F;
        for (int i = 0; i < cloud.layerAdd.length; i += layerStep) {
            float add = cloud.layerAdd[i];
            float red = Mth.clamp(dark + add, 0.0F, 1.0F);
            float green = Mth.clamp(0.6F * dark + add, 0.0F, 1.0F);
            float blue = Mth.clamp(add, 0.0F, 1.0F);
            float size = (cloud.layerScale[i] * 0.5F + 0.1F + progress * 2.0F) * cloud.baseScale * lodSizeBoost;
            billboard(consumer, pose, normal, right, up,
                    (float) x + cloud.layerOffsetX[i] * spread,
                    (float) y + cloud.layerOffsetY[i] * spread,
                    (float) z + cloud.layerOffsetZ[i] * spread,
                    size, red, green, blue, alpha);
        }
    }

    private static void renderWave(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
            ExplosionEffect effect, float partialTick) {
        if (effect.preset != Preset.BOMB) {
            return;
        }
        float visualAge = effect.age + partialTick;
        float alpha = 1.0F - visualAge / effect.waveLifetime;
        if (alpha <= 0.0F) {
            return;
        }
        float scale = (float) (1.0D - Math.exp(visualAge * -0.125D)) * effect.waveScale;
        float x = (float) effect.center.x;
        float y = (float) effect.center.y + 1.75F;
        float z = (float) effect.center.z;
        vertex(consumer, pose, normal, x - scale, y, z - scale, 1.0F, 1.0F, alpha);
        vertex(consumer, pose, normal, x - scale, y, z + scale, 1.0F, 0.0F, alpha);
        vertex(consumer, pose, normal, x + scale, y, z + scale, 0.0F, 0.0F, alpha);
        vertex(consumer, pose, normal, x + scale, y, z - scale, 0.0F, 1.0F, alpha);
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
            float x, float y, float z, float u, float v, float alpha) {
        vertex(consumer, pose, normal, x, y, z, u, v, 1.0F, 1.0F, 1.0F, alpha);
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
