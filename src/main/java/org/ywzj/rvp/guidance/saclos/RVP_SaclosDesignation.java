package org.ywzj.rvp.guidance.saclos;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
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
        if (!usesSaclosGuidance(projectile)) {
            return;
        }
        if (isHitlDesignate(projectile)) {
            Entity tracked = projectile.getTargetEntity();
            if (tracked != null && tracked.isAlive()) {
                projectile.setTargetPos(tracked.getBoundingBox().getCenter());
            }
            return;
        }
        // 人在回路电视（HITL_TV）导弹退出视角后：禁止再被吊舱实时瞄准线覆盖目标点（否则
        // 导弹会逐 tick 追玩家鼠标/吊舱方向，表现为"指令线鼠标操控"而非飞向最后锁定目标）。
        // 退出后的目标跟踪由 RVP_RuntimeHitlTvGuidanceSource 用 targetEntity / 最后 targetPos
        // 延续，并复用其 validateEntity/queryPoint 的烟雾与视线反制（目标入烟仍脱锁）。
        if (projectile.getRvpData() != null && projectile.getRvpData().isSaclosTvGuided()) {
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

    private static boolean usesSaclosGuidance(RVP_BaseBullet projectile) {
        if (projectile.getRvpData() == null) {
            return false;
        }
        var data = projectile.getRvpData();
        return data.isVehicleLaserGuided() || data.isSaclosTvGuided();
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

        if (projectile.getOwner() instanceof LivingEntity operator) {
            Vec3 sessionPoint = RVP_SaclosOperatorSession.getDesignationPoint(operator.getUUID());
            if (sessionPoint != null) {
                return sessionPoint;
            }
        }

        WeaponUnit aimUnit = resolveOperatorAimUnit(projectile);
        boolean allowLockedEntity = projectile.getRvpData() != null
                && projectile.getRvpData().isSaclosTvGuided();
        Vec3 podAim = RVP_SaclosPodAim.resolvePodAimPoint(aimUnit, allowLockedEntity);
        if (podAim != null) {
            return podAim;
        }
        return projectile.getTargetPos();
    }

    @Nullable
    public static WeaponUnit resolveOperatorAimUnit(RVP_BaseBullet projectile) {
        WeaponUnit shooterUnit = projectile.getShooterWeaponUnit();
        WeaponUnit aimUnit = RVP_CommandGuidanceAim.resolveOperatorAimUnit(shooterUnit);
        if (aimUnit != null) {
            return aimUnit;
        }
        AbstractVehicle vehicle = projectile.getShooterVehicle();
        if (vehicle != null && projectile.getOwner() instanceof LivingEntity operator) {
            if (vehicle.getOwnOperatorUnit(operator) instanceof WeaponUnit operatorUnit) {
                return RVP_CommandGuidanceAim.resolveOperatorAimUnit(operatorUnit);
            }
        }
        return shooterUnit;
    }

    public static boolean operatorHasSaclosMissileInGuidance(ServerPlayer player) {
        if (player.level() == null || !(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // O(实体) 遍历已加载实体，替代 ±4096 立方体 getEntitiesOfClass（8192³，灾难级）；
        // 距离闸门还原原 box 范围
        net.minecraft.world.phys.AABB searchBox = player.getBoundingBox().inflate(4096.0D);
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (entity instanceof RVP_BaseBullet bullet
                    && bullet.isAlive()
                    && bullet.getOwner() == player
                    && isInSaclosGuidanceStage(bullet)
                    && bullet.getBoundingBox().intersects(searchBox)) {
                return true;
            }
        }
        return false;
    }
}
