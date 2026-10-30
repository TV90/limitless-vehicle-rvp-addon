package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import java.io.IOException;
import java.util.List;

/** 独立定向喷焰程序；复用同帧近景/DH 输入和光晕，只绘制每条喷流的屏幕包围区域。 */
final class RVP_RocketPlumePass {
    /** 由 Minecraft 资源重载持有的程序；不缓存或改写光影包的程序。 */
    private static ShaderInstance shader;

    private RVP_RocketPlumePass() { }

    /** 在现有 Forge 着色器注册事件中加入 RVP 自有程序，不添加 Mixin。 */
    static void register(RegisterShadersEvent event) throws IOException {
        shader = null;
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("rocket_plume"),
                DefaultVertexFormat.POSITION), loaded -> shader = loaded);
    }

    /** 新程序可用且代码总开关开启，才申请相关逐帧工作。 */
    static boolean available() { return shader != null && RVP_RocketPlumeParameters.ENABLED; }

    /** 包围完整喷流并补足极远处的像素覆盖，不按环境灯距离截掉可见的远端发动机。 */
    static RVP_PressureWaveGeometry.Bounds bounds(RVP_RocketPlume plume, Matrix4f view,
            Matrix4f projection, Vec3 camera, int height) {
        Vector3f center = viewPosition(plume.center(), view, camera);
        float pixelRadius = 4F * Math.abs(center.z) / Math.max(height * Math.abs(projection.m11()), 0.0001F);
        return RVP_PressureWaveGeometry.screenBounds(projection, center.x, center.y, center.z,
                plume.boundRadius() + pixelRadius);
    }

    /** 模式 1 合入 HDR/光晕输入，模式 2 叠加显示峰值；同一形状、同一深度裁剪。 */
    static void render(RVP_ScreenFrameResources frame, List<RVP_RocketPlume> plumes,
            Matrix4f view, Matrix4f projection, Vec3 camera, float time, int mode) {
        if (!available() || plumes.isEmpty()) { return; }
        // 调用共用场景上传，近景、DH、光影手持物使用与环境照明完全相同的配对矩阵。
        RVP_ScreenLightPass.uploadScene(shader);
        shader.safeGetUniform("OutputMode").set(mode);
        shader.safeGetUniform("Time").set(time);
        for (RVP_RocketPlume plume : plumes) {
            // 调用整条喷流的包围框，不要求喷口本身在屏幕内。
            RVP_PressureWaveGeometry.Bounds bounds = bounds(plume, view, projection, camera, frame.height());
            if (bounds == null) { continue; }
            Vector3f position = viewPosition(plume.position(), view, camera);
            Vector3f axis = view.transformDirection(new Vector3f((float) plume.direction().x,
                    (float) plume.direction().y, (float) plume.direction().z)).normalize();
            shader.safeGetUniform("PlumeOriginLength").set(position.x, position.y, position.z, plume.length());
            shader.safeGetUniform("PlumeAxisRadius").set(axis.x, axis.y, axis.z, plume.radius());
            // 调用统一 HDR 上限：阶段只改变亮度，JSON 缩放只改变长/宽，两者互不代替。
            shader.safeGetUniform("PlumeEmission").set(RVP_LightParameters.ENGINE_CORE_INTENSITY * plume.strength(),
                    RVP_LightParameters.ENGINE_HALO_INTENSITY * plume.strength(), plume.seed(),
                    RVP_RocketPlumeParameters.BLOOM_INPUT_PEAK);
            frame.bind(mode == 1 ? frame.lightTarget() : frame.mainTarget(), frame.width(), frame.height());
            int x = Math.max(0, (int) Math.floor((bounds.left() + 1) * 0.5 * frame.width()) - 2);
            int y = Math.max(0, (int) Math.floor((bounds.bottom() + 1) * 0.5 * frame.height()) - 2);
            int right = Math.min(frame.width(), (int) Math.ceil((bounds.right() + 1) * 0.5 * frame.width()) + 2);
            int top = Math.min(frame.height(), (int) Math.ceil((bounds.top() + 1) * 0.5 * frame.height()) + 2);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, right - x, top - y);
            // 调用有限峰值合并或显示叠加，保持原世界 Alpha，不写深度，不读写同一颜色纹理。
            if (mode == 1) { frame.drawContribution(shader); }
            else { frame.drawEmission(shader); }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** 先用 double 相减，避免远世界坐标的喷焰抖动。 */
    private static Vector3f viewPosition(Vec3 position, Matrix4f view, Vec3 camera) {
        return view.transformPosition(new Vector3f((float) (position.x - camera.x),
                (float) (position.y - camera.y), (float) (position.z - camera.z)));
    }
}
