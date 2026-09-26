package org.ywzj.rvp.client.state;

import net.minecraft.client.resources.language.I18n;
import org.ywzj.rvp.network.S2CModuleHitNotify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [RVP] 部件战果通知客户端状态（2026-09-27 新增）：保存射手最近收到的
 * "摧毁部件/重创发动机"通知，供命中展板（{@code RVP_HitIndicatorOverlay}）
 * 在展板下方渲染，每条显示 60 tick（3 秒）。
 *
 * <p>文案在客户端本地化：摧毁 = {@code 摧毁 + gui.ywzj_rvp.module.<类型小写>}；
 * 引擎受损 = {@code gui.ywzj_rvp.hit.engine_damaged}（"重创发动机"）。</p>
 */
public final class RVP_ClientHitNotifyState {

    /** 单条通知显示时长（毫秒）= 60 tick。 */
    public static final long DURATION_MS = 3000L;
    /** 最多同时显示的行数（一骨多模块摧毁时截断，防刷屏）。 */
    private static final int MAX_LINES = 4;

    /** 单条通知：已本地化文案 + 颜色（展板配色语义：摧毁红 / 受损橙）。 */
    public record Notify(String text, int color, long expireAtMs) {
    }

    private static final List<Notify> NOTIFY = new ArrayList<>();

    private RVP_ClientHitNotifyState() {
    }

    /** 收包入口（客户端主线程）：构建本地化文案并入列；同文案已有未过期条目时跳过（重创只显示一次兜底）。 */
    public static void apply(S2CModuleHitNotify msg) {
        long now = System.currentTimeMillis();
        String text;
        int color;
        if (msg.kind == S2CModuleHitNotify.KIND_ENGINE_DAMAGED) {
            text = I18n.get("gui.ywzj_rvp.hit.engine_damaged");
            color = 0xFFFFD060; // 与展板"中度伤害"橙一致
        } else {
            String moduleName = I18n.get("gui.ywzj_rvp.module."
                    + msg.moduleName.toLowerCase(Locale.ROOT));
            text = I18n.get("gui.ywzj_rvp.hit.module_destroyed", moduleName);
            color = 0xFFFF5555; // 与展板"重度伤害"红一致
        }
        // 客户端兜底去重：服务端已做窗期去重，这里再防同文案重复入列
        for (Notify n : NOTIFY) {
            if (n.text().equals(text) && now < n.expireAtMs()) {
                return;
            }
        }
        NOTIFY.add(new Notify(text, color, now + DURATION_MS));
        while (NOTIFY.size() > MAX_LINES) {
            NOTIFY.remove(0);
        }
    }

    /** 取未过期通知（顺带清理过期项）；展板渲染每帧调用。 */
    public static List<Notify> active(long nowMs) {
        NOTIFY.removeIf(n -> nowMs >= n.expireAtMs());
        return NOTIFY;
    }

    /** 登出/换世界时清理。 */
    public static void clear() {
        NOTIFY.clear();
    }
}
