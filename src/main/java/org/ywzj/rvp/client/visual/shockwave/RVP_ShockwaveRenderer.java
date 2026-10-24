package org.ywzj.rvp.client.visual.shockwave;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 新视觉工厂的屏幕折射冲击波；着色器供核爆复用，核爆实例与参数独立管理。 */
public final class RVP_ShockwaveRenderer {

    /** 复用已存在的 warp_world 着色器资源。 */
    private static final ResourceLocation SHADER = RVP_MOD.modLocation("warp_world");
    /** 新效果淡出曲线的标准化时间长度（tick）。 */
    private static final int LIFETIME = 220;
    /** 新效果标准化曲线的淡出起始年龄（tick）。 */
    private static final int FADE_START = 100;
    /** 共享的单位球壳顶点缓存。 */
    private static final float[] SPHERE = RVP_ShockwaveMesh.buildSphereMesh();
    /** 当前世界活动球壳，集中绘制。 */
    private static final List<Shockwave> ACTIVE = new ArrayList<>();

    /** 已加载着色器。 */
    private static ShaderInstance shader;
    /** 着色器用途参数。 */
    private static Uniform useTypeUniform;
    /** 着色器动画时间。 */
    private static Uniform timeUniform;
    /** 扭曲强度参数。 */
    private static Uniform intensityUniform;
    /** 当前帧场景颜色副本，全部球壳共用。 */
    private static TextureTarget sceneCopy;

    /** 新工厂球壳所属世界，不参与核爆的初始化与清理。 */
    private static net.minecraft.client.multiplayer.ClientLevel activeLevel;

    private RVP_ShockwaveRenderer() {}

    private static void ensureLevel() {
        if (activeLevel != Minecraft.getInstance().level) {
            clear();
            activeLevel = Minecraft.getInstance().level;
        }
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SHADER,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), loaded -> {
            shader = loaded;
            useTypeUniform = loaded.getUniform("useType");
            timeUniform = loaded.getUniform("time");
            intensityUniform = loaded.getUniform("intensity");
        });
    }

    /** 返回可取消的球壳句柄，调用方关闭效果时必须移除。 */
    public static Shockwave spawn(double x, double y, double z, int initialAge,
            int duration, float radiusPerTick) {
        ensureLevel();
        if (initialAge >= duration || radiusPerTick <= 0) {
            return null;
        }
        // 新工厂球壳设硬上限；不会因连续引爆无限积累场景绘制。
        if (ACTIVE.size() >= 8) {
            ACTIVE.remove(0);
        }
        Shockwave wave = new Shockwave(x, y, z, Math.max(0, initialAge), duration, radiusPerTick);
        ACTIVE.add(wave);
        return wave;
    }

    /** 返回统一注册的着色器资源；调用方自行设置参数，不借此共享实例状态。 */
    @javax.annotation.Nullable
    public static ShaderInstance shader() {
        return shader;
    }

    /** 取消单个新工厂效果，不干扰其它冲击波。 */
    public static void remove(Shockwave wave) {
        ACTIVE.remove(wave);
    }

    public static void tick() {
        ensureLevel();
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            if (++ACTIVE.get(i).age >= ACTIVE.get(i).duration) {
                ACTIVE.remove(i);
            }
        }
    }

    public static void clear() {
        ACTIVE.clear();
        activeLevel = null;
        if (sceneCopy != null) {
            sceneCopy.destroyBuffers();
            sceneCopy = null;
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (ACTIVE.isEmpty() || shader == null
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        ensureSceneCopy(mainTarget);
        copyMainTarget(mainTarget);

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.getPartialTick();

        RenderSystem.backupProjectionMatrix();
        // 保留原 FOV、缩放与抖动，仅延长远裁剪面至一万格。
        org.joml.Matrix4f projection = new org.joml.Matrix4f(event.getProjectionMatrix());
        projection.m22(-(10000.0F + 0.05F) / (10000.0F - 0.05F));
        projection.m32(-(2 * 10000.0F * 0.05F) / (10000.0F - 0.05F));
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            modelView.setIdentity();
            modelView.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            modelView.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
            RenderSystem.applyModelViewMatrix();

            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.enableCull();
            RenderSystem.setShader(() -> shader);
            shader.setSampler("ScreenTexture", sceneCopy);
            setUniform(useTypeUniform, 1);
            setUniform(timeUniform, minecraft.level.getGameTime() + partialTick);

            Tesselator tesselator = Tesselator.getInstance();
            for (Shockwave shockwave : ACTIVE) {
                float visualAge = shockwave.age + partialTick;
                float alpha = alpha(visualAge * LIFETIME / shockwave.duration);
                if (alpha <= 0.0F) {
                    continue;
                }
                setUniform(intensityUniform, alpha);
                BufferBuilder builder = tesselator.getBuilder();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
                buildSphere(builder,
                        (float) (shockwave.x - cameraPos.x),
                        (float) (shockwave.y - cameraPos.y),
                        (float) (shockwave.z - cameraPos.z),
                        visualAge * shockwave.radiusPerTick + 1.0F,
                        Mth.clamp((int) (alpha * 255.0F), 0, 255));
                BufferUploader.drawWithShader(builder.end());
            }
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            mainTarget.bindWrite(false);
        }
    }

    private static float alpha(float age) {
        if (age <= FADE_START) {
            return 1.0F;
        }
        float progress = Mth.clamp((age - FADE_START) / (LIFETIME - FADE_START), 0.0F, 1.0F);
        float remaining = 1.0F - progress;
        return remaining * remaining * remaining;
    }

    private static void ensureSceneCopy(RenderTarget mainTarget) {
        if (sceneCopy == null) {
            sceneCopy = new TextureTarget(mainTarget.width, mainTarget.height, false, Minecraft.ON_OSX);
        } else if (sceneCopy.width != mainTarget.width || sceneCopy.height != mainTarget.height) {
            sceneCopy.resize(mainTarget.width, mainTarget.height, Minecraft.ON_OSX);
        }
    }

    private static void copyMainTarget(RenderTarget mainTarget) {
        GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, sceneCopy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, mainTarget.width, mainTarget.height,
                0, 0, sceneCopy.width, sceneCopy.height,
                GlConst.GL_COLOR_BUFFER_BIT, GlConst.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, mainTarget.frameBufferId);
        RenderSystem.viewport(0, 0, mainTarget.viewWidth, mainTarget.viewHeight);
    }

    private static void buildSphere(BufferBuilder builder, float centerX, float centerY,
            float centerZ, float radius, int alpha) {
        for (int offset = 0; offset < SPHERE.length; offset += 5) {
            float nx = SPHERE[offset];
            float ny = SPHERE[offset + 1];
            float nz = SPHERE[offset + 2];
            builder.vertex(centerX + nx * radius, centerY + ny * radius, centerZ + nz * radius)
                    .uv(SPHERE[offset + 3], SPHERE[offset + 4])
                    .color(255, 255, 255, alpha)
                    .normal(nx, ny, nz)
                    .endVertex();
        }
    }

    private static void setUniform(Uniform uniform, int value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform(Uniform uniform, float value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    public static final class Shockwave {
        /** 球心 X（格）。 */
        private final double x;
        /** 球心 Y（格）。 */
        private final double y;
        /** 球心 Z（格）。 */
        private final double z;
        /** 球壳年龄（tick）。 */
        private int age;
        /** 此球壳的寿命（tick）。 */
        private final int duration;
        /** 每 tick 扩张半径（格）。 */
        private final float radiusPerTick;

        private Shockwave(double x, double y, double z, int age, int duration, float radiusPerTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.age = age;
            this.duration = duration;
            this.radiusPerTick = radiusPerTick;
        }
    }
}
