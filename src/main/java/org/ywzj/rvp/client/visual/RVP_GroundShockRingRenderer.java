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
 * 可复用的地面冲击环几何渲染器。
 *
 * <p>该类只负责把服务端给出的八方向半径绘制成地面环，不负责推进年龄、判定空爆或配置
 * RenderLevelStageEvent。调用方应在世界渲染阶段已经建立半透明绘制状态后调用。</p>
 */
public final class RVP_GroundShockRingRenderer {
    /** 环形网格分段数，保证远近视角下轮廓平滑。 */
    private static final int SEGMENTS = 96;

    private RVP_GroundShockRingRenderer() {
    }

    /**
     * 绘制一次地面冲击环。
     *
     * @param parameters 环形几何所需的世界坐标、相机坐标和视觉参数
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
            // 调用 RVP 冲击波轮廓数据，在相邻八方向之间插值得到不对称环半径。
            double radiusA = parameters.profile().radiusAt(angleA) * parameters.expansion();
            double radiusB = parameters.profile().radiusAt(angleB) * parameters.expansion();
            double axisAX = Math.cos(angleA);
            double axisAZ = Math.sin(angleA);
            double axisBX = Math.cos(angleB);
            double axisBZ = Math.sin(angleB);
            vertex(builder, center.x + axisAX * radiusA, groundY, center.z + axisAZ * radiusA,
                    parameters.color(), 0.0F);
            vertex(builder, center.x + axisBX * radiusB, groundY, center.z + axisBZ * radiusB,
                    parameters.color(), 0.0F);
            vertex(builder, center.x + axisBX * radiusB * parameters.innerScale(), groundY,
                    center.z + axisBZ * radiusB * parameters.innerScale(), parameters.color(), parameters.alpha());
            vertex(builder, center.x + axisAX * radiusA * parameters.innerScale(), groundY,
                    center.z + axisAZ * radiusA * parameters.innerScale(), parameters.color(), parameters.alpha());
        }
        BufferUploader.drawWithShader(builder.end());
    }

    /** 一次地面冲击环绘制所需的只读参数。 */
    public record Parameters(
            /** 环心世界坐标。 */ Vec3 center,
            /** 当前相机世界坐标。 */ Vec3 camera,
            /** 环所在的地表高度。 */ double groundY,
            /** 服务端测量的八方向冲击波轮廓。 */ RVP_ShockwaveProfile profile,
            /** 当前环半径相对于最终半径的扩张比例。 */ float expansion,
            /** 内环相对于外环的半径比例。 */ float innerScale,
            /** 顶点颜色，范围为 0～1。 */ float color,
            /** 环内侧透明度，范围为 0～1。 */ float alpha
    ) {
        public Parameters {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(profile, "profile");
            if (!finite(center) || !finite(camera) || !Double.isFinite(groundY)
                    || !Float.isFinite(expansion) || expansion < 0.0F
                    || !Float.isFinite(innerScale) || innerScale < 0.0F || innerScale > 1.0F
                    || !Float.isFinite(color) || color < 0.0F || color > 1.0F
                    || !Float.isFinite(alpha) || alpha < 0.0F || alpha > 1.0F) {
                throw new IllegalArgumentException("Invalid ground shock ring parameters");
            }
        }

        /** 检查世界坐标是否为有限值，避免坏数据污染后续矩阵计算。 */
        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
        }
    }

    /** 写入一个带透明度渐变的环形顶点。 */
    private static void vertex(BufferBuilder builder, double x, double y, double z,
            float color, float alpha) {
        builder.vertex(x, y, z).color(color, color, color, alpha).endVertex();
    }
}
