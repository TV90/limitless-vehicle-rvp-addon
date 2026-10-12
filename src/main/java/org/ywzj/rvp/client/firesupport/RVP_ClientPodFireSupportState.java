package org.ywzj.rvp.client.firesupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端吊舱火力支援选择状态（2026-10-13 整合批）：按武器 path 记录服务端确认的
 * 已保存内联条目 id（S2CPodFireSupportSelection 下发，跨会话由服务端持久化）。
 * 吊舱地图工具读取本状态显示当前选择；null/缺失 = 未选择。
 */
public final class RVP_ClientPodFireSupportState {

    /** 单例。 */
    public static final RVP_ClientPodFireSupportState INSTANCE = new RVP_ClientPodFireSupportState();

    /** 武器 path → 已保存条目 id（服务端权威，客户端只读）。 */
    private final Map<String, String> selections = new ConcurrentHashMap<>();

    private RVP_ClientPodFireSupportState() {}

    /** S2C 回调：更新（entryId 空串 = 清除）。 */
    public void onSelection(String weaponPath, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            selections.remove(weaponPath);
        } else {
            selections.put(weaponPath, entryId);
        }
    }

    /** @return 当前已保存条目 id；未选择返回 null。 */
    public String getSelection(String weaponPath) {
        return selections.get(weaponPath);
    }
}
