package org.ywzj.rvp.guidance;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
    /** 接受的最小投影最长边比例；HUD 同时用该比例的平方过滤极细的投影。 */
    public static final double MIN_APPARENT_SIZE = 0.002;
    /** 目标投影最长边达到画幅短边的 12% 时不再因尺寸减分。 */
    private static final double FULL_APPARENT_SIZE = 0.12;
    /** 与发射前和自动制导的锁定门槛分开，只判断是否显示识别框。 */
    private static final float DISPLAY_CONFIDENCE_THRESHOLD = 0.4f;

    private RVP_OpticalTargeting() {}

    public record View(Vec3 origin, Vec3 forward) {}
    /** HUD 候选分别保留几何可见度与照明系数；制导候选仍使用最终置信度。 */
    public record Contact(Entity entity, Vec3 point, float confidence, float lighting) {
        public Contact(Entity entity, Vec3 point, float confidence) {
            this(entity, point, confidence, 1f);
        }
    }
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
                target -> -alignment(view, target.getBoundingBox().getCenter()), MAX_CANDIDATES, false, false);
    }

    /** 画面候选按距屏幕准星的平方距离排序，无投影的实体返回正无穷并跳过。 */
    public static List<Contact> scanDisplayed(Entity seeker, @Nullable AbstractVehicle carrier, View view,
            RVP_Range<Float> distance, double angle, boolean thermal, ToDoubleFunction<Entity> screenDistance) {
        return scan(seeker, carrier, view, distance, angle, false, screenDistance, MAX_DISPLAY_CANDIDATES, true, thermal);
    }

    private static List<Contact> scan(Entity seeker, @Nullable AbstractVehicle carrier, View view,
            RVP_Range<Float> distance, double angle, boolean automatic, ToDoubleFunction<Entity> priority,
            int limit, boolean displayScan, boolean thermal) {
        double radius = RVP_GuidanceRuntimeGeometry.resolveScanRadius(distance);
        Vec3 end = view.origin().add(view.forward().scale(radius));
        double width = radius * Math.sin(Math.toRadians(Math.min(90.0, angle)));
        AABB region = new AABB(view.origin(), end).inflate(Math.max(1.0, width));
        List<AbstractVehicle> foreground = displayScan
                ? seeker.level().getEntities(seeker, region, entity -> entity instanceof AbstractVehicle
                        && (isCandidate(seeker, carrier, entity, false)
                        || entity instanceof VehiclePart && entity.isAlive() && entity.isPickable()))
                        .stream().map(AbstractVehicle.class::cast).toList()
                : List.of();
        // 先按视场收窄并排序，仅给最靠近准星的有限候选计算可见度。
        return seeker.level().getEntities(seeker, region, target -> isCandidate(seeker, carrier, target, automatic))
                .stream().filter(target -> inView(view, target.getBoundingBox().getCenter(), distance, angle))
                // 过小的目标不占用 ATV 的射线预算。
                .filter(target -> !automatic || scopeApparentSize(target, view, 1f) >= MIN_APPARENT_SIZE)
                .map(target -> new RankedCandidate(target, priority.applyAsDouble(target)))
                .filter(candidate -> Double.isFinite(candidate.priority()))
                .sorted(Comparator.comparingDouble(RankedCandidate::priority)
                        .thenComparingInt(candidate -> candidate.entity().getId()))
                .limit(limit)
                .map(candidate -> sample(seeker, view, candidate.entity(), distance, angle, foreground))
                .filter(contact -> contact.confidence() > 0f)
                .map(contact -> displayScan
                        ? new Contact(contact.entity(), contact.point(), contact.confidence(),
                                lightFactor(seeker.level(), contact.point(), thermal))
                        : new Contact(contact.entity(), contact.point(), scopeConfidence(contact, view, 1f, thermal)))
                .filter(contact -> displayScan ? contact.lighting() > 0f : contact.confidence() > 0f)
                .toList();
    }

    /** 置信度等于 0.40 时仍达不到识别框门槛。 */
    public static boolean canDisplay(float confidence) {
        return confidence > DISPLAY_CONFIDENCE_THRESHOLD;
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
        int visibleWeight = 0;
        for (int i = 0; i < points.length; i++) {
            Vec3 point = points[i];
            if (clear(seeker, view.origin(), point) && !vehicleOccludes(target, view.origin(), point, foreground)) {
                visible++;
                // 中心占 60%，四角各占 10%；锁点仍取可见采样点的平均位置。
                visibleWeight += i == 0 ? 6 : 1;
                sum = sum.add(point);
            }
        }
        return new Contact(target, visible == 0 ? center : sum.scale(1.0 / visible), visibleWeight / 10f);
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

    /** 白光有效亮度从 0 到 10 时，光照系数从 0.1 线性增加到 1。 */
    public static float lightFactor(Level level, Vec3 point, boolean thermal) {
        if (thermal) return 1f;
        BlockPos pos = BlockPos.containing(point);
        if (!level.hasChunkAt(pos)) return 0f;
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        // 客户端整数 skyDarken 可能还停在进世界时的亮度，两端改用当前天色与天气计算。
        float daylight = Mth.clamp(Mth.cos(level.getTimeOfDay(1f) * Mth.TWO_PI) * 2f + 0.5f, 0f, 1f);
        daylight *= 1f - level.getRainLevel(1f) * 0.3125f;
        daylight *= 1f - level.getThunderLevel(1f) * 0.3125f;
        int light = Math.max(blockLight, skyLight - Math.round(11f * (1f - daylight)));
        return Mth.lerp(Mth.clamp(light / 10f, 0f, 1f), 0.1f, 1f);
    }

    /** 在刚好位于方块可见面外的位置采光，避免采到不可见的方块内部。 */
    public static float groundLightFactor(Level level, Vec3 origin, Vec3 ground, boolean thermal) {
        return lightFactor(level, ground.add(origin.subtract(ground).normalize().scale(0.05)), thermal);
    }

    /** 目标投影最长边从画幅短边的 0.2% 到 12% 时，尺寸系数从 0.1 线性增加到 1。 */
    public static float sizeFactor(double apparentSize) {
        if (apparentSize < MIN_APPARENT_SIZE) return 0f;
        float detail = (float) Mth.clamp((apparentSize - MIN_APPARENT_SIZE)
                / (FULL_APPARENT_SIZE - MIN_APPARENT_SIZE), 0.0, 1.0);
        return Mth.lerp(detail, 0.1f, 1f);
    }

    /** 原生武器站只有低/高两档，倍率使用站位数据，客户端只选择档位。 */
    public static float scopeMagnification(WeaponUnit station, boolean zoomed) {
        return Math.max(1f, zoomed ? station.getData().getZoomMax() : station.getData().getZoomMin());
    }

    /** 服务端使用固定参考视场估算尺寸，采样点照明与 HUD 使用同一权重。 */
    public static float scopeConfidence(Contact contact, View view, float magnification, boolean thermal) {
        float size = sizeFactor(scopeApparentSize(contact.entity(), view, magnification));
        float lighting = lightFactor(contact.entity().level(), contact.point(), thermal);
        // 使用当前权威公式，为实体计算实际锁定与跟踪分。
        return RVP_OpticalConfidenceRuntime.server().score(contact.confidence(), size, lighting);
    }

    private static double scopeApparentSize(Entity target, View view, float magnification) {
        AABB box = target.getBoundingBox();
        Vec3 toTarget = box.getCenter().subtract(view.origin());
        Vec3 sight = toTarget.normalize();
        Vec3 right = new Vec3(sight.z, 0, -sight.x).normalize();
        if (right.lengthSqr() == 0) right = new Vec3(1, 0, 0);
        Vec3 up = sight.cross(right);
        // 把包围盒投影到垂直视线的平面，翼展计入宽度，沿视线的进深不会直接当作宽度。
        double width = Math.abs(right.x) * box.getXsize() + Math.abs(right.z) * box.getZsize();
        double height = Math.abs(up.x) * box.getXsize() + Math.abs(up.y) * box.getYsize()
                + Math.abs(up.z) * box.getZsize();
        // 70° 参考视场的画面短边在目标平面的长度为 2 × tan(35°) × 距离。
        double viewSpan = 2.0 * Math.tan(Math.toRadians(35.0)) * Math.max(0.001, toTarget.length());
        return Math.max(width, height) * magnification / viewSpan;
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
