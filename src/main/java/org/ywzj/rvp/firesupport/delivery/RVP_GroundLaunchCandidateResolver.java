package org.ywzj.rvp.firesupport.delivery;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** 地射回退候选的纯数学定位与 Chunk 账本释放判定。 */
final class RVP_GroundLaunchCandidateResolver {
    /** 不可达时每次向目标缩短的发射距离，单位格。 */ static final double FALLBACK_STEP_METERS = 16.0D;
    /** 比较距离与坐标时忽略的浮点误差。 */ private static final double EPSILON = 1.0E-9D;

    private RVP_GroundLaunchCandidateResolver() {}

    /** 按任务锚点和入场方向计算既有固定炮位的 XZ 坐标。 */
    static Vec3 fromAnchor(double anchorX, double anchorZ, Vec3 inboundDirection, double distanceMeters) {
        return new Vec3(anchorX - inboundDirection.x * distanceMeters, 0.0D,
                anchorZ - inboundDirection.z * distanceMeters);
    }

    /** 按本发落点和入场方向计算回退炮位的 XZ 坐标。 */
    static Vec3 fromImpact(double impactX, double impactZ, Vec3 inboundDirection, double distanceMeters) {
        return new Vec3(impactX - inboundDirection.x * distanceMeters, 0.0D,
                impactZ - inboundDirection.z * distanceMeters);
    }

    /** 返回不超过锚点实际射程与配置标称距离的首次回退距离，保证回退不会拉远炮位。 */
    static double initialFallbackDistance(Vec3 anchorLaunchXZ, double impactX, double impactZ,
                                          double configuredDistanceMeters) {
        double actualDistance = horizontalDistance(anchorLaunchXZ, impactX, impactZ);
        return Math.min(Math.floor(configuredDistanceMeters), Math.floor(actualDistance));
    }

    /** 按确认的 16 格粒度缩短距离；非整除时仍返回最小距离，耗尽后返回 NaN。 */
    static double nextFallbackDistance(double currentDistanceMeters, double minimumDistanceMeters) {
        if (currentDistanceMeters <= minimumDistanceMeters + EPSILON) return Double.NaN;
        double next = currentDistanceMeters - FALLBACK_STEP_METERS;
        return next <= minimumDistanceMeters + EPSILON ? minimumDistanceMeters : next;
    }

    /** 判断替换候选时旧发射 Chunk 是否已不被目标或新候选使用，因而可释放任务账本。 */
    static boolean shouldReleaseSupersededLaunchChunk(ChunkPos previousLaunchChunk, ChunkPos nextLaunchChunk,
                                                      ChunkPos impactChunk) {
        return !previousLaunchChunk.equals(nextLaunchChunk) && !previousLaunchChunk.equals(impactChunk);
    }

    /** 计算炮位到本发落点的水平实际距离。 */
    static double horizontalDistance(Vec3 launchXZ, double impactX, double impactZ) {
        return Math.hypot(impactX - launchXZ.x, impactZ - launchXZ.z);
    }

    /** 判断两个候选坐标在双精度误差内是否相同。 */
    static boolean sameXZ(Vec3 first, Vec3 second) {
        return Math.abs(first.x - second.x) <= EPSILON && Math.abs(first.z - second.z) <= EPSILON;
    }
}
