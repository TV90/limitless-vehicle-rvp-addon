package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFrameCoordinator;
import org.ywzj.rvp.config.RVP_ClientConfig;

import java.lang.reflect.InvocationTargetException;

/**
 * 不引用任何 DH 类型的客户端可选依赖入口。
 *
 * <p>只有确认模组存在后才反射加载强类型 API 7.1 桥，保证无 DH 与专用服务器不会解析 DH 常量池。</p>
 */
public final class RVP_DistantHorizonsCompatBootstrap {
    /** Distant Horizons Forge 模组 ID。 */
    private static final String DH_MOD_ID = "distanthorizons";
    /** 唯一直接引用 DH API 类型的桥接类名。 */
    private static final String BRIDGE_CLASS_NAME =
            "org.ywzj.rvp.client.compat.distanthorizons.RVP_DhApi71Bridge";
    /** 客户端兼容初始化日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前运行期是否安装了 DH。 */
    private static boolean dhLoaded;
    /** 反射桥是否已成功进入等待/监听状态。 */
    private static boolean bridgeInstalled;
    /** DH 官方配置当前是否启用 LOD 渲染；桥初始化前保守视为启用。 */
    private static volatile boolean dhRenderingEnabled = true;

    private RVP_DistantHorizonsCompatBootstrap() {
    }

    /** 在客户端 setup 队列中检查可选依赖并安装强类型桥。 */
    public static void initialize() {
        dhLoaded = ModList.get().isLoaded(DH_MOD_ID);
        if (!dhLoaded) {
            return;
        }
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS_NAME, true,
                    RVP_DistantHorizonsCompatBootstrap.class.getClassLoader());
            bridgeClass.getMethod("initialize").invoke(null);
            bridgeInstalled = true;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | LinkageError exception) {
            bridgeInstalled = false;
            LOGGER.warn("RVP DH compat: state=FALLBACK reason=DH_BRIDGE_LOAD_FAILED", exception);
        }
    }

    /** 只要 DH 已安装且用户未关闭兼容，就在 AFTER_SKY 建立可降级计划。 */
    public static boolean shouldPrepareDhRoute() {
        return dhLoaded
                && dhRenderingEnabled
                && RVP_ClientConfig.getDistantHorizonsCompatMode()
                != RVP_ClientConfig.DistantHorizonsCompatMode.OFF;
    }

    /** 由已隔离的强类型桥同步 DH 官方 renderingEnabled 配置。 */
    public static void updateDhRenderingEnabled(boolean enabled) {
        dhRenderingEnabled = enabled;
    }

    /** 返回强类型 DH 桥是否已安装，供诊断使用。 */
    public static boolean isBridgeInstalled() {
        return bridgeInstalled;
    }

    /** 资源重载、换世界或退出时释放所有 RVP 自有 GL 资源与未消费计划。 */
    public static void clearClientResources() {
        // 调用本项目帧协调器，丢弃旧世界或旧资源代际的候选引用。
        RVP_RemoteVehicleFrameCoordinator.clear();
        if (RenderSystem.isOnRenderThread()) {
            RVP_DhDepthCompositeRenderer.release();
        } else {
            // 调用 RenderSystem 渲染队列，确保 GL 对象只在渲染线程删除。
            RenderSystem.recordRenderCall(RVP_DhDepthCompositeRenderer::release);
        }
    }
}
