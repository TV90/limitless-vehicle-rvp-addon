package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CRemoteAmmoSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RVP_ClientRemoteAmmoState {

    private static final Map<Integer, S2CRemoteAmmoSnapshot.Entry> ENTRIES = new LinkedHashMap<>();
    @Nullable
    private static ResourceLocation dimension;

    private RVP_ClientRemoteAmmoState() {}

    public static void applySnapshot(S2CRemoteAmmoSnapshot msg) {
        dimension = msg.dimension;
        ENTRIES.clear();
        for (S2CRemoteAmmoSnapshot.Entry entry : msg.entries) {
            ENTRIES.put(entry.entityId(), entry);
        }
    }

    public static Collection<S2CRemoteAmmoSnapshot.Entry> getEntries(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return java.util.List.of();
        }
        return ENTRIES.values();
    }

    @Nullable
    public static S2CRemoteAmmoSnapshot.Entry getEntry(@Nullable ResourceLocation currentDimension, int entityId) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return null;
        }
        return ENTRIES.get(entityId);
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
        ENTRIES.clear();
    }
}
