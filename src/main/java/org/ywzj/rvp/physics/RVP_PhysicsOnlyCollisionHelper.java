package org.ywzj.rvp.physics;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 纯物理碰撞体积（physics-only）管理。
 *
 * <p>“仅物理”碰撞盒按载具实例存于弱引用侧表，替代被删
 * {@code AbstractVehiclePhysicsOnlyCollisionMixin} 注入的 {@code rvp$physicsOnlyCubes}
 * 字段；重建/每 tick 更新由 {@code RVP_PhysicsOnlyCollisionEventHandler} 驱动
 * （载具加入世界重建、每 tick 更新、离开世界清理）。</p>
 */
public final class RVP_PhysicsOnlyCollisionHelper {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double EPSILON = 1.0E-6;

    private static final Map<AbstractVehicle, List<VehicleCubeOBB>> PHYSICS_ONLY_CUBES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_PhysicsOnlyCollisionHelper() {}

    public static List<VehicleCubeOBB> getPhysicsOnlyCubes(AbstractVehicle vehicle) {
        List<VehicleCubeOBB> cubes = PHYSICS_ONLY_CUBES.get(vehicle);
        return cubes == null ? List.of() : cubes;
    }

    public static void rebuildPhysicsOnlyCubes(AbstractVehicle vehicle) {
        try {
            List<VehicleCubeOBB> cubes = buildPhysicsOnlyCubes(vehicle);
            stripPhysicsOnlyBodyCubes(vehicle, cubes);
            if (cubes.isEmpty()) {
                PHYSICS_ONLY_CUBES.remove(vehicle);
            } else {
                PHYSICS_ONLY_CUBES.put(vehicle, cubes);
            }
            updatePhysicsOnlyCubes(vehicle);
            LOGGER.info("[RVP-PhysicsOnly] {} 重建完成: physicsOnlyCubes={} bodyCubes剩余={}", vehicle.getVehicleId(),
                    cubes.size(), vehicle.getVehicleCubeOBBs().size());
        } catch (Throwable t) {
            // 防御：任何异常都不允许破坏载具本体（已从车体剔除的 cube 无法回滚，仅影响碰撞盒精度）。
            LOGGER.error("[RVP-PhysicsOnly] {} rebuildPhysicsOnlyCubes 异常（已忽略）", vehicle.getVehicleId(), t);
            PHYSICS_ONLY_CUBES.remove(vehicle);
        }
    }

    public static void updatePhysicsOnlyCubes(AbstractVehicle vehicle) {
        for (VehicleCubeOBB cube : getPhysicsOnlyCubes(vehicle)) {
            cube.update(vehicle);
        }
    }

    /** 每 tick 更新所有已登记载具的 physics-only 盒（服务端，由事件处理器调用）。 */
    public static void tickVehicles(ServerLevel level) {
        if (PHYSICS_ONLY_CUBES.isEmpty()) {
            return;
        }
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractVehicle vehicle) {
                updatePhysicsOnlyCubes(vehicle);
            }
        }
    }

    /** 每 tick 更新所有已登记载具的 physics-only 盒（客户端，由事件处理器调用）。 */
    @OnlyIn(Dist.CLIENT)
    public static void tickClientVehicles(ClientLevel level) {
        if (PHYSICS_ONLY_CUBES.isEmpty()) {
            return;
        }
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle) {
                updatePhysicsOnlyCubes(vehicle);
            }
        }
    }

    /** 载具离开世界：清理弱引用侧表条目。 */
    public static void onVehicleLeave(AbstractVehicle vehicle) {
        PHYSICS_ONLY_CUBES.remove(vehicle);
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
            LOGGER.info("[RVP-PhysicsOnly] {} 无 physics-only 配置（structureModel={} bones={}）",
                    vehicle.getVehicleId(), cfg.structureModel(), cfg.physicsOnlyBones());
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
        LOGGER.info("[RVP-PhysicsOnly] {} 模型 {} 加载成功，physics_only_bone 提取 cubes={}", vehicle.getVehicleId(),
                cfg.structureModel(), cubes.size());
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
