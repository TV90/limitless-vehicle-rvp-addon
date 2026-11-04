package org.ywzj.rvp.client.compat.thermal;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.client.shader.ThermalHandler;

/** 每帧临时暂停光影，不改光影包选择、磁盘配置或原有开关状态。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ThermalShaderPause {
    /** 只在标题界面探测一次可选桥，构造空管线时不存在并行地形任务。 */
    private static boolean probed;
    /** 已核对的可选接口；不兼容时继续原光影+热成像路径。 */
    private static Bridge bridge;
    /** 当前显示帧借用的管线与地形程序；帧结束立即交还。 */
    private static AutoCloseable frame;
    /** 失败后停止新接管，避免每帧重复报错或反复变更状态。 */
    private static boolean failed;

    /** 可选桥只在确认安装 Oculus 后加载，普通客户端不解析其类型。 */
    public interface Bridge {
        AutoCloseable pause() throws ReflectiveOperationException;
        Fallback fallback(MultiBufferSource source);
    }

    /** 小网格提交作用域，关闭时还原 Iris 的即时顶点开关。 */
    public interface Fallback extends AutoCloseable {
        MultiBufferSource source();
        @Override void close();
    }

    /** 原路径无需全局状态切换。 */
    private record OriginalFallback(MultiBufferSource source) implements Fallback {
        @Override public void close() { }
    }

    public static Fallback fallback(MultiBufferSource source) {
        return frame == null || bridge == null ? new OriginalFallback(source) : bridge.fallback(source);
    }

    private RVP_ThermalShaderPause() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        // 调用统一收尾；上次渲染意外未发 END 时，下一帧也先交还原资源。
        finish();
        if (event.phase != TickEvent.Phase.START || failed) return;
        Minecraft mc = Minecraft.getInstance();
        if (!probed && mc.level == null) {
            probed = true;
            if (!ModList.get().isLoaded("oculus") || !ModList.get().isLoaded("embeddium")) return;
            try { bridge = (Bridge) Class.forName("org.ywzj.rvp.client.compat.thermal.RVP_OculusThermalBridge")
                    .getConstructor().newInstance(); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
        }
        if (bridge == null || mc.level == null || mc.player == null || !ThermalHandler.isActive()
                || !RVP_ClientConfig.isThermalPauseShadersEnabled()) return;
        try {
            // 调用可选作用域，原本关闭光影或维度尚未准备时返回 null。
            frame = bridge.pause();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
    }

    /** 世界卸载可能在加载界面中嵌套渲染；原渲染器销毁前先交还被借走的程序。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) finish();
    }

    private static void finish() {
        AutoCloseable previous = frame;
        frame = null;
        if (previous == null) return;
        try { previous.close(); }
        catch (Exception | LinkageError error) { fail(error); }
    }

    private static void fail(Throwable error) {
        if (!failed) LogUtils.getLogger().warn("[RVP] 热成像光影暂停接口不可用，保持原绘制路径", error);
        failed = true;
    }
}
