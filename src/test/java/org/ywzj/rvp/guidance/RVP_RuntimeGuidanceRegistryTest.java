package org.ywzj.rvp.guidance;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RuntimeGuidanceRegistryTest {

    private final Gson gson = new Gson();

    @Test
    void directMigrationSourcesAreRegistered() {
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.NONE));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.GPS));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.SARH));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.ARH));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.ARM));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.IR));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.AIR));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.LH));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.SALH));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.SACLOS));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.HITL_TV));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.HITL_CLOS_TV));
    }

    @Test
    void declaredButUnimplementedGuidanceTypesUseExplicitPlaceholders() {
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.MCLOS));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.TV));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.ATV));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.LOSBR));
        assertNotNull(RVP_RuntimeGuidanceSourceRegistry.get(RVP_EnumGuidanceType.LBR));
    }

    @Test
    void guidanceTypeQueriesSeeMainAndTerminalGuidance() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"GPS",
                  "terminal_guidance":{"guidance_type":"ARH"}
                }
                """);

        assertTrue(guidance.usesGuidanceType(RVP_EnumGuidanceType.GPS));
        assertTrue(guidance.usesGuidanceType(RVP_EnumGuidanceType.ARH));
        assertFalse(guidance.usesGuidanceType(RVP_EnumGuidanceType.SARH));
    }

    @Test
    void runtimeSourceTypeMatchesRegistryKey() {
        for (RVP_EnumGuidanceType type : new RVP_EnumGuidanceType[]{
                RVP_EnumGuidanceType.NONE,
                RVP_EnumGuidanceType.GPS,
                RVP_EnumGuidanceType.SARH,
                RVP_EnumGuidanceType.ARH,
                RVP_EnumGuidanceType.ARM,
                RVP_EnumGuidanceType.IR,
                RVP_EnumGuidanceType.AIR,
                RVP_EnumGuidanceType.LH,
                RVP_EnumGuidanceType.SALH,
                RVP_EnumGuidanceType.SACLOS,
                RVP_EnumGuidanceType.HITL_TV,
                RVP_EnumGuidanceType.HITL_CLOS_TV,
                RVP_EnumGuidanceType.MCLOS,
                RVP_EnumGuidanceType.TV,
                RVP_EnumGuidanceType.ATV,
                RVP_EnumGuidanceType.LOSBR,
                RVP_EnumGuidanceType.LBR
        }) {
            assertEquals(type, RVP_RuntimeGuidanceSourceRegistry.get(type).type());
        }
    }

    private RVP_GuidanceData parse(String guidanceJson) {
        String json = "{\"guidance_data\":" + guidanceJson + "}";
        return gson.fromJson(json, GuidanceHolder.class).guidanceData;
    }

    private static final class GuidanceHolder {
        @SerializedName("guidance_data")
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        private RVP_GuidanceData guidanceData;
    }
}
