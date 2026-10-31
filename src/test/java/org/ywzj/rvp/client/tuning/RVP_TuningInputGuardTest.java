package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 复现同键动作进入原版消费循环的先后关系，检查首次和后续打开的视角状态一致。 */
class RVP_TuningInputGuardTest {
    @Test
    void firstAndRepeatedPanelOpenNeverToggleTheExistingCameraSetting() {
        for (boolean initialSmoothCamera : new boolean[] {false, true}) {
            boolean smoothCamera = initialSmoothCamera;
            for (int cycle = 0; cycle < 3; cycle++) {
                AtomicInteger pendingCameraClicks = new AtomicInteger(1);
                // 调用事件处理器使用的同一输入策略，再执行原版“消费一次即翻转”的行为。
                assertEquals(1, RVP_TuningInputGuard.consumeCameraConflict(true, true,
                        () -> pendingCameraClicks.getAndUpdate(value -> Math.max(0, value - 1)) > 0));
                while (pendingCameraClicks.getAndUpdate(value -> Math.max(0, value - 1)) > 0) { smoothCamera = !smoothCamera; }
                assertEquals(initialSmoothCamera, smoothCamera, "第" + cycle + "次打开后视角不应改变");
            }
        }
    }

    @Test
    void unrelatedBindingsAndNonOpeningKeysKeepVanillaCameraClicks() {
        for (boolean[] condition : new boolean[][] {{false, true}, {true, false}, {false, false}}) {
            AtomicInteger pending = new AtomicInteger(1);
            assertEquals(0, RVP_TuningInputGuard.consumeCameraConflict(condition[0], condition[1],
                    () -> pending.getAndUpdate(value -> Math.max(0, value - 1)) > 0));
            assertEquals(1, pending.get(), "独立平滑视角按键仍由原版处理");
        }
    }
}
