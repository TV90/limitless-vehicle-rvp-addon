package org.ywzj.rvp.flight;

import java.util.Objects;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import org.ywzj.rvp.flight.RVP_FlightRequest.*;

/** 通用固定翼控制器：只计算，不读取实体/世界，也不执行业务动作。 */
public final class RVP_FixedWingFlightController {
    /** 姿态与低速保护使用的额外速度余量。 */
    private static final double LIFT_MARGIN = 1.12;

    /** 参数化求解本刻指令；调用方仅在执行成功后提交nextState。 */
    public Solution solve(Observation o, Airframe a, RVP_FlightRequest r,
                          RVP_FlightTuning t, State previous) {
        Objects.requireNonNull(o, "缺少载具身份与真实位置，不能安全写入");
        boolean estimatedAirframe = a == null;
        // 调用公共缺省构造补齐可恢复信息；业务未给导航目标时先保持航向和高度。
        if (a == null) a = Airframe.conservative();
        if (r == null) r = new RVP_FlightRequest("stabilize", 0, null, null, null, null);
        if (t == null) t = RVP_FlightTuning.smoothCruise();
        if (previous == null) previous = State.empty();
        boolean continuous = previous.tick() != Long.MIN_VALUE && o.tick() > previous.tick()
                && o.tick() - previous.tick() <= 3;
        boolean matchingGoal = previous.tick() != Long.MIN_VALUE && previous.goalId().equals(r.goalId())
                && previous.revision() == r.revision();
        boolean sameGoal = continuous && matchingGoal;
        double referenceYaw = matchingGoal ? previous.referenceHeadingDeg() : o.yawDeg();
        double referenceY = matchingGoal ? previous.referenceAltitudeY() : o.position().y();
        // 调用公共初始状态，丢失执行或换目标时重新捕获，但连续操纵仍可平滑交接。
        State state = sameGoal ? previous : State.empty();
        Limits limits = r.limits();
        // 调用本体公式的纯数值估算；不把估算速度当成任意姿态下的绝对安全保证。
        double rawMinimum = RVP_FlightMath.minimumSpeed(a.massKg(), a.dragMin(), a.dragMax(),
                a.liftToDrag(), a.airScale(), a.aoaMaxDeg(), 1.0);
        double speed = o.velocity().length();
        double hSpeed = o.velocity().horizontalLength();
        double requestedSpeed = r.speed().minimumSpeedFactor() ? rawMinimum * r.speed().value() : r.speed().value();
        double boundedSpeed = RVP_FlightMath.clamp(requestedSpeed, limits.minimumSpeedMps(), limits.maximumSpeedMps());
        double desiredSpeed = Math.max(rawMinimum * LIFT_MARGIN, boundedSpeed);
        String reason = desiredSpeed > limits.maximumSpeedMps() ? "SPEED_LIFT_CONFLICT"
                : boundedSpeed + 0.01 < rawMinimum * LIFT_MARGIN ? "SPEED_BELOW_LIFT_MARGIN" : "";

        // 将明确高度转换成世界Y；未知地形时保持/抬升，禁止把未知地面当成0。
        Terrain terrain = o.terrain();
        double desiredY = r.altitude().keepCurrent() ? referenceY : r.altitude().aboveGround()
                ? terrain.currentKnown() ? terrain.groundY() + r.altitude().value() : o.position().y()
                : r.altitude().value();
        desiredY = RVP_FlightMath.clamp(desiredY, limits.minimumWorldY(), limits.maximumWorldY());
        double floor = terrain.currentKnown() ? terrain.groundY() + limits.minimumClearance() : o.position().y();
        if (terrain.aheadKnown()) floor = Math.max(floor, terrain.aheadY() + limits.minimumClearance());
        boolean unknownTerrain = !terrain.currentKnown() || !terrain.aheadKnown();
        if (unknownTerrain) { floor = Math.max(floor, o.position().y()); reason = "TERRAIN_UNKNOWN"; }
        if (desiredY < floor) {
            desiredY = floor;
            if (floor > limits.maximumWorldY()) reason = "ALTITUDE_CLEARANCE_CONFLICT";
        }

        double desiredYaw = referenceYaw;
        double requestedRadius = 0, effectiveRadius = 0, orbitBank = 0;
        double radialError = 0;
        boolean reached = state.reached();
        Goal navigation = r.goal();
        // 固定翼不能在静止点悬停；要求保持间隔时把静止跟随解释为可飞的绕圈。
        if (navigation instanceof TrackPoint stationary && stationary.velocity().horizontalLength() < 0.1 && stationary.spacing() > 0) {
            navigation = new Orbit(stationary.point(), stationary.spacing(), state.turnSign());
            if (o.tick() - stationary.sampleTick() < 0 || o.tick() - stationary.sampleTick() > 60) reason = "TARGET_SAMPLE_STALE";
        }
        if (navigation instanceof HoldCourse hold) desiredYaw = Double.isFinite(hold.headingDeg()) ? hold.headingDeg() : referenceYaw;
        else if (navigation instanceof Takeoff takeoff) desiredYaw = Double.isFinite(takeoff.headingDeg()) ? takeoff.headingDeg() : referenceYaw;
        else if (navigation instanceof FlyTo fly) {
            Vector delta = fly.point().subtract(o.position());
            reached |= delta.horizontalLength() <= fly.arrivalRadius();
            if (sameGoal && !reached) {
                // 调用线段通过检测，避免高速飞机一步跨过小航点后反复掉头。
                reached = passedWaypoint(previous.lastPosition(), o.position(), fly.point(), fly.arrivalRadius());
            }
            desiredYaw = reached ? Double.isFinite(fly.exitHeadingDeg()) ? fly.exitHeadingDeg() : referenceYaw
                    : RVP_FlightMath.yawTo(delta.x(), delta.z());
        } else if (navigation instanceof TrackPoint track) {
            long age = o.tick() - track.sampleTick();
            if (age < 0 || age > 60) reason = "TARGET_SAMPLE_STALE";
            double seconds = RVP_FlightMath.clamp(Math.max(0, age) / 20.0 + track.predictionSeconds(), 0, 3);
            Vector target = track.point().add(track.velocity().scale(seconds));
            double targetHorizontalSpeed = track.velocity().horizontalLength();
            if (targetHorizontalSpeed > 0.1) target = target.add(new Vector(
                    -track.velocity().x() / targetHorizontalSpeed * track.spacing(), 0,
                    -track.velocity().z() / targetHorizontalSpeed * track.spacing()));
            Vector delta = target.subtract(o.position());
            if (delta.horizontalLength() > 1) desiredYaw = RVP_FlightMath.yawTo(delta.x(), delta.z());
        } else if (navigation instanceof Orbit orbit) {
            requestedRadius = orbit.radius();
            effectiveRadius = requestedRadius;
            // 同时求解倾斜升力、速度和半径；不反写用户的请求半径。
            for (int i = 0; i < 4; i++) {
                double bank = Math.min(limits.maxBankDeg(), Math.toDegrees(Math.atan(
                        desiredSpeed * desiredSpeed / (RVP_FlightMath.GRAVITY * effectiveRadius))));
                desiredSpeed = Math.max(desiredSpeed, rawMinimum * LIFT_MARGIN
                        / Math.sqrt(Math.max(0.1, Math.cos(Math.toRadians(bank)))));
                effectiveRadius = Math.max(requestedRadius, desiredSpeed * desiredSpeed
                        / (RVP_FlightMath.GRAVITY * Math.tan(Math.toRadians(limits.maxBankDeg()))));
            }
            if (effectiveRadius > requestedRadius + 0.01 && reason.isEmpty()) reason = "RADIUS_EXPANDED";
            if (desiredSpeed > limits.maximumSpeedMps()) reason = "SPEED_LIFT_CONFLICT";
            Vector radial = o.position().subtract(orbit.center());
            double distance = radial.horizontalLength();
            radialError = distance - effectiveRadius;
            orbitBank = orbit.direction() * Math.toDegrees(Math.atan(desiredSpeed * desiredSpeed
                    / (RVP_FlightMath.GRAVITY * effectiveRadius)));
            if (distance < effectiveRadius * 0.5) {
                desiredYaw = distance > 1 ? RVP_FlightMath.yawTo(radial.x(), radial.z()) : o.yawDeg();
                orbitBank = 0;
            } else {
                double tangent = RVP_FlightMath.yawTo(-orbit.direction() * radial.z(), orbit.direction() * radial.x());
                double correction = Math.toDegrees(Math.atan2(radialError, Math.max(10, effectiveRadius * 0.6)));
                desiredYaw = tangent + orbit.direction() * RVP_FlightMath.clamp(correction, -60, 60);
            }
        }

        double headingError = RVP_FlightMath.wrap(desiredYaw - o.yawDeg());
        int turnSign = Math.abs(headingError) > 3 && Math.abs(headingError) < 175
                ? (headingError > 0 ? 1 : -1) : state.turnSign();
        if (Math.abs(headingError) >= 175) headingError = turnSign * Math.abs(headingError);
        double altitudeError = desiredY - o.position().y();
        boolean lowSpeed = !o.onGround() && speed < rawMinimum * 1.02;
        boolean falling = !o.onGround() && o.position().y() + Math.min(0, o.velocity().y())
                * t.terrainLookAheadSeconds() < floor;
        boolean excessiveBank = !o.onGround() && Math.abs(o.bankRightDeg()) > Math.min(70, limits.maxBankDeg() + 10);
        double aoa = angleOfAttack(o);
        boolean approachingAoA = !o.onGround() && speed > 1
                && (aoa > a.aoaMaxDeg() - 1 || aoa < a.aoaMinDeg() + 0.1);
        // 本体仍有正常升力的区间不能反复触发整段恢复；真正越界才暂停航路。
        boolean excessiveAoA = !o.onGround() && speed > 1
                && (aoa > a.aoaMaxDeg() || aoa < a.aoaMinDeg());
        boolean danger = lowSpeed || falling || excessiveBank || excessiveAoA;
        int recoveryTicks = danger ? t.recoveryHoldTicks() : Math.max(0, state.recoveryTicks() - 1);
        boolean recovering = recoveryTicks > 0;

        double targetBank = RVP_FlightMath.clamp(orbitBank + headingError * t.headingToBank(),
                -limits.maxBankDeg(), limits.maxBankDeg());
        if (recovering || o.onGround()) targetBank = 0;
        double climbTarget = RVP_FlightMath.clamp(altitudeError * t.altitudeGain(),
                -limits.maxDescentMps(), limits.maxClimbMps());
        if (unknownTerrain) climbTarget = Math.max(0, climbTarget);
        if (lowSpeed) climbTarget = o.position().y() - floor > 60 ? -1.0 : Math.max(0, Math.min(1, climbTarget));
        // 本体局部滚转→俯仰→偏航耦合，需要按实际倾斜补偿转弯中的升降。
        double bankRad = Math.toRadians(o.bankRightDeg());
        double turnRate = Math.toDegrees(RVP_FlightMath.GRAVITY * Math.tan(bankRad)
                / Math.max(10, hSpeed)) / 20.0;
        double pitch = (climbTarget - o.velocity().y()) * t.climbGain()
                + turnRate * Math.sin(bankRad) / Math.max(0.05, a.pitchRate());
        double yaw = turnRate * Math.cos(bankRad) / Math.max(0.05, a.yawRate()) + headingError * 0.008;
        double roll = (targetBank - o.bankRightDeg()) * t.bankGain()
                - o.bankRateDegPerSecond() * t.bankRateDamping();
        if (o.pitchUpDeg() > limits.maxPitchUpDeg()) pitch = Math.min(pitch, -0.25);
        if (o.pitchUpDeg() < -limits.maxPitchDownDeg()) pitch = Math.max(pitch, 0.25);
        // 迎角过大时先减小抬头输入恢复附着气流；负迎角越界则温和抬头，不能继续机械追高度。
        if (approachingAoA && aoa > 0) pitch = Math.min(pitch, -0.15);
        if (approachingAoA && aoa < 0) pitch = Math.max(pitch, 0.2);
        if (o.onGround()) {
            pitch = speed >= rawMinimum * 1.05 ? Math.min(0.5, (10 - o.pitchUpDeg()) * 0.05) : 0;
            yaw = headingError * 0.025;
            roll = 0;
        }
        pitch = RVP_FlightMath.clamp(pitch, -0.9, 0.9);
        yaw = RVP_FlightMath.clamp(yaw, -0.85, 0.85);
        roll = RVP_FlightMath.clamp(roll, -0.85, 0.85);
        if (continuous) {
            pitch = RVP_FlightMath.approach(previous.pitch(), pitch, t.maximumInputStep());
            yaw = RVP_FlightMath.approach(previous.yaw(), yaw, t.maximumInputStep());
            roll = RVP_FlightMath.approach(previous.roll(), roll, t.maximumInputStep());
        }
        double throttleCap = Math.min(a.maximumThrottle(), limits.allowAfterburner() ? a.maximumThrottle() : 100);
        double drag = a.dragMin() + (a.dragMax() - a.dragMin()) * Math.sin(Math.toRadians(5));
        double dragAtTarget = desiredSpeed * desiredSpeed * drag
                * (1 + Math.abs(pitch) + Math.abs(yaw) / 4 + Math.abs(roll) / 8);
        double throttleTarget = dragAtTarget / Math.max(1, a.thrustN()) * 100
                + (desiredSpeed - speed) * t.speedGain() * 8;
        if (lowSpeed || o.onGround()) throttleTarget = throttleCap;
        double throttle = RVP_FlightMath.approach(o.throttlePercent(),
                RVP_FlightMath.clamp(throttleTarget, 0, throttleCap), t.maximumThrottleStep());
        throttle = RVP_FlightMath.clamp(throttle, 0, throttleCap);

        boolean stable = Math.abs(altitudeError) <= t.altitudeTolerance()
                && Math.abs(desiredSpeed - speed) <= desiredSpeed * t.speedToleranceFactor()
                && Math.abs(headingError) <= t.headingToleranceDeg()
                && (requestedRadius == 0 || Math.abs(radialError) <= Math.max(10, effectiveRadius * 0.12))
                && !recovering && !unknownTerrain && !o.onGround();
        int stableTicks = stable ? Math.min(t.captureTicks(), state.stableTicks() + 1) : 0;
        if (!o.onGround() && (a.pitchRate() < 0.001 || a.yawRate() < 0.001 || a.rollRate() < 0.001)) reason = "AXIS_UNAVAILABLE";
        if (!recovering && speed > limits.maximumSpeedMps() * 1.02 && !reason.equals("SPEED_LIFT_CONFLICT")) reason = "OVERSPEED";
        if (estimatedAirframe && reason.isEmpty()) reason = "AIRFRAME_ESTIMATED";
        Status status = recovering ? Status.RECOVERING
                : !reason.isEmpty() && !reason.equals("RADIUS_EXPANDED") ? Status.LIMITED : reached ? Status.REACHED
                : stableTicks >= t.captureTicks() ? Status.CAPTURED : Status.TRACKING;
        if (danger) reason = lowSpeed ? "LOW_SPEED" : falling ? "TERRAIN_CLEARANCE"
                : excessiveAoA ? "ANGLE_OF_ATTACK" : "EXCESSIVE_BANK";
        Command command = new Command(o.vehicleId(), o.dimension(), o.tick(), o.generation(), r.goalId(), r.revision(),
                pitch, yaw, roll, throttle, r.landingGear(), r.airbrake());
        Feedback feedback = new Feedback(status, reason, r.goalId(), r.revision(), o.tick(), altitudeError,
                desiredSpeed - speed, headingError, requestedRadius, effectiveRadius);
        State next = new State(r.goalId(), r.revision(), o.tick(), reached, stableTicks, recoveryTicks,
                turnSign, pitch, yaw, roll, o.position(), referenceYaw, referenceY);
        return new Solution(command, feedback, next);
    }

    /** 航点是否落在上次至本次水平移动线段的容许距离内。 */
    private static boolean passedWaypoint(Vector from, Vector to, Vector point, double radius) {
        Vector movement = to.subtract(from);
        double lengthSquared = movement.x() * movement.x() + movement.z() * movement.z();
        if (lengthSquared < 1.0E-8) return false;
        Vector delta = point.subtract(from);
        double fraction = RVP_FlightMath.clamp((delta.x() * movement.x() + delta.z() * movement.z()) / lengthSquared, 0, 1);
        return Math.hypot(delta.x() - movement.x() * fraction, delta.z() - movement.z() * fraction) <= radius;
    }

    /** 由完整速度和YXZ姿态计算本体迎角；不是把机头俯仰角误当作迎角。 */
    private static double angleOfAttack(Observation o) {
        double speed = o.velocity().length();
        if (speed < 0.001) return 0;
        double yaw = Math.toRadians(o.yawDeg()), pitch = Math.toRadians(o.pitchUpDeg()), bank = Math.toRadians(o.bankRightDeg());
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw), sinBank = Math.sin(bank), cosBank = Math.cos(bank);
        // 本体q=Ry(-yaw)*Rx(-pitchUp)*Rz(bank)，下面是该旋转后的机翼向上轴。
        double upX = -cosYaw * sinBank + sinYaw * cosBank * Math.sin(pitch);
        double upY = cosBank * Math.cos(pitch);
        double upZ = -sinYaw * sinBank - cosYaw * cosBank * Math.sin(pitch);
        double dot = (o.velocity().x() * upX + o.velocity().y() * upY + o.velocity().z() * upZ) / speed;
        return -Math.toDegrees(Math.asin(RVP_FlightMath.clamp(dot, -1, 1)));
    }
}
