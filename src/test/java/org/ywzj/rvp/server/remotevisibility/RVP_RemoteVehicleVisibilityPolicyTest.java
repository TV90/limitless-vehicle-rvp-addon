package org.ywzj.rvp.server.remotevisibility;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.NormalizationResult;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.VehicleCategory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_RemoteVehicleVisibilityPolicyTest {
    @Test
    void defaultMatrixMatchesConfiguredAuthority() {
        Set<VehicleCategory> all = Set.of(
                VehicleCategory.HELICOPTER,
                VehicleCategory.AIRCRAFT,
                VehicleCategory.GROUND_VEHICLES);

        assertEquals(all, RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.HELICOPTER));
        assertEquals(all, RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.AIRCRAFT));
        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT),
                RVP_CommonConfig.getRemoteVehicleVisibleTargetTypes(VehicleCategory.GROUND_VEHICLES));
    }

    @Test
    void duplicateTokensAreDeduplicated() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(
                List.of("helicopter", "helicopter", "aircraft"));

        assertEquals(Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT), result.accepted());
        assertEquals(Set.of(), result.unknown());
    }

    @Test
    void unknownUppercaseAndWhitespaceTokensAreRejected() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(
                List.of("ground_vehicles", "HELICOPTER", " aircraft", "unknown"));

        assertEquals(Set.of(VehicleCategory.GROUND_VEHICLES), result.accepted());
        assertEquals(Set.of("HELICOPTER", " aircraft", "unknown"), result.unknown());
    }

    @Test
    void emptyListIsLegalAndResultIsImmutable() {
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(List.of());

        assertEquals(Set.of(), result.accepted());
        assertEquals(Set.of(), result.unknown());
        assertThrows(UnsupportedOperationException.class,
                () -> result.accepted().add(VehicleCategory.HELICOPTER));
    }
}
