package org.ywzj.rvp.entity.projectile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeController;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSourceRegistry;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.ArrayList;
import java.util.List;

/** 改装档案的分帧试飞。只驱动未注册的展示实体的运动入口，不向世界生成弹体或运行碰撞、伤害、音效。 */
public final class RVP_WorkshopTrajectory {
    /** 三个预览场景，距离单位为格，发射平台速度由界面另选。 */
    public record Scene(int height, int range) {}
    public static final List<Scene> SCENES = List.of(new Scene(256, 1000), new Scene(256, 150), new Scene(768, 1000));
    /** 采样速度为格/刻，累计航程为格。 */
    public record Point(double distance, double speed, int pulse) {}
    /** 事件名称是界面的翻译键；位置来自同一刻的实际采样。 */
    public record Mark(int sample, String key) {}
    /** 单一展示条件完成后的只读采样。 */
    public record Curve(List<Point> points, List<Mark> marks, double peak) {}

    private final RVP_BaseBullet body;
    private final Marker target;
    private final int delayFuse;
    private final double distanceLimit;
    private final List<Point> points = new ArrayList<>();
    private final List<Mark> marks = new ArrayList<>();
    /** 只统计实际观测到的速度，包含发射瞬间。 */
    private double peak, distance;
    private Curve result;

    public RVP_WorkshopTrajectory(Level level, RVP_WeaponData data, Scene scene, int carrierMps) {
        var kind = data.getWeaponKind();
        delayFuse = data.getDelayFuse();
        distanceLimit = data.getProjectileData().resolveSelfDestructDistance(kind);
        body = RVP_ProjectileEntityFactory.create(kind, null, level, data);
        var start = new Vec3(0, scene.height(), 0);
        var aim = new RVP_BaseBullet.AimRot(0, 0);
        // 与游戏发射顺序一致，平台速度在实体自身出膛初始化之后叠加。
        body.initFromWeapon(data, kind, null, null, start, aim, new Vec3(0, 0, data.resolveMuzzleSpeed(kind)));
        target = new Marker(EntityType.MARKER, level);
        var point = start.add(0, 0, scene.range());
        target.setPos(point);
        target.setBoundingBox(new AABB(point, point).inflate(2));
        body.setGuidanceTargetPos(point);
        if (data.getGuidanceData().getGuidanceType() != RVP_EnumGuidanceType.GPS) body.setTargetEntity(target);
        Vec3 carrier = data.isInheritVehicleVelocity() ? new Vec3(0, 0, carrierMps / 20.0) : Vec3.ZERO;
        body.captureLaunchVehicleVelocity(carrier);
        body.setDeltaMovement(body.getDeltaMovement().add(carrier));
        body.finalizeSpawnOrientation(aim);
        sample();
    }

    /** 使用固定的本地观测；制导门控、发动机时序、转向和飞行阻力继续走游戏代码。 */
    private RVP_GuidanceIntent observe(RVP_GuidanceRuntimeContext context) {
        var active = context.active();
        var type = active.guidanceType();
        if (type == RVP_EnumGuidanceType.GPS || type == RVP_EnumGuidanceType.NONE)
            return RVP_RuntimeGuidanceSourceRegistry.get(type).evaluate(context);
        boolean direct = type == RVP_EnumGuidanceType.HITL_CLOS_TV || type == RVP_EnumGuidanceType.LBR
                || type == RVP_EnumGuidanceType.SACLOS && !active.semiCorrectionEnabled();
        return direct ? RVP_GuidanceIntent.point(target.position(), true, 1, type)
                : RVP_GuidanceIntent.entity(target, false, 1, type);
    }

    private void sample() {
        int pulse = body.isSecondMotorPulseBurningNow() ? 2 : RVP_ProjectileMotion.resolvePIPMotorState(body).burning() ? 1 : 0;
        int previous = points.isEmpty() ? 0 : points.get(points.size() - 1).pulse();
        int index = points.size();
        if (previous > 0 && pulse != previous) marks.add(new Mark(index, previous == 1 ? "burnout" : "burnout2"));
        if (pulse > 0 && pulse != previous) marks.add(new Mark(index, pulse == 1 ? "ignition" : "ignition2"));
        double speed = body.getDeltaMovement().length();
        peak = Math.max(peak, speed);
        points.add(new Point(distance, speed, pulse));
    }

    /** 每次至少走一刻；界面按时间预算续算，场景结束后重复读取不会再执行运动。 */
    public Curve advance(long budgetNanos) {
        long start = System.nanoTime();
        while (result == null) {
            Vec3 before = body.position();
            body.xRotO = body.getXRot();
            body.yRotO = body.getYRot();
            body.tickCount++;
            body.updateCount++;
            // 定时引信在该刻运动前触发；档案只截断曲线。
            if (delayFuse > 0 && body.updateCount >= delayFuse) return finish("end");
            body.prepareKinematicGuidanceTick();
            RVP_GuidanceRuntimeController.tick(body, this::observe);
            RVP_ProjectileMotion.finishAttackAngleGuidance(body);
            body.tickMotion();
            body.life--;
            Vec3 after = body.position(), step = after.subtract(before);
            double length = step.length(), speed = body.getDeltaMovement().length();
            String end = null;
            if (!Double.isFinite(length) || !Double.isFinite(speed)) {
                end = "invalid";
            } else {
                boolean atDistanceLimit = distanceLimit > 0 && distance + length >= distanceLimit;
                distance = atDistanceLimit ? distanceLimit : distance + length;
                sample();
                // 两格停止球使用线段最近点，高速弹可以在一刻内穿过它。
                Vec3 offset = target.position().subtract(before);
                double t = length > 0 ? Math.min(1, Math.max(0, offset.dot(step) / step.lengthSqr())) : 0;
                if (atDistanceLimit || offset.subtract(step.scale(t)).lengthSqr() <= 4 || after.y <= 64 || body.life < 0 || !body.isAlive()) end = "end";
                else if (body.tickCount >= 600) end = "timeout";
            }
            if (end != null) return finish(end);
            if (System.nanoTime() - start >= budgetNanos) break;
        }
        return result;
    }

    private Curve finish(String end) {
        marks.add(new Mark(points.size() - 1, end));
        result = new Curve(List.copyOf(points), List.copyOf(marks), peak);
        return result;
    }
}
