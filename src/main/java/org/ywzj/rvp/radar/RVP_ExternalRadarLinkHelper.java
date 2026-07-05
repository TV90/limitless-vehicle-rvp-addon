package org.ywzj.rvp.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.network.C2SClearExternalRadarLock;
import org.ywzj.rvp.network.C2SRequestExternalRadarLock;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;
import org.ywzj.rvp.uav.RVP_DeployableUavLinkRegistry;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public final class RVP_ExternalRadarLinkHelper {
    private RVP_ExternalRadarLinkHelper() {}

    public static Optional<AbstractVehicle> getLinkedRelayVehicle(AbstractVehicle launcher) {
        UUID childUuid = null;
        if (launcher instanceof AbstractVehicleLinkedUavExt ext) {
            childUuid = ext.ywzj_rvp$getLinkedChildVehicleUuid();
        }
        if (childUuid == null) {
            childUuid = RVP_DeployableUavLinkRegistry.getChildUuid(launcher.getUUID());
        }
        if (childUuid == null || !(launcher.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return Optional.empty();
        }
        Entity entity = serverLevel.getEntity(childUuid);
        return entity instanceof AbstractVehicle vehicle ? Optional.of(vehicle) : Optional.empty();
    }

    @Nullable
    public static RadarUnit getPreferredRelayLockRadar(@Nullable AbstractVehicle relayVehicle) {
        if (relayVehicle == null) {
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
        AbstractVehicle launcher = LocalVehiclePlayer.instance.getVehicle();
        Minecraft mc = Minecraft.getInstance();
        if (weaponUnit == null || launcher == null || mc.level == null) {
            return null;
        }
        Vec3 aimVec = weaponUnit.worldVec();
        Vec3 origin = weaponUnit.worldPivotPosition();
        return getClientEntries(launcher, mc.level.dimension().location()).stream()
                .map(entry -> resolveClientEntity(entry.entityId()))
                .filter(entity -> entity != null && entity.isAlive())
                .filter(entity -> {
                    Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(origin);
                    return toTarget.lengthSqr() > 1.0E-6
                            && Math.toDegrees(VectorUtil.angleBetween(aimVec, toTarget)) <= RVP_RadarRoleHelper.MANUAL_LOCK_REQUEST_FOV;
                })
                .min(Comparator.comparingDouble(entity -> {
                    Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(origin);
                    double angle = Math.toDegrees(VectorUtil.angleBetween(aimVec, toTarget));
                    double distanceScore = origin.distanceToSqr(entity.getBoundingBox().getCenter()) * 0.000001;
                    return angle + distanceScore;
                }))
                .orElse(null);
    }

    public static boolean applyClientLockRequest(@Nullable WeaponUnit weaponUnit, @Nullable Entity target) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (!(root instanceof WeaponUnitExternalRadarLockExt ext)) {
            return false;
        }
        RVP_RadarRoleHelper.clearAllRadarLocks(root);
        root.setFocusLockPos(null);
        root.setLockedEntity(target);
        ext.ywzj_rvp$setExternalRadarRequestedEntityId(target.getId());
        ext.ywzj_rvp$clearExternalRadarLockedEntityId();
        RVP_Network.CHANNEL.sendToServer(new C2SRequestExternalRadarLock(target.getId()));
        return true;
    }

    public static void clearClientLockRequest(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root instanceof WeaponUnitExternalRadarLockExt ext) {
            ext.ywzj_rvp$clearExternalRadarRequestedEntityId();
            ext.ywzj_rvp$clearExternalRadarLockedEntityId();
        }
        RVP_RadarRoleHelper.clearAllRadarLocks(root);
        if (root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            root.setLockedEntity(null);
        }
        RVP_Network.CHANNEL.sendToServer(new C2SClearExternalRadarLock());
    }

    @Nullable
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
    public static S2CExternalRadarSnapshot.Affiliation getAffiliation(@Nullable Entity entity) {
        if (entity == null) return null;
        Minecraft mc = Minecraft.getInstance();
        AbstractVehicle launcher = LocalVehiclePlayer.instance.getVehicle();
        if (mc.level == null || launcher == null) return null;
        S2CExternalRadarSnapshot.Entry entry = getClientEntry(launcher, mc.level.dimension().location(), entity.getId());
        return entry != null ? entry.affiliation() : null;
    }
}
