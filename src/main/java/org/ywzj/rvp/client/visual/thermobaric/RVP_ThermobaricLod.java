package org.ywzj.rvp.client.visual.thermobaric;

/**
 * 温压粒子的不可变四档距离 LOD 配置。
 *
 * @param nearMaxDistance 近距离档最大爆心距离，单位格
 * @param nearParticleRatio 近距离档粒子保留比例，范围 {@code 0..1}
 * @param mediumMaxDistance 中距离档最大爆心距离，单位格
 * @param mediumParticleRatio 中距离档粒子保留比例，范围 {@code 0..1}
 * @param farMaxDistance 远距离档最大爆心距离，单位格
 * @param farParticleRatio 远距离档粒子保留比例，范围 {@code 0..1}
 * @param beyondParticleRatio 超过远距离档后粒子保留比例，范围 {@code 0..1}
 */
public record RVP_ThermobaricLod(
        float nearMaxDistance,
        float nearParticleRatio,
        float mediumMaxDistance,
        float mediumParticleRatio,
        float farMaxDistance,
        float farParticleRatio,
        float beyondParticleRatio
) {
    /** 内建温压预设使用的默认四档粒子 LOD。 */
    public static final RVP_ThermobaricLod DEFAULT = new RVP_ThermobaricLod(
            512.0F, 1.0F,
            756.0F, 0.7F,
            1024.0F, 0.35F,
            0.0F);

    /**
     * 构造时统一保证距离不递减、粒子比例不递增，避免远距离档反而增加渲染负载。
     */
    public RVP_ThermobaricLod {
        nearMaxDistance = finiteNonNegative(nearMaxDistance);
        mediumMaxDistance = Math.max(nearMaxDistance, finiteNonNegative(mediumMaxDistance));
        farMaxDistance = Math.max(mediumMaxDistance, finiteNonNegative(farMaxDistance));
        nearParticleRatio = unitRatio(nearParticleRatio);
        mediumParticleRatio = Math.min(nearParticleRatio, unitRatio(mediumParticleRatio));
        farParticleRatio = Math.min(mediumParticleRatio, unitRatio(farParticleRatio));
        beyondParticleRatio = Math.min(farParticleRatio, unitRatio(beyondParticleRatio));
    }

    /** 按爆心到相机的距离平方解析当前档位的粒子保留比例。 */
    public float resolveParticleRatio(double distanceSquared) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
            return beyondParticleRatio;
        }
        if (distanceSquared <= square(nearMaxDistance)) {
            return nearParticleRatio;
        }
        if (distanceSquared <= square(mediumMaxDistance)) {
            return mediumParticleRatio;
        }
        if (distanceSquared <= square(farMaxDistance)) {
            return farParticleRatio;
        }
        return beyondParticleRatio;
    }

    /** 防御性规范化非负有限距离；解析器已负责记录配置错误。 */
    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, value) : 0.0F;
    }

    /** 防御性规范化粒子保留比例；解析器已负责记录配置错误。 */
    private static float unitRatio(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, Math.min(1.0F, value)) : 0.0F;
    }

    /** 使用 double 计算距离平方，避免自由配置的大距离在 float 中溢出。 */
    private static double square(float value) {
        return (double) value * value;
    }
}
