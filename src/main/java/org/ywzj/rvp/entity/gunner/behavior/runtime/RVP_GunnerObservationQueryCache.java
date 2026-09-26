package org.ywzj.rvp.entity.gunner.behavior.runtime;

import java.util.List;
import java.util.function.Supplier;

/**
 * 单 tick 惰性查询底座：无论上层派生多少观察查询，底层实体源只抓取一次。
 *
 * <p>该类型不依赖 Minecraft 实体，便于用普通单元测试固定“相同观察需求不会重复遍历世界”的约束。</p>
 */
final class RVP_GunnerObservationQueryCache<T> {

    /** 首次请求时创建不可变快照的数据源。 */
    private final Supplier<List<T>> source;
    /** 已创建的不可变快照；尚未请求时为 null。 */
    private List<T> snapshot;
    /** 数据源被实际调用的次数；正常单 tick 只允许为 0 或 1。 */
    private int loadCount;

    RVP_GunnerObservationQueryCache(Supplier<List<T>> source) {
        this.source = source;
    }

    /** 返回本 tick 的唯一不可变快照。 */
    List<T> snapshot() {
        if (snapshot == null) {
            snapshot = List.copyOf(source.get());
            loadCount++;
        }
        return snapshot;
    }

    /** 返回底层数据源实际加载次数。 */
    int loadCount() {
        return loadCount;
    }
}
