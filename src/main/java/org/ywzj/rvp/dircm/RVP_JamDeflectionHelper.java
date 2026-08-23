package org.ywzj.rvp.dircm;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeSaclosGuidanceSource;

/**
 * 被干扰弹体的强制偏转公共工具（SACLOS 光电干扰机与 DIRCM 共用）。
 *
 * <p>数学取自 {@link RVP_RuntimeSaclosGuidanceSource} 的
 * {@code applyVelocityRotation}（每 tick 把水平速度方向旋转指定角度）与
 * {@code offsetDir}（横向 + 向下合成斜向拉偏），抽成公共方法供两套干扰复用。</p>
 *
 * <p>DIRCM 复用现有 {@code RVP_BaseBullet.jamming*} 字段族：设置 {@code jammingStrength}、
 * {@code jammingSideSign}（远离干扰机侧）、{@code jammingHeadingRate}、{@code jammingOffset*}
 * 后，被干扰弹体每 tick 经 {@link #applyDeflection} 持续横向偏航 + 下坠。</p>
 */
public final class RVP_JamDeflectionHelper {

    private RVP_JamDeflectionHelper() {
    }

    /** 每 tick 最大旋转角度（度）：与 SACLOS 源一致的钳制上限。 */
    private static final double JAM_HEADING_MAX_DEG = 15.0;

    /**
     * 对被干扰弹体施加一次强制偏转：旋转水平速度方向（每 tick {@code headingRate×强度} 度，
     * 朝远离干扰机一侧）+ 叠加向下偏移分量。
     *
     * @param projectile 被干扰弹体（已写入 {@code jamming*} 字段）
     * @return 是否成功施加了偏转（强度 &gt; 0 且弹体有效）
     */
    public static boolean applyDeflection(RVP_BaseBullet projectile) {
        if (projectile == null || projectile.jammingStrength <= 0.0) {
            return false;
        }
        applyVelocityRotation(projectile);
        return true;
    }

    /** 直接修改速度方向分量：水平速度方向朝「远离干扰机」一侧旋转。 */
    private static void applyVelocityRotation(RVP_BaseBullet projectile) {
        double strength = projectile.jammingStrength;
        if (strength <= 0.0) {
            return;
        }
        double side = projectile.jammingSideSign;
        if (side == 0.0) {
            side = 1.0;
        }
        Vec3 vel = projectile.getDeltaMovement();
        double hSpeed = Math.hypot(vel.x, vel.z);
        if (hSpeed > 1.0E-6) {
            Vec3 hDir = new Vec3(vel.x, 0.0, vel.z).normalize();
            Vec3 away = buildHorizontalPerp(hDir).scale(side).normalize();
            double theta = Math.toRadians(Math.min(projectile.jammingHeadingRate * strength, JAM_HEADING_MAX_DEG));
            Vec3 hNew = hDir.scale(Math.cos(theta)).add(away.scale(Math.sin(theta)));
            if (hNew.lengthSqr() > 1.0E-8) {
                hNew = hNew.normalize().scale(hSpeed);
                projectile.setDeltaMovement(hNew.x, vel.y, hNew.z);
            }
        }
    }

    /** 生成与 {@code dir} 垂直的水平方向单位向量（dir 竖直时退化为正 x 方向）。 */
    private static Vec3 buildHorizontalPerp(Vec3 dir) {
        Vec3 horizontalDir = new Vec3(dir.x, 0.0, dir.z);
        if (horizontalDir.lengthSqr() <= 1.0E-8) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        Vec3 perp = horizontalDir.normalize().cross(up);
        return perp.lengthSqr() <= 1.0E-8 ? new Vec3(1.0, 0.0, 0.0) : perp.normalize();
    }
}