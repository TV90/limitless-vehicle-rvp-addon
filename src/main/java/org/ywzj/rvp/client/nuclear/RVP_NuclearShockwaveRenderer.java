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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.ywzj.rvp.client.visual.shake.RVP_ShockwaveScreenShake;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveRenderer;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveMesh;

import java.util.ArrayList;
import java.util.List;

/**
 * HBM 核爆专用折射球壳，保留改造前的参数、生命周期及绘制顺序。
 * 与威尔逊云仅复用无状态网格和已注册的着色器，不共享实例上限或远距投影。
 */
public final class RVP_NuclearShockwaveRenderer {

    /** 原核爆球壳寿命（tick），不随威尔逊云时长改变。 */
    private static final int LIFETIME = 220;
    /** 原核爆淡出开始时刻（tick）。 */
    private static final int FADE_START = 100;
    /** 原核爆球壳扩张速度（格/tick）。 */
    private static final float RADIUS_PER_TICK = 2.25F;
    /** 原 16×48 分段单位球壳，调用共享网格生成器保持相同顶点。 */
    private static final float[] SPHERE = RVP_ShockwaveMesh.buildSphereMesh();
    /** 仅核爆使用的实例列表，不参与新效果的预算淘汰。 */
    private static final List<Shockwave> ACTIVE = new ArrayList<>();

    /** 核爆原绘制阶段的场景副本，不与新效果交叉清理。 */
    private static TextureTarget sceneCopy;

    private RVP_NuclearShockwaveRenderer() {}

    public static void spawn(double x, double y, double z, int initialAge, long seed) {
        if (initialAge < LIFETIME) {
            ACTIVE.add(new Shockwave(x, y, z, Math.max(0, initialAge), seed));
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

    /** 核爆沿原球壳扩张速度检查玩家抵达，不改动原核爆震动或声音。 */
    public static void updateScreenShake(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { return; }
        Vec3 playerPosition = minecraft.player.getPosition((float) event.getPartialTick());
        double now = minecraft.level.getGameTime() + event.getPartialTick();
        for (Shockwave wave : ACTIVE) {
            float age = wave.age + (float) event.getPartialTick();
            if (age >= LIFETIME) { continue; }
            // 调用与核爆球壳绘制共用的半径公式，按实际玩家位置触发一次，并按最大范围衰减。
            wave.screenShake.update(now, playerPosition.distanceTo(new Vec3(wave.x, wave.y, wave.z)),
                    radius(age), radius(LIFETIME));
        }
    }

    /** 核爆球壳的实际外半径（格）；保留原有初始 1 格与每刻 2.25 格的扩张速度。 */
    private static float radius(float age) { return age * RADIUS_PER_TICK + 1.0F; }

    public static void render(RenderLevelStageEvent event) {
        // 调用共享资源入口取得着色器，避免重复注册同名 shader；状态与参数仍归核爆持有。
        ShaderInstance shader = RVP_ShockwaveRenderer.shader();
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
        Uniform useTypeUniform = shader.getUniform("useType");
        Uniform timeUniform = shader.getUniform("time");
        Uniform intensityUniform = shader.getUniform("intensity");
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
            // 调用共用半径公式绘制核爆球壳，使相机抵达判断与眼前波面保持同一速度。
            buildSphere(builder,
                    (float) (shockwave.x - cameraPos.x),
                    (float) (shockwave.y - cameraPos.y),
                    (float) (shockwave.z - cameraPos.z),
                    radius(visualAge),
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
        /** 球心 X 坐标（格）。 */
        private final double x;
        /** 球心 Y 坐标（格）。 */
        private final double y;
        /** 球心 Z 坐标（格）。 */
        private final double z;
        /** 核爆球壳年龄（tick）。 */
        private int age;
        /** 本核爆球壳的一次性抵达状态，新增反馈与旧核爆震动互不取消。 */
        private final RVP_ShockwaveScreenShake screenShake;

        private Shockwave(double x, double y, double z, int age, long seed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.age = age;
            // 创建核爆球壳的独立抵达状态，波面回收不截断已提交的 0.6 秒震动。
            screenShake = new RVP_ShockwaveScreenShake(seed);
        }
    }
}
