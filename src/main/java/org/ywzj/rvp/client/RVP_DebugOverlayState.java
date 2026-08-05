package org.ywzj.rvp.client;

/**
 * F10 调试覆盖层开关状态（命中调试HUD）。
 * <p>服务端始终发送命中调试数据包，客户端根据此开关决定是否渲染。
 */
public final class RVP_DebugOverlayState {

    private static boolean enabled;

    private RVP_DebugOverlayState() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
