package org.ywzj.rvp.hitl;

import net.minecraft.world.level.ChunkPos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 远程观察方形窗口的纯几何运算；不访问世界，不申请区块。 */
public record RVP_HitlRemoteWindow(int x, int z, int radius) {
    public RVP_HitlRemoteWindow {
        radius = Math.max(0, Math.min(RVP_HitlRemoteLimits.MAX_RADIUS_CHUNKS, radius));
    }

    /** 是否位于观察区域；使用 long 做差，避免非法坐标溢出。 */
    public boolean contains(int chunkX, int chunkZ) {
        return Math.abs((long) chunkX - x) <= radius && Math.abs((long) chunkZ - z) <= radius;
    }

    public boolean contains(long key) {
        return contains(ChunkPos.getX(key), ChunkPos.getZ(key));
    }

    /** 从中心向外按环枚举，避免远端地形占完预算而弹体所在区块仍未发送。 */
    public List<Long> chunks() {
        List<Long> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        result.add(ChunkPos.asLong(x + dx, z + dz));
                    }
                }
            }
        }
        return result;
    }

    /** 先保证当前窗口，再把速度方向前方至多两圈纳入有界预取；速度为零不预取。 */
    public List<Long> leases(double vx, double vz, int ahead) {
        LinkedHashSet<Long> result = new LinkedHashSet<>(chunks());
        int offset = Math.max(0, Math.min(2, ahead));
        if (offset > 0 && Double.isFinite(vx) && Double.isFinite(vz) && vx * vx + vz * vz > 1.0E-8) {
            int dx = Math.abs(vx) >= Math.abs(vz) * 0.5 ? (vx > 0 ? offset : -offset) : 0;
            int dz = Math.abs(vz) >= Math.abs(vx) * 0.5 ? (vz > 0 ? offset : -offset) : 0;
            result.addAll(new RVP_HitlRemoteWindow(x + dx, z + dz, radius).chunks());
        }
        return List.copyOf(result);
    }
}
