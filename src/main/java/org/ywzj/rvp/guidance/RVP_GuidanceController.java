package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Comparator;
import java.util.List;

/**
 * Selects and applies the active guidance source for an RVP projectile each tick.
 */
public final class RVP_GuidanceController {

    private RVP_GuidanceController() {}

    public static void tick(RVP_BaseBullet projectile) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || data.getGuidanceData().getStages().isEmpty()) {
            return;
        }

        RVP_GuidanceData.Stage stage = selectStage(projectile, data.getGuidanceData().getStages());
        if (stage == null) {
            return;
        }

        List<RVP_GuidanceData.Source> sources = stage.getSources().stream()
                .sorted(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority).reversed())
                .toList();
        for (RVP_GuidanceData.Source source : sources) {
            RVP_EnumGuidanceType type = source.getType();
            if (type == RVP_EnumGuidanceType.NONE) {
                return;
            }
            if (!isSourceSupported(projectile, type)) {
                continue;
            }
            RVP_EnumCounterDecision counterDecision = applyCountermeasures(projectile, type, data, source);
            if (counterDecision == RVP_EnumCounterDecision.DISCARD || counterDecision == RVP_EnumCounterDecision.STOP_STAGE) {
                return;
            }
            if (counterDecision == RVP_EnumCounterDecision.SKIP_SOURCE) {
                continue;
            }
            if (applySource(projectile, source, data)) {
                return;
            }
        }
    }

    private static boolean isSourceSupported(RVP_BaseBullet projectile, RVP_EnumGuidanceType type) {
        return switch (type) {
            case TV, ARH -> projectile instanceof RVP_MissileEntity;
            default -> true;
        };
    }

    private static RVP_GuidanceData.Stage selectStage(RVP_BaseBullet projectile, List<RVP_GuidanceData.Stage> stages) {
        int tick = projectile.getUpdateCount();
        double targetDistance = getCurrentTargetDistance(projectile);
        return stages.stream()
                .filter(stage -> tick >= stage.getStartTick())
                .filter(stage -> stage.getEndTick() < 0 || tick <= stage.getEndTick())
                .filter(stage -> targetDistance >= 0 || (stage.getMinDistance() <= 0f && stage.getMaxDistance() <= 0f))
                .filter(stage -> stage.getMinDistance() <= 0f || targetDistance >= stage.getMinDistance())
                .filter(stage -> stage.getMaxDistance() <= 0f || targetDistance <= stage.getMaxDistance())
                .max(Comparator.<RVP_GuidanceData.Stage>comparingInt(stage ->
                                (stage.getMinDistance() > 0f ? 1 : 0) + (stage.getMaxDistance() > 0f ? 1 : 0))
                        .thenComparingInt(RVP_GuidanceData.Stage::getStartTick))
                .orElse(null);
    }

    private static double getCurrentTargetDistance(RVP_BaseBullet projectile) {
        if (projectile.getTargetEntity() != null) {
            return projectile.distanceTo(projectile.getTargetEntity());
        }
        if (projectile.getTargetPos() != null) {
            return projectile.position().distanceTo(projectile.getTargetPos());
        }
        if (projectile.getLastGuidancePos() != null) {
            return projectile.position().distanceTo(projectile.getLastGuidancePos());
        }
        return -1;
    }

    private static RVP_EnumCounterDecision applyCountermeasures(RVP_BaseBullet projectile, RVP_EnumGuidanceType type,
                                                       RVP_WeaponData data, RVP_GuidanceData.Source source) {
        Entity target = projectile.getTargetEntity();
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(projectile, target, type, data.getSeekerData());
        if (result.intercepted()) {
            projectile.discard();
            return RVP_EnumCounterDecision.DISCARD;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(projectile::setTargetEntity);
            return RVP_EnumCounterDecision.CLEAR;
        }
        if (result.isDenied()) {
            return source.isFallbackOnJammed() ? RVP_EnumCounterDecision.SKIP_SOURCE : RVP_EnumCounterDecision.STOP_STAGE;
        }
        return RVP_EnumCounterDecision.CLEAR;
    }

    private static boolean applySource(RVP_BaseBullet projectile, RVP_GuidanceData.Source source, RVP_WeaponData data) {
        RVP_EnumGuidanceType type = source.getType();
        if (projectile.getUpdateCount() <= data.getRigidityTime()
                && type != RVP_EnumGuidanceType.MCLOS
                && type != RVP_EnumGuidanceType.TV) {
            return false;
        }
        return switch (type) {
            case NONE -> false;
            case IOG -> applyIog(projectile);
            case MCLOS -> applyLineOfSight(projectile, data, source);
            case TV -> projectile instanceof RVP_MissileEntity missile
                    && RVP_MissileGuidance.applyTv(missile, data, source);
            case SACLOS -> applySaclos(projectile, data, source);
            case GPS -> applyGps(projectile, source);
            case IR, SARH -> applyEntitySeeker(projectile, data, type);
            case ARH -> projectile instanceof RVP_MissileEntity missile
                    && RVP_MissileGuidance.applyActiveRadar(missile, data);
            case ARM -> applyAntiRadiation(projectile, data);
        };
    }

    private static boolean applyIog(RVP_BaseBullet projectile) {
        Vec3 last = projectile.getLastGuidancePos();
        return last != null && RVP_GuidanceMath.guidanceToPos(projectile, last);
    }

    private static boolean applyLineOfSight(RVP_BaseBullet projectile, RVP_WeaponData data,
                                            RVP_GuidanceData.Source source) {
        Vec3 direction = null;
        WeaponUnit weaponUnit = projectile.getShooterWeaponUnit();
        if ((direction == null || direction.lengthSqr() <= 1.0E-6) && weaponUnit != null) {
            direction = weaponUnit.worldVec().normalize();
        }
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            Entity controller = projectile.getOwner();
            if (controller == null && projectile.getShooterVehicle() != null) {
                controller = projectile.getShooterVehicle().getFirstPassenger();
            }
            if (controller instanceof LivingEntity living) {
                direction = living.getLookAngle().normalize();
            }
        }
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            return false;
        }
        Vec3 target = projectile.position().add(direction.scale(Math.max(data.getSeekerData().getRange(), 256.0)));
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(projectile, target,
                RVP_EnumGuidanceType.MCLOS, data.getSeekerData());
        if (result.isDenied()) {
            return false;
        }
        projectile.setTargetPos(target);
        return source.isTakeOverMotion()
                ? RVP_GuidanceMath.directToPos(projectile, target)
                : RVP_GuidanceMath.guidanceToPos(projectile, target);
    }

    private static boolean applyGps(RVP_BaseBullet projectile, RVP_GuidanceData.Source source) {
        if (projectile.getTargetPos() != null) {
            return source.isTakeOverMotion()
                    ? RVP_GuidanceMath.directToPos(projectile, projectile.getTargetPos())
                    : RVP_GuidanceMath.guidanceToPos(projectile, projectile.getTargetPos());
        }
        return false;
    }

    private static boolean applySaclos(RVP_BaseBullet projectile, RVP_WeaponData data, RVP_GuidanceData.Source source) {
        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            Vec3 aimPoint = target.getBoundingBox().getCenter();
            RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(projectile, aimPoint,
                    RVP_EnumGuidanceType.SACLOS, data.getSeekerData());
            if (result.isDenied()) {
                projectile.clearTarget();
                return false;
            }
            projectile.rememberGuidancePos(aimPoint);
            return source.isTakeOverMotion()
                    ? RVP_GuidanceMath.directToPos(projectile, aimPoint)
                    : RVP_GuidanceMath.guidanceToTarget(projectile, target);
        }
        Vec3 point = projectile.getTargetPos();
        if (point == null) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(projectile, point,
                RVP_EnumGuidanceType.SACLOS, data.getSeekerData());
        if (result.isDenied()) {
            projectile.clearTarget();
            return false;
        }
        return source.isTakeOverMotion()
                ? RVP_GuidanceMath.directToPos(projectile, point)
                : RVP_GuidanceMath.guidanceToPos(projectile, point);
    }

    private static boolean applyEntitySeeker(RVP_BaseBullet projectile, RVP_WeaponData data, RVP_EnumGuidanceType type) {
        if (type == RVP_EnumGuidanceType.SARH) {
            Entity illuminated = getIlluminatedTarget(projectile);
            if (illuminated == null) {
                projectile.clearTarget();
                return false;
            }
            projectile.setTargetEntity(illuminated);
        }
        Entity target = projectile.getTargetEntity();
        if (target != null && target.isAlive()) {
            if (!isValidEntityTarget(projectile, data, type, target)) {
                projectile.clearTarget();
                return false;
            }
            target = projectile.getTargetEntity();
            if (target == null || !target.isAlive()) {
                return false;
            }
            if (type == RVP_EnumGuidanceType.IR) {
                projectile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
            } else {
                projectile.rememberGuidancePos(target.getBoundingBox().getCenter());
            }
            return RVP_GuidanceMath.guidanceToTarget(projectile, target);
        }
        if (type == RVP_EnumGuidanceType.SARH) {
            return false;
        }
        if (projectile.tickCount % data.getScanInterval() != 0) {
            return false;
        }
        Entity scanned = scanSeekerTarget(projectile, data, type);
        if (scanned != null) {
            projectile.setTargetEntity(scanned);
            return RVP_GuidanceMath.guidanceToTarget(projectile, scanned);
        }
        return false;
    }

    private static Entity getIlluminatedTarget(RVP_BaseBullet projectile) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return projectile.getTargetEntity();
        }
        var radarUnit = unit.getMainRadarUnit();
        if (radarUnit != null && radarUnit.getLockedEntity() != null) {
            return radarUnit.getLockedEntity();
        }
        return unit.getLockedEntity();
    }

    private static boolean isValidEntityTarget(RVP_BaseBullet projectile, RVP_WeaponData data,
                                               RVP_EnumGuidanceType type, Entity target) {
        if (!RVP_GuidanceMath.isWithinSeekerCone(projectile, target, data)) {
            return false;
        }
        if (type == RVP_EnumGuidanceType.SARH && RVP_GuidanceMath.isOnGround(target, data.getLockMinHeight())) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(projectile, target, type, data.getSeekerData());
        if (result.intercepted()) {
            projectile.discard();
            return false;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(projectile::setTargetEntity);
            return true;
        }
        return !result.isDenied();
    }

    private static boolean applyAntiRadiation(RVP_BaseBullet projectile, RVP_WeaponData data) {
        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;
        boolean canScan = !projectile.isAntiRadiationLostPermanent() || data.isAntiRadiationAllowReacquire();
        if (canScan && projectile.tickCount >= projectile.getAntiRadiationNextScanTick()) {
            projectile.setAntiRadiationNextScanTick(projectile.tickCount + data.getAntiRadiationScanIntervalTick());
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters = AntiRadiationSeekerHelper.scanVisibleEmitters(
                    projectile.level(),
                    projectile.position(),
                    projectile.getLookAngle(),
                    data.getSeekerData().getFov(),
                    data.getSeekerData().getRange(),
                    projectile.getShooterVehicle(),
                    projectile.tickCount,
                    projectile.getRadiationPulseTickMap(),
                    data.getAntiRadiationRadiationPulseMemoryTick()
            );
            double bestScore = Double.MAX_VALUE;
            for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                double score = AntiRadiationSeekerHelper.score(projectile.position(), projectile.getLookAngle(),
                        data.getSeekerData().getFov(), data.getSeekerData().getRange(), emitter.pdw(), data.getAntiRadiationLockedBonus());
                if (score < bestScore) {
                    bestScore = score;
                    best = emitter;
                }
            }
        }
        if (best != null) {
            projectile.setAntiRadiationLostPermanent(false);
            int memoryTick = data.getAntiRadiationMemoryTick() > 0
                    ? data.getAntiRadiationMemoryTick()
                    : AntiRadiationSeekerHelper.getDefaultMemoryTick(best.radarUnit());
            projectile.setAntiRadiationMemoryLeftTick(memoryTick);
            projectile.setTargetPos(best.position());
            return RVP_GuidanceMath.guidanceToPos(projectile, best.position());
        }
        if (projectile.getAntiRadiationMemoryLeftTick() > 0 && projectile.getLastGuidancePos() != null) {
            projectile.setAntiRadiationMemoryLeftTick(projectile.getAntiRadiationMemoryLeftTick() - 1);
            projectile.setTargetPos(projectile.getLastGuidancePos());
            return RVP_GuidanceMath.guidanceToPos(projectile, projectile.getLastGuidancePos());
        }
        projectile.clearTarget();
        if (!data.isAntiRadiationAllowReacquire()) {
            projectile.setAntiRadiationLostPermanent(true);
        }
        return false;
    }

    private static Entity scanSeekerTarget(RVP_BaseBullet projectile, RVP_WeaponData data, RVP_EnumGuidanceType type) {
        double range = Math.max(data.getMaxLockOnRange(), data.getSeekerData().getRange());
        double maxAngle = Math.max(data.getMaxLockOnAngle(), data.getSeekerData().getFov());
        AABB box = projectile.getBoundingBox().inflate(range);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : projectile.level().getEntities(projectile, box, RVP_GuidanceMath::isVehicleTarget)) {
            if (entity == projectile.getShooterVehicle()) {
                continue;
            }
            if (projectile.position().distanceToSqr(entity.position()) > range * range) {
                continue;
            }
            if (type == RVP_EnumGuidanceType.SARH && RVP_GuidanceMath.isOnGround(entity, data.getLockMinHeight())) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(projectile.position());
            double angle = angleBetween(projectile.getLookAngle(), toTarget);
            if (angle > maxAngle) {
                continue;
            }
            double score = angle / Math.max(maxAngle, 1.0) + projectile.distanceTo(entity) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best instanceof AbstractVehicle ? best : null;
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        Vec3 na = a.normalize();
        Vec3 nb = b.normalize();
        double dot = net.minecraft.util.Mth.clamp(na.dot(nb), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

}
