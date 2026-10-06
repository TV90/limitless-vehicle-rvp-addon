package org.ywzj.rvp.client.state;

/**
 * 客户端直升机着舰/接管控制锁定状态（S2CHeliDockState 同步）。
 * <p>用于本地运动输入预清零：服务端 {@code ControlUnitMixin} 已拦截接管期的运动字段，
 * 客户端同步清零防止预测打架（RVP_ClientEvents 盘旋抑制同款模式）。</p>
 */
public final class RVP_ClientHeliDockState {

    private static volatile boolean controlLocked;

    private RVP_ClientHeliDockState() {
    }

    public static void setControlLocked(boolean locked) {
        controlLocked = locked;
    }

    public static boolean isControlLocked() {
        return controlLocked;
    }
}
