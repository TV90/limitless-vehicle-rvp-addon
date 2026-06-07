package org.ywzj.rvp.guidance;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.ywzj.rvp.guidance.RVP_GuidanceWhiteboxSupport.*;

class RVP_GuidancePhasePolicyWhiteboxTest {

    @Test
    void firstPhasePolicyKeepsLowestIndexAmongActive() {
        RVP_GuidanceData guidance = new RVP_GuidanceData();
        guidance.getStages().size(); // ensure default list
        RVP_GuidanceStageData early = stage("early", 0, 100);
        RVP_GuidanceStageData late = stage("late", 0, 200);
        List<RVP_GuidanceStageData> stages = List.of(early, late);
        guidance = guidanceWithPolicy(stages, RVP_EnumPhaseResolvePolicy.FIRST_PHASE);

        List<String> names = simulateStageNames(
                guidance,
                stages,
                ctx(50, 1000, true, true, 50),
                emptySticky()
        );
        assertEquals(List.of("early"), names);
    }

    private static RVP_GuidanceData guidanceWithPolicy(
            List<RVP_GuidanceStageData> stages,
            RVP_EnumPhaseResolvePolicy policy
    ) {
        RVP_GuidanceData guidance = new RVP_GuidanceData();
        try {
            var field = RVP_GuidanceData.class.getDeclaredField("stages");
            field.setAccessible(true);
            field.set(guidance, stages);
            var policyField = RVP_GuidanceData.class.getDeclaredField("phaseResolvePolicy");
            policyField.setAccessible(true);
            policyField.set(guidance, policy);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return guidance;
    }

    private static RVP_GuidanceStageData stage(String name, int startTick, int priority) {
        RVP_GuidanceStageData stage = new RVP_GuidanceStageData();
        try {
            var nameField = RVP_GuidanceStageData.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(stage, name);
            RVP_GuidanceActivationData activation = new RVP_GuidanceActivationData();
            var startField = RVP_GuidanceActivationData.class.getDeclaredField("startTick");
            startField.setAccessible(true);
            startField.set(activation, startTick);
            var activationField = RVP_GuidanceStageData.class.getDeclaredField("activation");
            activationField.setAccessible(true);
            activationField.set(stage, activation);
            RVP_GuidanceData.Source source = new RVP_GuidanceData.Source();
            var typeField = RVP_GuidanceData.Source.class.getDeclaredField("type");
            typeField.setAccessible(true);
            typeField.set(source, RVP_EnumGuidanceType.IOG);
            var priorityField = RVP_GuidanceData.Source.class.getDeclaredField("priority");
            priorityField.setAccessible(true);
            priorityField.set(source, priority);
            var sourcesField = RVP_GuidanceStageData.class.getDeclaredField("sources");
            sourcesField.setAccessible(true);
            sourcesField.set(stage, List.of(source));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return stage;
    }
}
