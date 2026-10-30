package org.ywzj.rvp.client.light;

import net.minecraftforge.fml.ModList;
import org.ywzj.vehicle.client.shader.ThermalHandler;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.lang.reflect.Method;

/** 光影只读桥：兼容 Oculus/Iris 的两种包名，不改变光影包、外部附件或管线配置。 */
public final class RVP_LightingCompatibility {
    /** 已探测的可选光影桥；无光影模组时为空。 */
    private static IrisBridge iris;
    /** 可选类只扫描一次，切换光影包时仍逐帧读取实时状态。 */
    private static boolean probed;

    private RVP_LightingCompatibility() { }

    /** 只对本体专用热成像让出彩色通道；DH 和光影均不再构成禁用条件。 */
    static String disabledReason() {
        // 调用本体热成像开关，普通彩色灯光不重复增强热成像画面。
        return ThermalHandler.isActive() ? "热成像已激活，保留原热源通道" : "";
    }

    /** 查询活动光影；未知版本保留主目标的通用路径，不能因安装模组而关闭灯光。 */
    public static boolean shadersActive() {
        // 调用延迟探测，保证专用服务器不会加载任何可选客户端类型。
        probe();
        if (iris == null) { return false; }
        try { return Boolean.TRUE.equals(iris.inUse.invoke(iris.instance)); }
        catch (ReflectiveOperationException | LinkageError exception) { return true; }
    }

    /** 手持物缩放的是裁剪空间 Z，转换到 0～1 深度后的上限为 (1+scale)/2。 */
    static float handDepthLimit() {
        // 调用实时光影状态；普通原版手持物在灯光之后绘制，无需遮罩。
        return shadersActive() ? iris == null ? 0.5625F : iris.handDepthLimit : 0F;
    }

    /** 使用光影记录的场景矩阵，避免后处理阶段已切到屏幕或手持物投影。 */
    static void sceneMatrices(Matrix4f view, Matrix4f projection) {
        if (!shadersActive() || iris == null || iris.state == null || iris.view == null || iris.projection == null) { return; }
        try {
            Object actualView = iris.view.invoke(iris.state);
            Object actualProjection = iris.projection.invoke(iris.state);
            if (actualView instanceof Matrix4fc matrix && matrix.isFinite()) { view.set(matrix); }
            if (actualProjection instanceof Matrix4fc matrix && matrix.isFinite()) { projection.set(matrix); }
        } catch (ReflectiveOperationException | LinkageError ignored) { /* 保留 Forge 世界事件的矩阵。 */ }
    }

    /** 阴影贴图使用太阳视角，不能被误认成玩家相机的远景深度。 */
    public static boolean shadowPass() {
        probe();
        if (iris == null || iris.shadows == null) { return false; }
        try { return Boolean.TRUE.equals(iris.shadows.invoke(null)); }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    /** 光影读取其实际 DH 深度/投影，普通 DH 读取公开事件发布的当帧快照。 */
    static RVP_LightDepthFrame.Snapshot distant(Object world, Matrix4f sceneView) {
        // 调用当前帧证明；DH 被关闭、没有渲染回调或换世界时不能读取光影缓存的旧纹理。
        if (!RVP_LightDepthFrame.INSTANCE.wasRendered(world)) { return null; }
        if (shadersActive() && iris != null && iris.pipelineManager != null && iris.dhDepth != null) {
            try {
                Object manager = iris.pipelineManager.invoke(null);
                if (manager == null) { return RVP_LightDepthFrame.INSTANCE.current(world); }
                Object pipeline = iris.pipeline.invoke(manager);
                if (pipeline != null) {
                    Object compat = iris.dhCompat.invoke(pipeline);
                    if (compat != null) {
                        Object depth = iris.dhDepth.invoke(compat);
                        int texture = depth instanceof Number number ? number.intValue() : -1;
                        Object matrix = iris.dhProjection.invoke(null);
                        if (texture > 0 && matrix instanceof Matrix4fc projection && projection.isFinite()) {
                            // 调用当帧信箱校验，不把原版 near/far 套到 DH 的纹理上。
                            RVP_LightDepthFrame.INSTANCE.publish(world, texture, new Matrix4f(projection), sceneView);
                        }
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
                // 其它版本仍可由 DH 官方事件提供来源，不关闭近景或整个灯光通道。
            }
        }
        // 调用世界/帧代号检查，绝不回读上一帧或上一维度的 DH 纹理。
        return RVP_LightDepthFrame.INSTANCE.current(world);
    }

    /** 只缓存公开方法；不持有重载后会销毁的具体光影管线实例。 */
    private static void probe() {
        if (probed) { return; }
        probed = true;
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) { return; }
        for (String root : new String[] {"net.irisshaders.iris", "net.coderbot.iris"}) {
            try {
                Class<?> api = Class.forName(root + ".api.v0.IrisApi");
                IrisBridge bridge = new IrisBridge(api.getMethod("getInstance").invoke(null),
                        api.getMethod("isShaderPackInUse"));
                // 只查公开字段和公开方法，无私有字段反射、访问器扩展或字节码注入。
                try {
                    Class<?> state = Class.forName(root + ".uniforms.CapturedRenderingState");
                    bridge.state = state.getField("INSTANCE").get(null);
                    bridge.view = state.getMethod("getGbufferModelView");
                    bridge.projection = state.getMethod("getGbufferProjection");
                } catch (ReflectiveOperationException | LinkageError ignored) { /* 保留通用矩阵路径。 */ }
                try {
                    bridge.shadows = Class.forName(root + ".shadows.ShadowRenderingState")
                            .getMethod("areShadowsCurrentlyBeingRendered");
                } catch (ReflectiveOperationException | LinkageError ignored) { /* 普通世界阶段无需阴影查询。 */ }
                try {
                    float handScale = Class.forName(root + ".pathways.HandRenderer").getField("DEPTH").getFloat(null);
                    if (Float.isFinite(handScale) && handScale > 0 && handScale < 1) {
                        bridge.handDepthLimit = (1F + handScale) * 0.5F;
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) { /* 保留已核对 Oculus 1.8 的默认缩放。 */ }
                try {
                    bridge.pipelineManager = Class.forName(root + ".Iris").getMethod("getPipelineManager");
                    bridge.pipeline = Class.forName(root + ".pipeline.PipelineManager").getMethod("getPipelineNullable");
                    bridge.dhCompat = Class.forName(root + ".pipeline.WorldRenderingPipeline").getMethod("getDHCompat");
                    Class<?> dh = Class.forName(root + ".compat.dh.DHCompat");
                    bridge.dhProjection = dh.getMethod("getProjection");
                    bridge.dhDepth = dh.getMethod("getDepthTex");
                } catch (ReflectiveOperationException | LinkageError ignored) { /* DH 公开事件仍可提供深度。 */ }
                iris = bridge;
                return;
            } catch (ReflectiveOperationException | LinkageError ignored) { /* 尝试另一种已知包名。 */ }
        }
    }

    /** 光影版本间可独立缺失的只读能力。 */
    private static final class IrisBridge {
        /** 公开 API 单例。 */
        private final Object instance;
        /** 活动光影包查询。 */
        private final Method inUse;
        /** 场景矩阵记录单例。 */
        private Object state;
        /** 场景观察矩阵查询。 */
        private Method view;
        /** 场景投影矩阵查询。 */
        private Method projection;
        /** 太阳阴影绘制状态查询。 */
        private Method shadows;
        /** 手持物缩放后的纹理深度上限，默认来自 Oculus 1.8 / Iris 1.20.1 的 0.125 缩放。 */
        private float handDepthLimit = 0.5625F;
        /** 当前管线管理器查询。 */
        private Method pipelineManager;
        /** 当前管线查询。 */
        private Method pipeline;
        /** 当前管线的 DH 对象查询。 */
        private Method dhCompat;
        /** 光影实际消费的 DH 深度查询。 */
        private Method dhDepth;
        /** 光影实际使用的 DH 投影查询。 */
        private Method dhProjection;

        private IrisBridge(Object instance, Method inUse) { this.instance = instance; this.inUse = inUse; }
    }
}
