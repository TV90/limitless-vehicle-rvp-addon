package org.ywzj.rvp.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.network.C2SClearExternalRadarLock;
import org.ywzj.rvp.network.C2SRequestExternalRadarLock;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;
import org.ywzj.rvp.uav.RVP_DeployableUavLinkRegistry;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RVP_ExternalRadarLinkHelper {
    public record ClientLockCandidate(int entityId, Vec3 position, @Nullable Entity resolvedEntity) {}

    private RVP_ExternalRadarLinkHelper() {}

    public static Optional<AbstractVehicle> getLinkedRelayVehicle(AbstractVehicle launcher) {
        UUID childUuid = RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(launcher);
        if (childUuid == null) {
            childUuid = RVP_DeployableUavLinkRegistry.getChildUuid(launcher.getUUID());
        }
        if (childUuid == null || !(launcher.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return Optional.empty();
        }
        Entity entity = serverLevel.getEntity(childUuid);
        if (entity instanceof AbstractVehicle vehicle && !vehicle.isDestroyed()) {
            return Optional.of(vehicle);
        }
        return Optional.empty();
    }

    @Nullable
    public static RadarUnit getPreferredRelayLockRadar(@Nullable AbstractVehicle relayVehicle) {
        if (relayVehicle == null || relayVehicle.isDestroyed()) {
            return null;
        }
        RadarUnit firstLockCapable = null;
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            if (!RVP_RadarRoleHelper.canLock(radarUnit)) {
                continue;
            }
            if (RVP_RadarRoleHelper.ROLE_FIRE_CONTROL.equalsIgnoreCase(RVP_RadarRoleHelper.getRadarRole(radarUnit))) {
                return radarUnit;
            }
            if (firstLockCapable == null) {
                firstLockCapable = radarUnit;
            }
        }
        return firstLockCapable;
    }

    public static Collection<S2CExternalRadarSnapshot.Entry> getClientEntries(@Nullable AbstractVehicle launcher,
                                                                              @Nullable ResourceLocation dimension) {
        if (launcher == null || dimension == null) {
            return java.util.List.of();
        }
        return RVP_ClientExternalRadarState.getEntries(dimension, launcher.getUUID());
    }

    @Nullable
    public static S2CExternalRadarSnapshot.Entry getClientEntry(@Nullable AbstractVehicle launcher,
                                                                @Nullable ResourceLocation dimension,
                                                                int entityId) {
        if (launcher == null || dimension == null) {
            return null;
        }
        return RVP_ClientExternalRadarState.getEntry(dimension, launcher.getUUID(), entityId);
    }

    public static int getClientRequestedEntityId(@Nullable AbstractVehicle launcher, @Nullable ResourceLocation dimension) {
        if (launcher == null || dimension == null) {
            return Integer.MIN_VALUE;
        }
        return RVP_ClientExternalRadarState.getRequestedEntityId(dimension, launcher.getUUID());
    }

    public static int getClientLockedEntityId(@Nullable AbstractVehicle launcher, @Nullable ResourceLocation dimension) {
        if (launcher == null || dimension == null) {
            return Integer.MIN_VALUE;
        }
        return RVP_ClientExternalRadarState.getLockedEntityId(dimension, launcher.getUUID());
    }

    @Nullable
    public static Entity getClientLockedEntity(@Nullable AbstractVehicle launcher, @Nullable ResourceLocation dimension) {
        int entityId = getClientLockedEntityId(launcher, dimension);
        return entityId == Integer.MIN_VALUE ? null : resolveClientEntity(entityId);
    }

    public static boolean hasClientExternalLockState(@Nullable AbstractVehicle launcher, @Nullable ResourceLocation dimension) {
        return getClientRequestedEntityId(launcher, dimension) != Integer.MIN_VALUE
                || getClientLockedEntityId(launcher, dimension) != Integer.MIN_VALUE;
    }

    public static boolean isClientTrackedByExternalRadar(@Nullable AbstractVehicle launcher,
                                                         @Nullable ResourceLocation dimension,
                                                         int entityId) {
        return entityId != Integer.MIN_VALUE && getClientEntry(launcher, dimension, entityId) != null;
    }

    @Nullable
    public static Entity findManualClientLockCandidate(@Nullable WeaponUnit weaponUnit) {
        ClientLockCandidate candidate = findManualClientLockCandidateData(weaponUnit);
        return candidate != null ? candidate.resolvedEntity() : null;
    }

    @Nullable
    public static ClientLockCandidate findManualClientLockCandidateData(@Nullable WeaponUnit weaponUnit) {
        List<ClientLockCandidate> candidates = collectManualClientLockCandidateData(weaponUnit, null);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Nullable
    public static ClientLockCandidate findViewManualClientLockCandidateData(@Nullable WeaponUnit weaponUnit) {
        List<ClientLockCandidate> candidates = collectViewManualClientLockCandidates(weaponUnit);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public static List<ClientLockCandidate> collectViewManualClientLockCandidates(@Nullable WeaponUnit weaponUnit) {
        return collectManualClientLockCandidateData(weaponUnit, RVP_RadarRoleHelper.resolveManualLockAimVec(weaponUnit));
    }

    @OnlyIn(Dist.CLIENT)
    public static List<ClientLockCandidate> collectManualClientLockCandidateData(@Nullable WeaponUnit weaponUnit,
                                                                                  @Nullable Vec3 aimVecOverride) {
        AbstractVehicle launcher = LocalVehiclePlayer.instance.getVehicle();
        Minecraft mc = Minecraft.getInstance();
        if (weaponUnit == null || launcher == null || mc.level == null) {
            return List.of();
        }
        Vec3 origin = weaponUnit.worldPivotPosition();
        Vec3 aimVec = aimVecOverride != null ? aimVecOverride : RVP_RadarRoleHelper.resolveManualLockAimVec(weaponUnit);
        final Vec3 finalAimVec = aimVec;
        return getClientEntries(launcher, mc.level.dimension().location()).stream()
                .map(entry -> {
                    Entity resolved = resolveClientEntity(entry.entityId());
                    if (resolved != null && !resolved.isAlive()) {
                        return null;
                    }
                    Vec3 targetPos = resolved != null ? resolved.getBoundingBox().getCenter() : position(entry);
                    return new ClientLockCandidate(entry.entityId(), targetPos, resolved);
                })
                .filter(candidate -> candidate != null && candidate.entityId() != Integer.MIN_VALUE)
                .filter(candidate -> {
                    Vec3 toTarget = candidate.position().subtract(origin);
                    return toTarget.lengthSqr() > 1.0E-6;
                })
                .sorted(Comparator.comparingDouble(candidate ->
                        RVP_RadarRoleHelper.scoreManualLockCandidate(origin, finalAimVec, candidate.position())))
                .toList();
    }

    public static boolean applyClientLockRequest(@Nullable WeaponUnit weaponUnit, @Nullable Entity target) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        return applyClientLockRequest(weaponUnit, target.getId(), target);
    }

    public static boolean applyClientLockRequest(@Nullable WeaponUnit weaponUnit, int targetEntityId) {
        return applyClientLockRequest(weaponUnit, targetEntityId, resolveClientEntity(targetEntityId));
    }

    private static boolean applyClientLockRequest(@Nullable WeaponUnit weaponUnit,
                                                  int targetEntityId,
                                                  @Nullable Entity target) {
        if (weaponUnit == null || targetEntityId == Integer.MIN_VALUE) {
            return false;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root == null) {
            return false;
        }
        RVP_RadarRoleHelper.clearAllRadarLocks(root);
        root.setFocusLockPos(null);
        if (target != null && target.isAlive()) {
            root.setLockedEntity(target);
        } else if (root.getLockedEntity() != null && root.getLockedEntity().getId() != targetEntityId) {
            root.setLockedEntity(null);
        }
        RVP_WeaponLockStateTable.setExternalRadarRequestedEntityId(root, targetEntityId);
        RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
        RVP_Network.CHANNEL.sendToServer(new C2SRequestExternalRadarLock(targetEntityId));
        return true;
    }

    public static void clearClientLockRequest(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root != null) {
            RVP_WeaponLockStateTable.clearExternalRadarRequestedEntityId(root);
            RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
        }
        RVP_RadarRoleHelper.clearAllRadarLocks(root);
        if (root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            root.setLockedEntity(null);
        }
        RVP_Network.CHANNEL.sendToServer(new C2SClearExternalRadarLock());
    }

    @Nullable
    @OnlyIn(Dist.CLIENT)
    public static Entity resolveClientEntity(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (serverEntity.entity != null && serverEntity.entity.getId() == entityId) {
                return serverEntity.entity;
            }
        }
        return null;
    }

    public static double speedKph(S2CExternalRadarSnapshot.Entry entry) {
        return velocity(entry).length() * 72.0;
    }

    public static Vec3 position(S2CExternalRadarSnapshot.Entry entry) {
        return entry.position();
    }

    public static Vec3 velocity(S2CExternalRadarSnapshot.Entry entry) {
        return entry.velocity();
    }

    /**
     * 获取指定实体的外部雷达 affiliation 判定。
     * 用于客户端 IFF fallback：当 getDriver() 返回 null 时，
     * 使用服务端外部雷达已经算出的 affiliation 作为判定依据。
     *
     * @return affiliation 如果该实体在外部雷达条目中；否则 null
     */
    @Nullable
    @OnlyIn(Dist.CLIENT)
    public static S2CExternalRadarSnapshot.Affiliation getAffiliation(@Nullable Entity entity) {
        if (entity == null) return null;
        Minecraft mc = Minecraft.getInstance();
        AbstractVehicle launcher = LocalVehiclePlayer.instance.getVehicle();
        if (mc.level == null || launcher == null) return null;
        S2CExternalRadarSnapshot.Entry entry = getClientEntry(launcher, mc.level.dimension().location(), entity.getId());
        return entry != null ? entry.affiliation() : null;
    }
}
