package org.ywzj.rvp.client.visual.cookoff;

/** 无客户端依赖的预算规则，供自动测试验证高密度残骸上限。 */
public final class RVP_WreckCookoffBudget {
    /**
     * 所有车辆每 tick 最多尝试生成的火星数。
     * 2026-10-06 随"每座炮塔 12 点接缝"从 160 上调到 320：单车上限同步翻倍，
     * 使双车同屏时各自仍能拿到与上调前相同的单车额度。
     */
    public static final int PARTICLES_PER_TICK = 320;
    /** 一辆车每 tick 的上限，防止高倍率或多出口耗尽全局配额。 */
    public static final int PARTICLES_PER_VEHICLE = 96;
    /** 同时渲染的出口总上限；所有车辆采用轮流分配而非整车抢占。 */
    public static final int MAX_COLUMNS = 96;
    /**
     * 满额距离，单位格；此距离内不做距离衰减。
     * 2026-10-01 由 96 放宽到 128
     */
    public static final double FULL_DETAIL_DISTANCE = 128;
    /** 舱盖出口的粒子权重；三档权重之比即 10 : 10 : 0。 */
    public static final int WEIGHT_HATCH = 10;
    /** 接缝出口的粒子权重；接缝点数最多，与舱盖同级，使每个采样点都有火星。 */
    public static final int WEIGHT_SEAM = 10;
    /**
     * 炮口出口的粒子权重；用户定版 2026-10-06：**取消炮口喷火星**，只保留炮口轴向火柱。
     * 权重为 0 使 {@link #baseDemand} 不再为炮口申请火星额度。
     */
    public static final int WEIGHT_MUZZLE = 0;

    private RVP_WreckCookoffBudget() {}

    /**
     * 单类出口的粒子权重；调用方（发射与预算估算）共用同一张表，避免两处各写一份。
     * 权重为 0 表示该类出口不参与火星（当前仅炮口）。
     */
    public static int weightOf(RVP_WreckCookoffResolver.Kind kind) {
        return switch (kind) {
            case HATCH -> WEIGHT_HATCH;
            case SEAM -> WEIGHT_SEAM;
            case MUZZLE -> WEIGHT_MUZZLE;
        };
    }

    /**
     * 一辆车在密度 1、粒子设置"全部"时的基础需求：各出现过的出口类别权重之和。
     * 若单车上限低于它，出口越多每处分到的火星越少，"大量接缝喷火"会被预算压回去。
     *
     * @param presentKinds 该车当前有效的出口类别
     */
    public static int baseDemand(Iterable<RVP_WreckCookoffResolver.Kind> presentKinds) {
        int base = 0;
        for (RVP_WreckCookoffResolver.Kind kind : presentKinds) {
            base += weightOf(kind);
        }
        return base;
    }

    /** 根据距离降低粒子数；{@link #FULL_DETAIL_DISTANCE} 格内满额，更远逐档下降。 */
    public static double distanceScale(double distance) {
        return distance <= FULL_DETAIL_DISTANCE ? 1
                : distance <= 256 ? 0.6
                : distance <= 512 ? 0.3 : 0;
    }

    /** 输入按近到远排列；逐轮每车分一个名额，剩余额度才给高需求车辆。 */
    public static int[] allocate(int[] requests, int total, int perVehicle) {
        int[] result = new int[requests.length];
        for (int round = 0; round < perVehicle && total > 0; round++) {
            for (int i = 0; i < requests.length && total > 0; i++) {
                if (requests[i] > round) {
                    result[i]++;
                    total--;
                }
            }
        }
        return result;
    }
}
