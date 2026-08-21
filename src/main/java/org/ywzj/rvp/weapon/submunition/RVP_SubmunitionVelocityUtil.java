package org.ywzj.rvp.weapon.submunition;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;

/**
 * 子弹药世界系附加速度计算。
 */
public final class RVP_SubmunitionVelocityUtil {

    /** 视为未配置附加速度的向量长度平方阈值。 */
    private static final double ZERO_VECTOR_LENGTH_SQR = 1.0E-16D;

    private RVP_SubmunitionVelocityUtil() {}

    /**
     * 在已完成继承、发射角和散布计算的速度末端叠加配置冲量。
     * 零向量或零随机因子不会消费随机数，保证未使用新字段的旧武器散布序列不变。
     */
    public static Vec3 applyConfiguredImpulse(Vec3 baseVelocity, RVP_SubmunitionPayloadData payload,
                                               RandomSource random) {
        // 调用本项目 payload 数据 getter：统一执行数组长度、非有限数值与默认值校验。
        Vec3 impulse = payload.getPayloadsVelocity();
        if (impulse.lengthSqr() <= ZERO_VECTOR_LENGTH_SQR) {
            return baseVelocity;
        }
        // 调用本项目 payload 数据 getter：把随机浮动比例限制在文档承诺的 0..1。
        float factor = payload.getPayloadsVelocityFactor();
        double multiplier = 1.0D;
        if (factor > 0f && random != null) {
            multiplier += (random.nextDouble() * 2.0D - 1.0D) * factor;
        }
        return baseVelocity.add(impulse.scale(multiplier));
    }
}
