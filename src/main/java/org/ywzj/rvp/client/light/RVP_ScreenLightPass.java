package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_BloomParameters;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhProjectionMath;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DistantHorizonsCompatBootstrap;
import java.util.List;

/** 世界颜色/深度→统一八盏光→四尺度光晕；总在 CRT/TV/HUD 之前完成。 */
public final class RVP_ScreenLightPass {
    /** 限频兼容性日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 所有光共享的颜色、深度与绘制目标。 */
    private static final RVP_ScreenFrameResources FRAME = new RVP_ScreenFrameResources();
    /** 同帧各光源的原版环境亮度；世界表面、粒子烟、体积烟与发动机外晕共享。 */
    private static final RVP_AmbientLightSampler AMBIENT = new RVP_AmbientLightSampler();
    /** 基础光 Shader；失败时整层关闭，原有特效不受影响。 */
    private static ShaderInstance lightShader;
    /** 可选光晕 Shader；失败时回退基础光直接合成。 */
    private static ShaderInstance bloomShader;
    /** 世界透明方块完成、粒子烟开始前保存的完整观察矩阵。 */
    private static final Matrix4f VIEW = new Matrix4f();
    /** 已在观察空间的模型面片不再乘第二次观察变换。 */
    private static final Matrix4f IDENTITY_VIEW = new Matrix4f();
    /** 与深度配对的世界投影。 */
    private static final Matrix4f PROJECTION = new Matrix4f();
    /** 已筛选的本帧光源，不在后处理时重新推进生命周期。 */
    private static List<RVP_ScreenLight> lights = List.of();
    /** 本帧独立筛选的程序喷焰，不占环境光的八个槽位。 */
    private static List<RVP_RocketPlume> plumes = List.of();
    /** 独立于灯光和仍燃烧喷口的本帧世界尾烟。 */
    private static List<RVP_RocketTrailSegment> trails = List.of();
    /** 世界时钟驱动动画，暂停时冻结；资源重载不沿用系统实时时间。 */
    private static float plumeTime;
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
    /** 最近捕获时真正提交的灯数、来源及位置；与管理器中尚未通过预算的条目区分。 */
    private static List<RVP_ScreenLight> lastSubmittedLights = List.of();

    private RVP_ScreenLightPass() { }

    /** 渲染帧开始即丢弃旧来源，早于 DH 的天空/地形事件。 */
    public static void beginFrame() {
        // 调用环境缓存清空，暂停、切世界或本帧没有灯光时也不能保留旧世界查询入口。
        AMBIENT.clear();
        lights = List.of(); frameLevel = null; distant = null; distantAnalysis = null;
        plumes = List.of();
        trails = List.of();
        Minecraft mc = Minecraft.getInstance();
        // 调用独立喷焰世界检查，早期 DH 回调到来之前先淘汰旧世界/过期状态。
        RVP_RocketPlumeManager.INSTANCE.beginWorld(mc.level, mc.level == null ? 0 : mc.level.getGameTime());
        // 调用世界尾烟时钟，燃尽后即使没有活动灯，也继续淘汰和绘制残留烟段。
        RVP_RocketTrailManager.INSTANCE.beginWorld(mc.level, mc.level == null ? 0 : mc.level.getGameTime());
        // 调用当帧烟雾几何清空，暂停及同一 tick 的多帧也不重复累加光照。
        RVP_SmokeLightPass.beginFrame();
        // 调用历史深度输入失效；正式投影使用下方三维几何，诊断也不能误读上帧范围。
        RVP_LightOcclusionPass.beginFrame();
        // 调用三维几何帧重置；方块快照按逻辑刻复用，实体盒仍逐显示帧插值。
        RVP_LightGeometryPass.beginFrame();
        lastSubmittedLights = List.of();
        // 调用两份帧状态清空；游戏暂停或没有 DH 回调时也不会复用旧深度。
        FRAME.discard();
        RVP_LightDepthFrame.INSTANCE.beginFrame(Minecraft.getInstance().level);
    }

    /** 由已隔离的 DH 公共 API 桥调用，只记录有活动光源的非阴影帧。 */
    public static void acceptDistantDepth(int texture, Matrix4f projection, Matrix4f view) {
        // 调用光源存在与光影阴影状态查询，太阳阴影贴图不能进入玩家画面的深度通道。
        if (!hasVisualInputs()
                || RVP_LightingCompatibility.shadowPass()) { return; }
        RVP_LightDepthFrame.INSTANCE.publish(Minecraft.getInstance().level, texture, projection, view);
    }

    /** DH 已完成地形绘制的当帧证明，光影覆盖 API 纹理时仍可使用它自己的公开 getter。 */
    public static void markDistantRendered() {
        // 调用阴影过滤，太阳方向的 DH 绘制不能给玩家画面颁发新帧证明。
        if (hasVisualInputs()
                && !RVP_LightingCompatibility.shadowPass()) {
            RVP_LightDepthFrame.INSTANCE.markRendered(Minecraft.getInstance().level);
        }
    }

    /** 最近一次真正捕获的输入路径，不能把“模组已安装”当成远景已经参与计算。 */
    public static String frameStatus() { return lastFrameStatus; }

    /** 命令只读显示真正提交的光源，方便排除正常多灯投影。 */
    static String submittedLightsStatus() {
        return "实际提交 " + lastSubmittedLights.size() + " 盏：" + lastSubmittedLights.stream()
                .map(light -> light.type() + "@" + light.position()).collect(java.util.stream.Collectors.joining("；"));
    }

    /** 在现有客户端注册链安装两个新程序，资源损坏时安全降级。 */
    public static void registerShaders(RegisterShadersEvent event) {
        lightShader = null; bloomShader = null; bloomAvailable = true;
        // 调用统一释放入口，资源重载时不能让旧场景和旧 Shader 配对。
        clear();
        try {
            // 调用已获授权的另名有界遮挡程序，保留原资源与独立照亮/光晕时间线。
            event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("screen_light_occlusion"),
                    DefaultVertexFormat.POSITION), loaded -> lightShader = loaded);
        } catch (Exception exception) {
            LOGGER.warn("RVP 屏幕光程序加载失败，已关闭屏幕光，保留现有爆炸和尾焰", exception);
        }
        try {
            // 调用已获确认的另名多尺度资源，原光晕程序完整保留。
            event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("screen_light_bloom_multiscale"),
                    DefaultVertexFormat.POSITION), loaded -> bloomShader = loaded);
        } catch (Exception exception) {
            LOGGER.warn("RVP 光晕程序加载失败，回退基础屏幕光", exception);
        }
        try {
            // 调用独立程序喷焰注册，旧模型及圆形核心资源不做删除或覆盖。
            RVP_RocketPlumePass.register(event);
        } catch (Exception exception) {
            LOGGER.warn("RVP 程序喷焰加载失败，请检查 rocket_plume_ranges 资源", exception);
        }
        try {
            // 调用烟雾独立受光材质注册，复用已有贴图，不新增粒子资源。
            RVP_SmokeLightPass.register(event);
        } catch (Exception exception) { LOGGER.warn("RVP 烟雾受光程序加载失败", exception); }
        try {
            // 调用已获确认的新体积尾烟资源注册，不覆盖任何既有喷焰或烟贴图资源。
            RVP_RocketTrailPass.register(event);
        } catch (Exception exception) { LOGGER.warn("RVP 体积尾烟加载失败，请检查 rocket_trail_volume 资源", exception); }
    }

    /** 尾焰是弹体视觉；关闭环境光或八灯预算为零时，仍要保留喷焰所需 DH 当帧输入。 */
    private static boolean hasVisualInputs() {
        return RVP_RocketPlumeManager.INSTANCE.hasActive()
                || RVP_RocketTrailManager.INSTANCE.hasActive()
                || hasLightWork();
    }

    /** 照亮总开关关闭时没有任何屏幕光需要绘制；喷焰与尾烟由各自的 hasActive 判断保留。 */
    private static boolean hasLightWork() {
        return RVP_LightingConfig.maxLights() > 0
                && RVP_LightingConfig.screenEnabled()
                && RVP_ScreenLightManager.INSTANCE.hasActiveLights();
    }

    /**
     * 透明方块/绊线完成后、原版粒子窗口前保存深度，保留实体、方块和光影不透明手持物。
     * Oculus 可在实体前提前画自定义粒子；RVP 烟材质自身禁写深度，不能只靠本事件保证排除烟。
     */
    public static void capture(RenderLevelStageEvent event) {
        // 调用模型顶点采集窗口：只在世界实体阶段记录炮口，早于粒子和最终屏幕合成关闭。
        RVP_SmokeLightPass.worldStage(event.getStage());
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) { return; }
        lights = List.of(); plumes = List.of(); frameLevel = null;
        trails = List.of();
        // 调用帧输入失效，暂停、窗口变化和无光帧均不复用旧深度。
        FRAME.discard();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || lightShader == null) {
            // 调用天空散射追踪，记录“着色器或世界不可用”这类整帧跳过。
            RVP_SkyScatterTrace.skip(mc.level == null ? "客户端世界为空" : "屏幕光着色器未加载（资源重载失败）");
            return;
        }
        // 调用世界身份检查，渲染先于客户端 tick 的新世界首帧也能正确清理。
        RVP_ScreenLightManager.INSTANCE.beginWorld(mc.level, mc.level.getGameTime());
        if (!hasVisualInputs()) {
            // 调用天空散射追踪，区分“总开关关闭/画面强度为0/确实没有活动光”。
            RVP_SkyScatterTrace.skip("没有任何视觉输入（screenLight=" + RVP_LightingConfig.screenEnabled()
                    + "，总强度=" + RVP_LightingConfig.intensity()
                    + "，跟踪条目=" + RVP_ScreenLightManager.INSTANCE.size() + "）");
            return;
        }
        // 调用只读兼容性检查，拿不到可信世界输入时不操作外部目标。
        String reason = RVP_LightingCompatibility.disabledReason();
        if (!reason.isEmpty()) {
            // 调用天空散射追踪，热成像等兼容性限制会让整层彩色光（含天空）关闭。
            RVP_SkyScatterTrace.skip(reason);
            diagnostic(reason, null);
            return;
        }
        VIEW.set(event.getPoseStack().last().pose());
        PROJECTION.set(event.getProjectionMatrix());
        // 调用光影公开场景快照查询，取得与深度配对的原世界矩阵。
        RVP_LightingCompatibility.sceneMatrices(VIEW, PROJECTION);
        if (!validMatrix(VIEW) || !validMatrix(PROJECTION)) {
            RVP_SkyScatterTrace.skip("世界矩阵无效");
            diagnostic("世界矩阵无效，跳过本帧", null);
            return;
        }
        // 调用已有正/反向深度分析，不能硬编码天空总是深度 1。
        nearAnalysis = RVP_DhProjectionMath.analyzeFromProjection(PROJECTION).orElse(null);
        if (nearAnalysis == null) {
            RVP_SkyScatterTrace.skip("近景投影端点无效");
            diagnostic("近景投影端点无效，跳过本帧", null);
            return;
        }
        camera = event.getCamera().getPosition();
        // 调用统一预算和球体投影裁剪，先剔除离屏光，最多只为八盏光查询实体/地形遮挡。
        // 追踪开启时额外传入诊断回调，把每条被剔除的候选与具体原因写进日志。
        // 照亮总开关只关闭照亮通道，不阻止采集：干扰机光晕仍需进入预算，逐灯分项由 hasEnabledChannel 判定。
        List<RVP_ScreenLight> candidates = RVP_LightingConfig.intensity() <= 0
                ? List.of() : RVP_ScreenLightManager.INSTANCE.collectVisible(camera, event.getPartialTick(),
                RVP_LightingConfig.maxDistance(), RVP_ScreenLightDebugCommands.limitForDiagnostics(RVP_LightingConfig.maxLights()), light -> {
                    // 调用分项开关：照亮/光晕/核心/天空全关的灯不占预算槽位，也不做遮挡查询。
                    if (!hasEnabledChannel(light)) { return false; }
                    // 调用显式单源诊断筛选；默认不影响正常灯数、颜色与生命周期。
                    if (!RVP_ScreenLightDebugCommands.acceptsForDiagnostics(light)) { return false; }
                    // 调用天空散射判断：方向性染色与光心是否在屏幕内无关，爆心在背后的夜空闪光也必须保留。
                    if (skyEnabled() && light.skyIntensity() > 0.001F) { return true; }
                    Vector3f center = viewPosition(light.position());
                    return RVP_PressureWaveGeometry.screenBounds(PROJECTION, center.x, center.y, center.z,
                            light.visibleRadius()) != null;
                }, RVP_SkyScatterTrace.enabled() ? RVP_SkyScatterTrace::dropped : null);
        // 三维遮挡按每个受光点到光源的路径判断；相机看不见光心不能成为取消整盏环境光的理由。
        // 喷口/光晕仍由各自的近景与 DH 可见性检查控制，不在这里把整盏灯误删。
        lights = candidates;
        lastSubmittedLights = lights;
        // 调用独立视锥预算，不按光心方块射线拒绝整条喷流：喷口被遮住时，露出的尾段仍应正确裁剪。
        if (RVP_RocketPlumePass.available()) {
            plumes = RVP_RocketPlumeManager.INSTANCE.collect(camera, event.getPartialTick(),
                    plume -> RVP_RocketPlumePass.bounds(plume, VIEW, PROJECTION, camera, mc.getMainRenderTarget().height) != null);
        }
        if (RVP_RocketTrailPass.available()) {
            // 调用世界烟段收集；相机离开喷口或弹体移除，都不取消仍可见的旧烟。
            trails = RVP_RocketTrailManager.INSTANCE.collect(camera, event.getPartialTick(),
                    segment -> RVP_RocketTrailPass.bounds(segment, VIEW, PROJECTION, camera) != null);
        }
        if (lights.isEmpty() && plumes.isEmpty() && trails.isEmpty()) {
            RVP_SkyScatterTrace.skip("采样与预算后没有任何可绘制内容");
            return;
        }
        plumeTime = ((mc.level.getGameTime() & 0xFFFFL) + event.getPartialTick()) / 20F;
        try {
            // 调用共享帧资源，每帧最多复制一次深度，与本帧矩阵配对。
            if (!FRAME.captureDepth()) {
                lights = List.of(); lastFrameStatus = "近景深度暂不可用";
                RVP_SkyScatterTrace.skip("近景深度暂不可用");
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
        if (expected == null || expected != Minecraft.getInstance().level
                || lights.isEmpty() && plumes.isEmpty() && trails.isEmpty() || lightShader == null) {
            // 调用天空散射追踪，区分“上一帧的帧身份失效/本帧无光/着色器丢失”。
            RVP_SkyScatterTrace.skip(expected == null ? "本帧没有捕获到世界画面（capture 阶段提前返回）"
                    : expected != Minecraft.getInstance().level ? "世界已切换，丢弃上一帧光源"
                    : lightShader == null ? "屏幕光着色器未加载" : "本帧没有提交任何光源");
            FRAME.discard(); return;
        }
        // 调用状态作用域，包含绘制/读取目标、视口、采样器和原版渲染状态的完整恢复。
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            if (!FRAME.captureColor()) {
                RVP_SkyScatterTrace.skip("场景颜色复制失败（目标或窗口变化）");
                diagnostic("场景目标或窗口发生变化，跳过本帧", null); return;
            }
            // 调用本帧原版光照取样入口；颜色副本已经固定，后续所有受光层只读同一份环境。
            AMBIENT.beginFrame(expected, event.getPartialTick());
            // 调用统一参数上传；世界位置先用 double 减相机，远坐标不会抖动。
            uploadLights();
            // 调用一次性的 uniform 声明检查：描述 JSON 漏写时 safeGetUniform 会静默返回空对象，天空项将恒为0。
            RVP_SkyScatterTrace.once("skyUniform", "着色器 uniform 检查：LightSkySpread0 "
                    + (lightShader.getUniform("LightSkySpread0") != null ? "已声明" : "缺失（描述 JSON 未同步，天空项会静默失效）")
                    + "，LightSkyColorIntensity0 "
                    + (lightShader.getUniform("LightSkyColorIntensity0") != null ? "已声明" : "缺失（描述 JSON 未同步，天空项会静默失效）"));
            // 照亮全关（默认状态）时只有光晕通道：不采集遮挡几何，也不跑整屏照亮 pass。
            boolean anySurface = lights.stream().anyMatch(light -> surfaceEnabled(light.type())
                    && light.radius() > 0 && light.intensity() > 0 && light.surfaceWeight() > 0);
            if (anySurface) {
                // 调用真实方块形状/实体盒采集，不再将缺失背面的相机深度用作投影几何。
                RVP_LightGeometryPass.prepare(Minecraft.getInstance().level, lights, VIEW, camera, event.getPartialTick());
            }
            // 调用统一几何绑定；数据不可用时保留照亮，不重新启用旧的轮廓投影猜测。
            RVP_LightGeometryPass.upload(lightShader);
            // 调用导弹广域橙晕开关：关闭时喷焰不再申请光晕链，纯导弹帧不再启动多尺度光晕。
            boolean useBloom = RVP_LightingConfig.bloomEnabled() && bloomShader != null && bloomAvailable
                    && (RVP_LightingConfig.missileHaloEnabled()
                            && plumes.stream().anyMatch(plume -> plume.haloRadius() > 0)
                    || lights.stream().anyMatch(light -> light.bloom() && haloEnabled(light.type())
                            && light.haloRadius() > 0));
            if (useBloom) {
                try { FRAME.ensureBloom(); }
                catch (RuntimeException exception) {
                    bloomAvailable = false; useBloom = false;
                    diagnostic("光晕目标不可用，回退基础屏幕光", exception);
                }
            }
            // 调用天空散射追踪帧摘要：本帧真正提交的光源与逐灯 sky 采样值，合并剔除原因后节流输出。
            RVP_SkyScatterTrace.frame(RVP_ScreenLightManager.INSTANCE.size(), lights,
                    useBloom ? "光晕链" : "直接合成（无光晕输入）");
            if (!useBloom) {
                // 纯喷焰帧没有环境灯：整屏灯光 pass 的输出与原画面完全相同，直接跳过。
                if (!lights.isEmpty()) {
                    lightShader.safeGetUniform("OutputMode").set(0);
                    FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
                    FRAME.draw(lightShader);
                }
                // 调用喷口独立叠加，即使关闭光晕，核心仍可达到显示白点而非旧的 0.9 上限。
                renderEmission();
                // 调用世界尺寸光晕；小分辨率 Bloom 目标失败时，仍可保留独立广域扩散。
                RVP_RocketPlumePass.renderHalos(FRAME, plumes, VIEW, PROJECTION, camera, plumeTime);
                // 调用真实喷流的独立显示叠加；关闭 Bloom 或环境照明不取消发动机视觉。
                RVP_RocketPlumePass.render(FRAME, plumes, VIEW, PROJECTION, camera, plumeTime, 2);
                // 调用独立烟雾受光，纯天空背景也按烟雾自己的几何位置计算，不拿背景墙代替它。
                RVP_SmokeLightPass.render(FRAME, event.getCamera(), event.getPartialTick());
                // 调用独立体积烟合成；无活动灯、关闭 Bloom 时也需要保留尾烟。
                RVP_RocketTrailPass.render(FRAME, trails, VIEW, PROJECTION, camera);
                // 调用用户显式请求的离屏诊断，已经合成完成的世界画面不受影响。
                if (!lights.isEmpty()) { RVP_LightOcclusionPass.captureReport(FRAME, lightShader); }
                return;
            }
            // 没有环境灯时灯光贡献恒为零：清空两张离屏目标即可，不再整屏跑两遍灯光程序。
            if (lights.isEmpty()) {
                FRAME.clearColorTarget(FRAME.lightTarget(), FRAME.width(), FRAME.height());
                FRAME.clearColorTarget(FRAME.glowTarget(), FRAME.width(), FRAME.height());
            } else {
                // 照亮全关时表面贡献恒为零：只清空照亮目标，不跑逐像素遮挡查询。
                if (anySurface) {
                    lightShader.safeGetUniform("OutputMode").set(1);
                    FRAME.bind(FRAME.lightTarget(), FRAME.width(), FRAME.height());
                    FRAME.draw(lightShader);
                } else {
                    FRAME.clearColorTarget(FRAME.lightTarget(), FRAME.width(), FRAME.height());
                }
                // 调用独立光晕来源：只读取每盏光的光晕范围，不把照亮结果送进模糊。
                lightShader.safeGetUniform("OutputMode").set(3);
                FRAME.bind(FRAME.glowTarget(), FRAME.width(), FRAME.height());
                FRAME.draw(lightShader);
            }
            // 调用定向喷焰合入独立光晕来源；最终白热核心仍单独显示。
            RVP_RocketPlumePass.render(FRAME, plumes, VIEW, PROJECTION, camera, plumeTime, 1);
            // 调用各层共同的模糊范围倍率；真实亮焰先通过连续过滤逐层缩小，不用稀疏大步长伪造远层。
            float blurStep = (plumes.isEmpty() ? 2F : RVP_RocketPlumeParameters.BLOOM_BLUR_STEP)
                    * RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.bloom,
                            RVP_LightRuntimeTuning.Parameter.blurScale);
            // 调用统一多尺度提取、柔化和加权回升，光晕输入始终只有 RVP 的光贡献。
            renderBloom(blurStep);
            // 调用最终合成，不再复制背景，保留场景 Alpha。
            bloomShader.setSampler("InputTexture", FRAME.light());
            bloomShader.setSampler("SceneColor", FRAME.color());
            bloomShader.setSampler("BloomTexture", FRAME.bloom(0, 1));
            bloomShader.safeGetUniform("Mode").set(2);
            FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
            FRAME.draw(bloomShader);
            // 调用只增强喷口的显示峰值，不提高普通爆炸/炮口光的曝光上限。
            renderEmission();
            // 调用独立光晕范围，再叠加真实细长核心；范围缩到零也不会取消喷焰本身。
            RVP_RocketPlumePass.renderHalos(FRAME, plumes, VIEW, PROJECTION, camera, plumeTime);
            RVP_RocketPlumePass.render(FRAME, plumes, VIEW, PROJECTION, camera, plumeTime, 2);
            RVP_SmokeLightPass.render(FRAME, event.getCamera(), event.getPartialTick());
            // 调用烟的吸收/散射混合，不加入高亮提取，也不污染用于遮挡的场景深度。
            RVP_RocketTrailPass.render(FRAME, trails, VIEW, PROJECTION, camera);
            // 调用可选单帧回读；正常绘制不会同步读取 GPU。
            if (!lights.isEmpty()) { RVP_LightOcclusionPass.captureReport(FRAME, lightShader); }
        } catch (RuntimeException exception) { diagnostic("灯光合成失败，保留世界画面", exception); }
        finally {
            lights = List.of(); plumes = List.of(); trails = List.of(); FRAME.discard();
            // 调用环境缓存释放，即使某个绘制层失败也不把世界引用留到下一帧。
            AMBIENT.clear();
        }
    }

    /** 上传固定八盏光；未用的槽位清零，避免上一帧数据泄漏。 */
    private static void uploadLights() {
        // 调用近景/DH 共用输入上传，新旧程序使用完全相同的深度约定。
        uploadScene(lightShader);
        // 调用显式遮挡绑定；构建新层级前 ready=false，会安全使用当帧原始深度。
        RVP_LightOcclusionPass.upload(lightShader, FRAME.depth());
        lightShader.safeGetUniform("LightCount").set(lights.size());
        // 调用观察空间方向换算，把世界竖直方向交给 Shader 作为定向光水平 140° 窗口的参考轴。
        Vector3f worldUpView = VIEW.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
        lightShader.safeGetUniform("WorldUpView").set(worldUpView.x, worldUpView.y, worldUpView.z);
        uploadLightSlots();
    }

    /** 上传同一帧近景、DH 与手持物输入，独立喷焰不另建一份深度管线。 */
    static void uploadScene(ShaderInstance target) {
        // 调用会话参数读取；统一上传给四个受光程序，F8及指令修改下一帧生效。
        target.safeGetUniform("AmbientSettings").set(
                RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global, RVP_LightRuntimeTuning.Parameter.ambientEnabled),
                RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global, RVP_LightRuntimeTuning.Parameter.ambientWorldWeight),
                RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global, RVP_LightRuntimeTuning.Parameter.ambientMinStrength),
                RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.global, RVP_LightRuntimeTuning.Parameter.ambientCurve));
        target.setSampler("SceneColor", FRAME.color());
        target.setSampler("SceneDepth", FRAME.depth());
        // 没有 DH 时仍绑定完整有效的纹理，避免未用采样器携带外部阴影比较模式。
        target.setSampler("DistantDepth", distant == null ? FRAME.depth() : FRAME.distantDepth());
        target.safeGetUniform("InverseProjection").set(nearAnalysis.inverseProjection());
        target.safeGetUniform("Projection").set(PROJECTION);
        target.safeGetUniform("NearEmptyDepth").set(nearAnalysis.emptyDepth());
        // Iris/Oculus 在世界结束前已绘制手持物，其压缩到近处的深度不能当作真实墙面受光。
        target.safeGetUniform("HandDepthLimit").set(RVP_LightingCompatibility.handDepthLimit());
        target.safeGetUniform("HasDistantDepth").set(distant == null ? 0 : 1);
        target.safeGetUniform("DistantEmptyDepth").set(distantAnalysis == null ? 1F : distantAnalysis.emptyDepth());
        target.safeGetUniform("DistantProjection").set(distant == null ? PROJECTION : distant.projection());
        target.safeGetUniform("InverseDistantProjection").set(distantAnalysis == null
                ? nearAnalysis.inverseProjection() : distantAnalysis.inverseProjection());
        // 两种深度必须先还原到同一观察空间，再按实际距离挑选最近可见表面。
        Matrix4f distantToView = distant == null ? new Matrix4f()
                : new Matrix4f(VIEW).mul(distant.view().invert());
        target.safeGetUniform("DistantToView").set(distantToView);
        target.safeGetUniform("ViewToDistant").set(new Matrix4f(distantToView).invert());
        target.safeGetUniform("ScreenSize").set((float) FRAME.width(), (float) FRAME.height());
        // 调用普通粒子默认变换；模型炮口在提交各自贴图批时单独切换单位矩阵。
        uploadGeometryView(target, false);
    }

    /** 同一受光程序分别接受相机相对粒子与已变换模型，避免相机转动造成光照错位。 */
    static void uploadGeometryView(ShaderInstance target, boolean viewSpace) {
        target.safeGetUniform("SmokeView").set(viewSpace ? IDENTITY_VIEW : VIEW);
    }

    /** 烟雾受光复用完全相同的预算和已采样灯光，不重置闪光年龄或发动机阶段。 */
    static List<RVP_ScreenLight> currentLights() { return lights; }

    /** 按实际世界位置取当帧缓存；烟雾重新排列灯槽后仍会绑定正确的那盏灯。 */
    static float ambientBrightness(Vec3 position) {
        // 调用共享环境取样器，持续发动机外晕也能复用已取样的位置。
        return AMBIENT.sample(position);
    }

    /** 上传环境光槽位；旧圆形喷口自发光由代码开关暂停，旧 Shader 实现仍保留。 */
    private static void uploadLightSlots() {
        for (int index = 0; index < RVP_ScreenLightBudget.MAX_LIGHTS; index++) {
            if (index >= lights.size()) {
                lightShader.safeGetUniform("LightAmbient" + index).set(RVP_AmbientLightSampler.UNAVAILABLE);
                lightShader.safeGetUniform("LightHemisphereAxis" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightPositionRadius" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightColorIntensity" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightHaloColorIntensity" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightShape" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightEmission" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightSkyColorIntensity" + index).set(0F, 0F, 0F, 0F);
                lightShader.safeGetUniform("LightSkySpread" + index).set(0F);
                continue;
            }
            RVP_ScreenLight light = lights.get(index);
            // 调用光源附近环境亮度，负值告诉显卡只用原画面，不把缺区块误当作暗处。
            lightShader.safeGetUniform("LightAmbient" + index).set(ambientBrightness(light.position()));
            if (light.hemisphereAxis() != null) {
                // 世界方向只乘观察旋转，不加相机平移；四分量分别上传，避免原版把数组误识别为标量。
                Vec3 axis = light.hemisphereAxis();
                Vector3f direction = VIEW.transformDirection(new Vector3f((float) axis.x, (float) axis.y, (float) axis.z)).normalize();
                lightShader.safeGetUniform("LightHemisphereAxis" + index).set(direction.x, direction.y, direction.z, 1F);
            } else { lightShader.safeGetUniform("LightHemisphereAxis" + index).set(0F, 0F, 0F, 0F); }
            Vector3f center = viewPosition(light.position());
            // 当前 P5 在代码层关闭；按完整屏幕光强度验证，不再施加两层光叠加时的 0.65 倍补偿。
            lightShader.safeGetUniform("LightPositionRadius" + index).set(center.x, center.y, center.z, light.radius());
            // 调用分项开关：照亮关闭时只置0强度，位置/半径仍上传，避免影响其它通道读取同一槽位。
            boolean surface = surfaceEnabled(light.type());
            boolean halo = haloEnabled(light.type());
            lightShader.safeGetUniform("LightColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                    surface ? light.intensity() * RVP_LightingConfig.intensity() : 0);
            // 调用独立光晕强度；照亮结束或爆炸brightness改动都不会覆盖该时间线和亮度。
            lightShader.safeGetUniform("LightHaloColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                    halo ? light.haloIntensity() * RVP_LightingConfig.intensity() : 0);
            // 照亮关闭时表面权重必须归零：Shader 以 shape.x>0 为条件执行逐像素遮挡步进。
            lightShader.safeGetUniform("LightShape" + index).set(surface ? light.surfaceWeight() : 0F,
                    RVP_LightingConfig.volumetricEnabled() ? light.volumetricStrength() : 0,
                    light.bloom() ? 1F : 0F,
                    halo ? (light.type() == RVP_LightSourceType.ROCKET_ENGINE ? 0F : light.haloRadius()) : 0F);
            // 调用自发光上传：爆炸白热核心与旧圆形喷口共用同一组 LightEmission 槽位，互不叠加。
            RVP_LightParameters.EngineEmission emission = coreEnabled(light.type()) ? light.emission() : null;
            if (emission == null && coreEnabled(light.type()) && RVP_RocketPlumeParameters.ROUND_SOURCE_ENABLED
                    && light.type() == RVP_LightSourceType.ROCKET_ENGINE) {
                // 调用统一代码预设；旧通道只在开关恢复时使用，已采样的强度包含二脉冲与燃尽。
                emission = RVP_LightParameters.engineEmission(light.radius(), light.intensity());
            }
            if (emission == null) {
                lightShader.safeGetUniform("LightEmission" + index).set(0F, 0F, 0F, 0F);
            } else {
                lightShader.safeGetUniform("LightEmission" + index).set(emission.coreRadius(), emission.haloRadius(),
                        emission.coreIntensity() * RVP_LightingConfig.intensity(),
                        emission.haloIntensity() * RVP_LightingConfig.intensity());
            }
            // 调用天空散射独立上传；只被没有深度的天空像素读取，不计入光晕提取能量，也不参与八灯预算排序。
            lightShader.safeGetUniform("LightSkyColorIntensity" + index).set(light.red(), light.green(), light.blue(),
                    skyEnabled() ? light.skyIntensity() * RVP_LightingConfig.intensity() : 0);
            lightShader.safeGetUniform("LightSkySpread" + index).set(light.skySpread());
        }
    }

    /** 核心独立达到显示峰值；使用硬件混合读取现有颜色，不增加背景复制或引入读写反馈。 */
    private static void renderEmission() {
        if (!hasEmission()) { return; }
        lightShader.safeGetUniform("OutputMode").set(2);
        // 调用原主目标和专用混合，深度/手持物遮罩仍使用已配对的近景与 DH 来源。
        FRAME.bind(FRAME.mainTarget(), FRAME.width(), FRAME.height());
        FRAME.drawEmission(lightShader);
    }

    /** 本帧是否存在需要绕过 0.9 曝光上限的自发光核心：爆炸白热核心优先，旧圆形喷口只在开关恢复时参与。 */
    private static boolean hasEmission() {
        for (RVP_ScreenLight light : lights) {
            if (coreEnabled(light.type()) && light.emission() != null
                    && (light.emission().coreIntensity() > 0.001F || light.emission().haloIntensity() > 0.001F)) {
                return true;
            }
        }
        // 调用旧通道开关；发动机没有核心分项开关，只有开关恢复时才参与。
        return RVP_RocketPlumeParameters.ROUND_SOURCE_ENABLED
                && lights.stream().anyMatch(light -> light.type() == RVP_LightSourceType.ROCKET_ENGINE);
    }

    /** 照亮通道：总开关关闭时不照亮任何表面；开启后按来源类型读取分项开关。 */
    private static boolean surfaceEnabled(RVP_LightSourceType type) {
        return RVP_LightingConfig.screenEnabled() && switch (type) {
            case EXPLOSION -> RVP_LightingConfig.explosionLightEnabled();
            case MUZZLE -> RVP_LightingConfig.muzzleLightEnabled();
            case ROCKET_ENGINE -> RVP_LightingConfig.engineLightEnabled();
            case EO_JAMMER, LASER -> true;
        };
    }

    /** 光晕通道：全部跟随照亮总开关，干扰机玻片也不例外。 */
    private static boolean haloEnabled(RVP_LightSourceType type) {
        return RVP_LightingConfig.screenEnabled() && switch (type) {
            case EO_JAMMER, MUZZLE, LASER -> true;
            case EXPLOSION -> RVP_LightingConfig.explosionHaloEnabled();
            case ROCKET_ENGINE -> RVP_LightingConfig.missileHaloEnabled();
        };
    }

    /** 自发光核心通道：总开关关闭时不绘制；开启后只有爆炸白热核心另有分项开关。 */
    private static boolean coreEnabled(RVP_LightSourceType type) {
        return RVP_LightingConfig.screenEnabled()
                && (type != RVP_LightSourceType.EXPLOSION || RVP_LightingConfig.explosionCoreEnabled());
    }

    /** 天空染色通道：属于照亮，受总开关约束。 */
    private static boolean skyEnabled() {
        return RVP_LightingConfig.screenEnabled() && RVP_LightingConfig.skyScatterEnabled();
    }

    /** 该灯是否至少有一个开启的通道；全关时不占八灯预算槽位，也不做遮挡查询。 */
    private static boolean hasEnabledChannel(RVP_ScreenLight light) {
        // 调用照亮通道判定；强度为0表示该路本来就没有贡献。
        if (surfaceEnabled(light.type()) && light.intensity() > 0.001F) { return true; }
        // 调用光晕通道判定。
        if (haloEnabled(light.type()) && light.haloIntensity() > 0.001F) { return true; }
        // 调用自发光核心判定：核心与光团任一有亮度都算贡献。
        if (coreEnabled(light.type()) && light.emission() != null
                && (light.emission().coreIntensity() > 0.001F || light.emission().haloIntensity() > 0.001F)) {
            return true;
        }
        // 调用天空通道判定；关闭时不保留只靠天空的灯。
        return skyEnabled() && light.skyIntensity() > 0.001F;
    }

    /** 四层依次缩小并柔化，再从最小层回升；固定基础权重总和为一，强度调节不相互补偿。 */
    private static void renderBloom(float blurStep) {
        for (int level = 0; level < RVP_BloomParameters.LEVELS; level++) {
            // 调用独立读写目标：首层提取亮部，后续层只缩小上一层，避免反复扣除亮部门槛。
            bloomPass(level == 0 ? 0 : 3, level == 0 ? FRAME.glow() : FRAME.bloom(level - 1, 0),
                    FRAME.color(), level, 0, 0, 0, 0, 0);
            // 调用横纵连续高斯过滤，每层使用自己的像素尺寸。
            bloomPass(1, FRAME.bloom(level, 0), FRAME.color(), level, 1,
                    blurStep / FRAME.bloomWidth(level), 0, 0, 0);
            bloomPass(1, FRAME.bloom(level, 1), FRAME.color(), level, 0,
                    0, blurStep / FRAME.bloomHeight(level), 0, 0);
        }
        // 调用会话参数一次取快照；近层和远层各自乘固定权重，关闭一组不让另一组突然变亮。
        float nearStrength = RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.bloom,
                RVP_LightRuntimeTuning.Parameter.nearStrength);
        float farStrength = RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.bloom,
                RVP_LightRuntimeTuning.Parameter.farStrength);
        for (int level = RVP_BloomParameters.LEVELS - 1; level >= 0; level--) {
            boolean hasCoarser = level + 1 < RVP_BloomParameters.LEVELS;
            // 调用加权回升；最小层的无用采样器仍绑定有效且非写入附件，禁止显卡读写反馈。
            bloomPass(4, FRAME.bloom(level, 0), hasCoarser ? FRAME.bloom(level + 1, 1) : FRAME.color(),
                    level, 1, 0, 0, RVP_BloomParameters.weight(level, nearStrength, farStrength), hasCoarser ? 1 : 0);
        }
    }

    /** 执行一轮提取、缩小、模糊或回升；三个采样器均不指向本轮写入附件。 */
    private static void bloomPass(int mode, int input, int coarser, int level, int targetIndex,
            float dx, float dy, float layerWeight, float coarserWeight) {
        bloomShader.setSampler("InputTexture", input);
        // 即使该模式未使用，三个采样器也保持完整有效的纹理绑定。
        bloomShader.setSampler("SceneColor", FRAME.color());
        bloomShader.setSampler("BloomTexture", coarser);
        bloomShader.safeGetUniform("Mode").set(mode);
        bloomShader.safeGetUniform("Direction").set(dx, dy);
        bloomShader.safeGetUniform("LayerWeights").set(layerWeight, coarserWeight);
        // 调用指定层目标与实际尺寸；每轮完整覆盖，窗口重建后不会读到旧光晕。
        FRAME.bind(FRAME.bloomTarget(level, targetIndex), FRAME.bloomWidth(level), FRAME.bloomHeight(level));
        FRAME.draw(bloomShader);
    }

    /** 还原观察空间光位置；与深度重建使用完全相同的观察矩阵。 */
    static Vector3f viewPosition(Vec3 position) {
        return VIEW.transformPosition(new Vector3f((float) (position.x - camera.x),
                (float) (position.y - camera.y), (float) (position.z - camera.z)));
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
        // 调用世界查询入口清理，资源重载和卸载不会沿用旧缓存。
        AMBIENT.clear();
        lights = List.of(); frameLevel = null; distant = null; distantAnalysis = null; nearAnalysis = null;
        lastSubmittedLights = List.of();
        plumes = List.of();
        trails = List.of();
        lastFrameStatus = "资源已清理，等待活动光源";
        // 调用借用引用清理，卸载后不触碰 DH 或光影拥有的纹理。
        RVP_LightDepthFrame.INSTANCE.clear();
        Runnable release = () -> {
            // 调用两套自有显存释放，不接触其它模组或玩家的资源。
            FRAME.release(); RVP_SmokeLightPass.release();
            // 调用独立层级释放，窗口/世界/资源切换不泄漏遮挡纹理或等待中的诊断。
            RVP_LightOcclusionPass.release();
            // 调用三维几何资源释放，不将方块/实体缓存带到下一个世界。
            RVP_LightGeometryPass.release();
        };
        if (RenderSystem.isOnRenderThread()) { release.run(); }
        else { RenderSystem.recordRenderCall(release::run); }
    }
}
