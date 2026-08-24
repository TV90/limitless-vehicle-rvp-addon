package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;

/** 子弹药释放瞬间解析父弹风向上下文的纯数学工具。 */
public final class RVP_WindDirectionUtil {

    private RVP_WindDirectionUtil() {}

    /**
     * 把以世界北方为零、顺时针为正的罗盘角转换为 Minecraft 水平单位向量。
     *
     * @param clockwiseDegrees 从北方顺时针旋转的角度，单位度
     * @return 世界水平单位向量；0 为北方 -Z，+90 为东方 +X
     */
    public static Vec3 resolveFixedNorth(double clockwiseDegrees) {
        if (!Double.isFinite(clockwiseDegrees)) {
            return Vec3.ZERO;
        }
        double radians = Math.toRadians(Math.IEEEremainder(clockwiseDegrees, 360.0D));
        return new Vec3(Math.sin(radians), 0.0D, -Math.cos(radians)).normalize();
    }

    /**
     * 优先使用父弹当前旋转朝向的反向；仅水平投影退化时才回退父弹当前速度反向。
     *
     * @param parentCurrentFacing 释放 Tick 由父弹 yaw/pitch 得到的当前朝向单位向量
     * @param parentCurrentVelocity 释放 Tick 的父弹当前速度，仅作极端垂直朝向回退
     * @param verticalFactor 垂直分量保留比例，范围由数据模型限制为 0～1
     * @return 可安全用于风漂的单位向量
     */
    public static Vec3 resolveParentFacingReverse(Vec3 parentCurrentFacing,
                                                  Vec3 parentCurrentVelocity,
                                                  float verticalFactor) {
        Vec3 facing = parentCurrentFacing == null ? Vec3.ZERO : parentCurrentFacing;
        Vec3 reverseFacing = facing.scale(-1.0D);
        Vec3 configured = new Vec3(
                reverseFacing.x,
                reverseFacing.y * Math.max(0.0f, Math.min(verticalFactor, 1.0f)),
                reverseFacing.z);
        if (configured.lengthSqr() < 1.0E-10) {
            Vec3 velocity = parentCurrentVelocity == null ? Vec3.ZERO : parentCurrentVelocity;
            configured = new Vec3(-velocity.x, 0.0D, -velocity.z);
        }
        return configured.lengthSqr() < 1.0E-10
                ? new Vec3(0.0D, 0.0D, 1.0D)
                : configured.normalize();
    }
}
