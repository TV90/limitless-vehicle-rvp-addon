package org.ywzj.rvp.firesupport;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_FireSupportWeaponBudgetTest {
    @Test
    void directChildExpansionUsesMissionWorstCaseAndStaysBounded() {
        assertEquals(4096L, RVP_FireSupportWeaponBudget.estimateExpandedEntities(16, 255));
        assertEquals(0L, RVP_FireSupportWeaponBudget.estimateExpandedEntities(-1, 10));
    }

    @Test
    void rejectsLongLifeNestedAndOverExpandedWeapons() {
        RVP_FireSupportResolvedWeapon unsafe = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.BOMB, false, false, false,
                2401, 256, true, 100_001.0F, 65.0F);
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportWeaponBudget.validate(unsafe, 16, problems, "weapon");
        assertThrows(IllegalArgumentException.class, problems::throwIfAny);
    }
}
