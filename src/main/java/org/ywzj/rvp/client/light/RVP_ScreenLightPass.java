package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhProjectionMath;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DistantHorizonsCompatBootstrap;
import java.util.List;

/** 世界颜色/深度→统一八盏光→可选半分辨率光晕；总在 CRT/TV/HUD 之前完成。 */
public final class RVP_ScreenLightPass {
    /** 限频兼容性日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 所有光共享的颜色、深度与绘制目标。 */
    private static final RVP_ScreenFrameResources FRAME = new RVP_ScreenFrameResources();
    /** 基础光 Shader；失败时整层关闭，原有特效不受影响。 */
    private static ShaderInstance lightShader;
    /** 可选光晕 Shader；失败时回退基础光直接合成。 */
    private static ShaderInstance bloomShader;
    /** 天气阶段保存的完整世界观察矩阵。 */
    private static final Matrix4f VIEW = new Matrix4f();
    /** 与深度配对的世界投影。 */
    private static final Matrix4f PROJECTION = new Matrix4f();
    /** 已筛选的本帧光源，不在后处理时重新推进生命周期。 */
    private static List<RVP_ScreenLight> lights = List.of();
    /** 捕获时的相机位置，世界坐标 double 相减后再交显卡。 */
    private static Vec3 camera = Vec3.ZERO;
    /** 深度输入所属世界，换维度不能消费旧画面。 */
    private static ClientLevel frameLevel;
    /** 上一次诊断的单调时钟时间，单位纳秒。 */
    private static long lastDiagnostic;
    /** 本次资源重载后光晕是否仍可分配，失败时不每帧重试。 */
    private static boolean bloomAvailable = true;
    /** 近景深度矩阵分析，同时包含实际空深度方向。 */
    private static RVP_DhProjectionMath.ProjectionAnalysis nearAnalysis;
    /** 本帧 DH 深度来源；无远景像素时仍照常处理近景。 */
    private static RVP_LightDepthFrame.Snapshot distant;
    /** DH 深度矩阵分析，不能与近景比较原始 0～1 深度值。 */
    private static RVP_DhProjectionMath.ProjectionAnalysis distantAnalysis;
    /** 最近一次实际画面输入状态，供客户端命令查看。 */
    private static String lastFrameStatus = "尚未绘制活动光源";

    private RVP_ScreenLightPass() { }

    /** 渲染帧开始即丢弃旧来源，早于 DH 的天空/地形事件。 */
    public static void beginFrame() {
        lights = List.of(); frameLevel = null; distant = null; distantAnalysis = null;
        // 调用两份帧状态清空；游戏暂停或没有 DH 回调时也不会复用旧深度。
        FRAME.discard();
        RVP_LightDepthFrame.INSTANCE.beginFrame(Minecraft.getInstance().level);
    }

    /** 由已隔离的 DH 公共 API 桥调用，只记录有活动光源的非阴影帧。 */
    public static void acceptDistantDepth(int texture, Matrix4f projection, Matrix4f view) {
        // 调用光源存在与光影阴影状态查询，太阳阴影贴图不能进入玩家画面的深度通道。
        if (!RVP_LightingConfig.screenEnabled() || !RVP_ScreenLightManager.INSTANCE.hasActiveLights()
                || RVP_LightingCompatibility.shadowPass()) { return; }
        RVP_LightDepthFrame.INSTANCE.publish(Minecraft.getInstance().level, texture, projection, view);
    }

    /** DH 已完成地形绘制的当帧证明，光影覆盖 API 纹理时仍可使用它自己的公开 getter。 */
    public static void markDistantRendered() {
        // 调用阴影过滤，太阳方向的 DH 绘制不能给玩家画面颁发新帧证明。
        if (RVP_LightingConfig.screenEnabled() && RVP_ScreenLightManager.INSTANCE.hasActiveLights()
                && !RVP_LightingCompatibility.shadowPass()) {
            RVP_LightDepthFrame.INSTANCE.markRendered(Minecraft.getInstance().level);
        }
    }

    /** 最近一次真正捕获的输入路径，不能把“模组已安装”当成远景已经参与计算。 */
    public static String frameStatus() { return lastFrameStatus; }

    /** 在现有客户端注册链安装两个新程序，资源损坏时安全降级。 */
    public static void registerShaders(RegisterShadersEvent event) {
        lightShader = null; bloomShader = null; bloomAvailable = true;
        // 调用统一释放入口，资源重载时不能让旧场景和旧 Shader 配对。
        clear();
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("screen_light"),
                    DefaultVertexFormat.POSITION), loaded -> lightShader = loaded);
        } catch (Exception exception) {
            LOGGER.warn("RVP 屏幕光程序加载失败，已关闭屏幕光，保留现有爆炸和尾焰", exception);
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("screen_light_bloom"),
                    DefaultVertexFormat.POSITION), loaded -> bloomShader = loaded);
        } catch (Exception exception) {
            LOGGER.warn("RVP 光晕程序加载失败，回退基础屏幕光", exception);
        }
    }

    /** 只有可见活动光才保存一次场景深度；这里的事件矩阵仍是真实观察矩阵。 */
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) { return; }
        lights = List.of(); frameLevel = null;
        // 调用帧输入失效，暂停、窗口变化和无光帧均不复用旧深度。
        FRAME.discard();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || lightShader == null || !RVP_LightingConfig.screenEnabled()
                || RVP_LightingConfig.maxLights() <= 0 || RVP_LightingConfig.intensity() <= 0) { return; }
        // 调用世界身份检查，渲染先于客户端 tick 的新世界首帧也能正确清理。
        RVP_ScreenLightManager.INSTANCE.beginWorld(mc.level, mc.level.getGameTime());
        if (!RVP_ScreenLightManager.INSTANCE.hasActiveLights()) { return; }
        // 调用只读兼容性检查，拿不到可信世界输入时不操作外部目标。
        String reason = RVP_LightingCompatibility.disabledReason();
        if (!reason.isEmpty()) { diagnostic(reason, null); return; }
        VIEW.set(event.getPoseStack().last().pose());
        PROJECTION.set(event.getProjectionMatrix());
        // 调用光影公开场景快照查询，取得与深度配对的原世界矩阵。
        RVP_LightingCompatibility.sceneMatrices(VIEW, PROJECTION);
        if (!validMatrix(VIEW) || !validMatrix(PROJECTION)) { diagnostic("世界矩阵无效，跳过本帧", null); return; }
        // 调用已有正/反向深度分析，不能硬编码天空总是深度 1。
        nearAnalysis = RVP_DhProjectionMath.analyzeFromProjection(PROJECTION).orElse(null);
        if (nearAnalysis == null) { diagnostic("近景投影端点无效，跳过本帧", null); return; }
        camera = event.getCamera().getPosition();
        // 调用统一预算和球体投影裁剪，先剔除离屏光，最多只为八盏光查询实体/地形遮挡。
        List<RVP_ScreenLight> candidates = RVP_ScreenLightManager.INSTANCE.collectVisible(camera, event.getPartialTick(),
                RVP_LightingConfig.maxDistance(), RVP_LightingConfig.maxLights(), light -> {
                    Vector3f center = viewPosition(light.position());
                    return RVP_PressureWaveGeometry.screenBounds(PROJECTION, center.x, center.y, center.z, light.radius()) != null;
                });
        // 调用世界碰撞查询补足屏幕外遮挡：被墙完全藏住的灯不照亮墙的正面。
        lights = candidates.stream().filter(light -> visibleFromCamera(mc, light)).toList();
        if (lights.isEmpty()) { return; }
        try {
            // 调用共享帧资源，每帧最多复制一次深度，与本帧矩阵配对。
            if (!FRAME.captureDepth()) {
                lights = List.of(); lastFrameStatus = "近景深度暂不可用";
                diagnostic(lastFrameStatus, null); return;
            }
            // 调用独立 DH/光影只读桥，远景深度另存一份，保持其自己的投影与观察矩阵。
            distant = RVP_LightingCompatibility.distant(mc.level, VIEW);
            distantAnalysis = distant == null ? null
                    : RVP_DhProjectionMath.analyzeFromProjection(distant.projection()).orElse(null);
            try {
                if (distantAnalysis == null || !FRAME.captureDistantDepth(distant.texture())) {
                    distant = null; distantAnalysis = null;
                }
            } catch (RuntimeException exception) {
                distant = null; distantAnalysis = null;
                diagnostic("本帧 DH 深度读取失败，近景光继续", exception);
            }
            frameLevel = mc.level;
            lastFrameStatus = (RVP_LightingCompatibility.shadersActive() ? "光影最终画面" : "原版最终画面")
                    + "；近景深度已捕获；" + (distant != null ? "DH 深度已捕获"
                    : RVP_DistantHorizonsCompatBootstrap.isDhRenderingEnabled() ? "DH 本帧没有可用深度，近景光继续" : "DH 未绘制");
        } catch (RuntimeException exception) {
            lights = List.of(); diagnostic("保存场景深度失败，跳过本帧", exception);
        }
    }

    /** HIGHEST 优先级世界末端调用，颜色已合成，CRT/TV 和所有 HUD 尚未开始。 */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) { return; }
        ClientLevel expected = frameLevel;
        frameLevel = null;
        if (expected == null || expected != Minecraft.getInstance().level || lights.isEmpty() || lightShader == null) {
            FRAME.discard(); return;
        }
        // 调用状态作用域，包含绘制/读取目标、视口、采样器和原版渲染状态的完整恢复。
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            if (!FRAME.captureColor()) { diagnostic("场景目标或窗口发生变化，跳过本帧", null); return; }
            // 调用统一参数上传；世界位置先用 double 减相机，远坐标不会抖动。
            uploadLights();
            boolean useBloom = RVP_LightingConfig.bloomEnabled() && bloomShader != null && bloomAvailable
                    && lights.stream().anyMatch(RVP_ScreenLight::bloom);
            if (useBloom) {
                try { FRAME.ensureBloom(); }
                catch (RuntimeException exception) {
                    bloomAvailable = false; useBloom = false;
                    diagnostic("光晕目标不可用，回退基础屏幕光", exception);
                }
            }
            if (!useBloom) {
                lightShader.safeGetUniform("OutputMode").set(0);
                FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
                FRAME.draw(lightShader);
                // 调用喷口独立叠加，即使关闭光晕，核心仍可达到显示白点而非旧的 0.9 上限。
                renderEmission();
                return;
            }
            lightShader.safeGetUniform("OutputMode").set(1);
            FRAME.bind(FRAME.lightTarget(), FRAME.width(), FRAME.height());
            FRAME.draw(lightShader);
            // 调用半分辨率提取、横向模糊和纵向模糊；全部只读取灯光贡献，不把 HUD 或整片白天纳入光晕。
            bloomPass(0, FRAME.light(), FRAME.bloomTarget(0), 0, 0);
            bloomPass(1, FRAME.bloom(0), FRAME.bloomTarget(1), 2.0F / FRAME.bloomWidth(), 0);
            bloomPass(1, FRAME.bloom(1), FRAME.bloomTarget(0), 0, 2.0F / FRAME.bloomHeight());
            // 调用最终合成，不再复制背景，保留场景 Alpha。
            bloomShader.setSampler("InputTexture", FRAME.light());
            bloomShader.setSampler("SceneColor", FRAME.color());
            bloomShader.setSampler("BloomTexture", FRAME.bloom(0));
            bloomShader.safeGetUniform("Mode").set(2);
            FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
            FRAME.draw(bloomShader);
            // 调用只增强喷口的显示峰值，不提高普通爆炸/炮口光的曝光上限。
            renderEmission();
        } catch (RuntimeException exception) { diagnostic("灯光合成失败，保留世界画面", exception); }
        finally { lights = List.of(); FRAME.discard(); }
    }

    /** 上传固定八盏光；未用的槽位清零，避免上一帧数据泄漏。 */
    private static void uploadLights() {
        lightShader.setSampler("SceneColor", FRAME.color());
        lightShader.setSampler("SceneDepth", FRAME.depth());
        // 没有 DH 时仍绑定完整有效的纹理，避免未用采样器携带外部阴影比较模式。
        lightShader.setSampler("DistantDepth", distant == null ? FRAME.depth() : FRAME.distantDepth());
        lightShader.safeGetUniform("InverseProjection").set(nearAnalysis.inverseProjection());
        lightShader.safeGetUniform("Projection").set(PROJECTION);
        lightShader.safeGetUniform("NearEmptyDepth").set(nearAnalysis.emptyDepth());
        // Iris/Oculus 在世界结束前已绘制手持物，其压缩到近处的深度不能当作真实墙面受光。
        lightShader.safeGetUniform("HandDepthLimit").set(RVP_LightingCompatibility.handDepthLimit());
        lightShader.safeGetUniform("HasDistantDepth").set(distant == null ? 0 : 1);
        lightShader.safeGetUniform("DistantEmptyDepth").set(distantAnalysis == null ? 1F : distantAnalysis.emptyDepth());
        lightShader.safeGetUniform("DistantProjection").set(distant == null ? PROJECTION : distant.projection());
        lightShader.safeGetUniform("InverseDistantProjection").set(distantAnalysis == null
                ? nearAnalysis.inverseProjection() : distantAnalysis.inverseProjection());
        // 两种深度必须先还原到同一观察空间，再按实际距离挑选最近可见表面。
        Matrix4f distantToView = distant == null ? new Matrix4f()
                : new Matrix4f(VIEW).mul(distant.view().invert());
        lightShader.safeGetUniform("DistantToView").set(distantToView);
        lightShader.safeGetUniform("ViewToDistant").set(new Matrix4f(distantToView).invert());
        lightShader.safeGetUniform("ScreenSize").set((float) FRAME.width(), (float) FRAME.height());
        lightShader.safeGetUniform("LightCount").set(lights.size());
        for (int index = 0; index < RVP_ScreenLightBudget.MAX_LIGHTS; index++) {
            if (index >= lights.size()) {
                lightShader.safeGetUniform("LightPositionRadius" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightColorIntensity" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightShape" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightEmission" + index).set(0F, 0F, 0F, 0F);
                continue;
            }
            RVP_ScreenLight light = lights.get(index);
            Vector3f center = viewPosition(light.position());
            // 当前 P5 在代码层关闭；按完整屏幕光强度验证，不再施加两层光叠加时的 0.65 倍补偿。
            lightShader.safeGetUniform("LightPositionRadius" + index).set(center.x, center.y, center.z, light.radius());
            lightShader.safeGetUniform("LightColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                    light.intensity() * RVP_LightingConfig.intensity());
            lightShader.safeGetUniform("LightShape" + index).set(light.surfaceWeight(),
                    RVP_LightingConfig.volumetricEnabled() ? light.volumetricStrength() : 0,
                    light.bloom() ? 1F : 0F, 0F);
            if (light.type() == RVP_LightSourceType.ROCKET_ENGINE) {
                // 调用统一代码预设；已采样的强度包含二脉冲、燃尽和预览年龄，不能重复乘阶段。
                RVP_LightParameters.EngineEmission emission = RVP_LightParameters.engineEmission(light.radius(), light.intensity());
                lightShader.safeGetUniform("LightEmission" + index).set(emission.coreRadius(), emission.haloRadius(),
                        emission.coreIntensity() * RVP_LightingConfig.intensity(),
                        emission.haloIntensity() * RVP_LightingConfig.intensity());
            } else { lightShader.safeGetUniform("LightEmission" + index).set(0F, 0F, 0F, 0F); }
        }
    }

    /** 核心独立达到显示峰值；使用硬件混合读取现有颜色，不增加背景复制或引入读写反馈。 */
    private static void renderEmission() {
        if (lights.stream().noneMatch(light -> light.type() == RVP_LightSourceType.ROCKET_ENGINE)) { return; }
        lightShader.safeGetUniform("OutputMode").set(2);
        // 调用原主目标和专用混合，深度/手持物遮罩仍使用已配对的近景与 DH 来源。
        FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
        FRAME.drawEmission(lightShader);
    }

    /** 执行一轮提取或模糊；半分辨率目标相互独立，绝不同时读写同一附件。 */
    private static void bloomPass(int mode, int input, int target, float dx, float dy) {
        bloomShader.setSampler("InputTexture", input);
        // 即使该模式未使用，三个采样器也保持完整有效的纹理绑定。
        bloomShader.setSampler("SceneColor", FRAME.color());
        bloomShader.setSampler("BloomTexture", FRAME.light());
        bloomShader.safeGetUniform("Mode").set(mode);
        bloomShader.safeGetUniform("Direction").set(dx, dy);
        FRAME.bind(target, FRAME.bloomWidth(), FRAME.bloomHeight());
        FRAME.draw(bloomShader);
    }

    /** 还原观察空间光位置；与深度重建使用完全相同的观察矩阵。 */
    private static Vector3f viewPosition(Vec3 position) {
        return VIEW.transformPosition(new Vector3f((float) (position.x - camera.x),
                (float) (position.y - camera.y), (float) (position.z - camera.z)));
    }

    /** 保守遮挡补充：不可见光源直接不提交，避免深度图没有记录的侧墙漏光。 */
    private static boolean visibleFromCamera(Minecraft mc, RVP_ScreenLight light) {
        if (camera.distanceToSqr(light.position()) < 0.04) { return true; }
        HitResult hit = mc.level.clip(new ClipContext(camera, light.position(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.getCameraEntity()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(light.position()) < 0.04;
    }

    /** 矩阵必须有限且可逆，不能把 NaN 送到整屏程序。 */
    static boolean validMatrix(Matrix4f matrix) {
        for (float value : matrix.get(new float[16])) { if (!Float.isFinite(value)) { return false; } }
        float determinant = matrix.determinant();
        return Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-9F;
    }

    /** 每十秒最多一条诊断，不向聊天栏刷屏，也不主动读取/清除其它模组的 OpenGL 错误。 */
    private static void diagnostic(String reason, Throwable exception) {
        long now = System.nanoTime();
        if (lastDiagnostic != 0 && now - lastDiagnostic < 10_000_000_000L) { return; }
        lastDiagnostic = now;
        if (exception == null) { LOGGER.info("[RVP][Light] {}", reason); }
        else { LOGGER.warn("[RVP][Light] {}", reason, exception); }
    }

    /** 世界卸载和资源重载释放临时显存；非渲染线程只排队，不直接调用 GL。 */
    public static void clear() {
        lights = List.of(); frameLevel = null; distant = null; distantAnalysis = null; nearAnalysis = null;
        lastFrameStatus = "资源已清理，等待活动光源";
        // 调用借用引用清理，卸载后不触碰 DH 或光影拥有的纹理。
        RVP_LightDepthFrame.INSTANCE.clear();
        if (RenderSystem.isOnRenderThread()) { FRAME.release(); }
        else { RenderSystem.recordRenderCall(FRAME::release); }
    }
}
