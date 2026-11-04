package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh.StaticMeshRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedVertexData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.ywzj.rvp.client.light.RVP_LightingCompatibility;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.client.shader.ThermalHandler;

/** 将大块刚性 mesh 交给 SBM 已有静态几何缓存；每实例只更新骨骼矩阵、光照和覆盖层。 */
final class RVP_SharedOpaqueMesh {
    /** 独立缓存所有者；世界退出、资源重载和闲置预算沿用 SBM 的生命周期。 */
    private static final ResourceLocation OWNER = new ResourceLocation("ywzj_rvp", "opaque_vehicle_mesh");
    /** 浮点矩阵的相对容差；拒绝剪切、非均匀缩放及不匹配的法线。 */
    private static final float TRANSFORM_EPSILON = 1.0E-4F;

    /** 几何身份仅含 baked chunk 和实际材质；车辆、位置、动画、光照均不能产生新缓存键。 */
    private record GeometryKey(BakedGeometryChunk chunk, RenderType material) { }

    private RVP_SharedOpaqueMesh() { }

    /** 返回 true 表示 SBM 已接管本次提交，包括正在按预算上传的 PENDING 状态。 */
    static boolean tryDraw(BakedGeometryChunk chunk, RenderType material, Matrix4fc transform,
                           Matrix3fc normal, int light, int overlay, int color, int minVertices) {
        // 调用现有客户端开关和光影查询；光影包及热成像继续沿用 AR/Oculus 原管线。
        if (!RVP_ClientConfig.isSharedOpaqueMeshEnabled() || RVP_LightingCompatibility.shadersActive()
                || ThermalHandler.isActive() || color != -1 || chunk.hasQuads() || !chunk.hasVertices()
                || chunk.vertices().vertexCount() < minVertices || !supportsTransform(transform, normal)) {
            return false;
        }
        PoseStack pose = new PoseStack();
        pose.last().pose().set(transform);
        pose.last().normal().set(normal);
        // 调用 SBM 的公开缓存入口；不创建平行 GPU 池、不改 AR 或 SBM 内部资源寿命。
        // 相同 chunk 的所有车共享局部顶点，车辆运动时仅改变本次提交的矩阵。
        return StaticMeshRenderer.tryDraw(OWNER, new GeometryKey(chunk, material), collector -> {
            VertexConsumer consumer = collector.buffer(material, VertexFormat.Mode.TRIANGLES);
            writeGeometry(chunk.vertices(), consumer);
            return true;
        }, pose, light, overlay).owned();
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
