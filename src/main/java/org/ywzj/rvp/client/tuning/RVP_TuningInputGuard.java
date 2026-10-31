package org.ywzj.rvp.client.tuning;

import java.util.function.BooleanSupplier;

/** 调参按键与其它动作共用按键时的队列处理，不修改玩家的平滑视角设置。 */
public final class RVP_TuningInputGuard {
    private RVP_TuningInputGuard() { }

    /** 只有本次按键用于打开面板且平滑视角同键时，消费后者的待处理点击。 */
    public static int consumeCameraConflict(boolean openingPanel, boolean cameraUsesSameKey, BooleanSupplier consumeCameraClick) {
        if (!openingPanel || !cameraUsesSameKey) { return 0; }
        int consumed = 0;
        while (consumeCameraClick.getAsBoolean()) { consumed++; }
        return consumed;
    }
}
