package org.ywzj.rvp.client.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
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
import org.joml.Matrix4f;
import org.ywzj.rvp.config.RVP_ClientConfig;

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

    /** 在 apply 前查询本帧纹理并执行一次深度感知合成；任何失败均不取消 DH 事件。 */
    @SuppressWarnings("deprecation")
    private static void beforeApply(DhApiRenderParam parameter) {
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
            // 调用本项目像素取证，验证 DH apply 是否将载具颜色转入世界目标。
            RVP_DhPixelDiagnostics.afterWorldStage("DH_BEFORE_CLEANUP", false);
        }
    }
}
