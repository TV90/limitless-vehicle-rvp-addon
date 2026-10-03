package org.ywzj.rvp.client.visual;

import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 水下爆炸水幕的离散运动与寿命计算工具。
 *
 * <p>工厂和粒子实例共用同一套重力/阻尼顺序，保证创建时计算出的回落寿命与实际 Tick 运动一致。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_UnderwaterExplosionPhysics {

    /** 水幕粒子的竖直重力加速度，单位为每 tick 的速度变化量。 */
    public static final double SPLASH_GRAVITY = 0.06D;
    /** 单枚水幕粒子计算回落时间的安全迭代上限，单位 tick。 */
    private static final int MAX_RETURN_TICKS = 8192;

    private RVP_UnderwaterExplosionPhysics() {
    }

    /**
     * 按粒子实际离散运动计算“上升越过水面后再次回到水面”的 tick 数。
     *
     * @param spawnY 粒子出生世界 Y 坐标
     * @param surfaceY 该水柱的水面世界 Y 坐标
     * @param initialVelocityY 粒子出生时的竖直速度
     * @param downwardDamping 下降阶段速度阻尼
     * @return 至少能够完成一次上升/回落的 tick 数
     */
    public static int resolveReturnTicks(double spawnY, double surfaceY, double initialVelocityY,
                                         float downwardDamping) {
        if (!Double.isFinite(spawnY) || !Double.isFinite(surfaceY)
                || !Double.isFinite(initialVelocityY)) {
            return 1;
        }
        if (initialVelocityY <= 0.0D || spawnY > surfaceY) {
            return 1;
        }

        double y = spawnY;
        double velocityY = initialVelocityY;
        boolean crossedSurface = spawnY >= surfaceY;
        float safeDamping = Math.max(RVP_UnderwaterExplosionTuning.MIN_DOWNWARD_DAMPING,
                Math.min(RVP_UnderwaterExplosionTuning.MAX_DOWNWARD_DAMPING, downwardDamping));
        for (int tick = 1; tick <= MAX_RETURN_TICKS; tick++) {
            // 下降阶段只阻尼已有的向下速度，再叠加重力，保证阻尼为低值时仍会继续回落。
            if (velocityY < 0.0D) {
                velocityY *= safeDamping;
            }
            velocityY -= SPLASH_GRAVITY;
            y += velocityY;
            if (!crossedSurface) {
                if (y >= surfaceY) {
                    crossedSurface = true;
                } else if (velocityY <= 0.0D && y <= spawnY) {
                    // 初始速度不足以越过水面时，粒子没有“离开后再回落”的第二阶段。
                    return tick;
                }
            } else if (y <= surfaceY) {
                return tick;
            }
        }
        // 极端调参仍给出有限寿命；实际粒子不会因普通参数在此处触发。
        return MAX_RETURN_TICKS;
    }

    /**
     * 生成当前随机基础寿命，并抬高到足够完成回落的时长。
     * 水面锚点粒子正常会在回落进入水面带时提前移除，这里的额外时长仅作为寿命安全上限。
     *
     * @param random 爆炸事件确定性随机源
     * @param returnTicks 回落到水面的最低 tick 数
     * @return 最终水幕粒子寿命，单位 tick
     */
    public static int resolveLifetime(RandomSource random, int returnTicks) {
        double denominator = random.nextDouble() * 0.8D + 0.2D;
        int randomLifetime = (int) (80.0D / denominator) + 2;
        int scaledLifetime = Math.max(1, Math.round(randomLifetime
                * RVP_UnderwaterExplosionTuning.getLifetimeScale()));
        int softCappedLifetime = Math.min(scaledLifetime,
                RVP_UnderwaterExplosionTuning.getMaxLifetimeTicks());
        // 粒子 Tick 在 age 达到 lifetime 时会先移除、再更新位置，因此额外保留一个 Tick
        // 才能保证 returnTicks 对应的那次回落位移确实执行。
        int guaranteedLifetime = Math.max(1, returnTicks) + 1
                + RVP_UnderwaterExplosionTuning.getReturnTailTicks();
        // 成熟帧锁定需要先完成一次成长阶段；水面提前清除时仍以水面判定为准。
        int guaranteedMaturity = RVP_UnderwaterExplosionTuning.getMatureTicks() + 1;
        // maxLifetime 是普通随机寿命的软上限，不能削掉物理回落或成长所需的寿命。
        return Math.max(Math.max(softCappedLifetime, guaranteedLifetime), guaranteedMaturity);
    }

    /**
     * 计算单枚水幕粒子的尺寸倍率：先在配置范围内随机，再按距爆心的平滑外围阻尼递减。
     *
     * @param randomUnit [0,1) 的确定性随机值
     * @param horizontalDistance 粒子与爆心的水平距离，单位格
     * @param radius 爆炸半径，单位格
     * @return 相对于基础 splash 尺寸的最终倍率
     */
    public static float resolveSizeMultiplier(float randomUnit, double horizontalDistance, float radius) {
        float safeRandom = Math.max(0.0F, Math.min(1.0F, randomUnit));
        float minimum = RVP_UnderwaterExplosionTuning.getSizeMultiplierMin();
        float maximum = RVP_UnderwaterExplosionTuning.getSizeMultiplierMax();
        float baseMultiplier = minimum + (maximum - minimum) * safeRandom;
        double safeRadius = Math.max(radius, 1.0E-4F);
        double radialProgress = Math.max(0.0D,
                Math.min(1.0D, horizontalDistance / safeRadius));
        // smoothstep 让中心区域保持较大尺寸，向外围过渡时不出现尺寸突变。
        double smoothProgress = radialProgress * radialProgress * (3.0D - 2.0D * radialProgress);
        float outerDamping = RVP_UnderwaterExplosionTuning.getOuterSizeDamping();
        float radialDamping = (float) (1.0D - (1.0D - outerDamping) * smoothProgress);
        return baseMultiplier * radialDamping;
    }
}
