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
        AIRBURST,
        /** 白光通道（未开启夜视/热成像）。 */
        VISION_WHITE,
        /** 夜视通道。 */
        VISION_NIGHT,
        /** 热成像通道。 */
        VISION_THERMAL,
        /** 装订呼叫火力打击信息（复用战术地图键）。 */
        FIRE_SUPPORT_BIND,
        /** 炮兵地图（复用战术地图键）。 */
        ARTILLERY_MAP,
        /** 焦点锁定（R 键，需观瞄站带焦点锁定器且处于观瞄视角）。 */
        FOCUS_LOCK
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
                                boolean airburst, boolean airburstActive,
                                @Nullable Action visionAction, boolean visionActive) {
        public static final WeaponPrompts NONE =
                new WeaponPrompts(false, false, null, false, false, false, false, false, null,
                        false, false, null, false);
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
        return cards(state, weapon, flight, Action.MAP);
    }

    /**
     * 完整动作条，地图卡按当前武器替换为火力装订/炮兵地图。
     *
     * @param mapAction 当前 K 键实际打开的地图类型对应的动作卡
     */
    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state, WeaponPrompts weapon, FlightPrompts flight,
                                   Action mapAction) {
        return cards(state, weapon, flight, mapAction, false, false);
    }

    /**
     * 完整动作条；{@code focusLock} 为当前观瞄站是否提供焦点锁定（由调用方按观瞄视角判定）。
     */
    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state, WeaponPrompts weapon, FlightPrompts flight,
                                   Action mapAction, boolean focusLock, boolean focusLockActive) {
        List<Card> cards = new ArrayList<>(cards(state, mapAction));
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
        if (focusLock) cards.add(toggle(Action.FOCUS_LOCK, focusLockActive));
        if (weapon.gpsMark()) cards.add(toggle(Action.GPS_MARK, weapon.gpsMarkActive()));
        if (weapon.gpsModeAction() != null) cards.add(ready(weapon.gpsModeAction()));
        if (weapon.airburst()) cards.add(toggle(Action.AIRBURST, weapon.airburstActive()));
        if (weapon.visionAction() != null) {
            cards.add(toggle(weapon.visionAction(), weapon.visionActive()));
        }
        return cards;
    }

    /**
     * 按键提示排列：导引头、快速锁定、焦点锁定、部署/切换等通用功能在前，
     * 火控/制导/视觉在中，盘旋与旋翼模式随后，战术地图与载具状态收尾。
     */
    public static List<Card> sortForDisplay(List<Card> cards) {
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(java.util.Comparator.comparingInt(card -> displayPriority(card.action())));
        return sorted;
    }

    private static int displayPriority(Action action) {
        return switch (action) {
            case SEEKER -> 10;
            case QUICK_LOCK -> 20;
            case FOCUS_LOCK -> 30;
            case DEPLOY -> 40;
            case SWITCH -> 50;
            case FIRE_CONTROL_STABLE, FIRE_CONTROL_SEMI_AUTO, FIRE_CONTROL_OFF -> 60;
            case GPS_MARK, GPS_SINGLE, GPS_MULTI, GPS_FAST, GPS_RADAR -> 70;
            case AIRBURST -> 80;
            case VISION_WHITE, VISION_NIGHT, VISION_THERMAL -> 90;
            case LOITER, ROTARY_NORMAL, ROTARY_AUTO_COLLECTIVE, ROTARY_HOVER -> 100;
            case MAP, FIRE_SUPPORT_BIND, ARTILLERY_MAP -> 110;
            case STATUS -> 120;
        };
    }

    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state) {
        return cards(state, Action.MAP);
    }

    /** 地图卡可替换为火力装订/炮兵地图；其它动作枚举按战术地图处理。 */
    public static List<Card> cards(RVP_AuxiliaryHudSnapshot state, Action mapAction) {
        Card map = ready(mapAction), status = ready(Action.STATUS);
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

    /**
     * 按当前武器决定 K 键地图卡：装订呼叫火力 > 炮兵地图 > 普通战术地图。
     * 与 RVP_ClientEvents 的按键分流保持同一优先级。
     */
    public static Action mapAction(boolean fireSupportBind, boolean artilleryMap) {
        if (fireSupportBind) return Action.FIRE_SUPPORT_BIND;
        return artilleryMap ? Action.ARTILLERY_MAP : Action.MAP;
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
