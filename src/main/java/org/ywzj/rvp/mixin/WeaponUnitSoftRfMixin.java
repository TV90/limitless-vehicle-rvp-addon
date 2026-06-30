package org.ywzj.rvp.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_AimAssistState;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_MachinegunLeadState;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSoftRfMixin {
    private static final Map<Integer, RVP_AimAssistState> YWZJ_RVP$AIM_ASSIST_STATES = new HashMap<>();
    private static final double YWZJ_RVP$AIM_ASSIST_ALPHA_BASE = 0.14D;
    private static final double YWZJ_RVP$AIM_ASSIST_ALPHA_SCALE = 0.026D;
    private static final double YWZJ_RVP$AIM_ASSIST_ALPHA_MAX = 0.34D;
    private static final int YWZJ_RVP$AIM_ASSIST_STALE_TICKS = 4;

    @Redirect(
            method = "tickFireControl",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;aim(Lnet/minecraft/world/phys/Vec3;)V",
                    ordinal = 1
            ),
            remap = false
    )
    private void ywzj_rvp$softRfAim(WeaponUnit self, Vec3 worldPos) {
        if (self.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            ywzj_rvp$clearAimAssist(self);
            self.aim(worldPos);
            return;
        }
        WeaponUnitData data = self.getData();
        if (!(data instanceof WeaponUnitDataExt ext)) {
            ywzj_rvp$clearAimAssist(self);
            self.aim(worldPos);
            return;
        }
        if (!"rvp_rf".equalsIgnoreCase(ext.ywzj_rvp$getFireControlMode())) {
            ywzj_rvp$clearAimAssist(self);
            self.aim(worldPos);
            return;
        }
        boolean machinegunLeadMode = RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(self);
        float offAxisDeg = Mth.clamp(ext.ywzj_rvp$getRfOffAxisDeg(), 0.0f, 89.0f);
        if (offAxisDeg <= 0.0f) {
            ywzj_rvp$clearAimAssist(self);
            self.aim(worldPos);
            return;
        }
        RVP_LeadSolution leadSolution = RVP_MachinegunLeadState.smooth(
                self,
                RVP_MachinegunLeadSolver.solveCurrent(self, 1.0f),
                1.0f
        );
        RVP_FireControlStabilizerState.Mode fireControlMode = RVP_FireControlStabilizerState.getMode(self);
        if (leadSolution != null) {
            worldPos = leadSolution.leadWorldPos();
            boolean scopedAndZoomed = LocalVehiclePlayer.instance != null
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE
                    && self.getZoom() > 1.0f;
            offAxisDeg *= scopedAndZoomed ? 0.1f : 0.1f;
        }
        Vec3 aimFrom = leadSolution != null ? ywzj_rvp$aimOrigin(self) : self.worldPivotPosition();
        if (fireControlMode == RVP_FireControlStabilizerState.Mode.OFF) {
            ywzj_rvp$clearAimAssist(self);
            return;
        }
        if (fireControlMode == RVP_FireControlStabilizerState.Mode.STABLE) {
            ywzj_rvp$clearAimAssist(self);
            if (machinegunLeadMode) {
                if (leadSolution == null) {
                    return;
                }
                ywzj_rvp$aimAlongDirection(self, worldPos.subtract(aimFrom));
            } else {
                self.aim(worldPos);
            }
            return;
        }
        if (machinegunLeadMode && leadSolution == null) {
            ywzj_rvp$clearAimAssist(self);
            return;
        }
        Vec3 targetDir = worldPos.subtract(aimFrom);
        if (targetDir.lengthSqr() < 1.0E-6) {
            ywzj_rvp$clearAimAssist(self);
            return;
        }
        // Clamp the operator's requested aim, not the turret's current pose.
        Vec3 desiredDir = self.worldVec(self.getXAimRot(), self.getYAimRot());
        double angleDeg = Math.toDegrees(VectorUtil.angleBetween(desiredDir, targetDir));
        if (angleDeg > offAxisDeg) {
            Vec3 clampedPoint = ywzj_rvp$clampAimToOffAxisBoundary(aimFrom, worldPos, desiredDir, offAxisDeg);
            if (leadSolution != null) {
                ywzj_rvp$clearAimAssist(self);
                ywzj_rvp$aimAlongDirection(self, clampedPoint.subtract(aimFrom));
            } else {
                Vec3 smoothedPoint = ywzj_rvp$smoothAimAssist(self, aimFrom, desiredDir, clampedPoint, angleDeg - offAxisDeg);
                self.aim(smoothedPoint);
            }
            return;
        }
        ywzj_rvp$clearAimAssist(self);
    }

    private static Vec3 ywzj_rvp$clampAimToOffAxisBoundary(Vec3 aimFrom, Vec3 worldPos, Vec3 desiredDir, float offAxisDeg) {
        Vec3 targetDirNorm = worldPos.subtract(aimFrom).normalize();
        Vec3 desiredDirNorm = desiredDir.normalize();
        double dot = Mth.clamp(targetDirNorm.dot(desiredDirNorm), -1.0, 1.0);
        Vec3 tangent = desiredDirNorm.subtract(targetDirNorm.scale(dot));

        // Degenerate case: current aim is almost exactly on/against target axis.
        if (tangent.lengthSqr() < 1.0E-6) {
            return worldPos;
        }

        tangent = tangent.normalize();
        double offAxisRad = Math.toRadians(offAxisDeg);
        Vec3 boundaryDir = targetDirNorm.scale(Math.cos(offAxisRad)).add(tangent.scale(Math.sin(offAxisRad))).normalize();
        return aimFrom.add(boundaryDir.scale(worldPos.distanceTo(aimFrom)));
    }

    private static Vec3 ywzj_rvp$smoothAimAssist(WeaponUnit self, Vec3 aimFrom, Vec3 desiredDir,
                                                 Vec3 targetPoint, double overflowDeg) {
        int nowTick = self.getVehicle().tickCount;
        int key = ywzj_rvp$key(self);
        RVP_AimAssistState state = YWZJ_RVP$AIM_ASSIST_STATES.computeIfAbsent(key, unused -> new RVP_AimAssistState());
        Vec3 desiredDirNorm = desiredDir.normalize();
        Vec3 targetDir = targetPoint.subtract(aimFrom);
        if (desiredDirNorm.lengthSqr() < 1.0E-6 || targetDir.lengthSqr() < 1.0E-6) {
            return targetPoint;
        }
        Vec3 targetDirNorm = targetDir.normalize();
        double targetDistance = targetDir.length();
        if (!state.initialized || nowTick - state.lastTick > YWZJ_RVP$AIM_ASSIST_STALE_TICKS) {
            state.currDir = desiredDirNorm;
            state.currDistance = targetDistance;
            state.initialized = true;
        } else if (state.lastTick != nowTick) {
            double dirErrorDeg = Math.toDegrees(VectorUtil.angleBetween(state.currDir, targetDirNorm));
            double alpha = Mth.clamp(
                    YWZJ_RVP$AIM_ASSIST_ALPHA_BASE + dirErrorDeg * YWZJ_RVP$AIM_ASSIST_ALPHA_SCALE + overflowDeg * 0.02D,
                    YWZJ_RVP$AIM_ASSIST_ALPHA_BASE,
                    YWZJ_RVP$AIM_ASSIST_ALPHA_MAX
            );
            state.currDir = state.currDir.lerp(targetDirNorm, alpha).normalize();
            state.currDistance += (targetDistance - state.currDistance) * alpha;
        }
        state.lastTick = nowTick;
        return aimFrom.add(state.currDir.scale(state.currDistance));
    }

    private static Vec3 ywzj_rvp$aimOrigin(WeaponUnit self) {
        AimContext aimContext = self.aimContext();
        if (aimContext != null && aimContext.from != null) {
            return aimContext.from;
        }
        return self.worldCurrentBoltPosition();
    }

    private static void ywzj_rvp$aimAlongDirection(WeaponUnit self, Vec3 desiredDir) {
        if (desiredDir.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 aimFrom = ywzj_rvp$aimOrigin(self);
        self.aim(aimFrom.add(desiredDir.normalize().scale(4096.0)));
    }

    private static void ywzj_rvp$clearAimAssist(WeaponUnit self) {
        YWZJ_RVP$AIM_ASSIST_STATES.remove(ywzj_rvp$key(self));
    }

    private static int ywzj_rvp$key(WeaponUnit self) {
        return self.getVehicle().getId() * 257 + self.getIndex();
    }
}
