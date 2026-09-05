package org.ywzj.rvp.firesupport;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RVP_FireSupportParameterValidationTest {
    @Test
    void acceptsExactFiniteStepAlignedParametersAsImmutableMap() {
        var preset = RVP_FireSupportTestProfiles.parse().patterns().get("line");
        Map<String, Double> result = RVP_FireSupportParameterValidator.validate(
                preset, Map.of("length_m", 125.0, "width_m", 24.0), 16);
        assertEquals(125.0, result.get("length_m"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("x", 1.0));
    }

    @Test
    void rejectsMissingExtraOutOfRangeUnalignedAndNonFiniteValues() {
        var preset = RVP_FireSupportTestProfiles.parse().patterns().get("line");
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportParameterValidator.validate(preset, Map.of("length_m", 120.0), 16));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportParameterValidator.validate(preset,
                        Map.of("length_m", 120.0, "width_m", 24.0, "extra", 1.0), 16));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportParameterValidator.validate(preset, Map.of("length_m", 500.0, "width_m", 24.0), 16));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportParameterValidator.validate(preset, Map.of("length_m", 122.0, "width_m", 24.0), 16));
        Map<String, Double> nan = new LinkedHashMap<>();
        nan.put("length_m", Double.NaN);
        nan.put("width_m", 24.0);
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportParameterValidator.validate(preset, nan, 16));
    }
}
