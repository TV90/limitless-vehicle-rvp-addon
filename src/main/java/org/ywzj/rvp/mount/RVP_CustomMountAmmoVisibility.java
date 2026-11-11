package org.ywzj.rvp.mount;

/**
 * 自定义挂架多弹体可见性分配（纯函数，供客户端渲染与单测共用）。
 */
public final class RVP_CustomMountAmmoVisibility {

    private RVP_CustomMountAmmoVisibility() {
    }

    /**
     * 旧行为：按挂架顺序整段消耗，前一个挂架的弹体全部消失后才轮到下一个。
     *
     * @param missileCounts 各挂架的弹体数量（调用方已按 ammo_slot / 配置顺序排序）
     * @param fired         已发射发数（会被钳制到 0..总弹数）
     * @return 每个挂架应隐藏的弹体数量
     */
    public static int[] sequential(int[] missileCounts, int fired) {
        int[] hidden = new int[missileCounts.length];
        int remaining = clampFired(missileCounts, fired);
        for (int i = 0; i < missileCounts.length; i++) {
            int take = Math.min(Math.max(0, missileCounts[i]), remaining);
            hidden[i] = take;
            remaining -= take;
        }
        return hidden;
    }

    /**
     * 轮转行为：每一发按挂架顺序消耗当前挂架的下一枚，匹配 RIPPLE 在多个挂点间
     * 交替开火时的弹体消失顺序。某个挂架弹体耗尽后自动跳过。
     *
     * @param missileCounts 各挂架的弹体数量（调用方已按 ammo_slot / 配置顺序排序）
     * @param fired         已发射发数（会被钳制到 0..总弹数）
     * @return 每个挂架应隐藏的弹体数量
     */
    public static int[] roundRobin(int[] missileCounts, int fired) {
        int[] hidden = new int[missileCounts.length];
        int remaining = clampFired(missileCounts, fired);
        int cursor = 0;
        while (remaining > 0) {
            boolean consumed = false;
            for (int step = 0; step < missileCounts.length; step++) {
                int index = (cursor + step) % missileCounts.length;
                if (hidden[index] >= missileCounts[index]) {
                    continue;
                }
                hidden[index]++;
                remaining--;
                cursor = (index + 1) % missileCounts.length;
                consumed = true;
                break;
            }
            if (!consumed) {
                break;
            }
        }
        return hidden;
    }

    private static int clampFired(int[] missileCounts, int fired) {
        int total = 0;
        for (int count : missileCounts) {
            total += Math.max(0, count);
        }
        return Math.max(0, Math.min(fired, total));
    }
}
