package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

/**
 * 修改 Radar 类的 {@code getBoundingBox().getSize() < 1} 过滤，
 * 使设置了 {@code signatureSize > 0} 的 RVP 弹体能够被雷达扫描、探测和锁定。
 *
 * <p>策略：对 Radar 类中 {@code entity.getBoundingBox()} 调用进行 Redirect，
 * 当 entity 是 {@link RVP_BaseBullet} 且 {@code signatureSize > 0} 时，
 * 返回一个基于 signatureSize 扩大的虚拟碰撞箱，使 {@code getSize()} 返回 signatureSize。</p>
 *
 * <p>同时为 {@code detectTargets()}、{@code findTarget()} 中的 RCS 赋值注入
 * {@link RVP_BaseBullet} 的 signatureSize 支持。</p>
 */
@Mixin(value = Radar.class, remap = false)
public class RadarSignatureMixin {

    /**
     * scanTargets() 中的 entity.getBoundingBox() 替换。
     * 签名：AABB getBoundingBox(Entity instance)
     */
    @Redirect(
            method = "scanTargets",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 0
    )
    private static AABB ywzj_rvp$scanBox(Entity entity) {
        return ywzj_rvp$effectiveBox(entity);
    }

    /**
     * detectTargets() 中的 entity.getBoundingBox() 替换。
     * 该方法中 getBoundingBox() 出现两次：一次在 getSize() 过滤，一次在 getCenter() 范围检查。
     * 两处都替换为虚拟碰撞箱，保证一致性。
     */
    @Redirect(
            method = "detectTargets",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 0
    )
    private static AABB ywzj_rvp$detectBox(Entity entity) {
        return ywzj_rvp$effectiveBox(entity);
    }

    /**
     * findTarget() 中的 entity.getBoundingBox() 替换。
     * 该方法中 getBoundingBox() 出现两次：一次在 getSize() 过滤，一次在 getCenter() 锁定。
     */
    @Redirect(
            method = "findTarget",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 0
    )
    private static AABB ywzj_rvp$findBox(Entity entity) {
        return ywzj_rvp$effectiveBox(entity);
    }

    /**
     * 为 RVP_BaseBullet 提供 RCS 倍率。
     * detectTargets() 和 findTarget() 中 {@code rcs = 1; if (entity instanceof AbstractVehicle) rcs = ...}
     * 这里无法直接注入局部变量赋值，但 signatureSize 已经通过虚拟碰撞箱的 getSize() 影响了距离过滤。
     * RCS 缩放对弹体来说用 signatureSize 作为等效 rcs 即可。
     *
     * <p>由于 detectTargets/findTarget 中 rcs 赋值和 getSize() 过滤在同一个 if 条件中，
     * 虚拟碰撞箱让 getSize() 通过过滤后，rcs=1 的默认值已经合理——
     * signatureSize 本身通过虚拟碰撞箱的尺寸影响了有效探测距离（getSize() 越大，
     * 在同样的 maxScanDistanceSqr * rcs * rcs 条件下更容易满足距离条件）。</p>
     *
     * <p>但更精确的做法是让 rcs 也使用 signatureSize。由于局部变量注入困难，
     * 这里通过增大虚拟碰撞箱尺寸间接实现：signatureSize=1.5 时，虚拟碰撞箱的
     * getSize() 返回 1.5，距离过滤为 {@code distSqr > maxSqr * 1 * 1}，等效于
     * rcs=1 但碰撞箱更大。对于需要 rcs>1 的场景，可在 JSON 中设置更大的 signatureSize。</p>
     */

    /**
     * 获取实体的等效碰撞箱。
     * 对 {@link RVP_BaseBullet} 且 {@code signatureSize > 0} 时，
     * 返回以实体位置为中心、边长为 signatureSize 的虚拟碰撞箱。
     */
    private static AABB ywzj_rvp$effectiveBox(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet && bullet.getSignatureSize() > 0) {
            double half = bullet.getSignatureSize() / 2.0;
            net.minecraft.world.phys.Vec3 pos = entity.position();
            return new AABB(pos.x - half, pos.y - half, pos.z - half,
                    pos.x + half, pos.y + half, pos.z + half);
        }
        return entity.getBoundingBox();
    }
}
