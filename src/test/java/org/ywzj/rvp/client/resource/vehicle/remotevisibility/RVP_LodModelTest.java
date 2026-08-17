package org.ywzj.rvp.client.resource.vehicle.remotevisibility;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.resource.vehicle.RVP_LodModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RVP_LodModelTest {
    @Test
    void parseFiltersMissingAndInvalidModelIds() {
        RVP_LodModel.Pojo missing = new RVP_LodModel.Pojo();
        RVP_LodModel.Pojo invalid = new RVP_LodModel.Pojo();
        invalid.model = "bad id with spaces";
        RVP_LodModel.Pojo valid = new RVP_LodModel.Pojo();
        valid.model = "rvp:vehicle/lod/test";

        List<RVP_LodModel> rules = RVP_LodModel.parse(List.of(missing, invalid, valid));

        assertEquals(1, rules.size());
        assertEquals("rvp:vehicle/lod/test", rules.get(0).model.toString());
    }

    @Test
    void parseNormalizesNegativeDistanceAndAglWithoutInventingOptionalResources() {
        RVP_LodModel.Pojo pojo = new RVP_LodModel.Pojo();
        pojo.model = "rvp:vehicle/lod/test";
        pojo.modelAir = "bad model id";
        pojo.texture = "bad texture id";
        pojo.distance = -10.0D;
        pojo.airDistance = -20.0D;
        pojo.airHeight = -30.0D;

        RVP_LodModel rule = RVP_LodModel.parse(List.of(pojo)).get(0);

        assertEquals(0.0D, rule.distance);
        assertEquals(0.0D, rule.airDistance);
        assertEquals(0.0D, rule.airHeight);
        assertNull(rule.modelAir);
        assertNull(rule.texture);
    }
}
