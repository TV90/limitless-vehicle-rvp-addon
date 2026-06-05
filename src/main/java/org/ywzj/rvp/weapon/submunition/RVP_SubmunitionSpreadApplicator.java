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

    private RVP_SubmunitionSpreadApplicator() {}

    public static Vec3 applyVelocitySpread(Vec3 baseVelocity, float yawDeg, float pitchDeg,
                                           RVP_SubmunitionSpreadData spread, int pelletIndex, int pelletCount,
                                           RandomSource random) {
        if (spread == null) {
            return baseVelocity;
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
}
