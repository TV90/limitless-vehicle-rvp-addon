package org.ywzj.rvp.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExternalRadarSyncService {
    private static final int SYNC_INTERVAL_TICKS = 5;

    private RVP_ExternalRadarSyncService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.serverLevel() == null) {
                continue;
            }
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
        }
    }

    private static S2CExternalRadarSnapshot buildSnapshot(ServerPlayer player) {
        S2CExternalRadarSnapshot msg = new S2CExternalRadarSnapshot();
        msg.dimension = player.serverLevel().dimension().location();
        if (!(player.getVehicle() instanceof AbstractVehicle launcher)) {
            return msg;
        }
        AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        if (relayVehicle == null || relayVehicle.isRemoved() || !relayVehicle.isAlive()) {
            return msg;
        }
        msg.launcherVehicleUuid = launcher.getUUID();
        msg.relayVehicleUuid = relayVehicle.getUUID();
        msg.relayRadarOn = hasAnyRadarOn(relayVehicle);
        if (!msg.relayRadarOn) {
            clearExternalLockStateForRelayOff(player, launcher, relayVehicle, msg);
            msg.entries = List.of();
            msg.sectors = List.of();
            return msg;
        }
        syncExternalLockState(player, launcher, relayVehicle, msg);
        msg.entries = collectEntries(player, launcher, relayVehicle);
        msg.sectors = collectSectors(relayVehicle);
        return msg;
    }

    private static void syncExternalLockState(ServerPlayer player,
                                              AbstractVehicle launcher,
                                              AbstractVehicle relayVehicle,
                                              S2CExternalRadarSnapshot msg) {
        WeaponUnit weaponUnit = resolveCurrentWeaponUnit(player, launcher);
        if (weaponUnit == null) {
            clearRelayLock(relayVehicle);
            return;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (!(root instanceof WeaponUnitExternalRadarLockExt ext)
                || root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            clearRelayLock(relayVehicle);
            return;
        }

        int requestedId = ext.ywzj_rvp$getExternalRadarRequestedEntityId();
        msg.requestedEntityId = requestedId;
        if (requestedId == Integer.MIN_VALUE) {
            ext.ywzj_rvp$clearExternalRadarLockedEntityId();
            clearRelayLock(relayVehicle);
            return;
        }

        Entity target = launcher.level().getEntity(requestedId);
        if (target == null || !target.isAlive()) {
            ext.ywzj_rvp$clearExternalRadarRequestedEntityId();
            ext.ywzj_rvp$clearExternalRadarLockedEntityId();
            clearRelayLock(relayVehicle);
            if (RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), requestedId)) {
                root.setLockedEntity(null);
            }
            msg.requestedEntityId = Integer.MIN_VALUE;
            return;
        }

        if (!RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), requestedId)) {
            root.setLockedEntity(target);
        }

        RadarUnit relayLockRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
        if (relayLockRadar == null) {
            ext.ywzj_rvp$clearExternalRadarLockedEntityId();
            return;
        }

        if (RVP_RadarRoleHelper.radarCurrentlyDetects(relayLockRadar, target)) {
            if (!RVP_RadarRoleHelper.entityMatches(relayLockRadar.getLockedEntity(), requestedId)) {
                relayLockRadar.setLockedEntity(target);
            }
            ext.ywzj_rvp$setExternalRadarLockedEntityId(requestedId);
            msg.lockedEntityId = requestedId;
            return;
        }

        if (relayLockRadar.getLockedEntity() != null) {
            relayLockRadar.setLockedEntity(null);
        }
        ext.ywzj_rvp$clearExternalRadarLockedEntityId();
    }

    @Nullable
    private static WeaponUnit resolveCurrentWeaponUnit(ServerPlayer player, AbstractVehicle launcher) {
        PartUnit<?> partUnit = launcher.getOwnOperatorUnit(player);
        if (!(partUnit instanceof WeaponUnit weaponUnit)) {
            return null;
        }
        return weaponUnit.getRootParentWeaponUnit();
    }

    private static void clearRelayLock(@Nullable AbstractVehicle relayVehicle) {
        RadarUnit relayLockRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
        if (relayLockRadar != null && relayLockRadar.getLockedEntity() != null) {
            relayLockRadar.setLockedEntity(null);
        }
    }

    private static boolean hasAnyRadarOn(AbstractVehicle relayVehicle) {
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.isOn()) {
                return true;
            }
        }
        return false;
    }

    private static void clearExternalLockStateForRelayOff(ServerPlayer player,
                                                          AbstractVehicle launcher,
                                                          AbstractVehicle relayVehicle,
                                                          S2CExternalRadarSnapshot msg) {
        msg.requestedEntityId = Integer.MIN_VALUE;
        msg.lockedEntityId = Integer.MIN_VALUE;

        WeaponUnit weaponUnit = resolveCurrentWeaponUnit(player, launcher);
        if (weaponUnit != null) {
            WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
            if (root instanceof WeaponUnitExternalRadarLockExt ext) {
                int requestedId = ext.ywzj_rvp$getExternalRadarRequestedEntityId();
                if (RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), requestedId)) {
                    root.setLockedEntity(null);
                }
                ext.ywzj_rvp$clearExternalRadarRequestedEntityId();
                ext.ywzj_rvp$clearExternalRadarLockedEntityId();
            }
        }

        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
    }

    private static List<S2CExternalRadarSnapshot.Entry> collectEntries(ServerPlayer player,
                                                                       AbstractVehicle launcher,
                                                                       AbstractVehicle relayVehicle) {
        Map<Integer, S2CExternalRadarSnapshot.Entry> byEntityId = new LinkedHashMap<>();
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            List<Entity> detectedEntities = scanTargets(radarUnit, relayVehicle);
            for (Entity entity : detectedEntities) {
                if (!shouldIncludeTarget(launcher, relayVehicle, entity)) {
                    continue;
                }
                S2CExternalRadarSnapshot.Entry entry = toEntry(player, radarUnit, entity);
                S2CExternalRadarSnapshot.Entry existing = byEntityId.get(entity.getId());
                if (existing == null || (existing.nctrLabel().isBlank() && !entry.nctrLabel().isBlank())) {
                    byEntityId.put(entity.getId(), entry);
                }
            }
        }
        return new ArrayList<>(byEntityId.values());
    }

    private static List<S2CExternalRadarSnapshot.RadarSector> collectSectors(AbstractVehicle relayVehicle) {
        List<S2CExternalRadarSnapshot.RadarSector> sectors = new ArrayList<>();
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn() || radarUnit.isUiHide()) {
                continue;
            }
            Vec3 pos = radarUnit.worldRadarPosition();
            float yaw = radarUnit.worldRot(radarUnit.getXRot(), radarUnit.getYRot()).y;
            float yRotMin = radarUnit.getYRotMin();
            float yRotMax = radarUnit.getYRotMax();
            sectors.add(new S2CExternalRadarSnapshot.RadarSector(
                    radarUnit.getId(),
                    pos.x,
                    pos.y,
                    pos.z,
                    yaw,
                    yRotMin,
                    yRotMax,
                    radarUnit.getMaxScanDistance()
            ));
        }
        return sectors;
    }

    private static List<Entity> scanTargets(RadarUnit radarUnit, AbstractVehicle relayVehicle) {
        RadarUnitData data = radarUnit.getData();
        boolean phaseMode = data instanceof RadarUnitDataExt ext
                && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode());
        List<Entity> targets = phaseMode
                ? Radar.scanTargets(relayVehicle, radarUnit.worldRadarPosition(), radarUnit.getMaxScanDistance(),
                pos -> isWithinRelayRadarVolume(radarUnit, pos, true))
                : Radar.detectTargets(relayVehicle, radarUnit.worldRadarPosition(), radarUnit.getMaxScanDistance(),
                pos -> isWithinRelayRadarVolume(radarUnit, pos, false));
        appendAmmoTargets(radarUnit, relayVehicle, targets, !phaseMode);
        return targets;
    }

    private static boolean isWithinRelayRadarVolume(RadarUnit radarUnit, Vec3 targetPos, boolean phaseMode) {
        if (!isWithinScanHeight(radarUnit, targetPos)) {
            return false;
        }
        Vec2 aimRot = radarUnit.aimRot(targetPos);
        float yMin = radarUnit.getYRotMin();
        float yMax = radarUnit.getYRotMax();
        float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
        if (!isYawWithin(y, yMin, yMax)) {
            return false;
        }
        if (Math.abs(aimRot.x - radarUnit.getXRot()) > radarUnit.getScanSectorAngle() / 2.0f) {
            return false;
        }
        if (!phaseMode && radarUnit.getYRotSpeed() > 0f && Math.abs(y - radarUnit.getYRot()) > radarUnit.getYRotSpeed() / 2.0f) {
            return false;
        }
        return true;
    }

    private static boolean isYawWithin(float y, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return y >= yMin && y <= yMax;
    }

    private static float normalizeYawForLimits(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return yaw;
        }
        boolean prefer360Space = yMin >= 0.0f && yMax > 180.0f;
        if (prefer360Space && yaw < 0.0f) {
            return yaw + 360.0f;
        }
        return yaw;
    }

    private static boolean isWithinScanHeight(RadarUnit radarUnit, Vec3 targetPos) {
        float minHeight = 25f;
        float maxHeight = 10000f;
        RadarUnitData data = radarUnit.getData();
        if (data instanceof RadarUnitDataExt ext) {
            minHeight = ext.ywzj_rvp$getScanMinHeight();
            maxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
        if (maxHeight < minHeight) {
            float swap = minHeight;
            minHeight = maxHeight;
            maxHeight = swap;
        }
        int groundY = radarUnit.getVehicle().level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(targetPos.x), (int) Math.floor(targetPos.z));
        double heightAboveGround = targetPos.y - groundY;
        return heightAboveGround >= minHeight && heightAboveGround <= maxHeight;
    }

    private static void appendAmmoTargets(RadarUnit radarUnit, AbstractVehicle relayVehicle, List<Entity> targets, boolean requireTrackingLine) {
        Vec3 radarPos = radarUnit.worldRadarPosition();
        double maxDistance = radarUnit.getMaxScanDistance();
        double maxDistanceSqr = maxDistance * maxDistance;
        net.minecraft.world.phys.AABB scanBox = new net.minecraft.world.phys.AABB(
                radarPos.subtract(maxDistance, maxDistance, maxDistance),
                radarPos.add(maxDistance, maxDistance, maxDistance)
        );
        java.util.Set<Integer> existingIds = new java.util.HashSet<>();
        for (Entity target : targets) {
            existingIds.add(target.getId());
        }
        for (RVP_BaseBullet bullet : relayVehicle.level().getEntitiesOfClass(RVP_BaseBullet.class, scanBox, bullet -> {
            if (bullet == null || !bullet.isAlive() || bullet.getVehicle() != null) {
                return false;
            }
            Vec3 pos = bullet.getBoundingBox().getCenter();
            if (pos.distanceToSqr(radarPos) > maxDistanceSqr) {
                return false;
            }
            if (!isWithinScanHeight(radarUnit, pos)) {
                return false;
            }
            Vec2 aimRot = radarUnit.aimRot(pos);
            float yMin = radarUnit.getYRotMin();
            float yMax = radarUnit.getYRotMax();
            float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!isYawWithin(y, yMin, yMax)) {
                return false;
            }
            if (requireTrackingLine && radarUnit.getYRotSpeed() > 0f && Math.abs(y - radarUnit.getYRot()) > radarUnit.getYRotSpeed() / 2.0f) {
                return false;
            }
            return Math.abs(aimRot.x - radarUnit.getXRot()) <= radarUnit.getScanSectorAngle() / 2.0f;
        })) {
            if (existingIds.add(bullet.getId())) {
                targets.add(bullet);
            }
        }
    }

    private static boolean shouldIncludeTarget(AbstractVehicle launcher, AbstractVehicle relayVehicle, Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity == launcher || entity == relayVehicle) {
            return false;
        }
        if (entity instanceof AbstractVehicle vehicle) {
            if (vehicle.getUUID().equals(launcher.getUUID()) || vehicle.getUUID().equals(relayVehicle.getUUID())) {
                return false;
            }
        }
        return true;
    }

    private static S2CExternalRadarSnapshot.Entry toEntry(ServerPlayer player, RadarUnit radarUnit, Entity entity) {
        Vec3 center = entity.getBoundingBox().getCenter();
        Vec3 velocity = entity.getDeltaMovement();
        return new S2CExternalRadarSnapshot.Entry(
                entity.getId(),
                resolveNctrLabel(radarUnit, entity),
                classify(player, entity),
                entity instanceof RVP_BaseBullet,
                center.x,
                center.y,
                center.z,
                velocity.x,
                velocity.y,
                velocity.z
        );
    }

    private static S2CExternalRadarSnapshot.Affiliation classify(ServerPlayer player, Entity entity) {
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        if (entity == player || entity == playerVehicle) {
            return S2CExternalRadarSnapshot.Affiliation.OWN;
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            if (bullet.getOwner() == player || (playerVehicle != null && bullet.getShooterVehicle() == playerVehicle)) {
                return S2CExternalRadarSnapshot.Affiliation.OWN;
            }
        }
        if (isAllied(player, entity.getTeam())) {
            return S2CExternalRadarSnapshot.Affiliation.FRIEND;
        }
        // 检查载具驾驶员是否为 Gunner，并依据其 faction 判定敌我
        if (entity instanceof AbstractVehicle vehicle) {
            if (vehicle.getDriver() instanceof GunnerEntity gunner) {
                RVP_EnumGunnerFaction faction = gunner.getProfileFaction();
                if (faction == RVP_EnumGunnerFaction.ENEMY) {
                    return S2CExternalRadarSnapshot.Affiliation.HOSTILE;
                }
                if (faction == RVP_EnumGunnerFaction.FRIENDLY) {
                    return S2CExternalRadarSnapshot.Affiliation.FRIEND;
                }
                // TEAM faction：继续走 getTeam() 逻辑
            }
        }
        // 检查 Gunner 自身（非骑乘状态）
        if (entity instanceof GunnerEntity gunner) {
            RVP_EnumGunnerFaction faction = gunner.getProfileFaction();
            if (faction == RVP_EnumGunnerFaction.ENEMY) {
                return S2CExternalRadarSnapshot.Affiliation.HOSTILE;
            }
            if (faction == RVP_EnumGunnerFaction.FRIENDLY) {
                return S2CExternalRadarSnapshot.Affiliation.FRIEND;
            }
        }
        return entity.getTeam() == null ? S2CExternalRadarSnapshot.Affiliation.UNKNOWN : S2CExternalRadarSnapshot.Affiliation.HOSTILE;
    }

    private static boolean isAllied(Player player, @Nullable net.minecraft.world.scores.Team team) {
        return player.getTeam() != null && team != null && team.isAlliedTo(player.getTeam());
    }

    private static String resolveNctrLabel(RadarUnit radarUnit, Entity entity) {
        RadarUnitData data = radarUnit.getData();
        if (!(data instanceof RadarUnitDataExt ext)) {
            return "";
        }
        String mode = ext.ywzj_rvp$getNctrMode();
        if ("NONE".equalsIgnoreCase(mode)) {
            return "";
        }
        if ("EARLY".equalsIgnoreCase(mode)) {
            return resolveEarlyNctrLabel(entity);
        }
        return resolveModernNctrLabel(entity);
    }

    private static String resolveEarlyNctrLabel(Entity entity) {
        if (entity instanceof org.ywzj.vehicle.entity.vehicle.FixedWingVehicle) {
            return "JET";
        }
        if (entity instanceof org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle) {
            return "HELI";
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            return switch (bullet.getWeaponKind()) {
                case MISSILE -> "MSL";
                case BOMB -> "BOMB";
                default -> "?";
            };
        }
        return "?";
    }

    private static String resolveModernNctrLabel(Entity entity) {
        if (entity instanceof AbstractVehicle vehicle) {
            return org.ywzj.rvp.config.VehicleUIPresetCache.getNctrName(vehicle.getVehicleId());
        }
        if (entity instanceof RVP_BaseBullet bullet && bullet.getWeaponId() != null) {
            ResourceLocation weaponId = bullet.getWeaponId();
            return org.ywzj.vehicle.custom.CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                    .map(index -> index.data().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(weaponId.getPath().toUpperCase(java.util.Locale.ROOT));
        }
        return resolveEarlyNctrLabel(entity);
    }
}
