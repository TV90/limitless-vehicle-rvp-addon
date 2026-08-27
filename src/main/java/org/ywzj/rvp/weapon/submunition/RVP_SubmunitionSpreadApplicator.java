package org.ywzj.rvp.weapon.submunition;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadShape;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;
import org.ywzj.rvp.weapon.util.RVP_CanisterGridUtil;
import org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * Applies {@link RVP_SubmunitionSpreadData} to spawn position / velocity for one pellet index.
 */
public final class RVP_SubmunitionSpreadApplicator {

    /** 黄金角，单位弧度；用于让少量子体也能均匀覆盖圆锥方位角。 */
    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));

    private RVP_SubmunitionSpreadApplicator() {}

    public static Vec3 applyVelocitySpread(Vec3 baseVelocity, float yawDeg, float pitchDeg,
                                           RVP_SubmunitionSpreadData spread, int pelletIndex, int pelletCount,
                                           RandomSource random) {
        return applyVelocitySpread(baseVelocity, yawDeg, pitchDeg, spread, Vec3.ZERO,
                pelletIndex, pelletCount, random);
    }

    public static Vec3 applyVelocitySpread(Vec3 baseVelocity, float yawDeg, float pitchDeg,
                                           RVP_SubmunitionSpreadData spread, Vec3 spawnOffset,
                                           int pelletIndex, int pelletCount, RandomSource random) {
        if (spread == null) {
            return baseVelocity;
        }
        if (spread.usesCloudRadialHorizontal()) {
            return sampleCloudRadialHorizontal(baseVelocity.length(), spawnOffset, spread, random);
        }
        if (spread.usesStratifiedCone()) {
            return sampleStratifiedCone(baseVelocity.length(), spread, spawnOffset,
                    pelletIndex, pelletCount, random);
        }
        if (spread.usesCanister() && spread.getCanisterType() >= 1) {
            float[] angular = new float[2];
            int[][] grid = spread.getCanisterShape() == RVP_EnumSpreadShape.SQUARE
                    ? RVP_CanisterGridUtil.assignCells(pelletCount, spread.getCanisterDistribution())
                    : null;
            if (grid != null) {
                RVP_SpreadDistributionUtil.sampleCanisterGridAngular(
                        grid[pelletIndex % pelletCount], pelletCount, spread.getCanisterDiff(), angular);
            } else {
                RVP_SpreadDistributionUtil.sampleCanisterAngular(
                        random, spread.getCanisterDistribution(), spread.getCanisterShape(),
                        spread.getCanisterDiff(), angular);
            }
            float newYaw = yawDeg + angular[0];
            float newPitch = pitchDeg + angular[1];
            return VectorUtil.rotToVec(newYaw, newPitch).normalize().scale(baseVelocity.length());
        }
        float box = spread.getBoxSpread();
        if (box <= 0f) {
            return baseVelocity;
        }
        return baseVelocity.add(
                (random.nextDouble() - 0.5) * box,
                (random.nextDouble() - 0.5) * box * 0.5,
                (random.nextDouble() - 0.5) * box
        );
    }

    public static Vec3 applyPositionOffset(Vec3 basePos, float yawDeg, float pitchDeg,
                                           RVP_SubmunitionSpreadData spread, int pelletIndex, int pelletCount,
                                           RandomSource random) {
        if (spread == null || !spread.usesCanister() || spread.getCanisterType() != 0) {
            return basePos;
        }
        float[] offset = new float[3];
        int[][] grid = spread.getCanisterShape() == RVP_EnumSpreadShape.SQUARE
                ? RVP_CanisterGridUtil.assignCells(pelletCount, spread.getCanisterDistribution())
                : null;
        if (grid != null) {
            RVP_SpreadDistributionUtil.sampleCanisterGridPosition(
                    grid[pelletIndex % pelletCount], pelletCount, spread.getCanisterDiff(), offset);
        } else {
            RVP_SpreadDistributionUtil.sampleCanisterPosition(
                    random, spread.getCanisterDistribution(), spread.getCanisterShape(),
                    spread.getCanisterDiff(), offset);
        }
        return basePos.add(offset[0], offset[1], offset[2]);
    }

    /**
     * 使用子体最终出生点相对释放点的水平投影采样云心外向速度；Y 恒为 0，交由子体重力积分。
     */
    static Vec3 sampleCloudRadialHorizontal(double speed, Vec3 spawnOffset,
                                            RVP_SubmunitionSpreadData spread, RandomSource random) {
        if (!Double.isFinite(speed) || speed <= 1.0E-10D) {
            return Vec3.ZERO;
        }
        double offsetX = spawnOffset == null ? 0.0D : spawnOffset.x;
        double offsetZ = spawnOffset == null ? 0.0D : spawnOffset.z;
        double horizontalLengthSqr = offsetX * offsetX + offsetZ * offsetZ;
        double azimuth;
        if (Double.isFinite(horizontalLengthSqr) && horizontalLengthSqr > 1.0E-12D) {
            azimuth = Math.atan2(offsetZ, offsetX);
        } else {
            // 云心、纯竖直偏移或非法偏移没有可用水平外向量时，随机选择稳定的水平兜底方向。
            azimuth = random.nextDouble() * Math.PI * 2.0D;
        }

        double directionJitter = Math.toRadians(spread.getCloudDirectionJitter());
        if (directionJitter > 0.0D) {
            azimuth += (random.nextDouble() * 2.0D - 1.0D) * directionJitter;
        }
        double speedJitter = spread.getCloudSpeedJitter();
        double sampledSpeed = speed;
        if (speedJitter > 0.0D) {
            sampledSpeed *= 1.0D + (random.nextDouble() * 2.0D - 1.0D) * speedJitter;
        }
        return new Vec3(Math.cos(azimuth) * sampledSpeed, 0.0D,
                Math.sin(azimuth) * sampledSpeed);
    }

    /**
     * 以世界正下方向为轴进行分层均匀立体角采样，并独立缩放径向展开与向下速度，
     * 避开垂直轴上的 yaw/pitch 欧拉角退化。
     */
    static Vec3 sampleStratifiedCone(double speed, RVP_SubmunitionSpreadData spread,
                                     int pelletIndex, int pelletCount, RandomSource random) {
        return sampleStratifiedCone(speed, spread, Vec3.ZERO, pelletIndex, pelletCount, random);
    }

    /**
     * 以世界正下方向为轴采样分层圆锥；可按子体序号或最终出生点的水平外向量选择方位。
     */
    static Vec3 sampleStratifiedCone(double speed, RVP_SubmunitionSpreadData spread, Vec3 spawnOffset,
                                     int pelletIndex, int pelletCount, RandomSource random) {
        // 调用本项目散布数据归一化接口：未配置径向速度时沿用 launch_speed，保持既有武器行为。
        double radialSpeed = spread.resolveConeRadialSpeed(speed);
        if ((speed <= 1.0E-10 && radialSpeed <= 1.0E-10) || pelletCount <= 0) {
            return Vec3.ZERO;
        }
        int index = Math.floorMod(pelletIndex, pelletCount);
        double radialOffset = (random.nextDouble() * 2.0D - 1.0D)
                * spread.getRadialJitter() / pelletCount;
        double u = Math.max(0.0D, Math.min(1.0D, (index + 0.5D) / pelletCount + radialOffset));
        double halfAngle = Math.toRadians(spread.getConeHalfAngle());
        double cosTheta = 1.0D + (Math.cos(halfAngle) - 1.0D) * u;
        double sinTheta = Math.sqrt(Math.max(0.0D, 1.0D - cosTheta * cosTheta));
        double azimuthCell = Math.PI * 2.0D / pelletCount;
        double azimuthJitter = (random.nextDouble() * 2.0D - 1.0D)
                * azimuthCell * spread.getAzimuthJitter();

        // 当前 schema 的 cone_axis 只接受 world_down；保留显式变量便于以后扩展新轴模式。
        Vec3 axis = "world_down".equals(spread.getConeAxis()) ? new Vec3(0.0D, -1.0D, 0.0D) : new Vec3(0.0D, -1.0D, 0.0D);
        // 调用本项目散布数据访问器：显式 spawn_radial 才让圆锥方位跟随最终出生点，默认保持黄金角旧行为。
        Vec3 radialDirection = "spawn_radial".equals(spread.getConeAzimuthMode())
                ? resolveSpawnRadialDirection(spawnOffset, index, azimuthJitter)
                : resolveGoldenAngleDirection(axis, index, azimuthJitter);
        Vec3 downwardVelocity = axis.scale(cosTheta * Math.max(speed, 0.0D));
        Vec3 radialVelocity = radialDirection.scale(sinTheta * radialSpeed);
        return downwardVelocity.add(radialVelocity);
    }

    /** 使用既有圆锥切平面坐标计算黄金角方位，保持未配置新字段时的速度序列。 */
    private static Vec3 resolveGoldenAngleDirection(Vec3 axis, int pelletIndex, double azimuthJitter) {
        double azimuth = pelletIndex * GOLDEN_ANGLE + azimuthJitter;
        Vec3 tangent = axis.cross(new Vec3(1.0D, 0.0D, 0.0D)).normalize();
        Vec3 bitangent = axis.cross(tangent).normalize();
        return tangent.scale(Math.cos(azimuth)).add(bitangent.scale(Math.sin(azimuth)));
    }

    /**
     * 将最终出生偏移的 X/Z 投影归一化为云心外向方位；退化点使用子体序号的黄金角稳定兜底。
     */
    private static Vec3 resolveSpawnRadialDirection(Vec3 spawnOffset, int pelletIndex, double azimuthJitter) {
        double offsetX = spawnOffset == null ? 0.0D : spawnOffset.x;
        double offsetZ = spawnOffset == null ? 0.0D : spawnOffset.z;
        double horizontalLengthSqr = offsetX * offsetX + offsetZ * offsetZ;
        if (!Double.isFinite(horizontalLengthSqr) || horizontalLengthSqr <= 1.0E-12D) {
            return resolveGoldenAngleDirection(new Vec3(0.0D, -1.0D, 0.0D), pelletIndex, azimuthJitter);
        }

        double inverseLength = 1.0D / Math.sqrt(horizontalLengthSqr);
        double radialX = offsetX * inverseLength;
        double radialZ = offsetZ * inverseLength;
        double cosJitter = Math.cos(azimuthJitter);
        double sinJitter = Math.sin(azimuthJitter);
        return new Vec3(
                radialX * cosJitter - radialZ * sinJitter,
                0.0D,
                radialX * sinJitter + radialZ * cosJitter);
    }
}
