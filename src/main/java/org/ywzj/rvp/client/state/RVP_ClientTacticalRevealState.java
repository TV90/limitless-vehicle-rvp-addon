package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CTacticalRevealSnapshot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RVP_ClientTacticalRevealState {

    @Nullable
    private static ResourceLocation dimension;
    private static final Set<Integer> FIRE_REVEAL_IDS = new LinkedHashSet<>();
    /** entityId → iffType */
    private static final Map<Integer, Integer> MARKED_ENTITY_IFF = new LinkedHashMap<>();

    private RVP_ClientTacticalRevealState() {}

    public static void applySnapshot(S2CTacticalRevealSnapshot msg) {
        dimension = msg.dimension;
        FIRE_REVEAL_IDS.clear();
        MARKED_ENTITY_IFF.clear();
        FIRE_REVEAL_IDS.addAll(msg.fireRevealIds);
        for (S2CTacticalRevealSnapshot.MarkedEntry entry : msg.markedEntries) {
            MARKED_ENTITY_IFF.put(entry.entityId(), entry.iffType());
        }
    }

    public static boolean isVisible(@Nullable ResourceLocation currentDimension, int entityId) {
        if (!matchesDimension(currentDimension)) {
            return false;
        }
        return FIRE_REVEAL_IDS.contains(entityId) || MARKED_ENTITY_IFF.containsKey(entityId);
    }

    /** 获取标记实体的 IFF 类型，未标记返回 -1 */
    public static int getMarkedIffType(@Nullable ResourceLocation currentDimension, int entityId) {
        if (!matchesDimension(currentDimension)) {
            return -1;
        }
        return MARKED_ENTITY_IFF.getOrDefault(entityId, -1);
    }

    public static Set<Integer> getVisibleIds(@Nullable ResourceLocation currentDimension) {
        if (!matchesDimension(currentDimension)) {
            return Set.of();
        }
        Set<Integer> ids = new LinkedHashSet<>(FIRE_REVEAL_IDS);
        ids.addAll(MARKED_ENTITY_IFF.keySet());
        return ids;
    }

    public static Map<Integer, Integer> getMarkedEntityIffMap(@Nullable ResourceLocation currentDimension) {
        if (!matchesDimension(currentDimension)) {
            return Map.of();
        }
        return new LinkedHashMap<>(MARKED_ENTITY_IFF);
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
        MARKED_ENTITY_IFF.clear();
    }

    private static boolean matchesDimension(@Nullable ResourceLocation currentDimension) {
        return currentDimension != null && dimension != null && dimension.equals(currentDimension);
    }
}
