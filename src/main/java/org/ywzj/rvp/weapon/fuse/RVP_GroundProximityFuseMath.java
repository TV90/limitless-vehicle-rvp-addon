package org.ywzj.rvp.weapon.fuse;

import net.minecraft.world.phys.Vec3;

/**
 * 近地引信运动段几何换算。世界碰撞射线由弹体逻辑负责，本类只还原准确起爆点。
 */
public final class RVP_GroundProximityFuseMath {

    /** 视为无运动段的长度平方阈值，避免除零和浮点噪声。 */
    private static final double MIN_SEGMENT_LENGTH_SQR = 1.0E-12D;

    private RVP_GroundProximityFuseMath() {}

    /** 仅在配置距离为正且弹体计时达到独立解保 Tick 时执行世界射线检测。 */
    public static boolean isArmed(float clearance, int armTick, int updateCount) {
        return Float.isFinite(clearance) && clearance > 0f && updateCount >= Math.max(armTick, 0);
    }

    /**
     * 将“原运动段向下平移 {@code clearance} 后”的方块命中点，按段内比例还原到原运动段。
     * 调用目的：高速弹体单 Tick 跨过设定高度时，仍在正确离地高度起爆而不是直接撞地。
     */
    public static Vec3 restoreDetonationPosition(Vec3 segmentStart, Vec3 segmentEnd,
                                                  Vec3 shiftedHitPosition, double clearance) {
        Vec3 movement = segmentEnd.subtract(segmentStart);
        double lengthSqr = movement.lengthSqr();
        if (lengthSqr <= MIN_SEGMENT_LENGTH_SQR || !Double.isFinite(clearance)) {
            return segmentStart;
        }
        Vec3 shiftedStart = segmentStart.add(0.0D, -Math.max(clearance, 0.0D), 0.0D);
        double progress = shiftedHitPosition.subtract(shiftedStart).dot(movement) / lengthSqr;
        if (!Double.isFinite(progress)) {
            return segmentStart;
        }
        progress = Math.max(0.0D, Math.min(progress, 1.0D));
        return segmentStart.add(movement.scale(progress));
    }
}
