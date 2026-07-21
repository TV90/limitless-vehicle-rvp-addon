package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CTacticalRevealSnapshot;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RVP_ClientTacticalRevealState {

    @Nullable
    private static ResourceLocation dimension;
    private static final Set<Integer> FIRE_REVEAL_IDS = new LinkedHashSet<>();
    private static final Set<Integer> MARKED_REVEAL_IDS = new LinkedHashSet<>();

    private RVP_ClientTacticalRevealState() {}

    public static void applySnapshot(S2CTacticalRevealSnapshot msg) {
        dimension = msg.dimension;
        FIRE_REVEAL_IDS.clear();
        MARKED_REVEAL_IDS.clear();
        FIRE_REVEAL_IDS.addAll(msg.fireRevealIds);
        MARKED_REVEAL_IDS.addAll(msg.markedRevealIds);
    }

    public static boolean isVisible(@Nullable ResourceLocation currentDimension, int entityId) {
        if (!matchesDimension(currentDimension)) {
            return false;
        }
        return FIRE_REVEAL_IDS.contains(entityId) || MARKED_REVEAL_IDS.contains(entityId);
    }

    public static Set<Integer> getVisibleIds(@Nullable ResourceLocation currentDimension) {
        if (!matchesDimension(currentDimension)) {
            return Set.of();
        }
        Set<Integer> ids = new LinkedHashSet<>(FIRE_REVEAL_IDS);
        ids.addAll(MARKED_REVEAL_IDS);
        return ids;
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        ResourceLocation levelDimension = mc.level.dimension().location();
        if (dimension != null && !dimension.equals(levelDimension)) {
            clear();
        }
    }

    public static void clear() {
        dimension = null;
        FIRE_REVEAL_IDS.clear();
        MARKED_REVEAL_IDS.clear();
    }

    private static boolean matchesDimension(@Nullable ResourceLocation currentDimension) {
        return currentDimension != null && dimension != null && dimension.equals(currentDimension);
    }
}
