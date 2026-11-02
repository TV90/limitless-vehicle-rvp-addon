package org.ywzj.rvp.client.gui;

import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import java.util.List;

/** 动作条的纯展示模型；角色、冷却和许可全部来自服务端快照。 */
public final class RVP_VehicleActionHudModel {
    public enum Action { DEPLOY, SWITCH, MAP, STATUS }
    public enum Phase { READY, DISABLED, COOLING, DEPLOYED }
    public record Card(Action action, Phase phase, boolean enabled, int cooldownSeconds) {}
    /** 所有几何使用 1920×1080 参考画布，GUI scale 不影响最终物理尺寸。 */
    public record Layout(float scale, float left, float top, float totalWidth) {}
    public static final int CARD_WIDTH = 88, CARD_HEIGHT = 104, GAP = 8, BOTTOM_MARGIN = 24;
    private RVP_VehicleActionHudModel() {}

    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state) {
        Card map = ready(Action.MAP), status = ready(Action.STATUS);
        if (state == null || state.role() == RVP_AuxiliaryHudSnapshot.Role.ORDINARY) return List.of(map, status);
        if (state.role() == RVP_AuxiliaryHudSnapshot.Role.CHILD)
            return List.of(available(Action.SWITCH, state.switchAllowed()), map, status);
        int seconds = (int) ((Math.max(0L, state.cooldownTicks()) + 19L) / 20L);
        if (seconds > 0) return List.of(new Card(Action.DEPLOY, Phase.COOLING, false, seconds),
                new Card(Action.SWITCH, Phase.COOLING, false, seconds), map, status);
        Card deploy = state.deployed() ? new Card(Action.DEPLOY, Phase.DEPLOYED, false, 0)
                : available(Action.DEPLOY, state.deployAllowed());
        return List.of(deploy, available(Action.SWITCH, state.switchAllowed()), map, status);
    }

    private static Card ready(Action action) { return new Card(action, Phase.READY, true, 0); }
    private static Card available(Action action, boolean enabled) {
        return new Card(action, enabled ? Phase.READY : Phase.DISABLED, enabled, 0);
    }

    /** 两卡、三卡、四卡都按真实总宽度居中，不给隐藏入口预留空槽。 */
    public static Layout layout(int guiWidth, int guiHeight, int count) {
        float scale = Math.max(0F, Math.min(guiWidth / 1920F, guiHeight / 1080F));
        float total = count <= 0 ? 0 : count * CARD_WIDTH + (count - 1) * GAP;
        return new Layout(scale, (guiWidth - total * scale) / 2F,
                guiHeight - (CARD_HEIGHT + BOTTOM_MARGIN) * scale, total * scale);
    }
}
