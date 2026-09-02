package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhDepthCompositeRenderer;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhCompatDiagnostics;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;

/** 协调同一帧计划在 RVP_FIRST、DH、原有通道和晚期降级之间只消费一次。 */
public final class RVP_RemoteVehicleFrameCoordinator {
    /** 纯状态唯一消费路由。 */
    private static final RVP_RemoteVehicleFrameRoute ROUTE = new RVP_RemoteVehicleFrameRoute();
    /** 当前等待消费的远距载具计划。 */
    private static RVP_RemoteVehicleFramePlan currentPlan;
    /** 当前帧最后一次深度合成失败原因。 */
    private static String failureReason = "NO_DH_EVENT_THIS_FRAME";

    private RVP_RemoteVehicleFrameCoordinator() {
    }

    /** 在 AFTER_SKY 保存本帧计划；新帧会丢弃上一帧已经结束的引用。 */
    public static void prepareForDh(RVP_RemoteVehicleFramePlan plan, String initialFailureReason) {
        currentPlan = plan;
        failureReason = initialFailureReason == null || initialFailureReason.isBlank()
                ? "NO_DH_EVENT_THIS_FRAME"
                : initialFailureReason;
        ROUTE.prepare(plan != null);
    }

    /** 返回仍等待 DH apply 前通道消费的计划；不存在或已消费时返回 null。 */
    public static RVP_RemoteVehicleFramePlan currentForDh() {
        if (RVP_ClientConfig.getDistantHorizonsCompatMode()
                == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST) {
            return null;
        }
        return ROUTE.state() == RVP_RemoteVehicleFrameRoute.State.PREPARED ? currentPlan : null;
    }

    /** 在 DH 颜色与深度均成功更新后标记本帧已消费。 */
    public static void markDhCompositeSuccess(RVP_RemoteVehicleFramePlan plan) {
        if (plan == currentPlan) {
            ROUTE.consumeDh();
            failureReason = "NONE";
        }
    }

    /** 保存一次合成失败原因，供明确降级与受限诊断使用。 */
    public static void markDhCompositeFailure(String reason) {
        if (reason != null && !reason.isBlank()) {
            failureReason = reason;
        }
    }

    /** 在 AFTER_ENTITIES 执行 CURRENT_PASS，其他降级保留到 AFTER_LEVEL。 */
    public static void finishCurrentPassFallback(RenderLevelStageEvent event) {
        if (ROUTE.state() != RVP_RemoteVehicleFrameRoute.State.PREPARED || currentPlan == null) {
            return;
        }
        if (RVP_ClientConfig.getDistantHorizonsCompatMode()
                == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST) {
            return;
        }
        DistantHorizonsFallbackMode fallback = RVP_ClientConfig.getDistantHorizonsFallbackMode();
        if (fallback != DistantHorizonsFallbackMode.CURRENT_PASS) {
            return;
        }
        if (ROUTE.consumeCurrentPass()) {
            // 调用本项目目标无关绘制入口，按用户选择保留既有 AFTER_ENTITIES 行为。
            RVP_RemoteVehicleVisualRenderer.renderPrepared(currentPlan, event.getCamera());
            // 调用本项目诊断器，受限记录深度兼容为何进入当前 pass。
            RVP_DhCompatDiagnostics.recordFallback(failureReason, fallback, currentPlan.selectedCount());
        }
    }

    /** 在 AFTER_LEVEL 执行 RVP_FIRST 或降级输出，并清除所有已结束的本帧计划。 */
    public static void finishLateOutput(RenderLevelStageEvent event) {
        try {
            if (RVP_ClientConfig.getDistantHorizonsCompatMode()
                    == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST) {
                if (currentPlan != null && ROUTE.consumeRvpFirst()) {
                    // 调用本项目晚期完整图像合成器，忽略地形深度并保证载具不被 DH 地形覆盖。
                    RVP_DhDepthCompositeRenderer.renderLateFallback(
                            currentPlan, Minecraft.getInstance().gameRenderer.getMainCamera(),
                            DistantHorizonsFallbackMode.ALWAYS_VISIBLE);
                    // 调用本项目诊断器，按配置受限记录 RVP 优先显示挡位与候选数量。
                    RVP_DhCompatDiagnostics.recordRvpFirst(currentPlan.selectedCount());
                }
                return;
            }
            if (ROUTE.state() == RVP_RemoteVehicleFrameRoute.State.DH_COMPOSITED) {
                return;
            }
            if (ROUTE.state() != RVP_RemoteVehicleFrameRoute.State.PREPARED || currentPlan == null) {
                return;
            }
            DistantHorizonsFallbackMode fallback = RVP_ClientConfig.getDistantHorizonsFallbackMode();
            if (!ROUTE.consumeLateFallback()) {
                return;
            }
            if (fallback == DistantHorizonsFallbackMode.SILHOUETTE
                    || fallback == DistantHorizonsFallbackMode.ALWAYS_VISIBLE) {
                // 调用本项目 DH 离屏合成器，在最终世界目标上实现轮廓或显式穿山完整图像。
                RVP_DhDepthCompositeRenderer.renderLateFallback(
                        currentPlan, Minecraft.getInstance().gameRenderer.getMainCamera(), fallback);
            }
            // 调用本项目诊断器，受限记录安全降级原因与候选数量。
            RVP_DhCompatDiagnostics.recordFallback(failureReason, fallback, currentPlan.selectedCount());
        } finally {
            currentPlan = null;
            ROUTE.clear();
        }
    }

    /** 换维度、退出世界或资源重载时清除尚未消费的计划。 */
    public static void clear() {
        currentPlan = null;
        failureReason = "NO_DH_EVENT_THIS_FRAME";
        ROUTE.clear();
    }
}
