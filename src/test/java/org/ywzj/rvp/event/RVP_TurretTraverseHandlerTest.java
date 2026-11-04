package org.ywzj.rvp.event;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.BoneTurretTraverseConfig;

import static org.junit.jupiter.api.Assertions.*;

class RVP_TurretTraverseHandlerTest {
    @Test void configuredBindingAndThresholdsDriveYawLimitsAndRecovery() {
        var config = BoneTurretTraverseConfig.parse(JsonParser.parseString("""
                {"part_id":"turret_secondary","threshold_light":80,"threshold_heavy":200}
                """));
        assertNotNull(config);
        assertEquals("turret_secondary", config.partId());
        float normal = 2.5f;
        assertEquals(normal, RVP_TurretTraverseHandler.speedForStage(normal, config.stage(79.9f)));
        assertEquals(0.75f, RVP_TurretTraverseHandler.speedForStage(normal, config.stage(80f)));
        assertEquals(0.75f, RVP_TurretTraverseHandler.speedForStage(normal, config.stage(199.9f)));
        assertEquals(0.01f, RVP_TurretTraverseHandler.speedForStage(normal, config.stage(200f)));
        assertEquals(normal, RVP_TurretTraverseHandler.speedForStage(normal, config.stage(0f)));
        assertEquals(0f, RVP_TurretTraverseHandler.speedForStage(0f, 2));
    }

    @Test void missingBindingAndInvalidThresholdsDisableTheModule() {
        for (String json : new String[]{"{}", """
                {"part_id":"  "}
                """, """
                {"part_id":"turret","threshold_light":300,"threshold_heavy":300}
                """, """
                {"part_id":"turret","threshold_light":[]}
                """}) {
            assertNull(BoneTurretTraverseConfig.parse(JsonParser.parseString(json)));
        }
    }

    @Test void omittedThresholdsUseTwoHundredAndSixHundred() {
        var config = BoneTurretTraverseConfig.parse(JsonParser.parseString("{\"part_id\":\"turret\"}"));
        assertNotNull(config);
        assertEquals(0, config.stage(199.99f));
        assertEquals(1, config.stage(200f));
        assertEquals(1, config.stage(599.99f));
        assertEquals(2, config.stage(600f));
    }
}
