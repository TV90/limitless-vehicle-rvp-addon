package org.ywzj.rvp.guidance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.guidance.RVP_GuidanceWhiteboxSupport.*;

/**
 * White-box tests for {@code docs/examples/guidance/x99_composite_demo.json} against guidance runtime.
 */
class RVP_GuidanceX99WhiteboxTest {

    private static final Path JSON = Path.of("docs/examples/guidance/x99_composite_demo.json");

    private static List<RVP_GuidanceStageData> stages;

    @BeforeAll
    static void loadGuidance() throws Exception {
        stages = loadStages(JSON);
        assertEquals(6, stages.size(), "X-99 must define 6 stages");
    }

    @Test
    void jsonStageNamesAndPrimaryTypes() {
        assertEquals("boost_iog", stages.get(0).getName());
        assertEquals(RVP_EnumGuidanceType.IOG, stages.get(0).getPrimaryGuidanceType());

        assertEquals("midcourse_gps_blend", stages.get(1).getName());
        assertEquals(RVP_EnumGuidanceType.GPS, stages.get(1).getPrimaryGuidanceType());

        assertEquals("midcourse_sarh", stages.get(2).getName());
        assertEquals(RVP_EnumGuidanceType.SARH, stages.get(2).getPrimaryGuidanceType());

        assertEquals("terminal_arh", stages.get(3).getName());
        assertEquals(RVP_EnumGuidanceType.ARH, stages.get(3).getPrimaryGuidanceType());

        assertEquals("terminal_ir", stages.get(4).getName());
        assertEquals(RVP_EnumGuidanceType.IR, stages.get(4).getPrimaryGuidanceType());

        assertEquals("sea_skim_blend", stages.get(5).getName());
        assertEquals(RVP_EnumGuidanceType.IOG, stages.get(5).getPrimaryGuidanceType());
    }

    @Test
    void boostStageOnlyActiveDuringRigidBoostWindow() {
        RVP_GuidanceActivationData boost = stages.get(0).getActivation();
        assertTrue(isActive(boost, ctx(10, 1500, true, true, 50)));
        assertFalse(isActive(boost, ctx(20, 1500, true, true, 50)));
    }

    @Test
    void midcourseGpsRequiresTick20AndTarget() {
        RVP_GuidanceActivationData gps = stages.get(1).getActivation();
        assertFalse(isActive(gps, ctx(19, 1000, true, true, 50)));
        assertTrue(isActive(gps, ctx(25, 1000, true, true, 50)));
        assertFalse(isActive(gps, ctx(25, -1, false, false, 50)));
        assertFalse(isActive(gps, ctx(25, 3000, true, true, 50)));
    }

    @Test
    void midcourseSarhRequiresIlluminationAndDistanceBand() {
        RVP_GuidanceActivationData sarh = stages.get(2).getActivation();
        assertTrue(isActive(sarh, ctx(25, 1000, true, true, 50)));
        assertFalse(isActive(sarh, ctx(25, 1000, true, false, 50)));
        assertFalse(isActive(sarh, ctx(25, 300, true, true, 50)));
        assertFalse(isActive(sarh, ctx(25, 2600, true, true, 50)));
    }

    @Test
    void terminalArhRequiresEntityWithin400m() {
        RVP_GuidanceActivationData arh = stages.get(3).getActivation();
        assertTrue(isActive(arh, ctx(5, 350, true, true, 50)));
        assertFalse(isActive(arh, ctx(5, 450, true, true, 50)));
        assertFalse(isActive(arh, ctx(5, 350, false, false, 50)));
    }

    @Test
    void terminalIrRequiresEntityWithin120m() {
        RVP_GuidanceActivationData ir = stages.get(4).getActivation();
        assertTrue(isActive(ir, ctx(5, 100, true, true, 50)));
        assertFalse(isActive(ir, ctx(5, 200, true, true, 50)));
    }

    @Test
    void seaSkimActiveOnlyAtLowAltitude() {
        RVP_GuidanceActivationData sea = stages.get(5).getActivation();
        assertTrue(isActive(sea, ctx(25, 1000, true, true, 15)));
        assertFalse(isActive(sea, ctx(25, 1000, true, true, 40)));
    }

    @Test
    void compositeCompatibilityMatrixForX99() {
        assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(RVP_EnumGuidanceType.GPS, RVP_EnumGuidanceType.SARH));
        assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(RVP_EnumGuidanceType.ARH, RVP_EnumGuidanceType.IR));
        assertTrue(RVP_GuidanceCompositeCompatibility.canComposite(RVP_EnumGuidanceType.GPS, RVP_EnumGuidanceType.IOG));
        assertFalse(RVP_GuidanceCompositeCompatibility.canComposite(RVP_EnumGuidanceType.SARH, RVP_EnumGuidanceType.ARH));
        assertFalse(RVP_GuidanceCompositeCompatibility.canComposite(RVP_EnumGuidanceType.SARH, RVP_EnumGuidanceType.IR));
    }

    @Test
    void resolveCompatibleDropsArhWhenSarhAlsoActiveAt350m() {
        List<RVP_GuidancePhaseSelector.StageSelection> raw = List.of(
                stageAt(1), stageAt(2), stageAt(3)
        );
        List<RVP_GuidancePhaseSelector.StageSelection> resolved = RVP_GuidanceCompositeCompatibility.resolveCompatible(
                raw,
                ss -> ss.stage().getActivation().specificityScore(),
                ss -> ss.stage().getPrimaryGuidanceType()
        );
        Set<RVP_EnumGuidanceType> types = resolved.stream()
                .map(ss -> ss.stage().getPrimaryGuidanceType())
                .collect(Collectors.toSet());
        assertTrue(types.contains(RVP_EnumGuidanceType.SARH));
        assertFalse(types.contains(RVP_EnumGuidanceType.ARH));
        assertTrue(types.contains(RVP_EnumGuidanceType.GPS));
    }

    @Test
    void resolveCompatibleKeepsArhAndIrAt100m() {
        List<RVP_GuidancePhaseSelector.StageSelection> raw = List.of(
                stageAt(1), stageAt(3), stageAt(4)
        );
        List<RVP_GuidancePhaseSelector.StageSelection> resolved = RVP_GuidanceCompositeCompatibility.resolveCompatible(
                raw,
                ss -> ss.stage().getActivation().specificityScore(),
                ss -> ss.stage().getPrimaryGuidanceType()
        );
        Set<RVP_EnumGuidanceType> types = resolved.stream()
                .map(ss -> ss.stage().getPrimaryGuidanceType())
                .collect(Collectors.toSet());
        assertTrue(types.contains(RVP_EnumGuidanceType.ARH));
        assertTrue(types.contains(RVP_EnumGuidanceType.IR));
    }

    @Test
    void sourceSteeringOverridesStageSteering() {
        RVP_GuidanceStageData terminalArh = stages.get(3);
        RVP_GuidanceData.Source arhSource = terminalArh.getSources().get(0);
        RVP_GuidanceEffectiveConfig effective = RVP_GuidanceConfigMerger.forSource(
                terminalArh, arhSource, RVP_EnumGuidanceType.ARH);
        assertEquals(0.55, effective.steering().getTurningFactor(), 1.0E-6);
        assertEquals(70f, effective.steering().getMaxDegreeOfMissile(), 1.0E-6);
    }

    @Test
    void gpsStageUsesBlendCompositeMode() {
        boolean hasBlend = stages.get(1).getSources().stream()
                .anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.BLEND);
        assertTrue(hasBlend);
    }

    @Test
    void enterOnceStagesHaveFlag() {
        assertTrue(stages.get(3).getActivation().isEnterOnce());
        assertTrue(stages.get(4).getActivation().isEnterOnce());
    }

    @Test
    void simulatedSelectionBoostOnlyAtTick10() {
        assertEquals(List.of("boost_iog"), simulateStageNames(stages, ctx(10, 1500, true, true, 50), emptySticky()));
    }

    @Test
    void simulatedSelectionMidcourseAt1000mExcludesTerminal() {
        List<String> names = simulateStageNames(stages, ctx(25, 1000, true, true, 50), emptySticky());
        assertTrue(names.contains("midcourse_gps_blend"));
        assertTrue(names.contains("midcourse_sarh"));
        assertFalse(names.contains("terminal_arh"));
        assertFalse(names.contains("terminal_ir"));
    }

    @Test
    void simulatedSelectionAt400mDropsIncompatibleArh() {
        List<String> names = simulateStageNames(stages, ctx(25, 400, true, true, 50), emptySticky());
        assertTrue(names.contains("midcourse_gps_blend"));
        assertTrue(names.contains("midcourse_sarh"));
        assertFalse(names.contains("terminal_arh"));
    }

    @Test
    void simulatedEnterOnceStickyKeepsTerminalArhWhenSarhCannotReactivate() {
        Set<Integer> sticky = emptySticky();
        simulateStageNames(stages, ctx(25, 350, true, true, 50), sticky);
        assertTrue(sticky.contains(3));

        List<String> names = simulateStageNames(stages, ctx(40, 600, true, false, 50), sticky);
        assertTrue(names.contains("terminal_arh"));
        assertFalse(names.contains("midcourse_sarh"));
    }

    @Test
    void simulatedEnterOnceStickyDoesNotOverrideSarhArhIncompatibility() {
        Set<Integer> sticky = emptySticky();
        simulateStageNames(stages, ctx(25, 350, true, true, 50), sticky);

        List<String> names = simulateStageNames(stages, ctx(40, 600, true, true, 50), sticky);
        assertTrue(names.contains("midcourse_sarh"));
        assertFalse(names.contains("terminal_arh"));
    }

    @Test
    void simulatedTerminalCompositeArhAndIrUnder120m() {
        Set<Integer> sticky = emptySticky();
        simulateStageNames(stages, ctx(30, 80, true, true, 15), sticky);

        List<String> names = simulateStageNames(stages, ctx(40, 80, true, true, 15), sticky);
        assertTrue(names.contains("terminal_arh"));
        assertTrue(names.contains("terminal_ir"));
        assertTrue(names.contains("sea_skim_blend"));
    }

    @Test
    void crossStageOverlapUsesWeightedBlendNotPerStageBlendMode() {
        assertTrue(stages.get(1).getSources().stream()
                .anyMatch(s -> s.getCompositeMode() == RVP_EnumCompositeMode.BLEND));
        List<String> multi = simulateStageNames(stages, ctx(25, 1000, true, true, 15), emptySticky());
        assertTrue(multi.size() > 1);
    }

    private static RVP_GuidancePhaseSelector.StageSelection stageAt(int index) {
        return new RVP_GuidancePhaseSelector.StageSelection(stages.get(index), index);
    }
}
