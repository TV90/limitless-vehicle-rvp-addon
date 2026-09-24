package org.ywzj.rvp.client.state;

/**
 * 雷达 HMD、IR HMD 与 EO HMD 的独立通道开关。
 *
 * <p>三类 HMD 可以同时工作：雷达通道由按键切换（雷达优先），IR 通道由当前武器与导引头状态
 * 自动驱动，EO 通道由按键切换（仅 EO 武器站且载具无雷达时可用）。</p>
 */
final class RVP_HmdChannelState {

    /** 雷达头瞄捕获通道是否开启。 */
    private boolean radarActive;

    /** 红外导引头头瞄通道是否开启。 */
    private boolean irActive;

    /** 光电（EO）头瞄通道是否开启。 */
    private boolean eoActive;

    /** @return 任一 HMD 通道是否开启。 */
    boolean isAnyActive() {
        return radarActive || irActive || eoActive;
    }

    /** @return 雷达 HMD 通道是否开启。 */
    boolean isRadarActive() {
        return radarActive;
    }

    /** @return IR HMD 通道是否开启。 */
    boolean isIrActive() {
        return irActive;
    }

    /** @return EO HMD 通道是否开启。 */
    boolean isEoActive() {
        return eoActive;
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

    /** 开启 EO HMD 通道，不影响雷达/IR HMD。 */
    void enableEo() {
        eoActive = true;
    }

    /** 关闭 EO HMD 通道，不影响雷达/IR HMD。 */
    void disableEo() {
        eoActive = false;
    }

    /** 离开有效载具上下文时同时关闭全部通道。 */
    void disableAll() {
        radarActive = false;
        irActive = false;
        eoActive = false;
    }
}
