package org.ywzj.rvp.guidance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.ecm.RVP_EcmIff;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.rvp.server.hitl.RVP_HitlRadioPath;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** 游戏内图像置信度，用既有方块、烟幕和实体边界模拟导引头的有效视野。 */
public final class RVP_OpticalTargeting {
    /** 单次扫描最多复核的近轴候选，限制拥挤场景的射线成本。 */
    private static final int MAX_CANDIDATES = 16;
    /** 单轮 HUD 可见度检查的候选上限。 */
    private static final int MAX_DISPLAY_CANDIDATES = 15;

    private RVP_OpticalTargeting() {}

    public record View(Vec3 origin, Vec3 forward) {}
    public record Contact(Entity entity, Vec3 point, float confidence) {}
    /** 缓存该候选的排序分数，避免在比较时反复投影。 */
    private record RankedCandidate(Entity entity, double priority) {}

    public static boolean isOptical(RVP_EnumGuidanceType type) {
        return type == RVP_EnumGuidanceType.TV || type == RVP_EnumGuidanceType.ATV;
    }

    public static View gunnerView(WeaponUnit unit) {
        // 调用与本体 SCOPE 相同的观瞄位置和武器站世界姿态。
        Vec3 origin = unit.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                ? unit.worldOwnerViewPosition(1f) : unit.worldOpticalSightPosition(1f);
        return new View(origin, unit.worldVec(unit.getXAimRot(), unit.getYAimRot()).normalize());
    }

    public static boolean isCandidate(Entity seeker, @Nullable AbstractVehicle carrier, Entity target, boolean automatic) {
        if (target == seeker || !target.isAlive() || target.isSpectator() || !target.isPickable()
                || target instanceof VehiclePart
                || !(target instanceof LivingEntity || target instanceof AbstractVehicle)) return false;
        Entity root = target.getRootVehicle();
        if (root == seeker.getRootVehicle() || carrier != null && root == carrier.getRootVehicle()) return false;
        // 驾驶员和乘员随载具整体识别，下车后才生成独立人员框。
        if (target instanceof LivingEntity && (target.getVehicle() instanceof AbstractVehicle
                || root instanceof AbstractVehicle)) return false;
        if (!automatic) return true;
        // ATV 可识别中立和空载车辆，阵营排除只使用本模式的友方判定。
        return target instanceof AbstractVehicle other && !RVP_EcmIff.isFriendlyOpticalTarget(carrier, other);
    }

    public static List<Contact> scan(Entity seeker, @Nullable AbstractVehicle carrier, View view,
            RVP_Range<Float> distance, double angle, boolean automatic) {
        return scan(seeker, carrier, view, distance, angle, automatic,
                target -> -alignment(view, target.getBoundingBox().getCenter()), MAX_CANDIDATES, false);
    }

    /** 画面候选按距屏幕准星的平方距离排序，无投影的实体返回正无穷并跳过。 */
    public static List<Contact> scanDisplayed(Entity seeker, @Nullable AbstractVehicle carrier, View view,
            RVP_Range<Float> distance, double angle, ToDoubleFunction<Entity> screenDistance) {
        return scan(seeker, carrier, view, distance, angle, false, screenDistance, MAX_DISPLAY_CANDIDATES, true);
    }

    private static List<Contact> scan(Entity seeker, @Nullable AbstractVehicle carrier, View view,
            RVP_Range<Float> distance, double angle, boolean automatic, ToDoubleFunction<Entity> priority,
            int limit, boolean checkVehicleOcclusion) {
        double radius = RVP_GuidanceRuntimeGeometry.resolveScanRadius(distance);
        Vec3 end = view.origin().add(view.forward().scale(radius));
        double width = radius * Math.sin(Math.toRadians(Math.min(90.0, angle)));
        AABB region = new AABB(view.origin(), end).inflate(Math.max(1.0, width));
        List<AbstractVehicle> foreground = checkVehicleOcclusion
                ? seeker.level().getEntities(seeker, region, entity -> entity instanceof AbstractVehicle
                        && (isCandidate(seeker, carrier, entity, false)
                        || entity instanceof VehiclePart && entity.isAlive() && entity.isPickable()))
                        .stream().map(AbstractVehicle.class::cast).toList()
                : List.of();
        // 先按视场收窄并排序，仅给最靠近准星的有限候选计算可见度。
        return seeker.level().getEntities(seeker, region, target -> isCandidate(seeker, carrier, target, automatic))
                .stream().filter(target -> inView(view, target.getBoundingBox().getCenter(), distance, angle))
                .map(target -> new RankedCandidate(target, priority.applyAsDouble(target)))
                .filter(candidate -> Double.isFinite(candidate.priority()))
                .sorted(Comparator.comparingDouble(RankedCandidate::priority)
                        .thenComparingInt(candidate -> candidate.entity().getId()))
                .limit(limit)
                .map(candidate -> sample(seeker, view, candidate.entity(), distance, angle, foreground))
                .filter(contact -> contact.confidence() > 0f).toList();
    }

    public static Contact sample(Entity seeker, View view, Entity target, RVP_Range<Float> distance, double angle) {
        return sample(seeker, view, target, distance, angle, List.of());
    }

    private static Contact sample(Entity seeker, View view, Entity target, RVP_Range<Float> distance, double angle,
                                  List<AbstractVehicle> foreground) {
        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        if (!inView(view, center, distance, angle)) return new Contact(target, center, 0f);

        Vec3 side = view.forward().cross(new Vec3(0, 1, 0)).normalize()
                .scale(Math.min(box.getXsize(), box.getZsize()) * 0.31);
        Vec3 up = new Vec3(0, box.getYsize() * 0.31, 0);
        Vec3[] points = {center, center.add(up).add(side), center.add(up).subtract(side),
                center.subtract(up).add(side), center.subtract(up).subtract(side)};
        Vec3 sum = Vec3.ZERO;
        int visible = 0;
        for (Vec3 point : points) {
            if (clear(seeker, view.origin(), point) && !vehicleOccludes(target, view.origin(), point, foreground)) {
                visible++;
                sum = sum.add(point);
            }
        }
        return new Contact(target, visible == 0 ? center : sum.scale(1.0 / visible), visible / (float) points.length);
    }

    private static boolean vehicleOccludes(Entity target, Vec3 start, Vec3 end, List<AbstractVehicle> foreground) {
        for (AbstractVehicle vehicle : foreground) {
            // 先用整体包围盒排除无关载具，再调用游戏的有向部件碰撞盒区分空隙和实体表面。
            if (vehicle == target.getRootVehicle() || vehicle.getBoundingBox().clip(start, end).isEmpty()
                    && !vehicle.getBoundingBox().contains(start)) continue;
            var parts = vehicle.getOBBs();
            if (parts == null || parts.isEmpty()
                    || RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(vehicle, start, end) != null) {
                return true;
            }
        }
        return false;
    }

    /** 相同的尺寸曲线用于观察画面和 TV 锁定。短边占画面 7.5% 时无扣分。 */
    public static float sizeWeightedConfidence(float visible, double apparentSize) {
        float detail = (float) Mth.clamp(apparentSize / 0.075, 0.0, 1.0);
        return visible * Mth.lerp(detail, 0.6f, 1f);
    }

    /** 原生武器站只有低/高两档，倍率使用站位数据，客户端只选择档位。 */
    public static float scopeMagnification(WeaponUnit station, boolean zoomed) {
        return Math.max(1f, zoomed ? station.getData().getZoomMax() : station.getData().getZoomMin());
    }

    /** TV 锁定使用同一参考视场估算目标短边占比，服务端无需依赖屏幕分辨率和用户 FOV。 */
    public static float scopeConfidence(Contact contact, View view, float magnification) {
        AABB box = contact.entity().getBoundingBox();
        double shortSide = Math.min(box.getYsize(), Math.min(box.getXsize(), box.getZsize()));
        // 70° 参考视场的画面短边在目标平面的长度为 2 × tan(35°) × 距离。
        double viewSpan = 2.0 * Math.tan(Math.toRadians(35.0))
                * Math.max(0.001, view.origin().distanceTo(box.getCenter()));
        return sizeWeightedConfidence(contact.confidence(), shortSide * magnification / viewSpan);
    }

    public static boolean clear(Entity seeker, Vec3 start, Vec3 end) {
        // 调用无加载射线读取地形，未知区块不可视；随后检查烟幕实体。
        boolean clear = seeker.level() instanceof ServerLevel server
                ? RVP_HitlRadioPath.check(server, seeker, start, end) == RVP_HitlRadioPath.Result.CLEAR
                : seeker.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, seeker)).getType() == HitResult.Type.MISS;
        return clear && RVP_CountermeasureState.findSightObstruction(seeker, start, end) == null;
    }

    /** 地面锁点在方块表面；往射线来源缩进一小段检查，避免目标方块挡住自己的锁。 */
    public static boolean groundVisible(Entity seeker, Vec3 origin, Vec3 point) {
        return clear(seeker, origin, point.lerp(origin, 1e-4));
    }

    public static Vec3 forward(Entity missile) {
        return missile.getDeltaMovement().lengthSqr() > 1e-6
                ? missile.getDeltaMovement().normalize()
                : VectorUtil.rotToVec(missile.getXRot(), missile.getYRot()).normalize();
    }

    public static boolean inView(View view, Vec3 point, RVP_Range<Float> distance, double angle) {
        double length = point.distanceTo(view.origin());
        return length > 0.001 && (distance == null || distance.contains((float) length))
                && alignment(view, point) >= Math.cos(Math.toRadians(angle));
    }

    public static double alignment(View view, Vec3 point) {
        return view.forward().dot(point.subtract(view.origin()).normalize());
    }

    public static RVP_Range<Float> lockRange(RVP_GuidanceData data) {
        return data.getLockTargetDistanceRange() == null
                ? data.getGuidanceTargetDistanceRange() : data.getLockTargetDistanceRange();
    }
}
