package org.ywzj.rvp.client.light;

import org.ywzj.rvp.client.performance.RVP_RenderMetric;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.*;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.vehicle.client.particle.DustSmokeParticle;
import org.ywzj.vehicle.client.particle.SmokeCloudParticle;
import org.ywzj.vehicle.client.particle.TrackDustParticle;
import org.ywzj.vehicle.client.particle.MissileTrailParticle;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** 所有 RVP 屏幕光共用的烟雾/炮口薄面片受光层；保留贴图 Alpha，不写特效深度。 */
public final class RVP_SmokeLightPass {
    /** 有限诊断。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当帧真实薄面片，包含引擎烟、自绘云和炮口特殊骨；三者共用数量上限。 */
    private static final RVP_SmokeLightGeometry GEOMETRY = new RVP_SmokeLightGeometry();
    /** 专用材质程序，不修改其它模组的材质。 */
    private static ShaderInstance shader;
    /** 烟雾光照上传缓冲，和原粒子引擎缓冲隔离。 */
    private static final BufferBuilder BUILDER = new BufferBuilder(262144);
    /** 获取原 RenderType 贴图所用的空批，不提交到画面。 */
    private static final BufferBuilder MATERIAL_PROBE = new BufferBuilder(256);
    /** 持续复用的显卡顶点缓冲。 */
    private static VertexBuffer vertexBuffer;
    /** 本体已存在的 public getParticles 入口，按类型缓存，不新增访问器或注入。 */
    private static Method particleAccessor;
    /** public 入口所属运行时类，不缓存具体引擎实例。 */
    private static Class<?> particleEngineClass;
    /** 读取失败只报告一次。 */
    private static boolean accessorWarning;
    /** 仅在主世界天空完成至粒子前采集模型顶点；界面预览不能混入世界光照。 */
    private static boolean modelCaptureOpen;
    /** 与 Shader 完全相同的观察空间光球，复用数组用于炮口面片剔除。 */
    private static final float[] VIEW_LIGHT_SPHERES = new float[RVP_ScreenLightBudget.MAX_LIGHTS * 4];

    private RVP_SmokeLightPass() { }

    /** 在现有注册事件中安装新程序，文件由用户授权新增。 */
    static void register(RegisterShadersEvent event) throws IOException {
        shader = null;
        // 调用与表面光共用有界遮挡核心的另名程序，原烟雾资源保留。
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("smoke_light_occlusion"),
                DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> shader = loaded);
    }

    /** 无环境灯、环境光或烟雾受光开关关闭、热成像时不收集，不依赖粒子数量生成光源。 */
    private static boolean wanted() {
        return shader != null && RVP_LightingConfig.screenEnabled() && RVP_LightingConfig.smokeLightEnabled()
                && RVP_LightingConfig.intensity() > 0
                && RVP_LightingConfig.maxLights() > 0 && RVP_ScreenLightManager.INSTANCE.hasActiveLights()
                && RVP_LightingCompatibility.disabledReason().isEmpty();
    }

    /** 每显示帧重置，暂停和同 tick 多帧也不能重复累计几何。 */
    static void beginFrame() { GEOMETRY.beginFrame(); modelCaptureOpen = false; }

    /** 复用既有 Forge 世界事件限定模型采集窗口，不新增注入点或渲染队列。 */
    static void worldStage(RenderLevelStageEvent.Stage stage) {
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) { modelCaptureOpen = true; }
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS
                || stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) { modelCaptureOpen = false; }
    }

    /** 公开给 RVP 模型的轻量入口；太阳阴影重画不能被误当成玩家眼前的炮口。 */
    public static boolean capturesMuzzle() {
        // 调用既有光影只读桥排除太阳视角，DH 和光影主画面仍正常接入。
        return modelCaptureOpen && wanted() && !RVP_LightingCompatibility.shadowPass();
    }

    /** 炮口模型顶点已乘实体/骨骼及观察矩阵；保留该空间，不重复旋转相机。 */
    public static VertexConsumer captureMuzzle(VertexConsumer original, ResourceLocation texture) {
        if (!capturesMuzzle()) { return original; }
        int id = Minecraft.getInstance().getTextureManager().getTexture(texture).getId();
        // 调用与烟雾共用的记录器，原材质与原顶点继续照常绘制、不增加深度写入。
        return GEOMETRY.capture(original, id, true);
    }

    /** RVP 自绘云正常绘制时包装顶点，不改贴图、粒子颜色、动画或 Alpha。 */
    public static VertexConsumer capture(VertexConsumer original, ResourceLocation texture) {
        if (!wanted()) { return original; }
        int id = Minecraft.getInstance().getTextureManager().getTexture(texture).getId();
        // 调用共用记录器，原顶点继续原样写入调用者的正常绘制缓冲。
        return GEOMETRY.capture(original, id);
    }

    /** 明确覆盖本项目、本体及原版烟/灰尘，不将火花、方块碎片或雨水统一当作烟雾。 */
    static boolean smoke(Particle particle) {
        return particle instanceof BaseAshSmokeParticle || particle instanceof CampfireSmokeParticle
                || particle instanceof ExplodeParticle || particle instanceof MissileTrailParticle
                || particle instanceof SmokeParticle || particle instanceof SmokeCloudParticle
                || particle instanceof DustSmokeParticle || particle instanceof TrackDustParticle
                || particle instanceof RVP_MchrSmokeParticle || particle instanceof RVP_NearSmokeParticle
                || particle instanceof RVP_WreckSmokeParticle || particle instanceof RVP_FlareTrailSmokeParticle
                || particle instanceof RVP_WhitePhosphorusParticle || particle instanceof RVP_RocketFlameParticle;
    }

    /** 复用本体既有 public 入口，读取所有渲染批；不改队列、不包装粒子实体、不增加 Mixin。 */
    private static void collectParticles(Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int[] textures = {RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2)};
        try {
            Class<?> type = mc.particleEngine.getClass();
            if (particleEngineClass != type || particleAccessor == null) {
                particleAccessor = type.getMethod("getParticles"); particleEngineClass = type;
            }
            Object result = particleAccessor.invoke(mc.particleEngine);
            if (!(result instanceof Map<?, ?> batches)) { return; }
            for (Map.Entry<?, ?> entry : batches.entrySet()) {
                if (!(entry.getKey() instanceof ParticleRenderType renderType)
                        || renderType == ParticleRenderType.NO_RENDER || renderType == ParticleRenderType.CUSTOM
                        || !(entry.getValue() instanceof Iterable<?> particles)) { continue; }
                VertexConsumer capture = null;
                for (Object item : particles) {
                    if (!(item instanceof Particle particle) || !particle.isAlive() || !smoke(particle)) { continue; }
                    if (capture == null) {
                        // 调用原材质 begin 取得实际独立贴图或图集，不调用 end、不把探测批画进主画面。
                        renderType.begin(MATERIAL_PROBE, mc.getTextureManager());
                        int texture = RenderSystem.getShaderTexture(0);
                        if (MATERIAL_PROBE.building()) { MATERIAL_PROBE.end().release(); }
                        capture = GEOMETRY.capture(null, texture);
                    }
                    if (capture != null) {
                        // 调用粒子原绘制函数只取当前面片，保留当前帧、抖动层、UV、颜色和透明度。
                        particle.render(capture, camera, partialTick);
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            if (!accessorWarning) {
                LOGGER.warn("RVP 烟雾光照无法读取本体既有粒子批次，自绘烟云通道继续", exception);
                accessorWarning = true;
            }
        } finally {
            for (int index = 0; index < textures.length; index++) { RenderSystem.setShaderTexture(index, textures[index]); }
        }
    }

    /** 世界光照完成后增加烟雾受光；只使用不含粒子烟的方块/实体深度作为遮挡。 */
    static void render(RVP_ScreenFrameResources frame, Camera camera, float partialTick) {
        // 发动机光只照墙体和地面：移动中的发动机光若重新点亮旧尾迹烟，会沿旧尾迹出现
        // 第二团火球（看起来像旧导弹被重画），因此烟雾层不接收发动机光。
        List<RVP_ScreenLight> lights = RVP_ScreenLightPass.currentLights().stream()
                // 干扰机仅保留玻片周围的小范围受光，不将夜间红光投到所有烟雾。
                .filter(light -> light.type() != RVP_LightSourceType.ROCKET_ENGINE && light.type() != RVP_LightSourceType.EO_JAMMER).toList();
        if (!wanted() || lights.isEmpty()) { return; }
        // 调用通用性能范围，默认关闭时不申请显卡查询；所有提前返回和异常均自动封口。
        try (var performance = RVP_RenderPerformance.scope(RVP_RenderStage.SMOKE_LIGHT)) {
            // 调用引擎收集，覆盖本体/原版粒子和所有已知 RVP 粒子烟；自绘烟已由正常绘制记录。
            collectParticles(camera, partialTick);
            RVP_ScreenLightPass.uploadScene(shader);
            // 调用同帧分层深度绑定；烟面片只接收光照，仍不会进入遮挡输入。
            RVP_LightOcclusionPass.upload(shader, frame.depth());
            // 调用表面光同一套三维碰撞几何，烟雾自身仍不成为遮挡物。
            RVP_LightGeometryPass.upload(shader);
            shader.safeGetUniform("OutputMode").set(0);
            shader.safeGetUniform("LightCount").set(lights.size());
            for (int index = 0; index < RVP_ScreenLightBudget.MAX_LIGHTS; index++) {
                if (index >= lights.size()) {
                    shader.safeGetUniform("LightAmbient" + index).set(RVP_AmbientLightSampler.UNAVAILABLE);
                    shader.safeGetUniform("LightPositionRadius" + index).set(0F, 0F, 0F, 0F);
                    shader.safeGetUniform("LightColorIntensity" + index).set(0F, 0F, 0F, 0F); continue;
                }
                RVP_ScreenLight light = lights.get(index);
                // 调用同帧源位置缓存，烟雾筛掉发动机及干扰机后不能沿用原八灯槽号。
                shader.safeGetUniform("LightAmbient" + index).set(RVP_ScreenLightPass.ambientBrightness(light.position()));
                // 调用相同观察变换与年龄/阶段采样，爆炸、炮口、发动机没有烟雾专用白名单。
                Vector3f position = RVP_ScreenLightPass.viewPosition(light.position());
                VIEW_LIGHT_SPHERES[index * 4] = position.x;
                VIEW_LIGHT_SPHERES[index * 4 + 1] = position.y;
                VIEW_LIGHT_SPHERES[index * 4 + 2] = position.z;
                VIEW_LIGHT_SPHERES[index * 4 + 3] = light.radius();
                shader.safeGetUniform("LightPositionRadius" + index).set(position.x, position.y, position.z, light.radius());
                // 调用烟雾独立亮度倍率，沿用同一批已调参光源、透明边缘和场景遮挡。
                shader.safeGetUniform("LightColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                        light.intensity() * light.surfaceWeight() * RVP_LightingConfig.intensity()
                                * RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global,
                                        RVP_LightRuntimeTuning.Parameter.smokeBrightness));
            }
            if (vertexBuffer == null) { vertexBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC); }
            for (RVP_SmokeLightGeometry.Batch batch : GEOMETRY.batches()) {
                boolean open = false;
                for (int quad = 0; quad + 4 * RVP_SmokeLightGeometry.STRIDE <= batch.size; quad += 4 * RVP_SmokeLightGeometry.STRIDE) {
                    // 调用面片/光球相交筛选，远离所有光源的云团不进入额外显卡通道。
                    if (!batch.intersects(quad, lights, camera.getPosition(), batch.viewSpace ? VIEW_LIGHT_SPHERES : null)) { continue; }
                    if (!open) { BUILDER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR); open = true; }
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int at = quad + vertex * RVP_SmokeLightGeometry.STRIDE;
                        float[] data = batch.data;
                        BUILDER.vertex(data[at], data[at + 1], data[at + 2]).uv(data[at + 3], data[at + 4])
                                .color(data[at + 5], data[at + 6], data[at + 7], data[at + 8]).endVertex();
                    }
                }
                if (!open) { continue; }
                frame.bind(frame.mainTarget(), frame.width(), frame.height());
                vertexBuffer.bind(); vertexBuffer.upload(BUILDER.end());
                shader.setSampler("Sampler0", batch.texture);
                // 调用空间匹配：粒子额外乘世界观察矩阵，炮口已在观察空间，使用单位矩阵。
                RVP_ScreenLightPass.uploadGeometryView(shader, batch.viewSpace);
                shader.apply();
                try {
                    // 调用只加亮、保留原场景 Alpha 的混合；贴图透明区域由片元程序保持零贡献。
                    frame.beginEmissionBlend();
                    // 调用实际批次绘制计数；贴图面片没有可靠屏幕面积时不填伪造像素数。
                    RVP_RenderPerformance.add(RVP_RenderMetric.DRAW_CALLS, 1);
                    vertexBuffer.draw();
                } finally { shader.clear(); VertexBuffer.unbind(); }
            }
        }
    }

    /** 世界卸载或资源重载释放自有显存和旧贴图编号；调用方保证在渲染线程执行。 */
    static void release() {
        GEOMETRY.clear(); modelCaptureOpen = false;
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
    }
}
