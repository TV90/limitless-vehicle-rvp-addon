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
    void rejectsOnlyExtremelyLargeWeaponBudgets() {
        RVP_FireSupportResolvedWeapon unsafe = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.BOMB, false, false, false,
                72001, 4097, true, 1_000_000_064.0F, 513.0F);
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportWeaponBudget.validate(unsafe, 16, problems, "weapon");
        assertThrows(IllegalArgumentException.class, problems::throwIfAny);
    }

    @Test
    void allowsNestedSubmunitionsInsideRelaxedVisibleBudget() {
        RVP_FireSupportResolvedWeapon nested = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.BOMB, false, false, false,
                6000, 512, true, 250_000.0F, 96.0F);
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportWeaponBudget.validate(nested, 128, problems, "weapon");
        problems.throwIfAny();
    }
}
