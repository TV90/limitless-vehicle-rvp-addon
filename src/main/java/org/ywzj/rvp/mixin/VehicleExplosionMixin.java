package org.ywzj.rvp.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.util.RVP_ExplosionDamageContext;
import org.ywzj.rvp.weapon.damage.RVP_DamageApplier;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VehicleExplosion;

/**
 * 在 {@link org.ywzj.rvp.util.RVP_Explosion} 触发父类爆炸时，对波及实体伤害应用 RVP 倍率。
 */
@Mixin(value = VehicleExplosion.class, remap = false)
public class VehicleExplosionMixin {

    @Redirect(
            method = "hurt",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/util/EntityUtil;hurt(Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/entity/Entity;F)V"
            )
    )
    private void rvp$scaleExplosionDamage(DamageSource source, Entity entity, float amount) {
        float scaled = RVP_DamageApplier.applyScaled(
                amount, entity, RVP_ExplosionDamageContext.current());
        EntityUtil.hurt(source, entity, scaled);
    }
}
