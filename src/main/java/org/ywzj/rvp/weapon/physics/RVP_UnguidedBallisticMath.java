package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 无世界状态的无制导离散弹道步进；实体运动与炮火支援解算共同调用，防止两套公式漂移。
 */
public final class RVP_UnguidedBallisticMath {
    /** 速度近似为零时停止执行方向阻力或归一化。 */ private static final double MIN_SPEED_SQUARED = 1.0E-12D;

    private RVP_UnguidedBallisticMath() {}

    /**
     * 把调用方期望的实体初始化后速度换算为 SpawnContext 需要的输入速度。
     * 调用本项目既有高初速火箭兼容规则的逆变换，保证实体初始化后仍为武器标称速度。
     */
    public static Vec3 resolveSpawnContextMotion(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                                  Vec3 desiredInitializedMotion) {
        if (data == null || kind == null || desiredInitializedMotion == null) return desiredInitializedMotion;
        float projectileSpeed = data.getProjectileVelocity();
        if (!data.usesPropulsion() && kind == RVP_EnumWeaponKind.ROCKET && projectileSpeed > 4.0F) {
            return desiredInitializedMotion.scale(4.0D / projectileSpeed);
        }
        return desiredInitializedMotion;
    }

    /** 对齐 {@code RVP_BulletEntity} 的“先移动、后摩擦与重力”单 Tick。 */
    public static Step stepCannon(Vec3 position, Vec3 velocity, float friction, float gravity) {
        Vec3 nextPosition = position.add(velocity);
        Vec3 nextVelocity = velocity.scale(1.0D - friction).add(0.0D, -gravity, 0.0D);
        return new Step(nextPosition, nextVelocity);
    }

    /** 对齐 {@code RVP_BaseBullet.tickBallisticMotion()} 的“先受力、后移动”单 Tick。 */
    public static Step stepProjectile(Vec3 position, Vec3 velocity, RVP_WeaponData data) {
        Vec3 nextVelocity = velocity.add(0.0D, data.getGravity(), 0.0D);
        nextVelocity = applyMchHorizontalDrag(nextVelocity, data.getDragInAir());
        if (data.getProjectileData().isConstantSpeed() && nextVelocity.lengthSqr() > 1.0E-6D) {
            nextVelocity = nextVelocity.normalize().scale(Math.max(velocity.length(), 0.01D));
        }
        nextVelocity = clampSpeed(nextVelocity, data.getProjectileData().getMinSpeed(),
                data.getProjectileData().getMaxSpeed());
        return new Step(position.add(nextVelocity), nextVelocity);
    }

    /** 对齐 {@code RVP_BombEntity}：未配置自定义重力时先补本体炸弹默认重力。 */
    public static Step stepBomb(Vec3 position, Vec3 velocity, RVP_WeaponData data) {
        Vec3 bombVelocity = data.getGravity() == 0.0F
                ? velocity.add(0.0D, -PhysicsEngine.G, 0.0D) : velocity;
        return stepProjectile(position, bombVelocity, data);
    }

    /** 对齐 MCH 语义：阻力只减少 X/Z，方向按包含 Y 的总速度归一。 */
    public static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag) {
        if (drag <= 0.0F || velocity.lengthSqr() <= MIN_SPEED_SQUARED) return velocity;
        double speed = velocity.length();
        return new Vec3(velocity.x - velocity.x / speed * drag, velocity.y,
                velocity.z - velocity.z / speed * drag);
    }

    /** 按武器最小/最大速度限制等比缩放速度向量。 */
    public static Vec3 clampSpeed(Vec3 velocity, float minSpeed, float maxSpeed) {
        double speed = velocity.length();
        if (speed <= 1.0E-6D) return velocity;
        if (maxSpeed > 0.0F && minSpeed > maxSpeed) minSpeed = 0.0F;
        if (maxSpeed > 0.0F && speed > maxSpeed) return velocity.scale(maxSpeed / speed);
        if (minSpeed > 0.0F && speed < minSpeed) return velocity.scale(minSpeed / speed);
        return velocity;
    }

    /** 一次离散积分后的不可变位置与速度。 */
    public record Step(
            /** Tick 完成后的世界位置。 */ Vec3 position,
            /** Tick 完成后供下一 Tick 使用的速度。 */ Vec3 velocity) {}
}
