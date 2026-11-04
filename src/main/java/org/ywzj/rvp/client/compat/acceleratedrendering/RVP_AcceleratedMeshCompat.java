package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.config.RVP_ClientConfig;

/** 可选 AR 的客户端入口；本类不在常量池中引用任何 AR 类型。 */
public final class RVP_AcceleratedMeshCompat {
    /** 只有检测到 AR 后才加载的强类型桥。 */
    private static final String BRIDGE_CLASS =
            "org.ywzj.rvp.client.compat.acceleratedrendering.RVP_AcceleratedMeshBridge";
    /** 初始化失败只记录一次，随后沿用原有 SBM 提交。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 是否已检查可选依赖；只由渲染线程访问。 */
    private static boolean initialized;
    /** 兼容接口可用时的消费者包装器；缺少依赖或初始化失败时为 null。 */
    private static ConsumerWrapper wrapper;

    /** 通过 Minecraft 类型隔离可选 AR 类加载。 */
    public interface ConsumerWrapper {
        /** 保留原消费者的绘制语义，只调整达到阈值的 mesh 提交策略。 */
        VertexConsumer wrap(VertexConsumer consumer, int minVertices);
        /** 只认可不增加材质/顶点语义的 AR GUI 包装，未知包装一律不旁路。 */
        default boolean isPanelSource(MultiBufferSource source, MultiBufferSource expected) { return false; }
        /** 检查 AR 的实体默认配置；热源事件的临时 VANILLA 状态不能改变此能力判定。 */
        default boolean worldSharingConfigured() { return false; }
        /** 光影的实体分类材质；未知的顶点加工包装必须回退。 */
        default RenderType entityMaterial(MultiBufferSource source, MultiBufferSource expected, RenderType material) { return null; }
    }

    private RVP_AcceleratedMeshCompat() {}

    /** 只包装主体单面 cutout mesh；其它材质与未加速消费者原样返回。 */
    public static MultiBufferSource wrapOpaqueMeshSource(MultiBufferSource source,
                                                         ResourceLocation texture,
                                                         int largestMeshVertices) {
        // 调用本项目客户端配置，允许用户独立关闭 dense 优化作 A/B 对照。
        if (texture == null || !RVP_ClientConfig.isAcceleratedDenseMeshEnabled()) {
            return source;
        }
        int minVertices = RVP_ClientConfig.getAcceleratedDenseMinVertices();
        if (largestMeshVertices < minVertices) {
            return source;
        }
        ConsumerWrapper active = getWrapper();
        if (active == null) {
            return source;
        }
        // 调用现有 RenderType 获取同一材质实例，不包装透明、双面或特殊骨通道；热源另有显式入口。
        RenderType cutoutMesh = RVP_RenderTypes.polyMeshCutout(texture);
        return type -> {
            VertexConsumer original = source.getBuffer(type);
            return type == cutoutMesh ? active.wrap(original, minVertices) : original;
        };
    }

    /** 识别 AR 的透明转发 source，仍保留其正常消费者，不解包 raw builder。 */
    public static boolean isPanelSource(MultiBufferSource source, MultiBufferSource expected) {
        ConsumerWrapper bridge = getWrapper();
        return bridge != null && bridge.isPanelSource(source, expected);
    }

    public static RenderType entityMaterial(MultiBufferSource source, MultiBufferSource expected, RenderType material) {
        if (source == expected) return material;
        ConsumerWrapper bridge = getWrapper();
        return bridge == null ? null : bridge.entityMaterial(source, expected, material);
    }

    /** 查询配置允许的共享能力；光影与热成像叠加时由世界入口单独保留原提交时序。 */
    public static boolean worldSharingConfigured() {
        ConsumerWrapper bridge = getWrapper();
        return bridge != null && bridge.worldSharingConfigured();
    }

    /** 延迟加载公开 AR API；卸载 AR 时既不解析其类型，也不改变原始消费者。 */
    private static ConsumerWrapper getWrapper() {
        if (!initialized) {
            initialized = true;
            if (!ModList.get().isLoaded("acceleratedrendering")) {
                return null;
            }
            try {
                Class<?> bridge = Class.forName(BRIDGE_CLASS, true,
                        RVP_AcceleratedMeshCompat.class.getClassLoader());
                wrapper = (ConsumerWrapper) bridge.getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
                LOGGER.warn("RVP mesh: AR dense 接口不可用，继续使用原有 SBM 渲染", exception);
            }
        }
        return wrapper;
    }
}
