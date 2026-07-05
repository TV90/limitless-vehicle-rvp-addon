package org.ywzj.rvp.uav;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RVP_DeployableUavLinkRegistry {
    private static final ConcurrentHashMap<UUID, UUID> PARENT_TO_CHILD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, UUID> CHILD_TO_PARENT = new ConcurrentHashMap<>();

    private RVP_DeployableUavLinkRegistry() {}

    public static void link(UUID parentUuid, UUID childUuid) {
        if (parentUuid == null || childUuid == null) {
            return;
        }
        PARENT_TO_CHILD.put(parentUuid, childUuid);
        CHILD_TO_PARENT.put(childUuid, parentUuid);
    }

    public static UUID getChildUuid(UUID parentUuid) {
        return parentUuid == null ? null : PARENT_TO_CHILD.get(parentUuid);
    }

    public static UUID getParentUuid(UUID childUuid) {
        return childUuid == null ? null : CHILD_TO_PARENT.get(childUuid);
    }

    public static void clearByParent(UUID parentUuid) {
        if (parentUuid == null) {
            return;
        }
        UUID childUuid = PARENT_TO_CHILD.remove(parentUuid);
        if (childUuid != null) {
            CHILD_TO_PARENT.remove(childUuid);
        }
    }

    public static void clearByChild(UUID childUuid) {
        if (childUuid == null) {
            return;
        }
        UUID parentUuid = CHILD_TO_PARENT.remove(childUuid);
        if (parentUuid != null) {
            PARENT_TO_CHILD.remove(parentUuid);
        }
    }
}

