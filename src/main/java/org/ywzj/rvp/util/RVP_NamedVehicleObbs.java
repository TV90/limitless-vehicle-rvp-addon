package org.ywzj.rvp.util;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.util.*;

/** 双端共用的具名受击 OBB；TV 选点和爆炸弱点使用同一份几何及稳定键。 */
public final class RVP_NamedVehicleObbs {
    /** @param bone 配置与结构模型中的骨名 @param index 该骨自身及匿名子骨中的稳定 cube 顺序 */
    public record Key(String bone, int index) {}
    /** @param key 稳定身份 @param obb 当前世界姿态下的受击体积 */
    public record Box(Key key, OBB obb) {
        public Vec3 center() { return new Vec3(obb.center()); }
        public double volume() {
            Vector3f e = obb.extents();
            return 8.0 * e.x * e.y * e.z;
        }
    }
    /** @param box 命中的具名体积 @param point 真实射线进入点 @param distanceSquared 到射线起点的距离平方 */
    public record Hit(Box box, Vec3 point, double distanceSquared) {}

    private RVP_NamedVehicleObbs() {}

    /** 全部受击骨参与最近判定，未配置弱点的车体同样可挡住后面的弱点；不扫描纯物理主包围盒。 */
    public static List<Box> collect(AbstractVehicle vehicle) {
        BedrockModel model = model(vehicle);
        if (model == null || vehicle.remote) return List.of();
        List<Box> result = new ArrayList<>();
        for (String name : new TreeSet<>(model.getBoneMap().keySet())) {
            result.addAll(resolveBone(vehicle, model, name));
        }
        return result;
    }

    /** 已选定的键只解析单个骨，不逐 tick 重扫整车；炮塔/炮管转动后仍追踪同一块。 */
    @Nullable
    public static Box resolve(AbstractVehicle vehicle, @Nullable Key key) {
        if (key == null || key.index < 0 || key.index > 4096 || vehicle.remote) return null;
        BedrockModel model = model(vehicle);
        if (model == null) return null;
        List<Box> boxes = resolveBone(vehicle, model, key.bone);
        return boxes.stream().filter(box -> box.key().equals(key)).findFirst().orElse(null);
    }

    @Nullable
    private static BedrockModel model(AbstractVehicle vehicle) {
        // 调用已同步的载具扩展配置和本体结构模型入口，不依赖显示模型或骨名白名单。
        var id = RVP_VehicleExtendedConfigManager.INSTANCE.get(vehicle).structureModel();
        return id == null ? null : CommonAssetsManager.structureModelManager().getStructureModel(id).orElse(null);
    }

    private static List<Box> resolveBone(AbstractVehicle vehicle, BedrockModel model, String name) {
        BedrockBone bone = model.getBoneMap().get(name);
        if (bone == null || name.equals("main_structure")) return List.of();
        // 已消耗爆反沿用直击穿透规则，不能继续挡住后方 OBB。
        if (org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.INSTANCE.isModuleDestroyed(
                vehicle.getUUID(), name, org.ywzj.rvp.vehicle.BoneModuleType.ERA)) return List.of();
        var physics = RVP_VehicleExtendedConfigManager.INSTANCE.get(vehicle).physicsOnlyBones();
        for (String excluded : physics) {
            BedrockBone parent = model.getBoneMap().get(excluded);
            for (BedrockBone b = bone; b != null; b = b.parent) if (b == parent) return List.of();
        }
        Set<BedrockBone> named = new HashSet<>(model.getBoneMap().values());
        VehicleCubeGroup group = null;
        int bestDepth = Integer.MAX_VALUE;
        // 结构骨的子骨也要继承实时炮塔姿态，不能回退到静态 bind pose。
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.getData() == null) continue;
            String structure = part.getData().getStructureBone();
            if (structure == null) continue;
            BedrockBone root = model.getBoneMap().get(structure);
            List<BedrockBone> path = path(root, bone);
            if (path == null || path.size() >= bestDepth) continue;
            if (part.isDetached()) return List.of();
            VehicleCubeGroup candidate = follow(part.getStructureGroup(), path);
            if (candidate != null) { group = candidate; bestDepth = path.size(); }
        }
        List<OBB> geometry = new ArrayList<>();
        if (group == null) {
            // 调用本体静态骨变换；车体仍实时跟随载具位置/旋转，具名子骨不会重复计入父骨。
            for (OBB.CubeOBB cube : OBB.getOBBsFromBone(bone, vehicle, new HashSet<>(named))) geometry.add(cube.obb());
        } else {
            collectLive(vehicle, bone, group, named, geometry);
        }
        List<Box> result = new ArrayList<>();
        for (int i = 0; i < geometry.size(); i++) {
            OBB obb = geometry.get(i);
            if (obb.extents().x > 0 && obb.extents().y > 0 && obb.extents().z > 0)
                result.add(new Box(new Key(name, i), obb));
        }
        return result;
    }

    @Nullable
    private static List<BedrockBone> path(@Nullable BedrockBone root, BedrockBone child) {
        if (root == null) return null;
        List<BedrockBone> result = new ArrayList<>();
        for (BedrockBone b = child; b != null; b = b.parent) {
            if (b == root) { Collections.reverse(result); return result; }
            result.add(b);
        }
        return null;
    }

    @Nullable
    private static VehicleCubeGroup follow(@Nullable VehicleCubeGroup group, List<BedrockBone> path) {
        // 本体按 Bedrock 子骨顺序构建并复制 group.children，沿相同层级恢复实例对应关系。
        for (BedrockBone child : path) {
            int index = new ArrayList<>(child.parent.getChildren()).indexOf(child);
            if (group == null || index < 0 || index >= group.children.size()) return null;
            group = group.children.get(index);
        }
        return group;
    }

    private static void collectLive(AbstractVehicle vehicle, BedrockBone bone, VehicleCubeGroup group,
                                    Set<BedrockBone> named, List<OBB> out) {
        for (BedrockCube cube : bone.cubes) {
            // 调用与本体 VehicleCubeOBB.update 相同的坐标组合，不向实时 group 添加临时 cube。
            out.add(liveBox(vehicle.position(), vehicle.centerOffset, vehicle.rotYXZ(), group, cube));
        }
        int index = 0;
        for (BedrockBone child : bone.getChildren()) {
            if (!named.contains(child) && index < group.children.size()) collectLive(vehicle, child, group.children.get(index), named, out);
            index++;
        }
    }

    /** 与本体实时部件坐标同源的纯变换入口，供旋转/移动回归使用。 */
    public static OBB liveBox(Vec3 position, Vec3 centerOffset, Quaternionf vehicleRotation,
                              VehicleCubeGroup group, BedrockCube cube) {
        var transform = group.globalTransform();
        Quaternionf localRotation = new Quaternionf(transform.rotation());
        Vector3f center = localRotation.transform(new Vector3f(cube.x() + cube.width() / 2,
                cube.y() + cube.height() / 2, cube.z() + cube.depth() / 2));
        Vec3 offset = transform.offset().add(center.x, center.y, center.z).subtract(centerOffset);
        Vector3f rotated = new Quaternionf(vehicleRotation).transform(offset.toVector3f());
        Vec3 world = position.add(centerOffset).add(rotated.x, rotated.y, rotated.z);
        return new OBB(world.toVector3f(), new Vector3f(cube.width() / 2, cube.height() / 2, cube.depth() / 2),
                new Quaternionf(vehicleRotation).mul(localRotation));
    }

    /** 只选射线先遇到的 OBB，避免 AABB 空洞或被遮住的薄弱点被点选。 */
    @Nullable
    public static Hit raycast(List<Box> boxes, Vec3 start, Vec3 end) {
        Hit best = null;
        for (Box box : boxes) {
            var hit = box.obb.clip(start.toVector3f(), end.toVector3f());
            if (hit.isEmpty()) continue;
            Vec3 point = new Vec3(hit.get());
            double distance = point.distanceToSqr(start);
            if (best == null || distance < best.distanceSquared - 1.0E-6
                    || Math.abs(distance - best.distanceSquared) <= 1.0E-6 && box.volume() < best.box.volume())
                best = new Hit(box, point, distance);
        }
        return best;
    }

    /** 到真实 OBB 实体表面的最短距离；同距重叠时取较小体积，不按骨中心距离或只扫弱点。 */
    @Nullable
    public static Box nearest(List<Box> boxes, Vec3 point) {
        Box best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Box box : boxes) {
            double d = point.distanceToSqr(new Vec3(OBB.getClosestPointOBB(point.toVector3f(), box.obb)));
            if (d < distance - 1.0E-6 || Math.abs(d - distance) <= 1.0E-6 && (best == null || box.volume() < best.volume())) {
                best = box; distance = d;
            }
        }
        return best;
    }
}
