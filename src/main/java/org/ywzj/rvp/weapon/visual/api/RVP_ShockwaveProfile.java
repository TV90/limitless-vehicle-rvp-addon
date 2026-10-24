package org.ywzj.rvp.weapon.visual.api;

import java.util.List;
import java.util.Objects;

/**
 * 服务端测量的冲击波形状，仅用于视觉，不代表真实压力或伤害。
 * @param hitClass 空爆、地爆或埋爆
 * @param groundY 爆心处地表高度（格）
 * @param solidity 地面约束程度，范围 0～1
 * @param reach 八方向最终半径（格），从 +X 起向 +Z 旋转，每隔 45 度一项
 */
public record RVP_ShockwaveProfile(HitClass hitClass, double groundY, float solidity, List<Float> reach) {
    /** 固定方向数，网络两端必须一致。 */
    public static final int DIRECTION_COUNT = 8;
    /** 视觉半径上限（格），防止极端配置生成无限大几何。 */
    public static final float MAX_RADIUS = 2048.0F;

    /** 地形关系分类。 */
    public enum HitClass {
        /** 爆心高于地面 2.5 格。 */
        AIR,
        /** 贴近地面。 */
        GROUND,
        /** 爆心低于地面超过 1 格。 */
        BURIED
    }

    public RVP_ShockwaveProfile {
        Objects.requireNonNull(hitClass, "hitClass");
        reach = List.copyOf(reach);
        if (!Double.isFinite(groundY) || !Float.isFinite(solidity) || solidity < 0 || solidity > 1
                || reach.size() != DIRECTION_COUNT
                || reach.stream().anyMatch(r -> !Float.isFinite(r) || r < 0 || r > MAX_RADIUS)) {
            throw new IllegalArgumentException("Invalid shockwave profile");
        }
    }

    /** 在相邻方向之间插值；角度单位为弧度，0 为 +X，π/2 为 +Z。 */
    public float radiusAt(double angle) {
        if (!Double.isFinite(angle)) {
            throw new IllegalArgumentException("Non-finite shockwave angle");
        }
        double sector = ((angle / (Math.PI * 2) % 1 + 1) % 1) * DIRECTION_COUNT;
        int index = (int) sector;
        return (float) (reach.get(index) + (reach.get((index + 1) % DIRECTION_COUNT) - reach.get(index))
                * (sector - index));
    }

    /** 按计划阈值判断爆心与地面的关系。 */
    public static HitClass classify(double heightAboveGround) {
        return heightAboveGround > 2.5 ? HitClass.AIR : heightAboveGround < -1 ? HitClass.BURIED : HitClass.GROUND;
    }

    /** 视觉尺寸计算使用 double 中间值，避免大倍率乘法溢出。 */
    public static float visualRadius(float explosionRadius, float scale) {
        return (float) Math.min(MAX_RADIUS, Math.max(4.0, explosionRadius * 2.5D) * scale);
    }
}
