package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

/**
 * 修改 Infrared 类的 {@code getBoundingBox().getSize() < 1} 过滤，
 * 使设置了 {@code signatureSize > 0} 的 RVP 弹体能够被红外导引头探测和锁定。
 *
 * <p>策略同 {@link RadarSignatureMixin}：对 Infrared 类中
 * {@code entity.getBoundingBox()} 调用进行 Redirect，
 * 当 entity 是 {@link RVP_BaseBullet} 且 {@code signatureSize > 0} 时，
 * 返回基于 signatureSize 的虚拟碰撞箱。</p>
 */
@Mixin(value = Infrared.class, remap = false)
public class InfraredSignatureMixin {

    @Redirect(
            method = "findTarget",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 0
    )
    private static AABB ywzj_rvp$irBox(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet && bullet.getSignatureSize() > 0) {
            double half = bullet.getSignatureSize() / 2.0;
            net.minecraft.world.phys.Vec3 pos = entity.position();
            return new AABB(pos.x - half, pos.y - half, pos.z - half,
                    pos.x + half, pos.y + half, pos.z + half);
        }
        return entity.getBoundingBox();
    }
}
