package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public final class RVP_ClientExtendedAirVisualState {

    private static ResourceLocation dimension;
    private static Set<Integer> entityIds = Set.of();
    private static Set<Integer> motorBurningEntityIds = Set.of();

    private RVP_ClientExtendedAirVisualState() {}

    public static void replace(ResourceLocation newDimension, Set<Integer> newEntityIds,
                               Set<Integer> newMotorBurningEntityIds) {
        dimension = newDimension;
        entityIds = newEntityIds == null ? Set.of() : Set.copyOf(new HashSet<>(newEntityIds));
        motorBurningEntityIds = newMotorBurningEntityIds == null
                ? Set.of()
                : Set.copyOf(new HashSet<>(newMotorBurningEntityIds));
    }

    public static boolean contains(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null && currentDimension.equals(dimension) && entityIds.contains(entityId);
    }

    public static boolean isMotorBurning(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null
                && currentDimension.equals(dimension)
                && motorBurningEntityIds.contains(entityId);
    }

    public static void clear() {
        dimension = null;
        entityIds = Set.of();
        motorBurningEntityIds = Set.of();
    }
}
