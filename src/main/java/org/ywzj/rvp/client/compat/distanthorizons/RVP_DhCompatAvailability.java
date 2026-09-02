package org.ywzj.rvp.client.compat.distanthorizons;

/**
 * 保存 DH 强类型桥的运行期可用性，使永久初始化失败不会被逐帧默认原因覆盖。
 */
final class RVP_DhCompatAvailability {
    /** 桥已就绪但当前帧没有成功消费 apply 前事件时使用的默认原因。 */
    static final String NO_EVENT_THIS_FRAME = "NO_DH_EVENT_THIS_FRAME";
    /** DH 已加载、但初始化完成事件尚未确认桥能力时使用的原因。 */
    static final String BRIDGE_INITIALIZING = "DH_BRIDGE_INITIALIZING";

    /** 深度感知桥是否已经完成版本、渲染器与事件绑定校验。 */
    private volatile boolean depthCompositeReady;
    /** 桥未就绪时必须跨帧保留的具体失败原因。 */
    private volatile String unavailableReason = BRIDGE_INITIALIZING;
    /** 已观察到的 DH API 版本，尚未进入 DH 初始化回调时为 unknown。 */
    private volatile String apiVersion = "unknown";

    /** 记录 DH API 版本，供成功统计和失败诊断使用。 */
    void updateApiVersion(String version) {
        if (version != null && !version.isBlank()) {
            apiVersion = version;
        }
    }

    /** 标记桥已完成全部能力校验与 before-apply 事件绑定。 */
    void markReady() {
        depthCompositeReady = true;
        unavailableReason = "NONE";
    }

    /** 标记桥永久或当前初始化阶段不可用，并保留明确原因。 */
    void markUnavailable(String reason) {
        depthCompositeReady = false;
        unavailableReason = normalizeReason(reason);
    }

    /** 返回当前是否允许 DH before-apply 回调执行深度合成。 */
    boolean isDepthCompositeReady() {
        return depthCompositeReady;
    }

    /** 返回新帧初始失败原因；只有已就绪桥才允许使用“本帧无事件”。 */
    String failureReasonForFrame() {
        return depthCompositeReady ? NO_EVENT_THIS_FRAME : unavailableReason;
    }

    /** 返回已观察到的 DH API 版本。 */
    String apiVersion() {
        return apiVersion;
    }

    /** 把空失败原因收敛为明确的初始化状态，禁止日志出现空 reason。 */
    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? BRIDGE_INITIALIZING : reason;
    }
}
