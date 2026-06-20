package org.ywzj.rvp.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_MachinegunLeadState;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSoftRfMixin {
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
            self.aim(worldPos);
            return;
        }
        WeaponUnitData data = self.getData();
        if (!(data instanceof WeaponUnitDataExt ext)) {
            self.aim(worldPos);
            return;
        }
        if (!"rvp_rf".equalsIgnoreCase(ext.ywzj_rvp$getFireControlMode())) {
            self.aim(worldPos);
            return;
        }
        float offAxisDeg = Mth.clamp(ext.ywzj_rvp$getRfOffAxisDeg(), 0.0f, 89.0f);
        if (offAxisDeg <= 0.0f) {
            self.aim(worldPos);
            return;
        }
        RVP_LeadSolution leadSolution = RVP_MachinegunLeadState.smooth(
                self,
                RVP_MachinegunLeadSolver.solveCurrent(self, 1.0f),
                1.0f
        );
        if (leadSolution != null) {
            worldPos = leadSolution.leadWorldPos();
            boolean scopedAndZoomed = LocalVehiclePlayer.instance != null
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE
                    && self.getZoom() > 1.0f;
            offAxisDeg *= scopedAndZoomed ? 0.01f : 0.1f;
        }
        Vec3 aimFrom = self.worldPivotPosition();
        Vec3 targetDir = worldPos.subtract(aimFrom);
        if (targetDir.lengthSqr() < 1.0E-6) {
            return;
        }
        // Clamp the operator's requested aim, not the turret's current pose.
        Vec3 desiredDir = self.worldVec(self.getXAimRot(), self.getYAimRot());
        double angleDeg = Math.toDegrees(VectorUtil.angleBetween(desiredDir, targetDir));
        if (angleDeg > offAxisDeg) {
            Vec3 clampedPoint = ywzj_rvp$clampAimToOffAxisBoundary(aimFrom, worldPos, desiredDir, offAxisDeg);
            self.aim(clampedPoint);
        }
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
}
