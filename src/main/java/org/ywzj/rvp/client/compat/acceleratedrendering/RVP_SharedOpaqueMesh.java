package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.StaticMeshRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedVertexData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.ywzj.rvp.config.RVP_ClientConfig;

/** 将大块刚性 mesh 交给 SBM 已有静态几何缓存；每实例只更新骨骼矩阵、光照和覆盖层。 */
public final class RVP_SharedOpaqueMesh {
    /** 独立缓存所有者；世界退出、资源重载和闲置预算沿用 SBM 的生命周期。 */
    private static final ResourceLocation OWNER = new ResourceLocation("ywzj_rvp", "opaque_vehicle_mesh");
    /** 浮点矩阵的相对容差；拒绝剪切、非均匀缩放及不匹配的法线。 */
    private static final float TRANSFORM_EPSILON = 1.0E-4F;

    /** 几何按 baked chunk、材质与光影分类共享；车辆身份、位置、动画、光照不进入键。 */
    private record GeometryKey(BakedGeometryChunk chunk, RenderType material, RVP_MeshShaderContext.Classification classification) { }

    private RVP_SharedOpaqueMesh() { }

    /** 返回 true 表示 SBM 已接管本次提交，包括正在按预算上传的 PENDING 状态。 */
    static boolean tryDraw(BakedGeometryChunk chunk, RenderType material, Matrix4fc transform,
                           Matrix3fc normal, int light, int overlay, int color, int minVertices) {
        // 调用现有客户端开关；光影保留扩展格式，热源入口另按目标缓冲身份校验。
        if (!isEnabled() || color != -1 || chunk.hasQuads() || !chunk.hasVertices()
                || chunk.vertices().vertexCount() < minVertices || !supportsTransform(transform, normal)) {
            return false;
        }
        PoseStack pose = new PoseStack();
        pose.last().pose().set(transform);
        pose.last().normal().set(normal);
        // 调用 SBM 的公开缓存入口；不创建平行 GPU 池、不改 AR 或 SBM 内部资源寿命。
        // 相同 chunk 的所有车共享局部顶点，车辆运动时仅改变本次提交的矩阵。
        // 调用现有 SBM 捕获：由当前 BufferBuilder/Iris 填充扩展格式，材质仍使用当前光影 shader。
        try (RVP_MeshShaderContext context = RVP_MeshShaderContext.open()) {
            if (context == null) return false;
            return StaticMeshRenderer.tryDraw(OWNER, new GeometryKey(chunk, material, context.classification()), collector -> {
                VertexConsumer consumer = collector.buffer(material, VertexFormat.Mode.TRIANGLES);
                writeGeometry(chunk.vertices(), consumer);
                return true;
            }, pose, light, overlay).owned();
        }
    }

    /** 热源复绘沿用世界的矩阵计算，避免 CPU/GPU 两条深度计算之间的舍入差。 */
    public static boolean tryDrawWorld(BakedGeometryChunk chunk, RenderType material, PoseStack pose,
                                       int light, int overlay, int minVertices) {
        return tryDraw(chunk, material, pose.last().pose(), pose.last().normal(), light, overlay, -1, minVertices);
    }

    /** 世界、热源和展板共用开关；特殊缓冲由调用入口分别校验。 */
    public static boolean isEnabled() {
        return RVP_ClientConfig.isSharedOpaqueMeshEnabled();
    }

    /** 展板镜像只作用于顶点，不能让 SBM 根据它重新推导法线，否则左右受光会翻转。 */
    public static boolean tryDrawPanel(BakedGeometryChunk chunk, RenderType material, PoseStack pose,
                                       int light, int overlay, int minVertices) {
        if (!isEnabled() || chunk.hasQuads() || !chunk.hasVertices()
                || chunk.vertices().vertexCount() < minVertices) return false;
        PanelTransform split = panelTransform(pose.last().pose(), pose.last().normal());
        if (split == null) return false;
        // 调用 Minecraft 公开矩阵栈：分离屏幕镜像与骨骼法线，不伪造 AR 的世界/GUI 渲染阶段。
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        try {
            view.last().pose().mul(split.viewSuffix());
            RenderSystem.applyModelViewMatrix();
            // 调用相同 OWNER、chunk、材质缓存，展板和世界不再各复制一份高面数顶点。
            return tryDraw(chunk, material, split.meshPose(), pose.last().normal(), light, overlay, -1, minVertices);
        } finally {
            view.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** 两矩阵的乘积仍为原顶点变换；SBM 的逆线性光照变换等于原法线矩阵的转置。 */
    record PanelTransform(Matrix4f viewSuffix, Matrix4f meshPose) { }

    static PanelTransform panelTransform(Matrix4fc pose, Matrix3fc normal) {
        if (!supportsTransform(pose, new Matrix3f(pose))
                || !supportsTransform(new Matrix4f().set(normal), normal)) return null;
        // P = (P * N^T) * N^-T；局部光源 N^T * L 与原来的 L dot (N * vertexNormal) 等价。
        Matrix4f meshPose = new Matrix4f().set(new Matrix3f(normal).invert().transpose());
        Matrix4f suffix = new Matrix4f(pose).mul(new Matrix4f().set(new Matrix3f(normal).transpose()));
        return new PanelTransform(suffix, meshPose);
    }

    /** 仅首次缓存捕获写入未变换顶点；UV2=0 指示 SBM 使用每实例光照。 */
    static void writeGeometry(BakedVertexData data, VertexConsumer consumer) {
        float[] positions = data.positions();
        float[] normals = data.normals();
        float[] uvs = data.uvs();
        for (int i = 0; i < data.vertexCount(); i++) {
            int p = i * 3;
            int uv = i * 2;
            consumer.vertex(positions[p], positions[p + 1], positions[p + 2], 1, 1, 1, 1,
                    uvs[uv], uvs[uv + 1], OverlayTexture.NO_OVERLAY, 0,
                    normals[p], normals[p + 1], normals[p + 2]);
        }
    }

    /** SBM 静态 API 支持刚性/均匀缩放实例；其它姿势继续使用可保留完整法线矩阵的 AR。 */
    static boolean supportsTransform(Matrix4fc pose, Matrix3fc normal) {
        if (!pose.isFinite() || !normal.isFinite() || Math.abs(pose.m03()) > TRANSFORM_EPSILON
                || Math.abs(pose.m13()) > TRANSFORM_EPSILON || Math.abs(pose.m23()) > TRANSFORM_EPSILON
                || Math.abs(pose.m33() - 1) > TRANSFORM_EPSILON) {
            return false;
        }
        float x2 = lengthSquared(pose.m00(), pose.m01(), pose.m02());
        float y2 = lengthSquared(pose.m10(), pose.m11(), pose.m12());
        float z2 = lengthSquared(pose.m20(), pose.m21(), pose.m22());
        float scale2 = Math.max(x2, Math.max(y2, z2));
        if (!Float.isFinite(scale2) || scale2 < 1.0E-10F) {
            return false;
        }
        float tolerance = scale2 * TRANSFORM_EPSILON;
        return Math.abs(x2 - y2) <= tolerance && Math.abs(x2 - z2) <= tolerance
                && Math.abs(dot(pose.m00(), pose.m01(), pose.m02(), pose.m10(), pose.m11(), pose.m12())) <= tolerance
                && Math.abs(dot(pose.m00(), pose.m01(), pose.m02(), pose.m20(), pose.m21(), pose.m22())) <= tolerance
                && Math.abs(dot(pose.m10(), pose.m11(), pose.m12(), pose.m20(), pose.m21(), pose.m22())) <= tolerance
                && sameDirection(pose.m00(), pose.m01(), pose.m02(), normal.m00(), normal.m01(), normal.m02())
                && sameDirection(pose.m10(), pose.m11(), pose.m12(), normal.m10(), normal.m11(), normal.m12())
                && sameDirection(pose.m20(), pose.m21(), pose.m22(), normal.m20(), normal.m21(), normal.m22());
    }

    private static float lengthSquared(float x, float y, float z) {
        return x * x + y * y + z * z;
    }

    private static float dot(float x, float y, float z, float a, float b, float c) {
        return x * a + y * b + z * c;
    }

    private static boolean sameDirection(float x, float y, float z, float a, float b, float c) {
        double product = (double) lengthSquared(x, y, z) * lengthSquared(a, b, c);
        return product > 1.0E-20 && Double.isFinite(product)
                && dot(x, y, z, a, b, c) / Math.sqrt(product) > 1 - TRANSFORM_EPSILON;
    }
}
