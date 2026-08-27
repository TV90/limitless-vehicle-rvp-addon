package org.ywzj.rvp.client.state;

import java.util.List;

/**
 * 客户端"RWR 受干扰（被伪造锁定）"状态：由 {@link org.ywzj.rvp.client.state.RVP_ClientEcmFakeLockHandler}
 * 每次收到伪造锁定包时打点，供 {@link org.ywzj.rvp.client.gui.RVP_EcmFakeRwrOverlay} 渲染
 * 与本体 RWR 锁定同款样式的伪造 blip（绿色锁定线 + 机型标签）。
 * 本体 RWR 覆盖层对无实体的伪造锁定画不出 blip，这里用公共 IGuiOverlay API 在表盘同一坐标兜底。
 */
public final class RVP_ClientEcmVictimState {

    private static volatile long lastLockMs = -1;
    /** 最近一次收到的伪造锁定源数量。 */
    private static volatile int lastCount = 0;
    /** 最近一次收到的伪造雷达类型列表（供表盘 blip 标签显示）。 */
    private static volatile List<String> lastTypes = List.of();

    private RVP_ClientEcmVictimState() {}

    /** 记录一次伪造锁定注入。 */
    public static void markLocked(List<String> fakeRadarTypes) {
        lastLockMs = System.currentTimeMillis();
        lastCount = fakeRadarTypes == null ? 0 : fakeRadarTypes.size();
        lastTypes = fakeRadarTypes == null ? List.of() : List.copyOf(fakeRadarTypes);
    }

    /** 是否处于伪造锁定持续窗口内（约 1.5s，配合服务端 200ms 刷新保持常亮）。 */
    public static boolean isLocked() {
        return lastLockMs > 0 && (System.currentTimeMillis() - lastLockMs) < 1500L;
    }

    public static int getCount() {
        return lastCount;
    }

    public static List<String> getTypes() {
        return lastTypes;
    }
}