package org.ywzj.rvp.guidance.saclos;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.source.RVP_MclosGuidanceSource;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Resolves live SACLOS designation point from operator aim / HITL designate.
 */
public final class RVP_SaclosDesignation {

    private RVP_SaclosDesignation() {}

    public static void tickUpdateLiveTarget(RVP_BaseBullet projectile) {
        if (projectile.level().isClientSide() || !projectile.isAlive()) {
            return;
        }
        if (!isInSaclosGuidanceStage(projectile)) {
            return;
        }
        if (isHitlDesignate(projectile)) {
            Entity tracked = projectile.getTargetEntity();
            if (tracked != null && tracked.isAlive()) {
                projectile.setTargetPos(tracked.getBoundingBox().getCenter());
            }
            return;
        }
        if (!RVP_SaclosOperatorSession.isLaserEnabled(projectile)) {
            return;
        }
        Vec3 point = resolveDesignationPoint(projectile);
        if (point != null) {
            projectile.setTargetPos(point);
        }
    }

    private static boolean isHitlDesignate(RVP_BaseBullet projectile) {
        return projectile instanceof RVP_MissileEntity missile
                && missile.rvp$isHitlActive()
                && missile.rvp$getHitlControlMode() == RVP_EnumHitlControlMode.DESIGNATE;
    }

    public static boolean isInSaclosGuidanceStage(RVP_BaseBullet projectile) {
        return projectile.rvp$isInSaclosGuidanceStage();
    }

    @Nullable
    public static Vec3 resolveDesignationPoint(RVP_BaseBullet projectile) {
        if (isHitlDesignate(projectile)) {
            Entity tracked = projectile.getTargetEntity();
            if (tracked != null && tracked.isAlive()) {
                return tracked.getBoundingBox().getCenter();
            }
            return projectile.getTargetPos();
        }

        if (projectile.getOwner() instanceof Player operator) {
            Vec3 sessionPoint = RVP_SaclosOperatorSession.getDesignationPoint(operator.getUUID());
            if (sessionPoint != null) {
                return sessionPoint;
            }
        }

        WeaponUnit aimUnit = resolveOperatorAimUnit(projectile);
        Vec3 podAim = RVP_SaclosPodAim.resolvePodAimPoint(aimUnit);
        if (podAim != null) {
            return podAim;
        }
        return projectile.getTargetPos();
    }

    @Nullable
    public static WeaponUnit resolveOperatorAimUnit(RVP_BaseBullet projectile) {
        WeaponUnit shooterUnit = projectile.getShooterWeaponUnit();
        WeaponUnit aimUnit = RVP_MclosGuidanceSource.resolveOperatorAimUnit(shooterUnit);
        if (aimUnit != null) {
            return aimUnit;
        }
        AbstractVehicle vehicle = projectile.getShooterVehicle();
        if (vehicle != null && projectile.getOwner() instanceof LivingEntity operator) {
            if (vehicle.getOwnOperatorUnit(operator) instanceof WeaponUnit operatorUnit) {
                return RVP_MclosGuidanceSource.resolveOperatorAimUnit(operatorUnit);
            }
        }
        return shooterUnit;
    }

    public static boolean operatorHasSaclosMissileInGuidance(ServerPlayer player) {
        if (player.level() == null) {
            return false;
        }
        for (RVP_BaseBullet bullet : player.level().getEntitiesOfClass(
                RVP_BaseBullet.class, player.getBoundingBox().inflate(4096))) {
            if (bullet.isAlive()
                    && bullet.getOwner() == player
                    && isInSaclosGuidanceStage(bullet)) {
                return true;
            }
        }
        return false;
    }
}
