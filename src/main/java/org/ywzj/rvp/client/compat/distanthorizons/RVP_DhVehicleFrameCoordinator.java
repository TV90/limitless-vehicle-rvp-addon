package org.ywzj.rvp.client.compat.distanthorizons;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_ClientConfig.DistantHorizonsFallbackMode;

/** 协调 remote/tracked 两个来源在 DH、当前 pass 与晚期回退之间只消费一次。 */
public final class RVP_DhVehicleFrameCoordinator {
    /** 当前帧信封的消费状态。 */
    private static State state = State.EMPTY;
    /** 当前等待消费的 DH 载具帧信封。 */
    private static RVP_DhVehicleFramePlan currentPlan;
    /** 当前帧最后一次深度合成失败原因。 */
    private static String failureReason = "NO_DH_EVENT_THIS_FRAME";

    private RVP_DhVehicleFrameCoordinator() {
    }

    /** 在 AFTER_SKY 保存远距与真实载具计划。 */
    public static void prepare(RVP_DhVehicleFramePlan plan, String initialFailureReason) {
        currentPlan = plan;
        failureReason = initialFailureReason == null || initialFailureReason.isBlank()
                ? "NO_DH_EVENT_THIS_FRAME"
                : initialFailureReason;
        state = plan == null ? State.EMPTY : State.PREPARED;
    }

    /** 返回仍等待 DH apply 前通道消费的帧信封。 */
    public static RVP_DhVehicleFramePlan currentForDh() {
        if (RVP_ClientConfig.getDistantHorizonsCompatMode()
                == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST) {
            return null;
        }
        return state == State.PREPARED ? currentPlan : null;
    }

    /** 在所有非空层的颜色与深度均成功写回后标记本帧已消费。 */
    public static void markDhCompositeSuccess(RVP_DhVehicleFramePlan plan) {
        if (plan == currentPlan && state == State.PREPARED) {
            state = State.DH_COMPOSITED;
            failureReason = "NONE";
        }
    }

    /** 保存分层合成失败原因，供显式回退和受限诊断使用。 */
    public static void markDhCompositeFailure(String reason) {
        if (reason != null && !reason.isBlank()) {
            failureReason = reason;
        }
    }

    /** 在 AFTER_ENTITIES 执行 CURRENT_PASS；真实实体继续使用其正常实体通道。 */
    public static void finishCurrentPassFallback(RenderLevelStageEvent event) {
        if (state != State.PREPARED || currentPlan == null
                || RVP_ClientConfig.getDistantHorizonsCompatMode()
                == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST
                || RVP_ClientConfig.getDistantHorizonsFallbackMode()
                != DistantHorizonsFallbackMode.CURRENT_PASS) {
            return;
        }
        if (currentPlan.remotePlan() != null) {
            // 调用现有远距渲染器，CURRENT_PASS 只补绘没有原生实体通道的远距代理。
            RVP_RemoteVehicleVisualRenderer.renderPrepared(
                    currentPlan.remotePlan(), event.getCamera());
        }
        state = State.CURRENT_PASS;
        // 调用本项目诊断器，记录 remote 与 tracked 的独立数量及回退原因。
        RVP_DhCompatDiagnostics.recordFallback(failureReason,
                DistantHorizonsFallbackMode.CURRENT_PASS,
                currentPlan.remoteSelectedCount(), currentPlan.trackedSelectedCount());
    }

    /** 在 AFTER_LEVEL 执行 RVP_FIRST 或显式晚期降级，并结束本帧。 */
    public static void finishLateOutput(RenderLevelStageEvent event) {
        // 调用像素取证，在本帧世界渲染末端记录 DH 与最终世界目标的同坐标颜色。
        RVP_DhPixelDiagnostics.afterWorldStage("AFTER_LEVEL", true);
        try {
            if (currentPlan == null) {
                return;
            }
            if (RVP_ClientConfig.getDistantHorizonsCompatMode()
                    == RVP_ClientConfig.DistantHorizonsCompatMode.RVP_FIRST) {
                if (state == State.PREPARED) {
                    // 调用统一晚期合成入口，使 remote/tracked 各绘制一次且不调用完整实体渲染器。
                    RVP_DhDepthCompositeRenderer.renderLateFallback(currentPlan,
                            Minecraft.getInstance().gameRenderer.getMainCamera(),
                            DistantHorizonsFallbackMode.ALWAYS_VISIBLE);
                    state = State.RVP_FIRST;
                    RVP_DhCompatDiagnostics.recordRvpFirst(
                            currentPlan.remoteSelectedCount(), currentPlan.trackedSelectedCount());
                }
                return;
            }
            if (state != State.PREPARED) {
                return;
            }
            DistantHorizonsFallbackMode fallback = RVP_ClientConfig.getDistantHorizonsFallbackMode();
            if (fallback == DistantHorizonsFallbackMode.SILHOUETTE
                    || fallback == DistantHorizonsFallbackMode.ALWAYS_VISIBLE) {
                // 调用统一晚期合成入口，按用户选择输出两类载具的轮廓或完整图像。
                RVP_DhDepthCompositeRenderer.renderLateFallback(currentPlan,
                        Minecraft.getInstance().gameRenderer.getMainCamera(), fallback);
            }
            state = State.LATE_FALLBACK;
            RVP_DhCompatDiagnostics.recordFallback(failureReason, fallback,
                    currentPlan.remoteSelectedCount(), currentPlan.trackedSelectedCount());
        } finally {
            currentPlan = null;
            state = State.EMPTY;
        }
    }

    /** 换维度、退出世界或资源重载时清除尚未消费的帧计划。 */
    public static void clear() {
        currentPlan = null;
        failureReason = "NO_DH_EVENT_THIS_FRAME";
        state = State.EMPTY;
    }

    /** 一帧统一信封的唯一消费状态。 */
    private enum State {
        /** 当前没有可消费帧。 */
        EMPTY,
        /** 帧已准备且仍等待 DH 或回退通道。 */
        PREPARED,
        /** 两个非空层均已完成 DH 深度合成。 */
        DH_COMPOSITED,
        /** 已由 AFTER_ENTITIES 当前 pass 消费。 */
        CURRENT_PASS,
        /** 已由 AFTER_LEVEL 降级消费。 */
        LATE_FALLBACK,
        /** 已按 RVP_FIRST 在 AFTER_LEVEL 消费。 */
        RVP_FIRST
    }
}
