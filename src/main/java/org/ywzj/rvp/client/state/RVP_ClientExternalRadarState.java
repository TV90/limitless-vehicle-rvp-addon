package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RVP_ClientExternalRadarState {
    @Nullable
    private static ResourceLocation dimension;
    @Nullable
    private static UUID launcherVehicleUuid;
    @Nullable
    private static UUID relayVehicleUuid;
    private static boolean relayRadarOn;
    private static int requestedEntityId = Integer.MIN_VALUE;
    private static int lockedEntityId = Integer.MIN_VALUE;
    private static final Map<Integer, S2CExternalRadarSnapshot.Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, S2CExternalRadarSnapshot.RadarSector> SECTORS = new LinkedHashMap<>();

    private RVP_ClientExternalRadarState() {}

    public static void applySnapshot(S2CExternalRadarSnapshot msg) {
        dimension = msg.dimension;
        launcherVehicleUuid = msg.launcherVehicleUuid;
        relayVehicleUuid = msg.relayVehicleUuid;
        relayRadarOn = msg.relayRadarOn;
        requestedEntityId = msg.requestedEntityId;
        lockedEntityId = msg.lockedEntityId;
        ENTRIES.clear();
        for (S2CExternalRadarSnapshot.Entry entry : msg.entries) {
            ENTRIES.put(entry.entityId(), entry);
        }
        SECTORS.clear();
        for (S2CExternalRadarSnapshot.RadarSector sector : msg.sectors) {
            SECTORS.put(sector.radarId(), sector);
        }
    }

    public static Collection<S2CExternalRadarSnapshot.Entry> getEntries(@Nullable ResourceLocation currentDimension,
                                                                        @Nullable UUID currentLauncherVehicleUuid) {
        if (!matches(currentDimension, currentLauncherVehicleUuid) || !relayRadarOn) {
            return java.util.List.of();
        }
        return ENTRIES.values();
    }

    public static List<S2CExternalRadarSnapshot.RadarSector> getSectors(@Nullable ResourceLocation currentDimension,
                                                                        @Nullable UUID currentLauncherVehicleUuid) {
        if (!matches(currentDimension, currentLauncherVehicleUuid) || !relayRadarOn) {
            return List.of();
        }
        return List.copyOf(SECTORS.values());
    }

    @Nullable
    public static S2CExternalRadarSnapshot.Entry getEntry(@Nullable ResourceLocation currentDimension,
                                                          @Nullable UUID currentLauncherVehicleUuid,
                                                          int entityId) {
        if (!matches(currentDimension, currentLauncherVehicleUuid) || !relayRadarOn) {
            return null;
        }
        return ENTRIES.get(entityId);
    }

    @Nullable
    public static UUID getRelayVehicleUuid(@Nullable ResourceLocation currentDimension, @Nullable UUID currentLauncherVehicleUuid) {
        return matches(currentDimension, currentLauncherVehicleUuid) ? relayVehicleUuid : null;
    }

    public static int getRequestedEntityId(@Nullable ResourceLocation currentDimension, @Nullable UUID currentLauncherVehicleUuid) {
        return matches(currentDimension, currentLauncherVehicleUuid) && relayRadarOn ? requestedEntityId : Integer.MIN_VALUE;
    }

    public static int getLockedEntityId(@Nullable ResourceLocation currentDimension, @Nullable UUID currentLauncherVehicleUuid) {
        return matches(currentDimension, currentLauncherVehicleUuid) && relayRadarOn ? lockedEntityId : Integer.MIN_VALUE;
    }

    private static boolean matches(@Nullable ResourceLocation currentDimension, @Nullable UUID currentLauncherVehicleUuid) {
        return currentDimension != null
                && currentLauncherVehicleUuid != null
                && dimension != null
                && launcherVehicleUuid != null
                && dimension.equals(currentDimension)
                && launcherVehicleUuid.equals(currentLauncherVehicleUuid);
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
        launcherVehicleUuid = null;
        relayVehicleUuid = null;
        relayRadarOn = false;
        requestedEntityId = Integer.MIN_VALUE;
        lockedEntityId = Integer.MIN_VALUE;
        ENTRIES.clear();
        SECTORS.clear();
    }
}
