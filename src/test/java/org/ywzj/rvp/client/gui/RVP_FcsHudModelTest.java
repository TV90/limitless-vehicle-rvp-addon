package org.ywzj.rvp.client.gui;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.Action;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.Card;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.Phase;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.markFcsDamaged;

class RVP_FcsHudModelTest {

    @Test
    void fcsDamageMarksFireControlVisionAndBothLockCards() {
        List<Card> cards = List.of(
                new Card(Action.FIRE_CONTROL_STABLE, Phase.ACTIVE, true, 0),
                new Card(Action.QUICK_LOCK, Phase.READY, true, 0),
                new Card(Action.FOCUS_LOCK, Phase.READY, true, 0),
                new Card(Action.VISION_THERMAL, Phase.ACTIVE, true, 0),
                new Card(Action.SEEKER, Phase.ACTIVE, true, 0));

        List<Card> marked = markFcsDamaged(cards, true, true);

        assertEquals(Phase.DAMAGED, marked.get(0).phase());
        assertEquals(Phase.DAMAGED, marked.get(1).phase());
        assertEquals(Phase.DAMAGED, marked.get(2).phase());
        assertEquals(Phase.DAMAGED, marked.get(3).phase());
        assertEquals(Phase.ACTIVE, marked.get(4).phase());
    }

    @Test
    void radarQuickLockIsNotMarkedByFcsDamage() {
        List<Card> cards = List.of(
                new Card(Action.FIRE_CONTROL_OFF, Phase.READY, true, 0),
                new Card(Action.QUICK_LOCK, Phase.ACTIVE, true, 0));

        List<Card> marked = markFcsDamaged(cards, true, false);

        assertEquals(Phase.DAMAGED, marked.get(0).phase());
        assertEquals(Phase.ACTIVE, marked.get(1).phase());
    }

    @Test
    void healthyFcsLeavesCardsUntouched() {
        List<Card> cards = List.of(new Card(Action.FIRE_CONTROL_STABLE, Phase.ACTIVE, true, 0));
        assertSame(cards, markFcsDamaged(cards, false, true));
    }

    @Test
    void fcsModuleNameParses() {
        assertEquals(BoneModuleType.FCS, BoneModuleType.byName("fcs"));
    }
}
