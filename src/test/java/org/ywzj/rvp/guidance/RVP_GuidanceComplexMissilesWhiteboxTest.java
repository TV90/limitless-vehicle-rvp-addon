package org.ywzj.rvp.guidance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSteeringData;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.guidance.RVP_GuidanceWhiteboxSupport.*;

/**
 * White-box tests for ten complex guidance example missiles under {@code docs/examples/guidance/}.
 */
class RVP_GuidanceComplexMissilesWhiteboxTest {

    abstract static class MissileFixture {
        final Path json;
        List<RVP_GuidanceStageData> stages;

        MissileFixture(String fileName) {
            this.json = Path.of("docs/examples/guidance/" + fileName);
        }

        @BeforeEach
        void load() throws Exception {
            stages = loadStages(json);
        }

        RVP_GuidanceStageData stage(String name) {
            int i = indexOf(stages, name);
            assertTrue(i >= 0, "missing stage: " + name);
            return stages.get(i);
        }

        RVP_GuidanceActivationData activation(String name) {
            return stage(name).getActivation();
        }
    }

    @Nested
    class M01Amraam extends MissileFixture {
        M01Amraam() { super("m01_amraam.json"); }

        @Test
        void loadsFourStages() { assertEquals(4, stages.size()); }

        @Test
        void sarhBandAndIllumination() {
            var sarh = activation("midcourse_sarh");
            assertTrue(isActive(sarh, ctx(20, 1500, true, true, 50)));
            assertFalse(isActive(sarh, ctx(20, 150, true, true, 50)));
            assertFalse(isActive(sarh, ctx(20, 1500, true, false, 50)));
        }

        @Test
        void terminalArhAt200mBoundary() {
            var arh = activation("terminal_arh");
            assertTrue(isActive(arh, ctx(30, 200, true, true, 50)));
            assertFalse(isActive(arh, ctx(30, 201, true, true, 50)));
        }

        @Test
        void selectionDropsArhWhenSarhActiveAt200m() {
            var names = simulateStageNames(stages, ctx(30, 200, true, true, 50), emptySticky());
            assertTrue(names.contains("midcourse_sarh"));
            assertFalse(names.contains("terminal_arh"));
        }

        @Test
        void stickyArhWithoutIlluminationPast200m() {
            Set<Integer> sticky = emptySticky();
            simulateStageNames(stages, ctx(30, 180, true, true, 50), sticky);
            var names = simulateStageNames(stages, ctx(50, 800, true, false, 50), sticky);
            assertTrue(names.contains("terminal_arh"));
            assertFalse(names.contains("midcourse_sarh"));
        }
    }

    @Nested
    class M02Jassm extends MissileFixture {
        M02Jassm() { super("m02_jassm.json"); }

        @Test
        void loadsFiveStages() { assertEquals(5, stages.size()); }

        @Test
        void gpsBlendAfterBoost() {
            assertFalse(isActive(activation("gps_blend"), ctx(30, 2000, true, true, 50)));
            assertTrue(isActive(activation("gps_blend"), ctx(35, 2000, true, true, 50)));
        }

        @Test
        void highCruiseAltitudeWindow() {
            var high = activation("high_cruise");
            assertTrue(isActive(high, ctx(60, 2000, true, true, 120)));
            assertFalse(isActive(high, ctx(60, 2000, true, true, 40)));
            assertFalse(isActive(high, ctx(40, 2000, true, true, 120)));
        }

        @Test
        void divePrepLowAltitude() {
            assertTrue(isActive(activation("dive_prep"), ctx(90, 600, true, true, 30)));
            assertFalse(isActive(activation("dive_prep"), ctx(70, 600, true, true, 30)));
        }

        @Test
        void gpsBlendUsesBlendMode() {
            assertTrue(stage("gps_blend").getSources().stream()
                    .anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.BLEND));
        }

        @Test
        void terminalIrUnder80m() {
            assertTrue(isActive(activation("terminal_ir"), ctx(100, 60, true, true, 20)));
            assertFalse(isActive(activation("terminal_ir"), ctx(100, 90, true, true, 20)));
        }
    }

    @Nested
    class M03Harm extends MissileFixture {
        M03Harm() { super("m03_harm.json"); }

        @Test
        void loadsFourStages() { assertEquals(4, stages.size()); }

        @Test
        void armPrimaryTypes() {
            assertEquals(RVP_EnumGuidanceType.ARM, stage("arm_search").getPrimaryGuidanceType());
            assertEquals(RVP_EnumGuidanceType.GPS, stage("gps_divert").getPrimaryGuidanceType());
        }

        @Test
        void armAndGpsIncompatible() {
            assertFalse(RVP_GuidanceCompositeCompatibility.canComposite(
                    RVP_EnumGuidanceType.ARM, RVP_EnumGuidanceType.GPS));
        }

        @Test
        void armAttackLatchesOnce() {
            assertTrue(activation("arm_attack").isEnterOnce());
        }

        @Test
        void selectionPrefersArmOverGpsAtOverlap() {
            var names = simulateStageNames(stages, ctx(30, 1000, true, true, 50), emptySticky());
            assertTrue(names.contains("arm_search"));
            assertFalse(names.contains("gps_divert"));
        }
    }

    @Nested
    class M04Tow extends MissileFixture {
        M04Tow() { super("m04_tow.json"); }

        @Test
        void loadsThreeStages() { assertEquals(3, stages.size()); }

        @Test
        void saclosMidcourseRequiresTarget() {
            var mid = activation("saclos_midcourse");
            assertTrue(isActive(mid, ctx(10, 2000, true, true, 30)));
            assertFalse(isActive(mid, ctx(10, -1, false, false, 30)));
        }

        @Test
        void saclosTerminalUsesEntityDistance() {
            var term = activation("saclos_terminal");
            assertTrue(isActive(term, ctx(20, 2000, 700, true, true, 30)));
            assertFalse(isActive(term, ctx(20, 2000, 900, true, true, 30)));
        }

        @Test
        void saclosAndGpsIncompatible() {
            assertFalse(RVP_GuidanceCompositeCompatibility.canComposite(
                    RVP_EnumGuidanceType.SACLOS, RVP_EnumGuidanceType.GPS));
        }

        @Test
        void midcourseOnlyDuringWireGuidance() {
            var names = simulateStageNames(stages, ctx(10, 3000, true, true, 20), emptySticky());
            assertEquals(List.of("saclos_midcourse"), names);
        }
    }

    @Nested
    class M05Tomahawk extends MissileFixture {
        M05Tomahawk() { super("m05_tomahawk.json"); }

        @Test
        void loadsFiveStages() { assertEquals(5, stages.size()); }

        @Test
        void gpsTransonicStartsAt35() {
            assertFalse(isActive(activation("gps_transonic"), ctx(34, 5000, true, true, 80)));
            assertTrue(isActive(activation("gps_transonic"), ctx(35, 5000, true, true, 80)));
        }

        @Test
        void terrainFollowLowAltitude() {
            assertTrue(isActive(activation("terrain_follow"), ctx(70, 3000, true, true, 20)));
            assertFalse(isActive(activation("terrain_follow"), ctx(50, 3000, true, true, 20)));
        }

        @Test
        void gpsTerminalDistanceBand() {
            var band = activation("gps_terminal_band");
            assertTrue(isActive(band, ctx(110, 1000, true, true, 30)));
            assertFalse(isActive(band, ctx(110, 300, true, true, 30)));
            assertFalse(isActive(band, ctx(110, 2500, true, true, 30)));
        }

        @Test
        void lowAltitudeCruiseOverlapsGpsAndTerrain() {
            var names = simulateStageNames(stages, ctx(80, 3000, true, true, 25), emptySticky());
            assertTrue(names.contains("gps_transonic"));
            assertTrue(names.contains("terrain_follow"));
        }
    }

    @Nested
    class M06Pl15 extends MissileFixture {
        M06Pl15() { super("m06_pl15.json"); }

        @Test
        void loadsFiveStages() { assertEquals(5, stages.size()); }

        @Test
        void dualSeekerCompatibility() {
            assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(
                    RVP_EnumGuidanceType.ARH, RVP_EnumGuidanceType.IR));
        }

        @Test
        void loftCorrectionHighAltitude() {
            assertTrue(isActive(activation("loft_correction"), ctx(50, 1500, true, true, 200)));
            assertFalse(isActive(activation("loft_correction"), ctx(50, 1500, true, true, 80)));
        }

        @Test
        void terminalArhAndIrCoexistAt80m() {
            Set<Integer> sticky = emptySticky();
            simulateStageNames(stages, ctx(40, 80, true, true, 30), sticky);
            var names = simulateStageNames(stages, ctx(50, 80, true, true, 30), sticky);
            assertTrue(names.contains("dual_arh"));
            assertTrue(names.contains("dual_ir"));
        }

        @Test
        void arhSteeringOverride() {
            RVP_GuidanceData.Source arh = stage("dual_arh").getSources().get(0);
            var effective = RVP_GuidanceConfigMerger.forSource(stage("dual_arh"), arh, RVP_EnumGuidanceType.ARH);
            assertEquals(0.6, effective.steering().getTurningFactor(), 1.0E-6);
        }
    }

    @Nested
    class M07Loft extends MissileFixture {
        M07Loft() { super("m07_loft.json"); }

        @Test
        void loadsFourStages() { assertEquals(4, stages.size()); }

        @Test
        void climbRequiresAltitudeAndTickWindow() {
            var climb = activation("climb_iog");
            assertTrue(isActive(climb, ctx(40, 3000, true, true, 80)));
            assertFalse(isActive(climb, ctx(40, 3000, true, true, 30)));
            assertFalse(isActive(climb, ctx(80, 3000, true, true, 80)));
        }

        @Test
        void apogeeGpsHighBand() {
            var apogee = activation("apogee_gps");
            assertTrue(isActive(apogee, ctx(50, 4000, true, true, 300)));
            assertFalse(isActive(apogee, ctx(50, 4000, true, true, 150)));
        }

        @Test
        void terminalArhWithin280m() {
            assertTrue(isActive(activation("terminal_arh"), ctx(90, 250, true, true, 40)));
            assertFalse(isActive(activation("terminal_arh"), ctx(90, 300, true, true, 40)));
        }

        @Test
        void apogeeOverlapsClimbAtHighAltitude() {
            var names = simulateStageNames(stages, ctx(50, 4000, true, true, 280), emptySticky());
            assertTrue(names.contains("climb_iog"));
            assertTrue(names.contains("apogee_gps"));
        }
    }

    @Nested
    class M08Yj83 extends MissileFixture {
        M08Yj83() { super("m08_yj83.json"); }

        @Test
        void loadsSixStages() { assertEquals(6, stages.size()); }

        @Test
        void sarhCruiseNeedsIllumination() {
            assertFalse(isActive(activation("sarh_cruise"), ctx(50, 2000, true, false, 80)));
            assertTrue(isActive(activation("sarh_cruise"), ctx(50, 2000, true, true, 80)));
        }

        @Test
        void seaSkimVeryLow() {
            assertTrue(isActive(activation("sea_skim"), ctx(60, 1500, true, true, 10)));
            assertFalse(isActive(activation("sea_skim"), ctx(60, 1500, true, true, 25)));
        }

        @Test
        void endgameIrWithin50m() {
            assertTrue(isActive(activation("ir_endgame"), ctx(70, 40, true, true, 8)));
            assertFalse(isActive(activation("ir_endgame"), ctx(70, 60, true, true, 8)));
        }

        @Test
        void terminalPenetrationDropsSarhAt160m() {
            var names = simulateStageNames(stages, ctx(70, 160, true, true, 10), emptySticky());
            assertTrue(names.contains("arh_penetration"));
            assertFalse(names.contains("sarh_cruise"));
        }

        @Test
        void arhAndIrCompositeAt40m() {
            Set<Integer> sticky = emptySticky();
            simulateStageNames(stages, ctx(60, 40, true, true, 8), sticky);
            var names = simulateStageNames(stages, ctx(70, 40, true, true, 8), sticky);
            assertTrue(names.contains("arh_penetration"));
            assertTrue(names.contains("ir_endgame"));
        }
    }

    @Nested
    class M09SpikeTv extends MissileFixture {
        M09SpikeTv() { super("m09_spike_tv.json"); }

        @Test
        void loadsFourStages() { assertEquals(4, stages.size()); }

        @Test
        void mclosCompatibleWithIogButStagesUseNonOverlappingTicks() {
            assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(
                    RVP_EnumGuidanceType.MCLOS, RVP_EnumGuidanceType.IOG));
            assertFalse(isActive(activation("iog_failover"), ctx(40, 500, true, true, 20)));
            assertTrue(isActive(activation("hitl_midcourse"), ctx(40, 500, true, true, 20)));
        }

        @Test
        void hitlMidcourseAfterBoost() {
            assertFalse(isActive(activation("hitl_midcourse"), ctx(12, 800, true, true, 20)));
            assertTrue(isActive(activation("hitl_midcourse"), ctx(20, 800, true, true, 20)));
        }

        @Test
        void failoverOnlyLate() {
            assertFalse(isActive(activation("iog_failover"), ctx(50, 200, true, true, 20)));
            assertTrue(isActive(activation("iog_failover"), ctx(130, 200, true, true, 20)));
        }

        @Test
        void hitlDominatesMidcourseNoIogOverlap() {
            var names = simulateStageNames(stages, ctx(40, 500, true, true, 25), emptySticky());
            assertTrue(names.contains("hitl_midcourse"));
            assertFalse(names.contains("iog_failover"));
            assertFalse(names.contains("boost_iog"));
        }

        @Test
        void stickyHitlTerminalBeyondActivationRange() {
            Set<Integer> sticky = emptySticky();
            simulateStageNames(stages, ctx(30, 400, true, true, 20), sticky);
            var names = simulateStageNames(stages, ctx(50, 900, true, true, 20), sticky);
            assertTrue(names.contains("hitl_terminal"));
        }
    }

    @Nested
    class M10Stress extends MissileFixture {
        M10Stress() { super("m10_stress.json"); }

        @Test
        void loadsFiveStages() { assertEquals(5, stages.size()); }

        @Test
        void gpsAndSarhCompatible() {
            assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(
                    RVP_EnumGuidanceType.GPS, RVP_EnumGuidanceType.SARH));
        }

        @Test
        void sarhBandStartsAt350m() {
            assertFalse(isActive(activation("sarh_band"), ctx(25, 300, true, true, 15)));
            assertTrue(isActive(activation("sarh_band"), ctx(25, 1000, true, true, 15)));
        }

        @Test
        void lowAltitudeTripleOverlap() {
            var names = simulateStageNames(stages, ctx(35, 1000, true, true, 12), emptySticky());
            assertTrue(names.contains("gps_sarh_blend"));
            assertTrue(names.contains("sarh_band"));
            assertTrue(names.contains("sea_skim"));
            assertEquals(3, names.size());
        }

        @Test
        void sarhDropsArhAt350mBoundary() {
            var names = simulateStageNames(stages, ctx(35, 350, true, true, 12), emptySticky());
            assertTrue(names.contains("sarh_band"));
            assertFalse(names.contains("arh_leak"));
        }

        @Test
        void arhLeakBelow160mWithoutSarh() {
            Set<Integer> sticky = emptySticky();
            simulateStageNames(stages, ctx(35, 140, true, true, 12), sticky);
            var names = simulateStageNames(stages, ctx(45, 140, true, true, 12), sticky);
            assertTrue(names.contains("arh_leak"));
            assertFalse(names.contains("sarh_band"));
        }

        @Test
        void mergeSteeringStageAndSource() {
            RVP_GuidanceStageData arh = stage("arh_leak");
            RVP_GuidanceSteeringData merged = RVP_GuidanceConfigMerger.mergeSteering(
                    arh.getSteeringData(), null);
            assertNotNull(merged);
            assertEquals(0.54, merged.getTurningFactor(), 1.0E-6);
        }
    }
}
