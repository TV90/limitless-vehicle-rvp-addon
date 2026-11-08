package org.ywzj.rvp.client.gui;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.event.RVP_RotaryFlightMode;
import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.*;
import static org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot.Role.*;

/** 覆盖配置缺失的部署子机、普通车、母车与不同 GUI scale 下真实像素大小/居中不变量。 */
class RVP_VehicleActionHudModelTest {
    private RVP_AuxiliaryHudSnapshot state(RVP_AuxiliaryHudSnapshot.Role role, int cooldown, boolean deployed) {
        return new RVP_AuxiliaryHudSnapshot(UUID.randomUUID(), role, cooldown, deployed, !deployed, deployed);
    }
    private List<Action> actions(RVP_AuxiliaryHudSnapshot s) { return cards(s).stream().map(Card::action).toList(); }

    @Test void ordinaryAndUnknownInstancesHaveOnlyMapAndStatus() {
        assertEquals(List.of(Action.MAP, Action.STATUS), actions(state(ORDINARY, 0, false)));
        assertEquals(List.of(Action.MAP, Action.STATUS), actions(null));
    }
    @Test void childIdentityOverridesBothAbsentAndPresentDeploymentConfig() {
        assertEquals(CHILD, RVP_AuxiliaryHudSnapshot.classify(true, false));
        assertEquals(CHILD, RVP_AuxiliaryHudSnapshot.classify(true, true));
        assertEquals(List.of(Action.SWITCH, Action.MAP, Action.STATUS), actions(state(CHILD, 0, true)));
        assertEquals(PARENT, RVP_AuxiliaryHudSnapshot.classify(false, true));
        assertEquals(ORDINARY, RVP_AuxiliaryHudSnapshot.classify(false, false));
    }
    @Test void parentRetainsFourActionsAndDeployedAppearanceInsteadOfRedeploy() {
        var s = state(PARENT, 0, true);
        assertEquals(List.of(Action.DEPLOY, Action.SWITCH, Action.MAP, Action.STATUS), actions(s));
        assertEquals(Phase.DEPLOYED, cards(s).get(0).phase());
        assertFalse(cards(s).get(0).enabled());
        assertTrue(cards(s).get(1).enabled());
    }
    @Test void cooldownDimsAuxiliaryActionsAndRoundsPositiveTicksUpToASecond() {
        for (int ticks : new int[]{1, 20, 21, 300}) {
            var list = cards(state(PARENT, ticks, false));
            for (var card : list.subList(0, 2)) {
                assertEquals(Phase.COOLING, card.phase()); assertFalse(card.enabled());
                assertEquals((ticks + 19) / 20, card.cooldownSeconds());
            }
            assertTrue(list.get(2).enabled()); assertTrue(list.get(3).enabled());
        }
    }
    @Test void everyRoleIsCenteredAndPhysicalSizeIsIndependentOfGuiScale() {
        for (int[] window : new int[][]{{1920,1080},{1280,720},{2560,1440},{3440,1440}}) {
            float physicalWidth = -1;
            for (int guiScale : new int[]{1,2,4}) {
                for (int count = 2; count <= 16; count++) {
                    var layout = layout(window[0] / guiScale, window[1] / guiScale, count);
                    assertEquals(window[0] / 2F, (layout.left() + layout.totalWidth() / 2) * guiScale, .01);
                    assertTrue(layout.left() >= 0); assertTrue(layout.top() >= 0);
                    if (physicalWidth < 0) physicalWidth = CARD_WIDTH * layout.scale() * guiScale;
                    assertEquals(physicalWidth, CARD_WIDTH * layout.scale() * guiScale, .01);
                }
            }
        }
    }

    private static WeaponPrompts weapon(boolean seeker, boolean seekerActive,
                                       Action fireControlAction, boolean fireControlActive,
                                       boolean quickLock, boolean quickLockActive) {
        return new WeaponPrompts(seeker, seekerActive, fireControlAction, fireControlActive,
                quickLock, quickLockActive, false, false, null, false, false, null, false);
    }

    @Test void weaponHintsDoNotInheritAuxiliaryCooldownOrLeaveEmptySlots() {
        var s = state(PARENT, 40, false);
        var list = cards(s, weapon(true, false, null, false, true, false), FlightPrompts.NONE);
        assertEquals(List.of(Action.DEPLOY, Action.SWITCH, Action.MAP, Action.STATUS, Action.SEEKER, Action.QUICK_LOCK),
                list.stream().map(Card::action).toList());
        assertEquals(Phase.COOLING, list.get(0).phase());
        assertTrue(list.subList(4, 6).stream().allMatch(Card::enabled));
    }

    @Test void togglePromptsStayLitWhileEnabledInsteadOfOnlyOnKeyPress() {
        var active = cards(null, weapon(true, true, null, false, true, true), FlightPrompts.NONE);
        assertEquals(Phase.ACTIVE, active.stream()
                .filter(card -> card.action() == Action.SEEKER).findFirst().orElseThrow().phase());
        assertEquals(Phase.ACTIVE, active.stream()
                .filter(card -> card.action() == Action.QUICK_LOCK).findFirst().orElseThrow().phase());

        var off = cards(null, weapon(true, false, null, false, true, false), FlightPrompts.NONE);
        assertEquals(Phase.READY, off.stream()
                .filter(card -> card.action() == Action.SEEKER).findFirst().orElseThrow().phase());
        assertEquals(Phase.READY, off.stream()
                .filter(card -> card.action() == Action.QUICK_LOCK).findFirst().orElseThrow().phase());
    }

    @Test void fireControlCardShowsOneOfThreeModesAndLightsWhenEnabled() {
        var stable = cards(null, weapon(false, false, Action.FIRE_CONTROL_STABLE, true, false, false),
                FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.FIRE_CONTROL_STABLE),
                stable.stream().map(Card::action).toList());
        assertEquals(Phase.ACTIVE, stable.get(2).phase());

        var semiAuto = cards(null, weapon(false, false, Action.FIRE_CONTROL_SEMI_AUTO, true, false, false),
                FlightPrompts.NONE);
        assertEquals(Action.FIRE_CONTROL_SEMI_AUTO, semiAuto.get(2).action());
        assertEquals(Phase.ACTIVE, semiAuto.get(2).phase());

        var offMode = cards(null, weapon(false, false, Action.FIRE_CONTROL_OFF, false, false, false),
                FlightPrompts.NONE);
        assertEquals(Action.FIRE_CONTROL_OFF, offMode.get(2).action());
        assertEquals(Phase.READY, offMode.get(2).phase());
    }

    @Test void loiterAndRotaryCardsFollowFeatureState() {
        // 可盘旋且未盘旋：普通卡片；盘旋中：ACTIVE 高亮
        var idle = cards(null, WeaponPrompts.NONE, new FlightPrompts(true, false, null));
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.LOITER), idle.stream().map(Card::action).toList());
        assertEquals(Phase.READY, idle.get(2).phase());
        var active = cards(null, WeaponPrompts.NONE, new FlightPrompts(true, true, null));
        assertEquals(Phase.ACTIVE, active.get(2).phase());

        // 直升机三态只放当前状态那一张卡，图标/文案由动作条按动作枚举选择
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.ROTARY_NORMAL),
                cards(null, WeaponPrompts.NONE, new FlightPrompts(false, false, RVP_RotaryFlightMode.NORMAL))
                        .stream().map(Card::action).toList());
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.ROTARY_AUTO_COLLECTIVE),
                cards(null, WeaponPrompts.NONE, new FlightPrompts(false, false, RVP_RotaryFlightMode.AUTO_COLLECTIVE))
                        .stream().map(Card::action).toList());
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.ROTARY_HOVER),
                cards(null, WeaponPrompts.NONE, new FlightPrompts(false, false, RVP_RotaryFlightMode.HOVER))
                        .stream().map(Card::action).toList());
    }

    @Test void gpsMarkerAndModeCardFollowSelectedMode() {
        var marked = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, true, true, Action.GPS_MULTI, false, false, null, false), FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.GPS_MARK, Action.GPS_MULTI),
                marked.stream().map(Card::action).toList());
        assertEquals(Phase.ACTIVE, marked.get(2).phase());
        assertEquals(Phase.READY, marked.get(3).phase());

        // FAST 模式由开火键取点，R 键不可用 → 不显示标点卡，只显示当前模式卡
        var fast = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, Action.GPS_FAST, false, false, null, false), FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.GPS_FAST),
                fast.stream().map(Card::action).toList());
    }

    @Test void visionCardShowsOneOfThreeChannelsAndLightsWhenNotWhite() {
        var white = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, null, false, false, Action.VISION_WHITE, false), FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.VISION_WHITE),
                white.stream().map(Card::action).toList());
        assertEquals(Phase.READY, white.get(2).phase());
        var night = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, null, false, false, Action.VISION_NIGHT, true), FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.VISION_NIGHT),
                night.stream().map(Card::action).toList());
        assertEquals(Phase.ACTIVE, night.get(2).phase());
        var thermal = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, null, false, false, Action.VISION_THERMAL, true), FlightPrompts.NONE);
        assertEquals(Action.VISION_THERMAL, thermal.get(2).action());
        assertEquals(Phase.ACTIVE, thermal.get(2).phase());
    }

    @Test void airburstCardLightsUpOnlyAfterRangeProgramming() {
        var pending = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, null, true, false, null, false), FlightPrompts.NONE);
        assertEquals(List.of(Action.MAP, Action.STATUS, Action.AIRBURST),
                pending.stream().map(Card::action).toList());
        assertEquals(Phase.READY, pending.get(2).phase());
        var programmed = cards(null, new WeaponPrompts(false, false, null, false, false,
                false, false, false, null, true, true, null, false), FlightPrompts.NONE);
        assertEquals(Phase.ACTIVE, programmed.get(2).phase());
    }
}
