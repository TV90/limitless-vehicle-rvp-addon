package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;
import org.ywzj.rvp.uav.RVP_UavLoiterGuidance.GuidanceOutput;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用本体 FixedWingVehicle.tickMove 公式顺序运行 120 秒的无碰撞连续飞行回归。 */
class RVP_UavLoiterFlightRegressionTest {

    private static final double MASS_KG = 4074.0;
    private static final double THRUST_N = 24444.0;
    private static final double DRAG_MIN = 16.296;
    private static final double DRAG_MAX = 24.444;
    private static final double LIFT_TO_DRAG = 3.0;
    private static final double CEILING = 512.0;
    private static final double AOA_MIN = -10.0;
    private static final double AOA_MAX = 25.0;

    @Test
    void zbl08aLikeFixedWingConvergesFor120SecondsAtMultipleAltitudes() {
        FlightResult defaultStart = fly(0.0, 164.1, 0.0, 180.0, 20.0, 0.0, 100.0, 99.0, 100.0, 2400, -1, 0.0);
        FlightResult highTarget = fly(100.0, 200.0, 0.0, -90.0, 28.0, 0.0, 100.0, 200.0, 100.0, 2400, -1, 0.0);
        FlightResult bankedStart = fly(100.0, 110.0, 0.0, 90.0, 33.0, 28.0, 100.0, 99.0, 100.0, 2400, -1, 0.0);
        FlightResult displacedStart = fly(155.0, 140.0, 0.0, 135.0, 26.0, 0.0, 100.0, 99.0, 70.0, 2400, -1, 0.0);
        FlightResult movedCenter = fly(100.0, 115.0, 0.0, 90.0, 30.0, 12.0, 100.0, 99.0, 100.0, 2400, 1200, 180.0);

        report("ZBL08A default", defaultStart);
        report("high target altitude", highTarget);
        report("pre-banked", bankedStart);
        report("off-radius", displacedStart);
        report("center moved during loiter", movedCenter);
        assertAll("连续飞行场景",
                () -> assertStable("ZBL08A default", defaultStart, 99.0),
                () -> assertStable("high target altitude", highTarget, 200.0),
                () -> assertStable("pre-banked", bankedStart, 99.0),
                () -> assertStable("off-radius", displacedStart, 99.0),
                () -> assertStable("center moved during loiter", movedCenter, 99.0));
    }

    private static void report(String label, FlightResult r) {
        System.out.printf("[UAV-120S] %s allMinY=%.2f finalY=[%.2f,%.2f] radialError=[%.2f,%.2f]"
                        + " effectiveR=%.2f speed=[%.2f,%.2f] meanSpeed=%.2f LOITER=%d/400 finalPhase=%s%n",
                label, r.minAltitudeAll(), r.minAltitude(), r.maxAltitude(), r.minRadialError(), r.maxRadialError(),
                r.meanEffectiveRadius(), r.minSpeed(), r.maxSpeed(), r.meanSpeed(), r.loiterSamples(), r.phase());
    }

    private static void assertStable(String label, FlightResult result, double targetAltitude) {
        assertAll(label,
                () -> assertTrue(result.minAltitudeAll() >= 66.0,
                        "全程低于 terrainY64 安全底线: min=" + result.minAltitudeAll()),
                () -> assertTrue(result.loiterSamples() >= 320,
                        "最终窗口没有持续盘旋: LOITER=" + result.loiterSamples() + "/400 phase=" + result.phase()),
                () -> assertTrue(result.minAltitude() >= targetAltitude - 10.0 && result.maxAltitude() <= targetAltitude + 10.0,
                        "最后窗口高度范围偏离: [" + result.minAltitude() + "," + result.maxAltitude()
                                + "] 速度=" + result.meanSpeed()),
                () -> assertTrue(result.minRadialError() >= -20.0 && result.maxRadialError() <= 20.0,
                        "相对动态可行半径的偏差: [" + result.minRadialError() + ","
                                + result.maxRadialError() + "] 可行半径=" + result.meanEffectiveRadius()
                                + " 速度=" + result.meanSpeed()),
                () -> assertTrue(result.minSpeed() >= 18.0 && result.maxSpeed() <= 50.0,
                        "最后窗口速度范围: [" + result.minSpeed() + "," + result.maxSpeed() + "]"));
    }

    private static FlightResult fly(double startX, double startY, double startZ, double startYawDeg,
                                    double startSpeedMps, double startBankDeg, double requestedRadius,
                                    double targetY, double initialThrottle, int ticks,
                                    int centerMoveTick, double movedCenterX) {
        double x = startX, y = startY, z = startZ;
        double centerX = 0.0;
        double yaw = startYawDeg, pitch = 0.0, bank = startBankDeg;
        double speed = startSpeedMps / 20.0;
        double vx = -Math.sin(Math.toRadians(yaw)) * speed;
        double vz = Math.cos(Math.toRadians(yaw)) * speed;
        double vy = 0.0;
        double throttle = initialThrottle;
        LoiterPhase phase = LoiterPhase.CLIMB;
        int phaseTicks = 0;
        double radiusSum = 0, altitudeSum = 0, speedSum = 0, effectiveRadiusSum = 0;
        double radialErrorMin = Double.POSITIVE_INFINITY, radialErrorMax = Double.NEGATIVE_INFINITY;
        double speedMin = Double.POSITIVE_INFINITY, speedMax = 0;
        double altitudeMin = Double.POSITIVE_INFINITY, altitudeMax = Double.NEGATIVE_INFINITY;
        double altitudeMinAll = Double.POSITIVE_INFINITY;
        int loiterSamples = 0;

        for (int tick = 0; tick < ticks; tick++) {
            if (tick == centerMoveTick) {
                centerX = movedCenterX;
                phase = LoiterPhase.APPROACH;
                phaseTicks = 0;
            }
            double horizontalSpeed = Math.sqrt(vx * vx + vz * vz);
            double speedMps = horizontalSpeed * 20.0;
            double climbRateMps = vy * 20.0;
            double scaleAir = scaleAir(y);
            double totalSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double pitchRatePerInput = Math.min(2.0, scaleAir * totalSpeed * 0.4 * 2.0);
            double yawRatePerInput = Math.min(3.0, scaleAir * totalSpeed * 0.4 * 3.0);
            double vMin = RVP_UavLoiterGuidance.estimateFixedWingMinSpeedMps(
                    MASS_KG, DRAG_MIN, DRAG_MAX, LIFT_TO_DRAG, scaleAir, 5.0, AOA_MAX);
            RVP_UavLoiterGuidance.FixedWingLoiterSolution solution =
                    RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(requestedRadius, vMin, horizontalSpeed);
            double bankAbs = solution.targetBankDeg();
            double dx = x;
            double dz = z;
            double radial = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
            GuidanceOutput out = switch (phase) {
                case CLIMB -> RVP_UavLoiterGuidance.computeClimb(x, y, z, (float) yaw,
                        centerX, 64.0, 0, targetY, climbRateMps, speedMps, solution.targetSpeedMps(),
                        false, tick, (float) bank);
                case TRANSIT -> RVP_UavLoiterGuidance.computeTransit(x, y, z, (float) yaw,
                        centerX, 64.0, 0, solution.actualRadius(), targetY, climbRateMps,
                        speedMps, solution.targetSpeedMps(), false, tick, (float) bank);
                case APPROACH -> RVP_UavLoiterGuidance.computeApproach(x, y, z, (float) yaw,
                        centerX, 64.0, 0, solution.actualRadius(), targetY, climbRateMps, 1,
                        false, tick, (float) bank, speedMps, solution.targetSpeedMps());
                case LOITER -> RVP_UavLoiterGuidance.computeLoiter(x, y, z, (float) yaw,
                        centerX, 64.0, 0, solution.actualRadius(), targetY, climbRateMps,
                        false, tick, (float) bank, speedMps, solution.targetBankDeg(), solution.targetSpeedMps(),
                        pitchRatePerInput, yawRatePerInput);
                case RECOVERY -> RVP_UavLoiterGuidance.computeRecovery(x, y, z, (float) yaw,
                        centerX, 64.0, 0, solution.actualRadius(), targetY, climbRateMps, 1,
                        tick, (float) bank, speedMps, solution.targetBankDeg(), solution.targetSpeedMps());
            };
            if (out.nextPhase() != phase) {
                phase = out.nextPhase();
                phaseTicks = 0;
            } else {
                phaseTicks++;
            }

            throttle = updateThrottle(throttle, out.forward(), out.backward(), 1.3);

            // 依本体 tickMove：输入杆量先叠加 inputStep，随后按气流方向、阻力、升力、尾舵力、操纵阻力排序。
            float xStep = 0.2F, yStep = 0.5F, zStep = 0.2F;
            float xInput = RVP_UavLoiterGuidance.compensateInput(out.pitchInput(), -xStep).setterValue() - xStep;
            float yawInput = RVP_UavLoiterGuidance.compensateInput(out.yawInput(), yStep).setterValue() + yStep;
            float rollInput = RVP_UavLoiterGuidance.compensateInput(out.rollInput(), -zStep).setterValue() - zStep;
            Quaternionf rotation = rotation(yaw, pitch, bank);
            Vector3f forward = rotation.transform(new Vector3f(0, 0, 1));
            Vector3f up = rotation.transform(new Vector3f(0, 1, 0));
            Vector3f left = rotation.transform(new Vector3f(1, 0, 0));
            Vector3f air = new Vector3f((float) vx, (float) vy, (float) vz);
            double thrustAcceleration = THRUST_N * (throttle / 100.0) / MASS_KG / 400.0;
            air.add(new Vector3f(forward).mul((float) thrustAcceleration));
            double angleX = angleBetween(air, up) - Math.PI / 2.0;
            double dragK = (DRAG_MAX - DRAG_MIN) * Math.abs(Math.sin(angleX)) + DRAG_MIN;
            double airSpeed = air.length();
            double speedSquared = airSpeed * airSpeed * 400.0;
            double dragForce = speedSquared * dragK;
            air.normalize().mul((float) (airSpeed - dragForce / MASS_KG / 400.0));
            double aRaw = air.length();
            double aoaDeg = Math.toDegrees(angleX);
            double liftFactor;
            if (aoaDeg >= AOA_MIN && aoaDeg <= AOA_MAX) {
                liftFactor = LIFT_TO_DRAG * scaleAir + 2.0 * scaleAir * aoaDeg / AOA_MAX;
            } else {
                double exceed = aoaDeg > AOA_MAX ? aoaDeg - AOA_MAX : AOA_MIN - aoaDeg;
                liftFactor = LIFT_TO_DRAG * scaleAir * Math.exp(-exceed);
            }
            double liftForce = speedSquared * dragK * liftFactor;
            air.add(new Vector3f(up).mul((float) (liftForce / MASS_KG / 400.0)));
            double angleY = angleBetween(air, left) - Math.PI / 2.0;
            double forwardSpeed = air.dot(forward);
            double tailForce = forwardSpeed * forwardSpeed * 400.0 * 8.0 * dragK * Math.sin(angleY);
            air.add(new Vector3f(left).mul((float) (tailForce / MASS_KG / 400.0)));
            double controlDrag = (Math.abs(xInput) + Math.abs(yawInput) / 4.0 + Math.abs(rollInput) / 8.0) * DRAG_MIN;
            double controlForce = speedSquared * controlDrag;
            aRaw -= controlForce / MASS_KG / 400.0;
            air.normalize().mul((float) aRaw);

            // 依本体滚转→俯仰→偏航顺序更新姿态；每轴均按当前速度和气动 scaleAir 限制转速。
            Quaternionf nextRotation = rotation(yaw, pitch, bank);
            double turnRate = scaleAir * air.length() * 0.4;
            nextRotation.rotateZ((float) Math.toRadians(rollInput * Math.min(4.0, turnRate * 4.0)));
            nextRotation.rotateX((float) Math.toRadians(xInput * Math.min(2.0, turnRate * 2.0)));
            double yawRate = Math.min(3.0, turnRate * 3.0);
            double finalAngleY = angleBetween(air, left) - Math.PI / 2.0;
            double yawDamp = Math.min(1.0, 2.0 / Math.max(1.0E-6, Math.abs(Math.toDegrees(finalAngleY))));
            if (Math.abs(yawInput) > 0.0) {
                nextRotation.rotateY((float) Math.toRadians(yawInput * yawRate * yawDamp));
            } else {
                nextRotation.rotateY((float) (turnRate * -finalAngleY / 5.0));
            }
            Vector3f down = new Vector3f(up).negate();
            if (angleBetween(air, down) < Math.PI / 4.0) {
                double downwardSpeed = air.dot(down);
                double spinRate = Math.min(1.5, downwardSpeed);
                nextRotation.rotateY((float) (Math.PI / 72.0 * spinRate));
            }
            Vector3f euler = new Vector3f();
            nextRotation.getEulerAnglesYXZ(euler);
            pitch = Math.toDegrees(euler.x);
            yaw = Math.toDegrees(-euler.y);
            bank = Math.toDegrees(euler.z);

            // PhysicsEngine.tick 将升力加速度与重力按 tick 积分；空中无碰撞时仅减去 G。
            vx = air.x;
            // PhysicsEngine 对自由飞行体先处理升力 force，再减 G；升力不足的分支自由落体。
            double liftForcePerTick = liftForce / 400.0;
            vy = air.y;
            if (liftForcePerTick >= org.ywzj.vehicle.vehicle.PhysicsEngine.G * MASS_KG) {
                vy -= org.ywzj.vehicle.vehicle.PhysicsEngine.G;
            } else {
                vy -= org.ywzj.vehicle.vehicle.PhysicsEngine.G;
            }
            vz = air.z;
            x += vx;
            y += vy;
            z += vz;

            double actualRadius = Math.sqrt((x - centerX) * (x - centerX) + z * z);
            altitudeMinAll = Math.min(altitudeMinAll, y);
            if (tick >= ticks - 400) {
                double currentEffectiveRadius = solution.actualRadius();
                double radialError = actualRadius - currentEffectiveRadius;
                radiusSum += actualRadius;
                effectiveRadiusSum += currentEffectiveRadius;
                altitudeSum += y;
                double sampleSpeed = Math.sqrt(vx * vx + vz * vz) * 20.0;
                speedSum += sampleSpeed;
                speedMin = Math.min(speedMin, sampleSpeed);
                speedMax = Math.max(speedMax, sampleSpeed);
                radialErrorMin = Math.min(radialErrorMin, radialError);
                radialErrorMax = Math.max(radialErrorMax, radialError);
                altitudeMin = Math.min(altitudeMin, y);
                altitudeMax = Math.max(altitudeMax, y);
                if (phase == LoiterPhase.LOITER) loiterSamples++;
            }
        }
        return new FlightResult(radiusSum / 400, altitudeSum / 400, speedSum / 400, effectiveRadiusSum / 400,
                radialErrorMin, radialErrorMax, speedMin, speedMax, altitudeMin, altitudeMax,
                altitudeMinAll, loiterSamples, phase, phaseTicks);
    }

    private static double updateThrottle(double throttle, boolean forward, boolean backward, double thrustK) {
        if (forward || backward) {
            if (thrustK > 1.0) {
                if (forward && throttle + 5.0 > 100.0) {
                    throttle = 100.0 * thrustK;
                } else if (backward && throttle > 100.0) {
                    throttle = 100.0;
                } else {
                    throttle = Mth.clamp((float) (throttle + (forward ? 5.0 : -5.0)), 0.0F, 100.0F);
                }
            } else {
                throttle = Mth.clamp((float) (throttle + (forward ? 5.0 : -5.0)), 0.0F, 100.0F);
            }
        }
        return Math.max(0.0, throttle);
    }

    private static double scaleAir(double y) {
        return Math.max(0.001, y < 64.0 ? 1.0 : Math.sqrt(Math.max(0.0, CEILING - y) / (CEILING - 64.0)));
    }

    private static double angleBetween(Vector3f a, Vector3f b) {
        double denominator = Math.max(1.0E-12, a.length() * b.length());
        return Math.acos(Mth.clamp((float) (a.dot(b) / denominator), -1.0F, 1.0F));
    }

    private static Quaternionf rotation(double yaw, double pitch, double bank) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(bank));
    }

    private record FlightResult(double meanRadius, double meanAltitude, double meanSpeed, double meanEffectiveRadius,
                                double minRadialError, double maxRadialError, double minSpeed, double maxSpeed,
                                double minAltitude, double maxAltitude,
                                double minAltitudeAll, int loiterSamples, LoiterPhase phase, int phaseTicks) {}
}
