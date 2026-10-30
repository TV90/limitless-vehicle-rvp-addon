package org.ywzj.rvp.client.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 夜视仪下的干扰机光斑（2026-10-31）。
 *
 * <p>不接 PostChain，纯自绘：在 {@code AFTER_LEVEL} 里把每个干扰机玻片的世界坐标投影成屏幕椭圆，
 * 用加性混合叠一层"白热核心 + 红外晕"。这样做的两个理由：</p>
 * <ul>
 *   <li>Iris/Oculus 启用光影时 PostChain 不可靠，自绘 quad 与 {@code RVP_ScreenLightPass} 同一条路，
 *       在光影下同样能留下；</li>
 *   <li>多台干扰机天然叠加——加性混合下两层光斑重叠处直接过曝成白，不需要额外分支。</li>
 * </ul>
 */
public final class RVP_NvgGlowPass {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 光斑世界半径（格）；实际屏幕尺寸按透视缩放，远处自动变小。 */
    private static final float WORLD_RADIUS = 2.2F;
    /** 横向拉宽 / 纵向压扁，让光斑是椭圆而不是正圆。 */
    private static final float RADIUS_SCALE_X = 1.25F;
    private static final float RADIUS_SCALE_Y = 0.85F;
    /** NDC 半径上限，避免贴脸时半个屏幕被糊白。 */
    private static final float MAX_NDC_RADIUS = 1.15F;
    /** NDC 半径下限：远处光斑不能缩到看不见（真实光晕在远距离也有最小可见尺寸）。 */
    private static final float MIN_NDC_RADIUS = 0.022F;
    /** 收集上限，防止异常情况下列表无限增长。 */
    private static final int MAX_POINTS = 64;
    /** 同一点重复收集的合并阈值（格²），一 tick 多帧渲染时不会把亮度叠成两倍。 */
    private static final double MERGE_DISTANCE_SQR = 0.25D;
    /** 光斑整体强度（对应 nvg_glow.fsh 的 Intensity）；用户要"巨亮"，给到 1.15。 */
    private static final float GLOW_INTENSITY = 1.15F;
    /** 外围发散晕的幂次（越大越聚拢）。 */
    private static final float HALO_POWER = 2.3F;
    /** 顶点向相机方向退的 NDC 深度量，避免与玻片表面 z-fighting 被 LESS 丢弃。 */
    private static final float DEPTH_BIAS = 0.0006F;
    /** 首次绘制打一行日志，便于确认光斑链路是否真的跑到了。 */
    private static boolean loggedOnce;

    private static ShaderInstance glowShader;
    /** 本帧待绘制的光斑世界坐标；实体渲染阶段收集，AFTER_LEVEL 消费后立即清空。 */
    private static final List<Vec3> POINTS = new ArrayList<>();

    private RVP_NvgGlowPass() {}

    public static void registerShaders(RegisterShadersEvent event) {
        glowShader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    RVP_MOD.modLocation("nvg_glow"), DefaultVertexFormat.POSITION),
                    loaded -> glowShader = loaded);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("RVP 夜视仪干扰机光斑程序加载失败，夜视下该光斑将不显示", exception);
        }
    }

    /** 收集一个干扰机玻片位置；同一位置重复上报会被合并。 */
    public static void add(Vec3 position) {
        if (position == null || POINTS.size() >= MAX_POINTS) {
            return;
        }
        for (Vec3 existing : POINTS) {
            if (existing.distanceToSqr(position) < MERGE_DISTANCE_SQR) {
                return;
            }
        }
        POINTS.add(position);
    }

    /** AFTER_LEVEL 末尾调用：投影绘制本帧所有光斑，然后清空。 */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (glowShader == null || POINTS.isEmpty() || !RVP_NvgHandler.isActive()) {
            POINTS.clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 2026-10-31 修正：不能拿 poseStack 当视图矩阵。AFTER_LEVEL 阶段它的内容依赖
        // 上一层是否 pop 干净（RVP_ScreenLightPass 拿完之后也要用 sceneMatrices 覆盖），
        // 用它算出来的 NDC 是错的，表现就是"日志显示画了、屏幕上一个像素都没有"。
        // 直接用相机朝向构造旋转视图矩阵，再手动减去相机位置，语义唯一。
        Quaternionf cameraRotation = new Quaternionf(event.getCamera().rotation());
        cameraRotation.conjugate();
        Matrix4f view = new Matrix4f().rotation(cameraRotation);
        Matrix4f projection = event.getProjectionMatrix();
        Vec3 camera = event.getCamera().getPosition();
        if (!loggedOnce) {
            loggedOnce = true;
            Vec3 p0 = POINTS.get(0);
            Vector4f v = new Vector4f((float) (p0.x - camera.x), (float) (p0.y - camera.y),
                    (float) (p0.z - camera.z), 1.0F);
            view.transform(v);
            Vector4f clip = new Vector4f(v);
            projection.transform(clip);
            float ndcX = clip.w == 0.0F ? 0.0F : clip.x / clip.w;
            float ndcY = clip.w == 0.0F ? 0.0F : clip.y / clip.w;
            LOGGER.info("[RVP-NVG] glow points={} viewZ={} ndc=({}, {}) w={}",
                    POINTS.size(), String.format("%.2f", v.z),
                    String.format("%.3f", ndcX), String.format("%.3f", ndcY),
                    String.format("%.3f", clip.w));
        }

        // 显式写 uniform：不依赖 program JSON 的默认值（默认值缺失时颜色会算成 0，整片涂黑）
        if (glowShader.safeGetUniform("CoreColor") != null) {
            glowShader.safeGetUniform("CoreColor").set(1.0F, 0.96F, 0.88F, 1.0F);
        }
        if (glowShader.safeGetUniform("EdgeColor") != null) {
            glowShader.safeGetUniform("EdgeColor").set(0.95F, 0.14F, 0.05F, 1.0F);
        }
        if (glowShader.safeGetUniform("Intensity") != null) {
            glowShader.safeGetUniform("Intensity").set(GLOW_INTENSITY);
        }
        if (glowShader.safeGetUniform("HaloPower") != null) {
            glowShader.safeGetUniform("HaloPower").set(HALO_POWER);
        }

        // GL 默认深度函数是 LESS，quad 的 z 与玻片表面相等时会被整片丢弃；
        // 显式改 LEQUAL，顶点上再向相机方向退一点点，双保险避免 z-fighting。
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableBlend();
        // 加性混合：多台干扰机靠近时自然累加，重叠处直接烧白
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> glowShader);
        try {
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder builder = tesselator.getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (Vec3 point : POINTS) {
                appendQuad(builder, point, view, projection, camera);
            }
            // 空批次也走 end()：零顶点绘制是合法的，比手工判断更不易漏掉收尾
            tesselator.end();
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            POINTS.clear();
        }
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    /** 把一个世界点投影成椭圆四边形写入 builder；不可见时返回 false。 */
    private static boolean appendQuad(BufferBuilder builder, Vec3 point,
                                      Matrix4f view, Matrix4f projection, Vec3 camera) {
        Vector4f viewPos = new Vector4f(
                (float) (point.x - camera.x),
                (float) (point.y - camera.y),
                (float) (point.z - camera.z), 1.0F);
        view.transform(viewPos);
        // 相机背后
        if (viewPos.z > -0.05F) {
            return false;
        }
        Vector4f clip = new Vector4f(viewPos);
        projection.transform(clip);
        if (clip.w <= 1.0E-5F) {
            return false;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float ndcZ = clip.z / clip.w;
        // 透视缩放：m11 = 1/tan(fov/2)，除以视空间距离得到 NDC 半径
        float radius = WORLD_RADIUS * projection.m11() / (-viewPos.z);
        radius = Math.min(radius, MAX_NDC_RADIUS);
        // 远处也保底可见：不加下限的话 40 格外只剩十几像素，等于没有
        radius = Math.max(radius, MIN_NDC_RADIUS);
        // 完全在屏幕外（留出半径余量）就跳过
        if (ndcX < -1.0F - radius || ndcX > 1.0F + radius
                || ndcY < -1.0F - radius || ndcY > 1.0F + radius) {
            return false;
        }
        float rx = radius * RADIUS_SCALE_X;
        float ry = radius * RADIUS_SCALE_Y;
        float z = ndcZ - DEPTH_BIAS;
        builder.vertex(ndcX - rx, ndcY - ry, z).endVertex();
        builder.vertex(ndcX + rx, ndcY - ry, z).endVertex();
        builder.vertex(ndcX + rx, ndcY + ry, z).endVertex();
        builder.vertex(ndcX - rx, ndcY + ry, z).endVertex();
        return true;
    }
}
