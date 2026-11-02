package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rvp_hide_in_creative 的登记表：只按载具 id 判定，缺省 / 空值不隐藏。
 */
class RVP_CreativeHiddenVehiclesTest {

    @AfterEach
    void reset() {
        RVP_CreativeHiddenVehicles.clear();
    }

    @Test
    void hiddenVehicleIsReportedAndClearedOnReset() {
        ResourceLocation suav = ResourceLocation.tryParse("rvp:suav");
        assertFalse(RVP_CreativeHiddenVehicles.isHidden(suav));
        assertTrue(RVP_CreativeHiddenVehicles.isEmpty());

        RVP_CreativeHiddenVehicles.add(suav);
        assertTrue(RVP_CreativeHiddenVehicles.isHidden(suav));
        assertFalse(RVP_CreativeHiddenVehicles.isEmpty());

        RVP_CreativeHiddenVehicles.clear();
        assertFalse(RVP_CreativeHiddenVehicles.isHidden(suav));
        assertTrue(RVP_CreativeHiddenVehicles.isEmpty());
    }

    @Test
    void otherVehiclesAndNullStayVisible() {
        RVP_CreativeHiddenVehicles.add(new ResourceLocation("rvp", "96l6"));
        assertFalse(RVP_CreativeHiddenVehicles.isHidden(new ResourceLocation("rvp", "m142")));
        assertFalse(RVP_CreativeHiddenVehicles.isHidden(null));

        RVP_CreativeHiddenVehicles.add(null);
        assertTrue(RVP_CreativeHiddenVehicles.isHidden(new ResourceLocation("rvp", "96l6")));
    }
}
