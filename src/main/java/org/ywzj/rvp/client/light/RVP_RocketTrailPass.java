package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_GlStateSync;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/** 新尾烟逐像素绘制；只在烟段投影区域采样，颜色使用预乘透明度合成，不写世界深度。 */
final class RVP_RocketTrailPass {
    /** 随资源重载更新的 RVP 自有程序，不改写本体或光影包程序。 */
    private static ShaderInstance shader;

    private RVP_RocketTrailPass() { }

    /** 复用已有 Forge 注册事件，不增加任何 Mixin 或访问器。 */
    static void register(RegisterShadersEvent event) throws IOException {
        shader = null;
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("rocket_trail_volume"),
                DefaultVertexFormat.POSITION), loaded -> shader = loaded);
    }

    /** 只有新程序可用且代码开关开启时才收集本帧烟段。 */
    static boolean available() { return shader != null && RVP_RocketTrailParameters.VOLUME_ENABLED; }

    /** 整段烟与端点都参与裁剪；离屏的导弹留下的可见烟仍会被绘制。 */
    static RVP_PressureWaveGeometry.Bounds bounds(RVP_RocketTrailSegment segment,
            Matrix4f view, Matrix4f projection, Vec3 camera) {
        // 调用两端共同投影，长细烟不再用长度作为矩形高度，避免大片空像素进入体积计算。
        return RVP_RocketTrailGeometry.bounds(segment, view, projection, camera);
    }

    /** 从远到近绘制已筛选烟段；同帧共享近景/DH 深度，不为每枚导弹复制背景。 */
    static void render(RVP_ScreenFrameResources frame, List<RVP_RocketTrailSegment> segments,
            Matrix4f view, Matrix4f projection, Vec3 camera) {
        if (!available() || segments.isEmpty()) { return; }
        // 调用统一深度与手持物输入，烟团使用和程序喷焰相同的近景/DH 坐标约定。
        RVP_ScreenLightPass.uploadScene(shader);
        shader.safeGetUniform("ViewToWorld").set(new Matrix4f(view).invert());
        // 调用投影面积总预算，进入密烟时也不会对数百层整屏烟重复运行二十四步。
        var projected = RVP_RocketTrailGeometry.budget(segments, camera, segment -> bounds(segment, view, projection, camera));
        for (RVP_RocketTrailGeometry.Projected input : projected) {
            RVP_RocketTrailSegment segment = input.segment();
            var box = input.bounds();
            shader.safeGetUniform("RaySteps").set(Math.max(8,
                    RVP_RocketTrailParameters.RAY_STEPS - (int) (input.area() * 16)));
            Vector3f start = viewPosition(segment.start(), view, camera);
            Vector3f end = viewPosition(segment.end(), view, camera);
            shader.safeGetUniform("TrailStart").set(start.x, start.y, start.z, segment.startRadius());
            shader.safeGetUniform("TrailEnd").set(end.x, end.y, end.z, segment.endRadius());
            shader.safeGetUniform("TrailDensity").set(segment.startDensity(), segment.endDensity());
            shader.safeGetUniform("TrailColor").set(segment.grey(), segment.age(), segment.seed(), segment.noiseScale());
            // 世界坐标先用 double 求噪声周期余数，再转 float；大坐标处也不让翻滚纹理跟着相机游动。
            shader.safeGetUniform("TrailNoiseOrigin").set((float) ((segment.start().x * segment.noiseScale()) % 4096),
                    (float) ((segment.start().y * segment.noiseScale()) % 4096),
                    (float) ((segment.start().z * segment.noiseScale()) % 4096));
            // 调用同帧光源筛选，最多四盏与烟段相交的灯；只改变烟色，不把烟登记为挡光实体。
            uploadLights(segment);
            frame.bind(frame.mainTarget(), frame.width(), frame.height());
            int x = Math.max(0, (int) Math.floor((box.left() + 1) * 0.5 * frame.width()) - 1);
            int y = Math.max(0, (int) Math.floor((box.bottom() + 1) * 0.5 * frame.height()) - 1);
            int right = Math.min(frame.width(), (int) Math.ceil((box.right() + 1) * 0.5 * frame.width()) + 1);
            int top = Math.min(frame.height(), (int) Math.ceil((box.top() + 1) * 0.5 * frame.height()) + 1);
            // 调用双向状态同步，不能污染 DH/光影缓存中裁剪和混合的开关状态。
            RVP_GlStateSync.scissor(true);
            GL11.glScissor(x, y, Math.max(0, right - x), Math.max(0, top - y));
            frame.drawVolumeSmoke(shader);
        }
        // 调用同步恢复，外层 State 继续负责恢复进入整套屏幕通道之前的完整显卡状态。
        RVP_GlStateSync.scissor(false);
    }

    /** 每段只上传附近四盏光，避免所有烟段都进行八灯多步遮挡计算。 */
    private static void uploadLights(RVP_RocketTrailSegment segment) {
        List<RVP_ScreenLight> lights = RVP_ScreenLightPass.currentLights().stream()
                .filter(light -> light.position().distanceToSqr(segment.center())
                        < Math.pow(light.radius() + segment.boundRadius(), 2))
                .sorted(Comparator.comparingDouble(light -> light.position().distanceToSqr(segment.center())))
                .limit(4).toList();
        shader.safeGetUniform("LightCount").set(lights.size());
        for (int index = 0; index < 4; index++) {
            if (index >= lights.size()) {
                shader.safeGetUniform("LightPositionRadius" + index).set(0F, 0F, 0F, 0F);
                shader.safeGetUniform("LightColorIntensity" + index).set(0F, 0F, 0F, 0F);
                continue;
            }
            RVP_ScreenLight light = lights.get(index);
            // 调用同帧观察空间转换，不能把背景墙位置拿来当烟团位置。
            Vector3f position = RVP_ScreenLightPass.viewPosition(light.position());
            shader.safeGetUniform("LightPositionRadius" + index).set(position.x, position.y, position.z, light.radius());
            shader.safeGetUniform("LightColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                    light.intensity() * light.surfaceWeight() * RVP_LightingConfig.intensity()
                            * RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global,
                                    RVP_LightRuntimeTuning.Parameter.smokeBrightness));
        }
    }

    /** 相机相对坐标先用 double 相减，再转观察空间，降低远坐标抖动。 */
    private static Vector3f viewPosition(Vec3 position, Matrix4f view, Vec3 camera) {
        return view.transformPosition(new Vector3f((float) (position.x - camera.x),
                (float) (position.y - camera.y), (float) (position.z - camera.z)));
    }
}
