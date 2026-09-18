package org.ywzj.rvp.util;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

public final class RVP_CcipUtil {

    private static final int MAX_TICKS = 1200;

    /** 弹道 march 的最大飞行距离（格）：超出视距的落点对准星无意义（2026-09-19 天空垂落修复）。 */
    private static final double MARCH_MAX_DISTANCE = 512.0D;

    private RVP_CcipUtil() {}

    @Nullable
    public static Vec3 computeBombImpact(Level level, Vec3 startPos, Vec3 startVelocity, RVP_WeaponData data) {
        if (level == null || startPos == null || startVelocity == null || data == null) {
            return null;
        }
        double x = startPos.x;
        double y = startPos.y;
        double z = startPos.z;
        Vec3 velocity = startVelocity;
        double flightSpeed = Math.max(startVelocity.length(), 0.01D);
        boolean usesPropulsion = data.usesPropulsion();
        boolean bombDefaultGravity = data.getWeaponKind() == RVP_EnumWeaponKind.BOMB
                && !usesPropulsion
                && data.getGravity() == 0f;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (usesPropulsion) {
                velocity = stepPropulsionVelocity(velocity, data);
            } else {
                velocity = stepBallisticVelocity(velocity, data, bombDefaultGravity, flightSpeed);
            }
            flightSpeed = Math.max(velocity.length(), 0.01D);

            x += velocity.x;
            y += velocity.y;
            z += velocity.z;

            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            if (y <= groundY) {
                return new Vec3(x, groundY, z);
            }
        }
        return null;
    }

    private static Vec3 stepBallisticVelocity(Vec3 velocity, RVP_WeaponData data, boolean bombDefaultGravity, double flightSpeed) {
        double gravity = data.getGravity();
        if (bombDefaultGravity) {
            gravity = -PhysicsEngine.G;
        }
        Vec3 next = velocity.add(0.0D, gravity, 0.0D);
        next = applyMchHorizontalDrag(next, data.getDragInAir());
        if (data.getProjectileData().isConstantSpeed() && next.lengthSqr() > 1.0E-6) {
            next = next.normalize().scale(Math.max(flightSpeed, 0.01D));
        }
        return clampSpeed(next, data.getProjectileData().getMinSpeed(), data.getProjectileData().getMaxSpeed());
    }

    private static Vec3 stepPropulsionVelocity(Vec3 velocity, RVP_WeaponData data) {
        Vec3 next = velocity;
        double speedSqr = next.lengthSqr();
        if (speedSqr > 1.0E-12 && data.getResolvedDragCoefficient() > 0f) {
            next = next.add(next.normalize().scale(-data.getResolvedDragCoefficient() * speedSqr));
        }
        float gravity = data.getGravity();
        if (gravity != 0f) {
            next = next.add(0.0D, gravity, 0.0D);
        } else {
            next = next.subtract(0.0D, PhysicsEngine.G, 0.0D);
        }
        return clampSpeed(next, data.getProjectileData().getMinSpeed(), data.getProjectileData().getMaxSpeed());
    }

    private static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag) {
        if (drag <= 0f) {
            return velocity;
        }
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        double dirX = velocity.x / speed;
        double dirZ = velocity.z / speed;
        return new Vec3(
                velocity.x - dirX * drag,
                velocity.y,
                velocity.z - dirZ * drag
        );
    }

    private static Vec3 clampSpeed(Vec3 velocity, float minSpeed, float maxSpeed) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float clampedMin = Math.max(minSpeed, 0f);
        float clampedMax = Math.max(maxSpeed, 0f);
        if (clampedMax > 0f && clampedMin > 0f && clampedMin > clampedMax) {
            clampedMin = 0f;
        }
        if (clampedMax > 0f && speed > clampedMax) {
            return velocity.normalize().scale(clampedMax);
        }
        if (clampedMin > 0f && speed < clampedMin) {
            return velocity.normalize().scale(clampedMin);
        }
        return velocity;
    }

    /**
     * RVP 弹药弹道 march：观瞄准星预测用（按武器 kind 走与真实弹完全相同的积分器）。
     *
     * <p>MACHINEGUN 走 {@link RVP_UnguidedBallisticMath#stepCannon}（位移 → {@code cannon_friction}
     * 线性摩擦 → {@code cannon_gravity} 重力，对齐 {@code RVP_BulletEntity}）；MISSILE/ROCKET/BOMB 走
     * {@link RVP_UnguidedBallisticMath#stepBomb}/{@link RVP_UnguidedBallisticMath#stepProjectile}
     * （{@code data.getGravity} / {@code drag_in_air} / 恒速与最小最大速度钳制，与炮火支援解算同源）。
     * 初速 {@code resolveMuzzleSpeed(kind)}，按 {@code inherit_vehicle_velocity} 叠加载具速度。</p>
     *
     * <p>每 tick 段内用本体 {@link VectorUtil#hitPosition}（方块+实体 AABB+遮挡，与本体准星同一语义）
     * 求交，返回点偏离段终点即视为命中并返回落点。寿命（{@code life}，钳 [10,120]）内、或飞行距离超过
     * 视距上限（512 格）仍未命中时返回 <b>null</b>——调用方（观瞄准星）据此把准星居中，而不是标记
     * 数千格外地平线下的弹道终点（2026-09-19 天空垂落修复）。本体准星对 RVP 武器只有直线射线
     * （无下坠/阻力且不识别 RVP 弹道参数），此方法补齐物理模型（2026-09-18 观瞄准星 RVP 弹道适配）。
     * 当前调用方（观瞄准星适配）仅对 MACHINEGUN 类启用（2026-09-19 用户定版，火箭/导弹/炸弹暂不适配）；
     * 其余 kind 的积分分支保留备用。</p>
     */
    @Nullable
    public static Vec3 computeBulletImpact(Level level, Vec3 startPos, Vec3 aimDir, RVP_WeaponData data,
                                           @Nullable Entity contextEntity) {
        if (level == null || startPos == null || aimDir == null || data == null
                || aimDir.lengthSqr() < 1.0E-8) {
            return null;
        }
        RVP_EnumWeaponKind kind = data.getWeaponKind();
        Vec3 velocity = aimDir.normalize()
                .scale(Math.max(data.resolveMuzzleSpeed(kind), 0.01f));
        if (data.isInheritVehicleVelocity() && contextEntity != null) {
            velocity = velocity.add(contextEntity.getDeltaMovement());
        }
        int maxTicks = Mth.clamp(data.getLife() > 0 ? data.getLife() : 120, 10, 120);
        Vec3 position = startPos;
        double travelled = 0.0D;
        for (int tick = 0; tick < maxTicks; tick++) {
            Vec3 next;
            Vec3 nextVelocity;
            switch (kind) {
                case BOMB -> {
                    RVP_UnguidedBallisticMath.Step step = RVP_UnguidedBallisticMath.stepBomb(position, velocity, data);
                    next = step.position();
                    nextVelocity = step.velocity();
                }
                case MISSILE, ROCKET -> {
                    // 先受力后移动语义（与 RVP_BaseBullet.tickBallisticMotion 一致）
                    RVP_UnguidedBallisticMath.Step step = RVP_UnguidedBallisticMath.stepProjectile(position, velocity, data);
                    next = step.position();
                    nextVelocity = step.velocity();
                }
                default -> {
                    // MACHINEGUN：先移动后摩擦与重力（与 RVP_BulletEntity.tickBulletMotionAndFacing 一致）
                    next = position.add(velocity);
                    nextVelocity = RVP_UnguidedBallisticMath.stepCannon(position, velocity,
                            data.getCannonFriction(), data.getCannonGravity()).velocity();
                }
            }
            // 调用本体命中求交：方块+实体 AABB+遮挡一体语义，返回点与段终点不重合即段内有命中
            Vec3 segmentHit = VectorUtil.hitPosition(contextEntity, position, next);
            if (segmentHit.distanceToSqr(next) > 1.0E-6) {
                return segmentHit;
            }
            position = next;
            velocity = nextVelocity;
            travelled += velocity.length();
            // 视距外仍无命中：落点对准星无意义，交由调用方居中处理
            if (travelled >= MARCH_MAX_DISTANCE || position.y < level.getMinBuildHeight() - 16) {
                return null;
            }
        }
        return null;
    }
}
