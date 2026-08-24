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
        if (spread == null) {
            return baseVelocity;
        }
        if (spread.usesStratifiedCone()) {
            return sampleStratifiedCone(baseVelocity.length(), spread, pelletIndex, pelletCount, random);
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
     * 以世界正下方向为轴进行分层均匀立体角采样，并独立缩放径向展开与向下速度，
     * 避开垂直轴上的 yaw/pitch 欧拉角退化。
     */
    static Vec3 sampleStratifiedCone(double speed, RVP_SubmunitionSpreadData spread,
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
        double azimuth = index * GOLDEN_ANGLE
                + (random.nextDouble() * 2.0D - 1.0D)
                * azimuthCell * spread.getAzimuthJitter();

        // 当前 schema 的 cone_axis 只接受 world_down；保留显式变量便于以后扩展新轴模式。
        Vec3 axis = "world_down".equals(spread.getConeAxis()) ? new Vec3(0.0D, -1.0D, 0.0D) : new Vec3(0.0D, -1.0D, 0.0D);
        Vec3 tangent = axis.cross(new Vec3(1.0D, 0.0D, 0.0D)).normalize();
        Vec3 bitangent = axis.cross(tangent).normalize();
        Vec3 downwardVelocity = axis.scale(cosTheta * Math.max(speed, 0.0D));
        Vec3 radialVelocity = tangent.scale(Math.cos(azimuth) * sinTheta * radialSpeed)
                .add(bitangent.scale(Math.sin(azimuth) * sinTheta * radialSpeed));
        return downwardVelocity.add(radialVelocity);
    }
}
