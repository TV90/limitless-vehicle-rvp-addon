package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 阶段 A 从实体 RVP 制导/运动链复制出的纯实现。它刻意使用 turningFactor 方向混合，
 * 不读取本体 maxG，也不引入 max_turn_degree_per_tick。
 */
public final class RVP_RvpTrajectoryIntegrator implements RVP_VirtualTrajectoryIntegrator {
    public static final String ID = "rvp_current";
    public static final int VERSION = 1;

    @Override public String implementationId() { return ID; }
    @Override public int implementationVersion() { return VERSION; }

    @Override
    public RVP_VirtualTrajectoryResult step(RVP_VirtualTrajectoryState state,
                                             RVP_VirtualGuidanceInput guidance,
                                             RVP_VirtualTrajectoryParameters p) {
        int tick = state.flightTick() + 1;
        Vec3 velocity = state.velocity();
        Vec3 targetDelta = guidance.fixedTargetPosition().subtract(state.position());
        // 实体制导链使用历史 flightSpeed（峰值）与当前速度的较大者。
        double speed = Math.max(state.peakFlightSpeed(), velocity.length());
        boolean cruise = p.cruiseStartTick() != null && tick >= p.cruiseStartTick()
                && horizontalLength(targetDelta) > p.cruiseEndHorizontalDistance();
        Vec3 steered = cruise
                ? steerGpsCruise(velocity, targetDelta, speed, p.turningFactor(), p.cruiseLevelingFactor(), false)
                : steerPursuit(velocity, targetDelta, speed, p.turningFactor());
        double theta = angleBetween(velocity, steered);
        velocity = steered;

        float xRot = state.xRot();
        float yRot = state.yRot();
        if (velocity.lengthSqr() > 1.0E-8) {
            Vec3 n = velocity.normalize();
            xRot = (float) Math.toDegrees(-Math.asin(Mth.clamp(n.y, -1.0, 1.0)));
            yRot = (float) (Math.toDegrees(Math.atan2(n.z, n.x)) - 90.0);
        }
        if (tick >= p.ignitionTick()) {
            int motorTick = tick - p.ignitionTick();
            if (p.propulsion() && motorTick <= p.motorBurnTime()) {
                velocity = velocity.add(directionFromRotation(xRot, yRot).scale(p.thrust() / Math.max(p.mass(), 1.0E-6f)));
            }
            double speedSqr = velocity.lengthSqr();
            double drag = p.dragCoefficient() * p.altitudeDragFactor();
            if (speedSqr > 1.0E-12 && drag > 0.0) {
                velocity = velocity.add(velocity.normalize().scale(-drag * speedSqr));
            }
            velocity = p.gravity() != 0f ? velocity.add(0, p.gravity(), 0)
                    : velocity.subtract(0, PhysicsEngine.G, 0);
        }
        velocity = clampSpeed(velocity, p.minSpeed(), p.maxSpeed());
        Vec3 position = state.position().add(velocity);
        RVP_VirtualTrajectoryState next = new RVP_VirtualTrajectoryState(
                position, velocity, xRot, yRot, Math.max(state.peakFlightSpeed(), velocity.length()),
                state.flightDistance() + velocity.length(), tick, state.remainingLife() - 1,
                state.secondPulseStartTick());
        return new RVP_VirtualTrajectoryResult(next, theta, !isFinite(next));
    }

    public static Vec3 steerPursuit(Vec3 current, Vec3 toTarget, double speed, float turningFactor) {
        if (toTarget == null || toTarget.lengthSqr() <= 1.0E-8 || speed <= 1.0E-8) return current;
        Vec3 desired = toTarget.normalize().scale(speed);
        float factor = Mth.clamp(turningFactor, 0f, 1f);
        Vec3 blended = current.normalize().scale(1.0 - factor).add(desired.normalize().scale(factor));
        return blended.lengthSqr() <= 1.0E-8 ? current.normalize().scale(speed) : blended.normalize().scale(speed);
    }

    public static Vec3 steerGpsCruise(Vec3 current, Vec3 toTarget, double speed, float turningFactor,
                                      float levelingFactor, boolean resetVertical) {
        Vec3 horizontal = new Vec3(toTarget.x, 0, toTarget.z);
        double distance = horizontal.length();
        if (distance <= 1.0E-8) return steerPursuit(current, toTarget, speed, turningFactor);
        double horizontalSpeed = Math.sqrt(current.x * current.x + current.z * current.z);
        Vec3 desired = horizontal.scale(Math.max(horizontalSpeed, speed * 0.01) / distance);
        float factor = Mth.clamp(turningFactor, 0f, 1f);
        double y = resetVertical ? 0.0 : current.y;
        if (!resetVertical && y > 0.0) y += -y * Mth.clamp(levelingFactor, 0f, 1f);
        return new Vec3(current.x + (desired.x - current.x) * factor, y,
                current.z + (desired.z - current.z) * factor);
    }

    private static Vec3 clampSpeed(Vec3 velocity, float min, float max) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) return velocity;
        if (max > 0f && min > max) min = 0f;
        if (max > 0f && speed > max) return velocity.normalize().scale(max);
        if (min > 0f && speed < min) return velocity.normalize().scale(min);
        return velocity;
    }
    private static double horizontalLength(Vec3 v) { return Math.sqrt(v.x * v.x + v.z * v.z); }
    private static Vec3 directionFromRotation(float pitch, float yaw) {
        float y = -yaw * ((float)Math.PI / 180f) - (float)Math.PI;
        float x = -pitch * ((float)Math.PI / 180f);
        return new Vec3(Mth.sin(y) * Mth.cos(x), Mth.sin(x), Mth.cos(y) * Mth.cos(x));
    }
    private static double angleBetween(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-12 || b.lengthSqr() <= 1.0E-12) return 0.0;
        return Math.acos(Mth.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0));
    }
    private static boolean isFinite(RVP_VirtualTrajectoryState s) {
        return finite(s.position()) && finite(s.velocity()) && Float.isFinite(s.xRot()) && Float.isFinite(s.yRot())
                && Double.isFinite(s.peakFlightSpeed()) && Double.isFinite(s.flightDistance());
    }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }

}
