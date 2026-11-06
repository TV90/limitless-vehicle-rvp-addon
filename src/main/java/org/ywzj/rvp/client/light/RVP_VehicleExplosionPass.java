package org.ywzj.rvp.client.light;

import org.ywzj.rvp.client.performance.RVP_RenderMetric;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_GlStateSync;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionPreview.Frame;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;

import java.io.IOException;

/** F8专用程序大爆炸：逐像素沿视线累积烟火，只在爆炸投影矩形内绘制，不写世界深度。 */
final class RVP_VehicleExplosionPass {
    /** 资源重载时由Forge替换并管理销毁的独立程序。 */
    private static ShaderInstance shader;

    private RVP_VehicleExplosionPass() { }

    /** 复用现有客户端着色器注册事件，不增加Mixin或本体改动。 */
    static void register(RegisterShadersEvent event) throws IOException {
        shader = null;
        // 调用本模组资源定位，程序不覆盖任何既有爆炸资源。
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("vehicle_explosion"),
                DefaultVertexFormat.POSITION), loaded -> shader = loaded);
    }

    /** 注册失败时不请求额外深度输入，保留其余视觉通道。 */
    static boolean available() { return shader != null; }

    /** 同一帧尺寸同时用于裁剪和体积求交，镜头在火球里时保守覆盖全屏。 */
    static RVP_PressureWaveGeometry.Bounds bounds(Frame explosion, Matrix4f view, Matrix4f projection, Vec3 camera) {
        Vec3 delta = explosion.center().subtract(camera);
        Vector3f center = view.transformPosition(new Vector3f((float) delta.x, (float) delta.y, (float) delta.z));
        // 调用既有球体投影计算，爆心离屏但火球露出的部分也不会漏绘。
        return RVP_PressureWaveGeometry.screenBounds(projection, center.x, center.y, center.z, explosion.boundRadius());
    }

    /** 末端合成；共享当前帧近景/DH深度，热浪单独复制一次已包含其它视觉层的颜色。 */
    static void render(RVP_ScreenFrameResources frame, Frame explosion, Matrix4f view, Matrix4f projection, Vec3 camera) {
        if (!available() || explosion == null) { return; }
        // 调用通用性能范围，默认关闭时不申请显卡查询；所有提前返回和异常均自动封口。
        try (var performance = RVP_RenderPerformance.scope(RVP_RenderStage.VEHICLE_EXPLOSION)) {
            // 调用相同包围范围重新校验，窗口变化或没有可见区域时不复制背景。
            var box = bounds(explosion, view, projection, camera);
            if (box == null || !frame.refreshCompositeColor()) { return; }
            // 调用统一场景绑定，正反深度、手持物遮罩和DH远景沿用同一套坐标规则。
            RVP_ScreenLightPass.uploadScene(shader);
            shader.safeGetUniform("ViewToWorld").set(new Matrix4f(view).invert());
            Vec3 delta = explosion.center().subtract(camera);
            Vector3f center = view.transformPosition(new Vector3f((float) delta.x, (float) delta.y, (float) delta.z));
            var settings = explosion.settings();
            shader.safeGetUniform("ExplosionCenter").set(center.x, center.y, center.z, explosion.boundRadius());
            shader.safeGetUniform("Timeline").set(explosion.age(), settings.get(Parameter.DURATION),
                    settings.get(Parameter.EXPANSION), settings.get(Parameter.FIRE_DURATION));
            shader.safeGetUniform("Shape").set(settings.get(Parameter.RADIUS), settings.get(Parameter.STRETCH),
                    settings.get(Parameter.BILLOW), settings.get(Parameter.NOISE_SCALE));
            shader.safeGetUniform("Motion").set(settings.get(Parameter.TURBULENCE), settings.get(Parameter.SMOKE_EXPANSION),
                    settings.get(Parameter.SEED), settings.get(Parameter.CORE));
            shader.safeGetUniform("FireColor").set(settings.get(Parameter.BRIGHTNESS), settings.get(Parameter.FIRE_GREEN),
                    settings.get(Parameter.FIRE_BLUE));
            shader.safeGetUniform("Smoke").set(settings.get(Parameter.SMOKE_DENSITY), settings.get(Parameter.SMOKE_GREY));
            shader.safeGetUniform("Air").set(settings.get(Parameter.FLASH), settings.get(Parameter.FLASH_DURATION),
                    settings.get(Parameter.DISTORTION), settings.get(Parameter.WAVE_DURATION));
            shader.safeGetUniform("RaySteps").set((int) settings.get(Parameter.RAY_STEPS));
            // 调用共享目标绑定，禁止深度写入；颜色副本和主输出始终是不同纹理。
            frame.bind(frame.mainTarget(), frame.width(), frame.height());
            int x = Math.max(0, (int) Math.floor((box.left() + 1) * 0.5 * frame.width()) - 1);
            int y = Math.max(0, (int) Math.floor((box.bottom() + 1) * 0.5 * frame.height()) - 1);
            int right = Math.min(frame.width(), (int) Math.ceil((box.right() + 1) * 0.5 * frame.width()) + 1);
            int top = Math.min(frame.height(), (int) Math.ceil((box.top() + 1) * 0.5 * frame.height()) + 1);
            // 调用GL状态同步，外层现有State作用域负责完整恢复所有绘制状态。
            RVP_GlStateSync.scissor(true);
            GL11.glScissor(x, y, right - x, top - y);
            // 调用准确的提交矩形统计，实际采样仍可能因深度/浓度提前结束。
            frame.profileArea(right - x, top - y, (int) settings.get(Parameter.RAY_STEPS));
            RVP_RenderPerformance.add(RVP_RenderMetric.ITEMS, 1);
            try {
                // 调用不混合的完整颜色合成，烟吸收、火焰发光、热浪共用一遍积分，原场景Alpha原样保留。
                frame.draw(shader);
            } finally {
                // 调用同步恢复，后续诊断和其它模组不继承爆炸裁剪矩形。
                RVP_GlStateSync.scissor(false);
            }
        }
    }
}
