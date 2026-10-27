package org.ywzj.rvp.client.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;

import java.util.Objects;

/**
 * 可复用的低矮马赫杆近似墙渲染器。
 *
 * <p>这里的“马赫杆”是视觉上的近地面竖直冲击前沿，不是流体力学求解结果。调用方应在
 * 世界渲染阶段已经建立半透明绘制状态后调用。</p>
 */
public final class RVP_MachStemRenderer {
    /** 环形墙体分段数，与地面冲击环保持一致。 */
    private static final int SEGMENTS = 96;

    private RVP_MachStemRenderer() {
    }

    /**
     * 绘制一圈低矮的近地面马赫杆墙体。
     *
     * @param parameters 马赫杆墙体的世界坐标、轮廓和高度参数
     */
    public static void render(Parameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Vec3 center = parameters.center().subtract(parameters.camera());
        double groundY = parameters.groundY() - parameters.camera().y;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int segment = 0; segment < SEGMENTS; segment++) {
            double angleA = segment * Math.PI * 2.0D / SEGMENTS;
            double angleB = (segment + 1) * Math.PI * 2.0D / SEGMENTS;
            // 调用 RVP 冲击波轮廓数据，让马赫杆墙体沿地形感知的冲击环同步扩张。
            double radiusA = parameters.profile().radiusAt(angleA) * parameters.expansion();
            double radiusB = parameters.profile().radiusAt(angleB) * parameters.expansion();
            double axisAX = Math.cos(angleA);
            double axisAZ = Math.sin(angleA);
            double axisBX = Math.cos(angleB);
            double axisBZ = Math.sin(angleB);
            float color = parameters.color();
            float alpha = parameters.alpha();
            vertex(builder, center.x + axisAX * radiusA * parameters.outerScale(), groundY,
                    center.z + axisAZ * radiusA * parameters.outerScale(), color, alpha);
            vertex(builder, center.x + axisBX * radiusB * parameters.outerScale(), groundY,
                    center.z + axisBZ * radiusB * parameters.outerScale(), color, alpha);
            vertex(builder, center.x + axisBX * radiusB * parameters.topScale(), groundY + parameters.height(),
                    center.z + axisBZ * radiusB * parameters.topScale(), color, 0.0F);
            vertex(builder, center.x + axisAX * radiusA * parameters.topScale(), groundY + parameters.height(),
                    center.z + axisAZ * radiusA * parameters.topScale(), color, 0.0F);
        }
        BufferUploader.drawWithShader(builder.end());
    }

    /** 一次马赫杆墙体绘制所需的只读参数。 */
    public record Parameters(
            /** 墙体中心世界坐标。 */ Vec3 center,
            /** 当前相机世界坐标。 */ Vec3 camera,
            /** 墙体贴合的地表高度。 */ double groundY,
            /** 服务端测量的八方向冲击波轮廓。 */ RVP_ShockwaveProfile profile,
            /** 当前墙体半径相对于最终半径的扩张比例。 */ float expansion,
            /** 墙体底部相对于冲击环外半径的比例。 */ float outerScale,
            /** 墙体顶部相对于冲击环外半径的比例。 */ float topScale,
            /** 墙体高度，单位为格。 */ float height,
            /** 顶点颜色，范围为 0～1。 */ float color,
            /** 墙体底部透明度，范围为 0～1。 */ float alpha
    ) {
        public Parameters {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(profile, "profile");
            if (!finite(center) || !finite(camera) || !Double.isFinite(groundY)
                    || !Float.isFinite(expansion) || expansion < 0.0F
                    || !Float.isFinite(outerScale) || outerScale < 0.0F
                    || !Float.isFinite(topScale) || topScale < 0.0F
                    || !Float.isFinite(height) || height < 0.0F
                    || !Float.isFinite(color) || color < 0.0F || color > 1.0F
                    || !Float.isFinite(alpha) || alpha < 0.0F || alpha > 1.0F) {
                throw new IllegalArgumentException("Invalid Mach stem parameters");
            }
        }

        /** 检查世界坐标是否为有限值，避免坏数据污染后续矩阵计算。 */
        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
        }
    }

    /** 写入一个底部可见、顶部渐隐的墙体顶点。 */
    private static void vertex(BufferBuilder builder, double x, double y, double z,
            float color, float alpha) {
        builder.vertex(x, y, z).color(color, color, color, alpha).endVertex();
    }
}
