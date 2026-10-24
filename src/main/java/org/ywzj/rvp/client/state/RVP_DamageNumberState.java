package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端伤害飘字缓冲：世界坐标 + 伤害值 + 生成时间，由 {@code RVP_DamageNumberOverlay} 消费。
 *
 * <p>用 <b>毫秒</b>时间戳而非 tick：飘字生命周期跨 tick 边界，用 tick 计数在低帧率下会跳格。
 * 坐标是命中瞬间的固定世界点（不跟随实体）——跟随的话高速目标会把数字甩出视野，反而看不清。</p>
 */
public final class RVP_DamageNumberState {

    /** 飘字存活时间（毫秒）。 */
    public static final long LIFETIME_MS = 1100L;

    /** 世界空间上飘高度（格）。 */
    public static final double RISE_BLOCKS = 0.9D;

    /** 同屏上限：连续扫射时不无限堆积（超限丢最早的一条）。 */
    private static final int MAX_ENTRIES = 64;

    /** 一条飘字。{@code drift} 是 [-1,1] 的固定水平偏移种子，避免同一位置的多个数字完全重合。 */
    public record Entry(Vec3 pos, float amount, long bornMs, double drift) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private RVP_DamageNumberState() {}

    public static void add(double x, double y, double z, float amount) {
        if (!(amount > 0.0F) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        ENTRIES.add(new Entry(new Vec3(x, y, z), amount, System.currentTimeMillis(),
                Math.random() * 2.0D - 1.0D));
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(0);
        }
    }

    /** 只读快照视图（overlay 每帧遍历，不复制列表）。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 剔除过期项；overlay 每帧调用一次。 */
    public static void prune(long nowMs) {
        ENTRIES.removeIf(entry -> nowMs - entry.bornMs() > LIFETIME_MS);
    }

    /** 断线/切维度时清空，避免残留飘字漂在新场景里。 */
    public static void clear() {
        ENTRIES.clear();
    }
}
