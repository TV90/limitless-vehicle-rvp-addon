package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosPodAim;
import org.ywzj.rvp.network.C2SSaclosDesignation;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * Client SACLOS laser designator: cockpit pod aim, or HITL TV designated target marker.
 */
public final class RVP_ClientSaclosState {

    private static boolean laserEnabled = true;
    private static boolean lastSentTargeting = true;
    private static int trackedMissileId = -1;
    private static int pendingAcquireTicks;
    @Nullable
    private static Vec3 laserHudPos;

    private RVP_ClientSaclosState() {}

    public static void onSaclosWeaponFired() {
        pendingAcquireTicks = 40;
        trackedMissileId = -1;
        laserEnabled = true;
    }

    public static void tick(Minecraft mc, LocalPlayer player) {
        if (mc.level == null || player == null) {
            reset();
            return;
        }

        if (RVP_ClientHitlState.isDesignateMode()) {
            tickHitlDesignate(mc);
            return;
        }

        if (!LocalVehiclePlayer.instance.onVehicle()) {
            reset();
            return;
        }

        acquireTrackedMissile(player, mc.level);

        boolean guiding = isGuiding();
        if (guiding && laserEnabled) {
            laserHudPos = resolvePodAimPoint();
        } else {
            laserHudPos = null;
        }

        syncDesignation(guiding);
    }

    private static void tickHitlDesignate(Minecraft mc) {
        int designatedEntityId = RVP_ClientHitlState.getClientDesignatedEntityId();
        if (designatedEntityId >= 0 && mc.level != null) {
            Entity target = mc.level.getEntity(designatedEntityId);
            if (target != null && target.isAlive()) {
                laserHudPos = target.getBoundingBox().getCenter();
                return;
            }
        }
        Vec3 clientPoint = RVP_ClientHitlState.getClientDesignatedPos();
        if (clientPoint != null) {
            laserHudPos = clientPoint;
            return;
        }
        Entity entity = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (entity instanceof RVP_MissileEntity missile && missile.isAlive()) {
            laserHudPos = missile.getTargetPos();
        } else {
            laserHudPos = null;
        }
    }

    /** Press R while guiding (cockpit): toggle laser designator on/off. */
    public static void toggleLaser() {
        laserEnabled = !laserEnabled;
        if (!laserEnabled) {
            laserHudPos = null;
            RVP_Network.CHANNEL.sendToServer(C2SSaclosDesignation.of(false, Vec3.ZERO));
            lastSentTargeting = false;
            return;
        }
        Vec3 point = resolvePodAimPoint();
        laserHudPos = point;
        if (point != null) {
            RVP_Network.CHANNEL.sendToServer(C2SSaclosDesignation.of(true, point));
            lastSentTargeting = true;
        }
    }

    @Nullable
    private static Vec3 resolvePodAimPoint() {
        WeaponUnit unit = resolveOperatorWeaponUnit();
        Vec3 hit = RVP_SaclosPodAim.resolvePodAimPoint(unit);
        if (hit != null) {
            return hit;
        }
        return unit != null ? unit.weaponHitPos : null;
    }

    @Nullable
    private static WeaponUnit resolveOperatorWeaponUnit() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        WeaponUnit unit = lvp.getWeaponUnit();
        if (unit == null) {
            return null;
        }
        if (unit.isParentWeaponUnitAim()) {
            unit = unit.getRootParentWeaponUnit();
        }
        return unit;
    }

    private static void acquireTrackedMissile(LocalPlayer player, Level level) {
        if (trackedMissileId >= 0) {
            Entity tracked = level.getEntity(trackedMissileId);
            if (tracked instanceof RVP_BaseBullet bullet && bullet.isAlive()) {
                pendingAcquireTicks = 0;
                return;
            }
            trackedMissileId = -1;
        }
        if (pendingAcquireTicks <= 0) {
            return;
        }
        pendingAcquireTicks--;

        RVP_BaseBullet best = null;
        int youngestTick = Integer.MAX_VALUE;
        Entity vehicle = player.getVehicle();
        for (RVP_BaseBullet bullet : level.getEntitiesOfClass(
                RVP_BaseBullet.class, player.getBoundingBox().inflate(256))) {
            if (!bullet.isAlive()) {
                continue;
            }
            RVP_WeaponData data = RVP_ClientSaclosGuidance.resolveWeaponData(bullet);
            if (data == null || !data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)) {
                continue;
            }
            Entity owner = bullet.getOwner();
            if (owner != player && (vehicle == null || owner != vehicle)) {
                if (owner != null || bullet.distanceTo(vehicle == null ? player : vehicle) > 256.0D) {
                    continue;
                }
            }
            if (bullet.tickCount < youngestTick) {
                youngestTick = bullet.tickCount;
                best = bullet;
            }
        }
        if (best != null) {
            trackedMissileId = best.getId();
            pendingAcquireTicks = 0;
        }
    }

    private static void syncDesignation(boolean guiding) {
        if (!guiding) {
            if (!lastSentTargeting) {
                RVP_Network.CHANNEL.sendToServer(C2SSaclosDesignation.of(false, Vec3.ZERO));
                lastSentTargeting = true;
            }
            laserEnabled = true;
            trackedMissileId = -1;
            return;
        }

        if (!laserEnabled) {
            return;
        }
        if (laserHudPos == null) {
            return;
        }
        RVP_Network.CHANNEL.sendToServer(C2SSaclosDesignation.of(true, laserHudPos));
        lastSentTargeting = true;
    }

    public static boolean isGuiding() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }

        if (RVP_ClientHitlState.isDesignateMode()) {
            Entity missile = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
            return missile instanceof RVP_BaseBullet bullet
                    && bullet.isAlive();
        }

        if (trackedMissileId >= 0) {
            Entity missile = mc.level.getEntity(trackedMissileId);
            if (missile instanceof RVP_BaseBullet bullet && bullet.isAlive()) {
                return RVP_ClientSaclosGuidance.isInSaclosPhase(bullet);
            }
        }
        return RVP_ClientSaclosGuidance.isOperatorGuiding(player, mc.level);
    }

    public static boolean isLaserEnabled() {
        if (RVP_ClientHitlState.isDesignateMode()) {
            return getLaserHudPos() != null;
        }
        return laserEnabled;
    }

    @Nullable
    public static Vec3 getLaserHudPos() {
        return laserHudPos;
    }

    public static void reset() {
        laserEnabled = true;
        lastSentTargeting = true;
        trackedMissileId = -1;
        pendingAcquireTicks = 0;
        laserHudPos = null;
    }

    public static boolean isSaclosWeapon(AbstractVehicleWeapon<?> weapon) {
        return weapon instanceof RVP_WeaponBase rvp
                && rvp.getData().usesGuidanceType(RVP_EnumGuidanceType.SACLOS);
    }
}
