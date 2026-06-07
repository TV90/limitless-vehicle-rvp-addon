package org.ywzj.rvp.guidance.source;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.guidance.RVP_WireGuidanceSteering;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Manual command to line of sight (MCLOS), integrated with staged guidance.
 *
 * <p>刚性段内不产出意图（由 {@link org.ywzj.rvp.guidance.RVP_GuidanceCompositor} 统一门控）。
 * 过刚性段后解析拖线指令航向；{@code take_over_motion:true} 时由 {@link org.ywzj.rvp.guidance.RVP_GuidanceMath}
 * 走 MCH 瞬时 {@code setMotion+setRotation}。</p>
 */
public final class RVP_MclosGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.MCLOS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        RVP_BaseBullet projectile = context.projectile();

        Vec3 direction = resolveDirection(projectile, source);
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.MCLOS);
        }

        Vec3 target = projectile.position().add(direction.scale(Math.max(context.effective().seeker().getRange(), 256.0)));
        // Wire MCLOS (take_over_motion) follows command heading only — no seeker LOS to a synthetic far point.
        // queryPoint raycasts missile→target; terrain along that ray always blocks when aiming at ground targets.
        if (!source.isTakeOverMotion()) {
            RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                    projectile, target, RVP_EnumGuidanceType.MCLOS, context.effective().seeker());
            if (result.isDenied()) {
                return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.MCLOS);
            }
        }

        projectile.setTargetPos(target);
        return RVP_GuidanceIntent.point(target, source.isTakeOverMotion(), source.getWeight(), RVP_EnumGuidanceType.MCLOS);
    }

    static Vec3 resolveDirection(RVP_BaseBullet projectile, RVP_GuidanceData.Source source) {
        if (source.getParams().useWeaponUnitAim(true) || projectile instanceof org.ywzj.rvp.entity.projectile.RVP_MissileEntity) {
            Vec2 command = RVP_WireGuidanceSteering.resolveCommand(projectile).orElse(null);
            if (command != null) {
                Vec3 dir = VectorUtil.rotToVec(command.x, command.y);
                if (dir.lengthSqr() > 1.0E-6) {
                    return dir.normalize();
                }
            }
        }

        if (source.getParams().useWeaponUnitAim(true)) {
            Vec3 dir = operatorAimDirection(projectile.getShooterWeaponUnit());
            if (dir != null) {
                return dir;
            }
        }

        if (source.getParams().useOwnerLook(false)) {
            Entity controller = projectile.getOwner();
            if (controller == null && projectile.getShooterVehicle() != null) {
                controller = projectile.getShooterVehicle().getFirstPassenger();
            }
            if (controller instanceof LivingEntity living) {
                Vec3 dir = living.getLookAngle();
                if (dir.lengthSqr() > 1.0E-6) {
                    return dir.normalize();
                }
            }
        }

        return null;
    }

    public static Vec3 operatorAimDirection(WeaponUnit shooterUnit) {
        WeaponUnit aimUnit = resolveOperatorAimUnit(shooterUnit);
        if (aimUnit == null) {
            return null;
        }
        Vec3 dir = aimUnit.worldVec(aimUnit.getXAimRot(), aimUnit.getYAimRot());
        if (dir.lengthSqr() <= 1.0E-6) {
            dir = aimUnit.worldVec();
        }
        return dir.lengthSqr() > 1.0E-6 ? dir.normalize() : null;
    }

    public static WeaponUnit resolveOperatorAimUnit(WeaponUnit shooterUnit) {
        if (shooterUnit == null) {
            return null;
        }
        if (shooterUnit.isParentWeaponUnitAim()) {
            return shooterUnit.getRootParentWeaponUnit();
        }
        return shooterUnit;
    }
}
