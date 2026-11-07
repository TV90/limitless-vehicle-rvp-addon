package org.ywzj.rvp.client.gui;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.event.RVP_RotaryFlightMode;
import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import java.util.ArrayList;
import java.util.List;

/** 动作条的纯展示模型；辅助载具状态来自服务端快照，武器动作来自当前操作武器站。 */
public final class RVP_VehicleActionHudModel {
    public enum Action {
        DEPLOY, SWITCH, MAP, STATUS, SEEKER,
        /** 火控稳定模式。 */
        FIRE_CONTROL_STABLE,
        /** 火控半自动模式。 */
        FIRE_CONTROL_SEMI_AUTO,
        /** 火控关闭模式。 */
        FIRE_CONTROL_OFF,
        QUICK_LOCK,
        /** 固定翼自动盘旋开关。 */
        LOITER,
        /** 直升机常规机动。 */
        ROTARY_NORMAL,
        /** 直升机自动总距。 */
        ROTARY_AUTO_COLLECTIVE,
        /** 直升机悬停。 */
        ROTARY_HOVER,
        /** GPS 制导武器标点（R 键）。 */
        GPS_MARK,
        /** GPS 单点模式。 */
        GPS_SINGLE,
        /** GPS 多点模式。 */
        GPS_MULTI,
        /** GPS 快速模式。 */
        GPS_FAST,
        /** GPS 雷达模式。 */
        GPS_RADAR,
        /** 定距空爆引信装订（R 键测距）。 */
        AIRBURST
    }
    public enum Phase { READY, DISABLED, COOLING, DEPLOYED, ACTIVE }
    public record Card(Action action, Phase phase, boolean enabled, int cooldownSeconds) {}

    /**
     * 武器类提示：applicable 决定是否出卡，active 决定是否常亮（替代只有按键瞬间的反馈）。
     * {@code gpsModeAction} 为当前 GPS 模式对应的动作枚举，null 表示非 GPS 武器。
     */
    public record WeaponPrompts(boolean seeker, boolean seekerActive,
                                @Nullable Action fireControlAction, boolean fireControlActive,
                                boolean quickLock, boolean quickLockActive,
                                boolean gpsMark, boolean gpsMarkActive,
                                @Nullable Action gpsModeAction,
                                boolean airburst, boolean airburstActive) {
        public static final WeaponPrompts NONE =
                new WeaponPrompts(false, false, null, false, false, false, false, false, null, false, false);
    }

    /** 飞行类提示：盘旋是否可用/进行中，以及直升机三态（非旋翼传 null）。 */
    public record FlightPrompts(boolean loiterApplicable, boolean loitering,
                                @Nullable RVP_RotaryFlightMode rotaryMode) {
        public static final FlightPrompts NONE = new FlightPrompts(false, false, null);
    }
    /** 所有几何使用 1920×1080 参考画布，GUI scale 不影响最终物理尺寸。 */
    public record Layout(float scale, float left, float top, float totalWidth) {}
    public static final int CARD_WIDTH = 88, CARD_HEIGHT = 104, GAP = 8, BOTTOM_MARGIN = 24;
    private RVP_VehicleActionHudModel() {}

    /**
     * 完整动作条：辅助载具 + 飞行模式 + 武器提示。
     *
     * @param weapon 当前武器站可显示的动作及其开关状态
     * @param flight 盘旋与直升机三态
     */
    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state, WeaponPrompts weapon, FlightPrompts flight) {
        List<Card> cards = new ArrayList<>(cards(state));
        if (flight.loiterApplicable()) cards.add(toggle(Action.LOITER, flight.loitering()));
        if (flight.rotaryMode() != null) {
            cards.add(ready(switch (flight.rotaryMode()) {
                case NORMAL -> Action.ROTARY_NORMAL;
                case AUTO_COLLECTIVE -> Action.ROTARY_AUTO_COLLECTIVE;
                case HOVER -> Action.ROTARY_HOVER;
            }));
        }
        if (weapon.seeker()) cards.add(toggle(Action.SEEKER, weapon.seekerActive()));
        if (weapon.fireControlAction() != null) {
            cards.add(toggle(weapon.fireControlAction(), weapon.fireControlActive()));
        }
        if (weapon.quickLock()) cards.add(toggle(Action.QUICK_LOCK, weapon.quickLockActive()));
        if (weapon.gpsMark()) cards.add(toggle(Action.GPS_MARK, weapon.gpsMarkActive()));
        if (weapon.gpsModeAction() != null) cards.add(ready(weapon.gpsModeAction()));
        if (weapon.airburst()) cards.add(toggle(Action.AIRBURST, weapon.airburstActive()));
        return cards;
    }

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
    /** 开关类动作：开启时常亮，关闭时回到普通待命外观。 */
    private static Card toggle(Action action, boolean active) {
        return active ? new Card(action, Phase.ACTIVE, true, 0) : ready(action);
    }
    private static Card available(Action action, boolean enabled) {
        return new Card(action, enabled ? Phase.READY : Phase.DISABLED, enabled, 0);
    }

    /** 按可见卡片总宽度居中。 */
    public static Layout layout(int guiWidth, int guiHeight, int count) {
        float scale = Math.max(0F, Math.min(guiWidth / 1920F, guiHeight / 1080F));
        float total = count <= 0 ? 0 : count * CARD_WIDTH + (count - 1) * GAP;
        return new Layout(scale, (guiWidth - total * scale) / 2F,
                guiHeight - (CARD_HEIGHT + BOTTOM_MARGIN) * scale, total * scale);
    }
}
