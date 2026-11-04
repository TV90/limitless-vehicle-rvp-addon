package org.ywzj.rvp.client.gui;

import org.junit.jupiter.api.Test;
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
                for (int count = 2; count <= 7; count++) {
                    var layout = layout(window[0] / guiScale, window[1] / guiScale, count);
                    assertEquals(window[0] / 2F, (layout.left() + layout.totalWidth() / 2) * guiScale, .01);
                    assertTrue(layout.left() >= 0); assertTrue(layout.top() >= 0);
                    if (physicalWidth < 0) physicalWidth = CARD_WIDTH * layout.scale() * guiScale;
                    assertEquals(physicalWidth, CARD_WIDTH * layout.scale() * guiScale, .01);
                }
            }
        }
    }

    @Test void weaponHintsDoNotInheritAuxiliaryCooldownOrLeaveEmptySlots() {
        var s = state(PARENT, 40, false);
        var list = cards(s, true, false, true);
        assertEquals(List.of(Action.DEPLOY, Action.SWITCH, Action.MAP, Action.STATUS, Action.SEEKER, Action.QUICK_LOCK),
                list.stream().map(Card::action).toList());
        assertEquals(Phase.COOLING, list.get(0).phase());
        assertTrue(list.subList(4, 6).stream().allMatch(Card::enabled));
    }
}
