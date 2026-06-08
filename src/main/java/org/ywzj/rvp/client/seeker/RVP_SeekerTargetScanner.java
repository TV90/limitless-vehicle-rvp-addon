package org.ywzj.rvp.client.seeker;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds lock candidates for RVP IR / SARH seeker HUD (screen-centre FOV cone).
 */
public final class RVP_SeekerTargetScanner {

    private RVP_SeekerTargetScanner() {}

    public record Candidate(Entity entity, double screenDistSq, double boreAngleDeg) {}

    public static List<Candidate> listCandidates(
            WeaponUnit weaponUnit,
            RVP_WeaponData weaponData,
            RVP_EnumGuidanceType mode,
            double screenCenterX,
            double screenCenterY
    ) {
        List<Candidate> out = new ArrayList<>();
        if (weaponUnit == null || weaponData == null || mode == RVP_EnumGuidanceType.NONE) {
            return out;
        }
        float fov = RVP_SeekerWeaponUtil.seekerFov(weaponData);
        float range = RVP_SeekerWeaponUtil.seekerRange(weaponData);
        WeaponUnit operatorUnit = resolveOperatorUnit(weaponUnit);
        Vec3 aimOrigin = RVP_SeekerGeometry.aimOrigin(operatorUnit);
        Vec3 aimDir = RVP_SeekerGeometry.aimDirection(operatorUnit);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return out;
        }

        if (mode == RVP_EnumGuidanceType.SARH) {
            collectSarhRadarCandidates(out, operatorUnit, weaponData, aimOrigin, aimDir, fov, range,
                    screenCenterX, screenCenterY);
            out.sort(Comparator.comparingDouble(Candidate::screenDistSq));
            return out;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isIrCandidate(entity, camera.getEntity(), vehicle)) {
                continue;
            }
            if (!isWithinCone(entity, aimOrigin, aimDir, fov, range)) {
                continue;
            }
            if (!RVP_SeekerTargetValidator.isValidIrTarget(weaponUnit, entity, weaponData)) {
                continue;
            }
            addIfVisible(out, entity, aimOrigin, aimDir, fov, range, screenCenterX, screenCenterY);
        }
        out.sort(Comparator.comparingDouble(Candidate::screenDistSq));
        return out;
    }

    public static Entity pickAutoTarget(
            WeaponUnit weaponUnit,
            RVP_WeaponData weaponData,
            RVP_EnumGuidanceType mode,
            double screenCenterX,
            double screenCenterY
    ) {
        List<Candidate> candidates = listCandidates(weaponUnit, weaponData, mode, screenCenterX, screenCenterY);
        if (candidates.isEmpty()) {
            return null;
        }
        if (mode == RVP_EnumGuidanceType.IR) {
            return candidates.stream()
                    .min(Comparator.comparingDouble(Candidate::boreAngleDeg))
                    .map(Candidate::entity)
                    .orElse(null);
        }
        return candidates.get(0).entity();
    }

    public static Entity cycleTarget(
            WeaponUnit weaponUnit,
            RVP_WeaponData weaponData,
            RVP_EnumGuidanceType mode,
            Entity current,
            double screenCenterX,
            double screenCenterY
    ) {
        List<Candidate> candidates = listCandidates(weaponUnit, weaponData, mode, screenCenterX, screenCenterY);
        if (candidates.isEmpty()) {
            return null;
        }
        if (current == null) {
            return candidates.get(0).entity();
        }
        int currentIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).entity().getId() == current.getId()) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            return candidates.get(0).entity();
        }
        if (candidates.get(0).entity().getId() != current.getId()) {
            return candidates.get(0).entity();
        }
        int next = currentIndex + 1;
        if (next >= candidates.size()) {
            next = 0;
        }
        return candidates.get(next).entity();
    }

    private static WeaponUnit resolveOperatorUnit(WeaponUnit weaponUnit) {
        WeaponUnit operator = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.getWeaponUnit();
        if (operator != null) {
            return operator.getRootParentWeaponUnit();
        }
        if (weaponUnit != null) {
            return weaponUnit.getRootParentWeaponUnit();
        }
        return weaponUnit;
    }

    private static void collectSarhRadarCandidates(
            List<Candidate> out,
            WeaponUnit operatorUnit,
            RVP_WeaponData weaponData,
            Vec3 aimOrigin,
            Vec3 aimDir,
            float fov,
            float range,
            double screenCenterX,
            double screenCenterY
    ) {
        RadarUnit radar = RVP_SeekerWeaponUtil.resolveFireControlRadar(operatorUnit);
        if (radar == null) {
            return;
        }
        float lockMinHeight = RVP_SeekerWeaponUtil.resolveSeeker(weaponData).getLockMinHeight();
        AbstractVehicle ownVehicle = operatorUnit.getVehicle();
        for (RadarUnit.DetectedObject detected : radar.getDetectedEntities().values()) {
            Entity entity = detected.entity;
            if (!isSarhCandidate(entity, ownVehicle, lockMinHeight)) {
                continue;
            }
            addIfVisible(out, entity, aimOrigin, aimDir, fov, range, screenCenterX, screenCenterY);
        }
    }

    private static boolean isSarhCandidate(Entity entity, AbstractVehicle ownVehicle, float lockMinHeight) {
        if (entity == null || !entity.isAlive() || entity == ownVehicle) {
            return false;
        }
        if (entity.getVehicle() != null || entity instanceof PartEntity<?> || entity.isSpectator()) {
            return false;
        }
        if (entity.getBoundingBox().getSize() < 1) {
            return false;
        }
        return lockMinHeight <= 0f || !RVP_GuidanceMath.isOnGround(entity, lockMinHeight);
    }

    private static void addIfVisible(
            List<Candidate> out,
            Entity entity,
            Vec3 aimOrigin,
            Vec3 aimDir,
            float fov,
            float range,
            double screenCenterX,
            double screenCenterY
    ) {
        if (!isWithinCone(entity, aimOrigin, aimDir, fov, range)) {
            return;
        }
        Vec3 screen = VectorUtil.worldToScreen(entity.getBoundingBox().getCenter());
        if (screen.z < 0) {
            return;
        }
        double dx = screen.x - screenCenterX;
        double dy = screen.y - screenCenterY;
        double boreAngle = Math.toDegrees(VectorUtil.angleBetween(
                aimDir,
                entity.getBoundingBox().getCenter().subtract(aimOrigin)));
        out.add(new Candidate(entity, dx * dx + dy * dy, boreAngle));
    }

    private static boolean isWithinCone(Entity entity, Vec3 origin, Vec3 aimDir, float fovDeg, float range) {
        if (origin.distanceToSqr(entity.position()) > range * range) {
            return false;
        }
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(origin);
        if (toTarget.lengthSqr() < 1.0E-6) {
            return false;
        }
        double angle = Math.toDegrees(VectorUtil.angleBetween(aimDir, toTarget));
        return angle <= fovDeg;
    }

    private static boolean isIrCandidate(Entity entity, Entity cameraEntity, AbstractVehicle vehicle) {
        if (entity == null || !entity.isAlive() || entity == cameraEntity || entity == vehicle) {
            return false;
        }
        if (entity.getVehicle() != null || entity instanceof PartEntity<?> || entity.isSpectator()) {
            return false;
        }
        return entity.getBoundingBox().getSize() >= 1.0;
    }

}
