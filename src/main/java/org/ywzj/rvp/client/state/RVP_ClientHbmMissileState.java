package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CHbmMissileSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RVP_ClientHbmMissileState {

    private static final Map<Integer, S2CHbmMissileSnapshot.Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<Integer, RVP_RemoteHbmMissileEntity> PROXIES = new LinkedHashMap<>();
    @Nullable
    private static ResourceLocation dimension;

    private RVP_ClientHbmMissileState() {}

    public static void applySnapshot(S2CHbmMissileSnapshot msg) {
        dimension = msg.dimension;
        ENTRIES.clear();
        for (S2CHbmMissileSnapshot.Entry entry : msg.entries) {
            ENTRIES.put(entry.entityId(), entry);
        }
        rebuildProxyCache();
    }

    public static Collection<Entity> getProxyEntities(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return List.of();
        }
        rebuildProxyCache();
        return List.copyOf(PROXIES.values());
    }

    @Nullable
    public static S2CHbmMissileSnapshot.Entry getEntry(@Nullable ResourceLocation currentDimension, int entityId) {
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
            return;
        }
        rebuildProxyCache();
    }

    public static void clear() {
        dimension = null;
        ENTRIES.clear();
        PROXIES.clear();
    }

    private static void rebuildProxyCache() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || dimension == null || !dimension.equals(mc.level.dimension().location())) {
            PROXIES.clear();
            return;
        }
        PROXIES.keySet().removeIf(entityId -> !ENTRIES.containsKey(entityId));
        for (S2CHbmMissileSnapshot.Entry entry : ENTRIES.values()) {
            RVP_RemoteHbmMissileEntity proxy = PROXIES.computeIfAbsent(entry.entityId(),
                    entityId -> new RVP_RemoteHbmMissileEntity(mc.level, entityId));
            proxy.apply(entry);
        }
    }
}
