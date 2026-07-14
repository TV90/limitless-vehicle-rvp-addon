package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Analytic intercept-point solver adapted for RVP guidance.
 *
 * <p>This only predicts a future aim point; it does not modify projectile
 * motion directly. Steering remains in {@link RVP_GuidanceMath} so missiles
 * can keep using RVP's own propulsion/drag pipeline.</p>
 */
public final class RVP_InterceptSolver {

    private static final double EPS = 1.0E-6;

    private RVP_InterceptSolver() {}

    public static Solution solve(Vec3 missilePos, Vec3 missileVelocity, double missileSpeed, Vec3 targetPos, Vec3 targetVelocity) {
        if (missilePos == null || targetPos == null) {
            return Solution.invalid(targetPos);
        }
        double speed = Math.max(missileSpeed, missileVelocity != null ? missileVelocity.length() : 0.0);
        if (speed <= EPS) {
            return Solution.invalid(targetPos);
        }

        Vec3 relPos = targetPos.subtract(missilePos);
        double relPosLenSqr = relPos.lengthSqr();
        if (relPosLenSqr <= EPS) {
            return new Solution(targetPos, 0.0, true);
        }

        Vec3 targetVel = targetVelocity == null ? Vec3.ZERO : targetVelocity;
        double targetSpeedSqr = targetVel.lengthSqr();
        double a = targetSpeedSqr - speed * speed;
        double b = 2.0 * relPos.dot(targetVel);
        double c = relPosLenSqr;

        double time = -1.0;
        if (Math.abs(a) < EPS) {
            if (Math.abs(b) > EPS) {
                double linear = -c / b;
                if (linear > 0.0) {
                    time = linear;
                }
            }
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant >= 0.0) {
                double sqrt = Math.sqrt(discriminant);
                double t1 = (-b + sqrt) / (2.0 * a);
                double t2 = (-b - sqrt) / (2.0 * a);
                if (t1 > 0.0 && t2 > 0.0) {
                    time = Math.min(t1, t2);
                } else {
                    time = Math.max(t1, t2);
                }
            }
        }

        if (time <= EPS) {
            Vec3 missileVel = missileVelocity == null ? Vec3.ZERO : missileVelocity;
            Vec3 relVel = targetVel.subtract(missileVel);
            Vec3 relDir = relPos.normalize();
            double closingSpeed = speed - relVel.dot(relDir);
            if (closingSpeed > EPS) {
                time = Math.sqrt(relPosLenSqr) / closingSpeed;
            }
        }

        if (time <= EPS || !Double.isFinite(time)) {
            return Solution.invalid(targetPos);
        }
        return new Solution(targetPos.add(targetVel.scale(time)), time, true);
    }

    public static final class Solution {
        @Nullable
        private final Vec3 interceptPos;
        private final double timeToGo;
        private final boolean valid;

        private Solution(@Nullable Vec3 interceptPos, double timeToGo, boolean valid) {
            this.interceptPos = interceptPos;
            this.timeToGo = timeToGo;
            this.valid = valid;
        }

        public static Solution invalid(@Nullable Vec3 fallback) {
            return new Solution(fallback, 0.0, false);
        }

        @Nullable
        public Vec3 interceptPos() {
            return interceptPos;
        }

        public double timeToGo() {
            return timeToGo;
        }

        public boolean valid() {
            return valid;
        }
    }
}
