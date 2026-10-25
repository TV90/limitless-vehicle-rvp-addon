package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveRenderer;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteAmmoVisualRenderer;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleBillboardManager;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;
import org.ywzj.rvp.client.visual.thermobaric.RVP_ThermobaricScreenFeedback;
import org.ywzj.rvp.client.visual.shake.RVP_ScreenShakeExecutor;

/** 通用客户端视觉效果的 Tick、渲染和世界清理事件入口。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVisualEvents {
    /** 声音去重与屏幕反馈当前所属的客户端世界。 */
    private static ClientLevel feedbackLevel;

    private RVP_ClientVisualEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().isPaused()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // 调用 RVP 客户端视觉分派器，清除退出世界后遗留的实例。
            RVP_ClientVisualEffectDispatcher.clear();
            // 退出或卸载世界时释放共享球壳和场景缓冲。
            RVP_ShockwaveRenderer.clear();
            // 调用 RVP 弹药视觉状态与渲染器，清除退出世界后遗留的授权集合和尾迹。
            clearRemoteAmmoVisuals();
            // 调用 RVP 载具视觉状态，清除退出世界后遗留的非世界代理。
            RVP_ClientRemoteVehicleVisualState.clear();
            // 调用 RVP Billboard 管理器，释放退出世界后遗留的动态纹理与 RenderTarget。
            RVP_RemoteVehicleBillboardManager.clear();
            // 清理温压反馈及所有调用方的通用震动任务。
            clearScreenFeedback();
            return;
        }
        if (feedbackLevel != level) {
            // 切换客户端世界时丢弃旧世界的屏幕反馈。
            clearScreenFeedback();
            feedbackLevel = level;
        }
        // 调用 RVP 客户端视觉分派器，统一推进当前世界的效果实例。
        RVP_ClientVisualEffectDispatcher.tick(level);
        // 新工厂球壳仅在此处推进一次；核爆仍由自身管理器推进。
        RVP_ShockwaveRenderer.tick();
        // 调用 RVP 载具视觉状态，执行维度互斥与 25 Tick 代理超时清理。
        RVP_ClientRemoteVehicleVisualState.tick(level);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // 调用 RVP 客户端视觉分派器，在天气之后统一渲染半透明世界效果。
            RVP_ClientVisualEffectDispatcher.render(event);
            // 集中复制一次场景并绘制新工厂球壳；核爆保留原绘制阶段，热成像不执行折射。
            RVP_ShockwaveRenderer.render(event);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        // 调用温压屏幕反馈服务，在 GUI 最后阶段绘制当前最强闪光。
        RVP_ThermobaricScreenFeedback.renderFlash(event);
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        // 调用通用震动执行器，统一应用温压及其他参数调用方提交的镜头偏移。
        RVP_ScreenShakeExecutor.applyCameraShake(event);
        // 调用工厂实例的反馈入口，新增效果无需 Mixin。
        RVP_ClientVisualEffectDispatcher.applyCameraShake(event);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // 调用 RVP 客户端视觉分派器，确保切换维度时实例不会跨世界复用。
            RVP_ClientVisualEffectDispatcher.clear();
            // 退出或卸载世界时释放共享球壳和场景缓冲。
            RVP_ShockwaveRenderer.clear();
            // 调用 RVP 弹药视觉状态与渲染器，确保切换维度后不保留旧世界集合和尾迹。
            clearRemoteAmmoVisuals();
            // 调用 RVP 载具视觉状态，确保换维度后不复用旧世界代理与样本。
            RVP_ClientRemoteVehicleVisualState.clear();
            // 调用 RVP Billboard 管理器，确保换维度后不复用旧世界的离屏快照。
            RVP_RemoteVehicleBillboardManager.clear();
            // 卸载世界时同步清理所有调用方的震动任务。
            clearScreenFeedback();
        }
    }

    /** 同时清理弹药视觉授权集合与远程尾迹状态。 */
    private static void clearRemoteAmmoVisuals() {
        RVP_ClientRemoteAmmoVisualState.clear();
        RVP_RemoteAmmoVisualRenderer.clear();
    }

    /** 同时清理温压声音、闪光与全部调用方的通用震动任务。 */
    private static void clearScreenFeedback() {
        // 清空温压声音去重表与其闪光、震动任务。
        RVP_ThermobaricScreenFeedback.clearAll();
        // 额外清空其他调用方的震动，保证通用系统不会跨世界残留。
        RVP_ScreenShakeExecutor.clearAll();
        feedbackLevel = null;
    }
}
