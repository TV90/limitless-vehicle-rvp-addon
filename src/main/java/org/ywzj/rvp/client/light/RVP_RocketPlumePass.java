package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;
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
        // 调用另名的独立橙晕程序，保留旧版资源，喷流几何和深度来源继续共用。
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("rocket_plume_ranges"),
                DefaultVertexFormat.POSITION), loaded -> shader = loaded);
    }

    /** 新程序可用且代码总开关开启，才申请相关逐帧工作。 */
    static boolean available() { return shader != null && RVP_RocketPlumeParameters.ENABLED; }

    /** 包围完整喷流并补足极远处的像素覆盖，不按环境灯距离截掉可见的远端发动机。 */
    static RVP_PressureWaveGeometry.Bounds bounds(RVP_RocketPlume plume, Matrix4f view,
            Matrix4f projection, Vec3 camera, int height) {
        // 调用几何和广域光晕两种包围框并取并集，光晕还在画面内时不能按细尾焰剔除整条输入。
        var shape = shapeBounds(plume, view, projection, camera, height);
        if (!RVP_LightingConfig.bloomEnabled()) { return shape; }
        var halo = haloBounds(plume, view, projection, camera);
        if (shape == null) { return halo; }
        if (halo == null) { return shape; }
        return new RVP_PressureWaveGeometry.Bounds(Math.min(shape.left(), halo.left()),
                Math.min(shape.bottom(), halo.bottom()), Math.max(shape.right(), halo.right()), Math.max(shape.top(), halo.top()));
    }

    /** 原喷流范围仍使用实际长宽；广域光晕不能让二十步喷焰采样扩大到整屏。 */
    static RVP_PressureWaveGeometry.Bounds shapeBounds(RVP_RocketPlume plume, Matrix4f view,
            Matrix4f projection, Vec3 camera, int height) {
        Vector3f center = viewPosition(plume.center(), view, camera);
        float pixelRadius = 4F * Math.abs(center.z) / Math.max(height * Math.abs(projection.m11()), 0.0001F);
        return RVP_PressureWaveGeometry.screenBounds(projection, center.x, center.y, center.z,
                plume.boundRadius() + pixelRadius);
    }

    /** 以实际喷口为中心投影世界半径，距离/FOV/窗口变化时自动改变像素覆盖。 */
    static RVP_PressureWaveGeometry.Bounds haloBounds(RVP_RocketPlume plume, Matrix4f view,
            Matrix4f projection, Vec3 camera) {
        Vector3f center = viewPosition(plume.position(), view, camera);
        if (center.z >= -0.0001F || plume.haloRadius() <= 0) { return null; }
        return RVP_PressureWaveGeometry.screenBounds(projection, center.x, center.y, center.z, plume.haloRadius());
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
            // 光晕范围为零时仅取消模糊来源，显示模式仍保留真实白热喷焰。
            if (mode == 1 && plume.haloRadius() <= 0) { continue; }
            // 调用整条喷流的包围框，不要求喷口本身在屏幕内。
            RVP_PressureWaveGeometry.Bounds bounds = shapeBounds(plume, view, projection, camera, frame.height());
            if (bounds == null) { continue; }
            // 调用同一喷口、朝向、燃烧强度上传，细尾焰与广域光晕不能各自推算弹体姿态。
            uploadPlume(plume, view, camera);
            // 未使用可见度采样的模式也绑定有效外部输入，避免采样器别名指向正在写入的附件。
            shader.setSampler("PlumeVisibility", frame.depth());
            frame.bind(mode == 1 ? frame.glowTarget() : frame.mainTarget(), frame.width(), frame.height());
            // 调用实际包围框裁剪，不额外执行离屏片元的二十次采样。
            scissor(frame, bounds);
            // 调用有限峰值合并或显示叠加，保持原世界 Alpha，不写深度，不读写同一颜色纹理。
            if (mode == 1) { frame.drawContribution(shader); }
            else { frame.drawEmission(shader); }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** 先以一像素求可见发光比例，再绘制世界尺寸光晕；所有喷流共用一份小目标，不新增数量上限。 */
    static void renderHalos(RVP_ScreenFrameResources frame, List<RVP_RocketPlume> plumes,
            Matrix4f view, Matrix4f projection, Vec3 camera, float time) {
        if (!available() || !RVP_LightingConfig.bloomEnabled() || plumes.isEmpty()) { return; }
        // 调用共享目标与矩阵输入，支持原版、DH、光影及组合路径。
        frame.ensurePlumeVisibility();
        RVP_ScreenLightPass.uploadScene(shader);
        shader.safeGetUniform("Time").set(time);
        for (RVP_RocketPlume plume : plumes) {
            var bounds = haloBounds(plume, view, projection, camera);
            if (bounds == null) { continue; }
            // 调用独立光晕快照；照亮范围和光晕范围分别调参，阶段仍仅改变亮度。
            uploadPlume(plume, view, camera);
            // 可见度模式只写一像素；此时采样器指向近景深度，绝不读写自己的同一附件。
            shader.setSampler("PlumeVisibility", frame.depth());
            shader.safeGetUniform("OutputMode").set(3);
            frame.bind(frame.plumeVisibilityTarget(), 1, 1);
            frame.draw(shader);
            // 广域模式只读上一步结果；每像素不再重复二十步喷流采样或查询光源可见度。
            shader.setSampler("PlumeVisibility", frame.plumeVisibility());
            shader.safeGetUniform("OutputMode").set(4);
            frame.bind(frame.mainTarget(), frame.width(), frame.height());
            scissor(frame, bounds);
            frame.drawEmission(shader);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** 上传真实几何、世界光晕半径与代码亮度；不把照明半径用作喷焰模型缩放。 */
    private static void uploadPlume(RVP_RocketPlume plume, Matrix4f view, Vec3 camera) {
        Vector3f position = viewPosition(plume.position(), view, camera);
        Vector3f axis = view.transformDirection(new Vector3f((float) plume.direction().x,
                (float) plume.direction().y, (float) plume.direction().z)).normalize();
        shader.safeGetUniform("PlumeOriginLength").set(position.x, position.y, position.z, plume.length());
        shader.safeGetUniform("PlumeAxisRadius").set(axis.x, axis.y, axis.z, plume.radius());
        shader.safeGetUniform("PlumeHaloRadius").set(plume.haloRadius());
        // 调用会话亮度入口，只替换已有程序输入；阶段包络、二十步采样、遮挡和显示映射保持原算法。
        shader.safeGetUniform("PlumeEmission").set(
                RVP_LightRuntimeTuning.get(Group.engine, Parameter.coreIntensity) * plume.strength(),
                RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity) * plume.strength(), plume.seed(),
                RVP_LightRuntimeTuning.get(Group.bloom, Parameter.inputPeak));
        // 调用独立广域亮度；只乘一次推进阶段，不再借用外围橙焰的亮度。
        shader.safeGetUniform("PlumeHaloIntensity").set(
                RVP_LightRuntimeTuning.get(Group.engine, Parameter.worldHaloIntensity) * plume.strength());
    }

    /** 所有局部绘制共用保守矩形，视口仍为完整世界画面。 */
    private static void scissor(RVP_ScreenFrameResources frame, RVP_PressureWaveGeometry.Bounds bounds) {
        int x = Math.max(0, (int) Math.floor((bounds.left() + 1) * 0.5 * frame.width()) - 2);
        int y = Math.max(0, (int) Math.floor((bounds.bottom() + 1) * 0.5 * frame.height()) - 2);
        int right = Math.min(frame.width(), (int) Math.ceil((bounds.right() + 1) * 0.5 * frame.width()) + 2);
        int top = Math.min(frame.height(), (int) Math.ceil((bounds.top() + 1) * 0.5 * frame.height()) + 2);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, right - x, top - y);
    }

    /** 先用 double 相减，避免远世界坐标的喷焰抖动。 */
    private static Vector3f viewPosition(Vec3 position, Matrix4f view, Vec3 camera) {
        return view.transformPosition(new Vector3f((float) (position.x - camera.x),
                (float) (position.y - camera.y), (float) (position.z - camera.z)));
    }
}
