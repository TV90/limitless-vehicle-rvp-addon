package org.ywzj.rvp.physics;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.ext.RVPPhysicsOnlyCollisionAccess;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class RVP_PhysicsOnlyCollisionHelper {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double EPSILON = 1.0E-6;

    private RVP_PhysicsOnlyCollisionHelper() {}

    public static List<VehicleCubeOBB> getPhysicsOnlyCubes(AbstractVehicle vehicle) {
        if (vehicle instanceof RVPPhysicsOnlyCollisionAccess access) {
            return access.rvp$getPhysicsOnlyCubes();
        }
        return List.of();
    }

    public static void rebuildPhysicsOnlyCubes(AbstractVehicle vehicle) {
        if (!(vehicle instanceof RVPPhysicsOnlyCollisionAccess access)) {
            return;
        }
        List<VehicleCubeOBB> cubes = buildPhysicsOnlyCubes(vehicle);
        stripPhysicsOnlyBodyCubes(vehicle, cubes);
        access.rvp$setPhysicsOnlyCubes(cubes);
        updatePhysicsOnlyCubes(vehicle);
    }

    public static void updatePhysicsOnlyCubes(AbstractVehicle vehicle) {
        for (VehicleCubeOBB cube : getPhysicsOnlyCubes(vehicle)) {
            cube.update(vehicle);
        }
    }

    public static Vec3 closestNonPhysicsOnlyHitPosition(AbstractVehicle vehicle, Vec3 start, Vec3 end) {
        if (vehicle == null) {
            return null;
        }
        List<VehicleCubeOBB> physicsOnlyCubes = getPhysicsOnlyCubes(vehicle);
        double minDistance = Double.MAX_VALUE;
        Vec3 minDistanceHitPos = null;
        for (OBB obb : vehicle.getOBBs()) {
            if (isPhysicsOnlyObb(obb, physicsOnlyCubes)) {
                continue;
            }
            Vec3 hitPos = obb.clip(start.toVector3f(), end.toVector3f()).map(Vec3::new).orElse(null);
            if (hitPos == null) {
                continue;
            }
            double distance = hitPos.distanceTo(start);
            if (distance < minDistance) {
                minDistance = distance;
                minDistanceHitPos = hitPos;
            }
        }
        return minDistanceHitPos;
    }

    public static Vec3 closestPhysicsOnlyHitPosition(AbstractVehicle vehicle, Vec3 start, Vec3 end) {
        double minDistance = Double.MAX_VALUE;
        Vec3 minDistanceHitPos = null;
        for (VehicleCubeOBB cube : getPhysicsOnlyCubes(vehicle)) {
            if (cube == null || cube.obb() == null) {
                continue;
            }
            Vec3 hitPos = cube.obb().clip(start.toVector3f(), end.toVector3f()).map(Vec3::new).orElse(null);
            if (hitPos == null) {
                continue;
            }
            double distance = hitPos.distanceTo(start);
            if (distance < minDistance) {
                minDistance = distance;
                minDistanceHitPos = hitPos;
            }
        }
        return minDistanceHitPos;
    }

    public static boolean isInsidePhysicsOnlyVolume(AbstractVehicle vehicle, Vec3 point) {
        return isInsidePhysicsOnlyVolume(getPhysicsOnlyCubes(vehicle), point);
    }

    public static boolean isInsidePhysicsOnlyVolume(List<VehicleCubeOBB> physicsOnlyCubes, Vec3 point) {
        if (point == null || physicsOnlyCubes == null || physicsOnlyCubes.isEmpty()) {
            return false;
        }
        for (VehicleCubeOBB cube : physicsOnlyCubes) {
            if (cube != null && cube.obb() != null && containsWithEpsilon(cube.obb(), point, 1.0E-4f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhysicsOnlyObb(OBB obb, List<VehicleCubeOBB> physicsOnlyCubes) {
        if (obb == null || physicsOnlyCubes == null || physicsOnlyCubes.isEmpty()) {
            return false;
        }
        for (VehicleCubeOBB cube : physicsOnlyCubes) {
            if (cube != null && sameObb(obb, cube.obb())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWithEpsilon(OBB obb, Vec3 point, float epsilon) {
        Vector3f rel = new Vector3f(point.toVector3f()).sub(obb.center());
        Vector3f[] axes = obb.getAxes();
        float projX = Math.abs(rel.dot(axes[0]));
        float projY = Math.abs(rel.dot(axes[1]));
        float projZ = Math.abs(rel.dot(axes[2]));
        return projX <= obb.extents().x + epsilon
                && projY <= obb.extents().y + epsilon
                && projZ <= obb.extents().z + epsilon;
    }

    private static boolean sameObb(OBB left, OBB right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return sameVector(left.center(), right.center())
                && sameVector(left.extents(), right.extents())
                && sameQuaternion(left.rotation(), right.rotation());
    }

    private static List<VehicleCubeOBB> buildPhysicsOnlyCubes(AbstractVehicle vehicle) {
        var cfg = RVP_VehicleExtendedConfigManager.INSTANCE.get(vehicle);
        if (!cfg.hasPhysicsOnlyBones()) {
            return List.of();
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(cfg.structureModel()).orElse(null);
        if (model == null) {
            LOGGER.warn("[RVP][PhysicsOnly] Missing structure model {} for {}", cfg.structureModel(), vehicle.getVehicleId());
            return List.of();
        }
        List<VehicleCubeOBB> cubes = new ArrayList<>();
        Set<BedrockBone> visitedRoots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String boneName : cfg.physicsOnlyBones()) {
            BedrockBone bone = model.getBoneMap().get(boneName);
            if (bone == null) {
                LOGGER.warn("[RVP][PhysicsOnly] Missing physics_only_bone {} on {}", boneName, vehicle.getVehicleId());
                continue;
            }
            if (!visitedRoots.add(bone)) {
                continue;
            }
            VehicleCubeGroup group = buildGroupChain(bone);
            collectRecursive(bone, group, cubes);
        }
        return cubes;
    }

    private static void stripPhysicsOnlyBodyCubes(AbstractVehicle vehicle, List<VehicleCubeOBB> physicsOnlyCubes) {
        if (physicsOnlyCubes.isEmpty()) {
            return;
        }
        List<VehicleCubeOBB> vehicleBodyCubes = vehicle.getVehicleCubeOBBs();
        if (vehicleBodyCubes.isEmpty()) {
            return;
        }
        vehicleBodyCubes.removeIf(bodyCube -> matchesAnyPhysicsCube(bodyCube, physicsOnlyCubes));
    }

    private static boolean matchesAnyPhysicsCube(VehicleCubeOBB bodyCube, List<VehicleCubeOBB> physicsOnlyCubes) {
        for (VehicleCubeOBB physicsCube : physicsOnlyCubes) {
            if (sameCubeTemplate(bodyCube, physicsCube)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCubeTemplate(VehicleCubeOBB left, VehicleCubeOBB right) {
        return nearlyEquals(left.x, right.x)
                && nearlyEquals(left.y, right.y)
                && nearlyEquals(left.z, right.z)
                && nearlyEquals(left.width, right.width)
                && nearlyEquals(left.height, right.height)
                && nearlyEquals(left.depth, right.depth)
                && sameVec(left.offset(), right.offset())
                && sameGroupChain(left.group, right.group);
    }

    private static boolean sameGroupChain(VehicleCubeGroup left, VehicleCubeGroup right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return sameVec(left.pivot, right.pivot)
                && sameQuaternion(left.rotation, right.rotation)
                && sameGroupChain(left.parent, right.parent);
    }

    private static boolean sameQuaternion(Quaternionf left, Quaternionf right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return nearlyEquals(left.x, right.x)
                && nearlyEquals(left.y, right.y)
                && nearlyEquals(left.z, right.z)
                && nearlyEquals(left.w, right.w);
    }

    private static boolean sameVec(Vec3 left, Vec3 right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return nearlyEquals(left.x, right.x)
                && nearlyEquals(left.y, right.y)
                && nearlyEquals(left.z, right.z);
    }

    private static boolean sameVector(Vector3f left, Vector3f right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return nearlyEquals(left.x, right.x)
                && nearlyEquals(left.y, right.y)
                && nearlyEquals(left.z, right.z);
    }

    private static boolean nearlyEquals(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }

    private static VehicleCubeGroup buildGroupChain(BedrockBone bone) {
        VehicleCubeGroup parent = bone.parent == null ? null : buildGroupChain(bone.parent);
        return new VehicleCubeGroup(parent, new Quaternionf(bone.rotation), pivotOf(bone));
    }

    private static void collectRecursive(BedrockBone bone, VehicleCubeGroup group, List<VehicleCubeOBB> cubes) {
        for (BedrockCube cube : bone.cubes) {
            cubes.add(VehicleCubeOBB.init(group, cube));
        }
        for (BedrockBone child : bone.getChildren()) {
            VehicleCubeGroup childGroup = new VehicleCubeGroup(group, new Quaternionf(child.rotation), pivotOf(child));
            collectRecursive(child, childGroup, cubes);
        }
    }

    private static Vec3 pivotOf(BedrockBone bone) {
        return new Vec3(bone.x / 16.0, bone.y / 16.0, bone.z / 16.0);
    }
}
