package org.ywzj.rvp.client.nuclear;

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

/** Screen-space refractive sphere used by the standalone nuclear backend. */
public final class RVP_NuclearShockwaveRenderer {

    private static final ResourceLocation SHADER = RVP_MOD.modLocation("warp_world");
    private static final int RINGS = 16;
    private static final int SEGMENTS = 48;
    private static final int LIFETIME = 220;
    private static final int FADE_START = 100;
    private static final float RADIUS_PER_TICK = 2.25F;
    private static final float[] SPHERE = buildSphereMesh();
    private static final List<Shockwave> ACTIVE = new ArrayList<>();

    private static ShaderInstance shader;
    private static Uniform useTypeUniform;
    private static Uniform timeUniform;
    private static Uniform intensityUniform;
    private static TextureTarget sceneCopy;

    private RVP_NuclearShockwaveRenderer() {}

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SHADER,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), loaded -> {
            shader = loaded;
            useTypeUniform = loaded.getUniform("useType");
            timeUniform = loaded.getUniform("time");
            intensityUniform = loaded.getUniform("intensity");
        });
    }

    public static void spawn(double x, double y, double z, int initialAge) {
        if (initialAge < LIFETIME) {
            ACTIVE.add(new Shockwave(x, y, z, Math.max(0, initialAge)));
        }
    }

    public static void tick() {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            if (ACTIVE.get(i).age++ >= LIFETIME) {
                ACTIVE.remove(i);
            }
        }
    }

    public static void clear() {
        ACTIVE.clear();
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
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        modelView.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        RenderSystem.setShader(() -> shader);
        shader.setSampler("ScreenTexture", sceneCopy);
        setUniform(useTypeUniform, 1);
        setUniform(timeUniform, minecraft.level.getGameTime() + partialTick);

        Tesselator tesselator = Tesselator.getInstance();
        for (Shockwave shockwave : ACTIVE) {
            float visualAge = shockwave.age + partialTick;
            float alpha = alpha(visualAge);
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
                    visualAge * RADIUS_PER_TICK + 1.0F,
                    Mth.clamp((int) (alpha * 255.0F), 0, 255));
            BufferUploader.drawWithShader(builder.end());
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
        mainTarget.bindWrite(false);
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

    private static float[] buildSphereMesh() {
        float[] vertices = new float[RINGS * SEGMENTS * 4 * 5];
        int offset = 0;
        for (int ring = 0; ring < RINGS; ring++) {
            float v0 = ring / (float) RINGS;
            float v1 = (ring + 1) / (float) RINGS;
            float theta0 = (float) (Math.PI * v0);
            float theta1 = (float) (Math.PI * v1);
            for (int segment = 0; segment < SEGMENTS; segment++) {
                float u0 = segment / (float) SEGMENTS;
                float u1 = (segment + 1) / (float) SEGMENTS;
                float phi0 = (float) (Math.PI * 2.0D * u0);
                float phi1 = (float) (Math.PI * 2.0D * u1);
                offset = putVertex(vertices, offset, theta0, phi0, u0, v0);
                offset = putVertex(vertices, offset, theta1, phi0, u0, v1);
                offset = putVertex(vertices, offset, theta1, phi1, u1, v1);
                offset = putVertex(vertices, offset, theta0, phi1, u1, v0);
            }
        }
        return vertices;
    }

    private static int putVertex(float[] vertices, int offset,
            float theta, float phi, float u, float v) {
        float sinTheta = Mth.sin(theta);
        vertices[offset++] = sinTheta * Mth.cos(phi);
        vertices[offset++] = Mth.cos(theta);
        vertices[offset++] = sinTheta * Mth.sin(phi);
        vertices[offset++] = u;
        vertices[offset++] = v;
        return offset;
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

    private static final class Shockwave {
        private final double x;
        private final double y;
        private final double z;
        private int age;

        private Shockwave(double x, double y, double z, int age) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.age = age;
        }
    }
}
