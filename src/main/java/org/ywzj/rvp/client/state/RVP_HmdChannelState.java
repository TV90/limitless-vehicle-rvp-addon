package org.ywzj.rvp.client.state;

/**
 * 雷达 HMD 与 IR HMD 的独立通道开关。
 *
 * <p>两类 HMD 可以同时工作：雷达通道由按键切换，IR 通道由当前武器与导引头状态自动驱动。</p>
 */
final class RVP_HmdChannelState {

    /** 雷达头瞄捕获通道是否开启。 */
    private boolean radarActive;

    /** 红外导引头头瞄通道是否开启。 */
    private boolean irActive;

    /** @return 任一 HMD 通道是否开启。 */
    boolean isAnyActive() {
        return radarActive || irActive;
    }

    /** @return 雷达 HMD 通道是否开启。 */
    boolean isRadarActive() {
        return radarActive;
    }

    /** @return IR HMD 通道是否开启。 */
    boolean isIrActive() {
        return irActive;
    }

    /** 开启雷达 HMD 通道，不影响 IR HMD。 */
    void enableRadar() {
        radarActive = true;
    }

    /** 关闭雷达 HMD 通道，不影响 IR HMD。 */
    void disableRadar() {
        radarActive = false;
    }

    /** 设置 IR HMD 通道，不影响雷达 HMD。 */
    void setIrActive(boolean active) {
        irActive = active;
    }

    /** 离开有效载具上下文时同时关闭两个通道。 */
    void disableAll() {
        radarActive = false;
        irActive = false;
    }
}
