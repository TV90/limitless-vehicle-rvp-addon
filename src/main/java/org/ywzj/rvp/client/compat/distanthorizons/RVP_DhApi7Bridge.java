package org.ywzj.rvp.client.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.client.light.RVP_ScreenLightPass;
import org.ywzj.rvp.client.state.RVP_FocusLockAim;
import org.ywzj.rvp.client.state.RVP_FocusLockRaycast;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Distant Horizons API 7 强类型桥。
 *
 * <p>这是项目中唯一直接引用 DH API 类型的类；最低支持已验证的 API 7.0.1，
 * 只使用公开 API，不访问 common/core 内部实现。</p>
 */
public final class RVP_DhApi7Bridge {
    /** 已验证 DH API 的主版本要求。 */
    private static final int REQUIRED_API_MAJOR = 7;
    /** 已验证 DH API 的最低次版本。 */
    private static final int REQUIRED_API_MINOR = 0;
    /** 当次版本等于最低次版本时要求的最低补丁版本。 */
    private static final int REQUIRED_API_PATCH = 1;
    /** DH 初始化完成事件处理器的唯一实例。 */
    private static final AfterInitHandler AFTER_INIT_HANDLER = new AfterInitHandler();
    /** DH apply shader 前事件处理器的唯一实例。 */
    private static final BeforeApplyHandler BEFORE_APPLY_HANDLER = new BeforeApplyHandler();
    /** 公开 cleanup 前事件处理器，在 DH apply 完成后读取本帧目标，不取消事件。 */
    private static final BeforeCleanupHandler BEFORE_CLEANUP_HANDLER = new BeforeCleanupHandler();
    /** 是否已经注册初始化事件，防止客户端 setup 重入。 */
    private static boolean initializationEventBound;
    /** 远景数据可能访问磁盘，单个守护工作线程执行；焦点服务最多保留一个未完成请求。 */
    private static final ExecutorService FOCUS_TERRAIN_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "RVP-FocusTerrain");
        worker.setDaemon(true);
        return worker;
    });

    private RVP_DhApi7Bridge() {
    }

    /**
     * 绑定 DH 初始化完成事件；一切 Delayed API 访问均推迟到该事件之后。
     *
     * @return 初始化事件已经绑定或本次绑定成功时为 true
     */
    public static boolean initialize() {
        if (initializationEventBound) {
            return true;
        }
        // 调用 DH 官方事件注册入口，在其 Delayed 单例可用后再检查渲染能力。
        DhApiResult<Void> registration = DhApiEventRegister.on(
                DhApiAfterDhInitEvent.class, AFTER_INIT_HANDLER);
        if (!eventRegistrationSucceeded(registration)) {
            disable("DH_INIT_EVENT_BIND_FAILED", resultDetail(registration));
            return false;
        }
        initializationEventBound = true;
        return true;
    }

    /** 校验 API 版本与原生 OpenGL 渲染能力，并绑定 apply 前事件。 */
    private static void afterDhInit() {
        int apiMajor = DhApi.getApiMajorVersion();
        int apiMinor = DhApi.getApiMinorVersion();
        int apiPatch = DhApi.getApiPatchVersion();
        String apiVersion = apiMajor + "." + apiMinor + "." + apiPatch;
        // 调用本项目无 DH 类型入口，保存运行期真实 API 版本供诊断输出。
        RVP_DistantHorizonsCompatBootstrap.updateApiVersion(apiVersion);
        if (!isSupportedApiVersion(apiMajor, apiMinor, apiPatch)) {
            disable("DH_API_TOO_OLD", "api=" + apiVersion + ", required>=7.0.1 and <8.0.0");
            return;
        }

        // 调用无 DH 类型的可选入口安装远景选点，CPU 地形查询不依赖 OpenGL 合成能力。
        RVP_DistantHorizonsCompatBootstrap.installFocusTerrainRaycaster(RVP_DhApi7Bridge::raycastFocusTerrain);

        IDhApiRenderProxy renderProxy = DhApi.Delayed.renderProxy;
        if (renderProxy == null || DhApi.Delayed.configs == null) {
            disable("TEXTURE_UNAVAILABLE", "renderProxy=" + renderProxy
                    + ", configs=" + DhApi.Delayed.configs);
            return;
        }
        // 调用 DH 官方图形配置，使关闭 LOD 时直接恢复 RVP 原有 AFTER_ENTITIES 路径。
        boolean renderingEnabled = Boolean.TRUE.equals(
                DhApi.Delayed.configs.graphics().renderingEnabled().getValue());
        RVP_DistantHorizonsCompatBootstrap.updateDhRenderingEnabled(renderingEnabled);
        DhApi.Delayed.configs.graphics().renderingEnabled().addChangeListener(
                enabled -> RVP_DistantHorizonsCompatBootstrap
                        .updateDhRenderingEnabled(Boolean.TRUE.equals(enabled)));
        try {
            // 调用 DH 官方渲染 API/原生性查询：只有直接 OpenGL 路径才允许借用其 GL 纹理。
            if (renderProxy.getRenderingApi() != EDhApiRenderingApi.OPEN_GL
                    || !renderProxy.isNativeRenderer()) {
                disable("NON_OPENGL_ENGINE",
                        "api=" + renderProxy.getRenderingApi() + ", native=" + renderProxy.isNativeRenderer());
                return;
            }
        } catch (IllegalStateException exception) {
            disable("NON_OPENGL_ENGINE", exception.getMessage());
            return;
        }

        // 调用 DH 官方事件注册入口，使合成发生在 DH apply shader 消费颜色/深度之前。
        DhApiResult<Void> registration = DhApiEventRegister.on(
                DhApiBeforeApplyShaderRenderEvent.class, BEFORE_APPLY_HANDLER);
        if (!eventRegistrationSucceeded(registration)) {
            disable("DH_APPLY_EVENT_BIND_FAILED", resultDetail(registration));
            return;
        }
        // 调用 DH 公开事件注册，补齐 apply 后的只读取证；失败仅提示，不改变兼容可用性。
        DhApiResult<Void> cleanupRegistration = DhApiEventRegister.on(
                DhApiBeforeRenderCleanupEvent.class, BEFORE_CLEANUP_HANDLER);
        if (!eventRegistrationSucceeded(cleanupRegistration)) {
            RVP_DhCompatDiagnostics.warnOnce("DH_PIXEL_CLEANUP_EVENT_UNAVAILABLE",
                    resultDetail(cleanupRegistration));
        }
        // 调用本项目无 DH 类型入口，确认版本、原生渲染器与 apply 事件均已就绪。
        RVP_DistantHorizonsCompatBootstrap.markDepthCompositeReady();
    }

    /** 通过官方 API 查询远景地形；世界匹配在客户端完成，耗时扫描放到后台。 */
    private static CompletableFuture<Vec3> raycastFocusTerrain(Level level, Vec3 origin,
                                                               Vec3 direction, int maxDistance) {
        var repository = DhApi.Delayed.terrainRepo;
        var world = DhApi.Delayed.worldProxy;
        if (repository == null || world == null || !world.worldLoaded()) {
            return CompletableFuture.completedFuture(null);
        }
        IDhApiLevelWrapper selected = null;
        // 调用官方世界枚举与原版世界对象出口，不按维度显示名猜测，避免跨维度误命中。
        for (IDhApiLevelWrapper wrapper : world.getAllLoadedLevelWrappers()) {
            if (wrapper.getWrappedMcObject() == level) {
                selected = wrapper;
                break;
            }
        }
        if (selected == null) {
            // 调用官方单人世界入口：DH 单人联机共用的数据层可能暴露服务端世界包装，
            // 不能只用客户端对象身份匹配；仍核对真实维度键，禁止按显示名或首项猜测。
            IDhApiLevelWrapper singlePlayer = world.getSinglePlayerLevel();
            if (singlePlayer != null && singlePlayer.getWrappedMcObject() instanceof Level wrapped
                    && wrapped.dimension().equals(level.dimension())) {
                selected = singlePlayer;
            }
        }
        if (selected == null) {
            return CompletableFuture.completedFuture(null);
        }
        IDhApiLevelWrapper queryLevel = selected;
        return CompletableFuture.supplyAsync(
                // 调用公开地形柱查询与独立遍历，避免 DH 射线的高空提前结束和缺失数据中止。
                () -> raycastTerrainColumns(repository, queryLevel, origin, direction, maxDistance),
                FOCUS_TERRAIN_EXECUTOR);
    }

    /** 只读 DH 官方地形柱并求真实表面交点；由后台线程执行，测试也使用同一数据适配路径。 */
    @Nullable
    static Vec3 raycastTerrainColumns(IDhApiTerrainDataRepo repository, IDhApiLevelWrapper level,
                                      Vec3 origin, Vec3 direction, int maxDistance) {
        // 调用官方缓存创建/关闭接口，单次查询结束后释放可能持有的远景区域数据。
        try (IDhApiTerrainDataCache cache = repository.createSoftCache()) {
            // 调用从近到远的逐柱遍历，先裁剪高度，且不受普通渲染视距截断。
            return RVP_FocusLockRaycast.terrainSurface(origin, direction, maxDistance,
                    level.getMinHeight(), level.getMaxHeight(), (x, z) -> {
                        // 调用官方只读数据接口，不请求原版区块加载或远处地形生成。
                        var result = repository.getColumnDataAtBlockPos(level, x, z, cache);
                        if (result == null || !result.success || result.payload == null) return null;
                        Vec3 nearest = null;
                        for (var column : result.payload) {
                            if (column == null || column.blockStateWrapper == null
                                    || column.blockStateWrapper.isAir()) continue;
                            // 调用真实柱体求交，检查全部实心高度段，不使用柱底、取整位置或空气层。
                            Vec3 hit = RVP_FocusLockAim.terrainColumnIntersection(origin, direction, maxDistance,
                                    x, z, column.bottomYBlockPos, column.topYBlockPos, column.detailLevel);
                            if (hit != null && (nearest == null
                                    || origin.distanceToSqr(hit) < origin.distanceToSqr(nearest))) {
                                nearest = hit;
                            }
                        }
                        return nearest;
                    });
        } catch (RuntimeException | LinkageError unavailable) {
            // 换世界、数据尚未就绪或可选 API 失败时保留方向锁定，不把失败变成假坐标。
            return null;
        }
    }

    /** 在 apply 前查询本帧纹理并执行一次深度感知合成；任何失败均不取消 DH 事件。 */
    @SuppressWarnings("deprecation")
    private static void beforeApply(DhApiRenderParam parameter) {
        // 调用独立灯光深度入口：没有远距载具、关闭载具兼容或延迟透明时仍需发布 DH 来源。
        publishLightingDepth(parameter);
        RVP_DhVehicleFramePlan plan = RVP_DhVehicleFrameCoordinator.currentForDh();
        if (!RVP_DistantHorizonsCompatBootstrap.isDepthCompositeReady()
                || plan == null || parameter == null) {
            return;
        }
        IDhApiRenderProxy renderProxy = DhApi.Delayed.renderProxy;
        if (renderProxy == null) {
            fail("TEXTURE_UNAVAILABLE", "renderProxy=null");
            return;
        }
        // 调用 DH 官方延迟透明状态：未经实机验证的管线默认明确降级，不静默猜测消费时机。
        if (renderProxy.getDeferTransparentRendering()
                && !RVP_ClientConfig.isDistantHorizonsExperimentalShaderPipelineAllowed()) {
            fail("DEFERRED_SHADER_UNVERIFIED", "deferTransparentRendering=true");
            return;
        }

        // API 7.0.1 的公开 OpenGL 纹理 getter 可逐帧取得当前颜色与深度纹理 ID。
        DhApiResult<Integer> colorResult = renderProxy.getDhColorTextureId();
        DhApiResult<Integer> depthResult = renderProxy.getDhDepthTextureId();
        if (!validTexture(colorResult) || !validTexture(depthResult)) {
            fail("TEXTURE_UNAVAILABLE", "color=" + resultDetail(colorResult)
                    + ", depth=" + resultDetail(depthResult));
            return;
        }

        RVP_DhRenderParameters copiedParameters = new RVP_DhRenderParameters(
                toJoml(parameter.dhProjectionMatrix), parameter.nearClipPlane,
                parameter.farClipPlane, parameter.partialTicks, String.valueOf(parameter.renderPass));
        try {
            // 调用本项目离屏/深度合成器，把可见载具像素写入 DH 借用颜色与深度附件。
            if (RVP_DhDepthCompositeRenderer.composite(plan,
                    Minecraft.getInstance().gameRenderer.getMainCamera(), copiedParameters,
                    colorResult.payload, depthResult.payload)) {
                RVP_DhVehicleFrameCoordinator.markDhCompositeSuccess(plan);
            }
        } catch (RuntimeException | LinkageError exception) {
            fail("FRAMEBUFFER_INCOMPLETE", exception.toString());
        }
    }

    /** 只记录当帧公开纹理与配对矩阵，不修改 DH 颜色/深度，不受载具合成开关影响。 */
    @SuppressWarnings("deprecation")
    private static void publishLightingDepth(DhApiRenderParam parameter) {
        if (parameter == null || parameter.dhProjectionMatrix == null || parameter.dhModelViewMatrix == null) { return; }
        // 调用当前帧证明，即使光影覆盖了 API 纹理，也只允许读取确实画过地形的本帧结果。
        RVP_ScreenLightPass.markDistantRendered();
        if (DhApi.Delayed.renderProxy == null) { return; }
        try {
            // 调用 DH 官方深度 getter，逐次读取以应对重建后纹理编号复用。
            DhApiResult<Integer> depth = DhApi.Delayed.renderProxy.getDhDepthTextureId();
            if (validTexture(depth)) {
                // 调用客户端灯光入口，过滤阴影视角并复制矩阵；实际 GL 读取留到天气之后。
                RVP_ScreenLightPass.acceptDistantDepth(depth.payload,
                        toJoml(parameter.dhProjectionMatrix), toJoml(parameter.dhModelViewMatrix));
            }
        } catch (RuntimeException | LinkageError exception) {
            RVP_DhCompatDiagnostics.warnOnce("LIGHT_DEPTH_QUERY_FAILED", exception.toString());
        }
    }

    /** 判断 DH API 版本是否位于已验证的 7.0.1 及以上 7.x 范围。 */
    static boolean isSupportedApiVersion(int major, int minor, int patch) {
        if (major != REQUIRED_API_MAJOR) {
            return false;
        }
        return minor > REQUIRED_API_MINOR
                || (minor == REQUIRED_API_MINOR && patch >= REQUIRED_API_PATCH);
    }

    /** 逐字段把 DH 行主序矩阵映射到 JOML 列/行字段，禁止直接使用数组构造器。 */
    static Matrix4f toJoml(DhApiMat4f source) {
        return new Matrix4f()
                .m00(source.m00).m10(source.m01).m20(source.m02).m30(source.m03)
                .m01(source.m10).m11(source.m11).m21(source.m12).m31(source.m13)
                .m02(source.m20).m12(source.m21).m22(source.m22).m32(source.m23)
                .m03(source.m30).m13(source.m31).m23(source.m32).m33(source.m33);
    }

    /** 判断 DH 事件注册结果是否成功。 */
    private static boolean eventRegistrationSucceeded(DhApiResult<Void> result) {
        return result != null && result.success;
    }

    /** 判断 DH API 纹理结果是否成功且包含正 OpenGL ID。 */
    private static boolean validTexture(DhApiResult<Integer> result) {
        return result != null && result.success && result.payload != null && result.payload > 0;
    }

    /** 生成人类可读、无空指针的 DH API 结果诊断。 */
    private static String resultDetail(DhApiResult<?> result) {
        return result == null ? "null" : result.success + ":" + result.payload + ":" + result.message;
    }

    /** 标记桥初始化不可用，并只警告一次相同原因。 */
    private static void disable(String reason, String detail) {
        // 调用本项目无 DH 类型入口，跨帧保留初始化阶段的明确失败原因。
        RVP_DistantHorizonsCompatBootstrap.markDepthCompositeUnavailable(reason);
        // 调用本项目诊断器，避免同一永久原因重复刷屏。
        RVP_DhCompatDiagnostics.warnOnce(reason, detail);
    }

    /** 标记本帧失败，并只警告一次相同原因。 */
    private static void fail(String reason, String detail) {
        RVP_DhVehicleFrameCoordinator.markDhCompositeFailure(reason);
        RVP_DhCompatDiagnostics.warnOnce(reason, detail);
    }

    /** DH 初始化完成事件转发器，不保存 DH 共享事件参数。 */
    private static final class AfterInitHandler extends DhApiAfterDhInitEvent {
        @Override
        public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
            afterDhInit();
        }
    }

    /** DH apply 前事件转发器；异常不得取消 DH 自身 apply shader。 */
    private static final class BeforeApplyHandler extends DhApiBeforeApplyShaderRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            beforeApply(event == null ? null : event.value);
        }
    }

    /** DH apply 后、清理前的公开事件转发器，只读附件且不取消 DH 清理。 */
    private static final class BeforeCleanupHandler extends DhApiBeforeRenderCleanupEvent {
        @Override
        public void beforeCleanup(DhApiEventParam<DhApiRenderParam> event) {
            // 调用灯光独立入口，补齐延迟透明完成后的最终深度，仍不取消 DH 自身清理。
            publishLightingDepth(event == null ? null : event.value);
            // 调用本项目像素取证，验证 DH apply 是否将载具颜色转入世界目标。
            RVP_DhPixelDiagnostics.afterWorldStage("DH_BEFORE_CLEANUP", false);
        }
    }
}
