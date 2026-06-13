package org.ywzj.rvp.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.ywzj.rvp.weapon.damage.RVP_HitboxDamageContext;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mixin(AbstractVehicle.class)
public class AbstractVehicleHitboxDamageFactorMixin {

    @ModifyVariable(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2
    )
    private float rvp$applyHitboxDamageFactor(float amount, DamageSource source) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (amount <= 0f) {
            return amount;
        }
        if (self.level() != null && self.level().isClientSide()) {
            return amount;
        }
        if (RVP_HitboxDamageContext.shouldSkipGlobalVehicleHurtScaling()) {
            return amount;
        }

        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();
        if (direct == null && attacker == null) {
            return amount;
        }
        if (attacker instanceof AbstractVehicle) {
            return amount;
        }

        Vec3 segmentStart = null;
        Vec3 segmentEnd = null;

        if (direct != null) {
            segmentEnd = direct.position();
            if (direct instanceof Projectile) {
                segmentStart = new Vec3(direct.xOld, direct.yOld, direct.zOld);
            } else {
                Vec3 delta = direct.getDeltaMovement();
                segmentStart = segmentEnd.subtract(delta);
            }
        } else if (attacker instanceof LivingEntity living) {
            segmentStart = living.getEyePosition();
            segmentEnd = self.position().add(0, self.getBbHeight() * 0.5, 0);
        }

        if (segmentStart == null || segmentEnd == null) {
            return amount;
        }

        RVP_VehicleHitboxFactorManager.HitboxDamageResult res = RVP_VehicleHitboxFactorManager.INSTANCE
                .resolveHitboxDamage(self, segmentStart, segmentEnd);
        float mult = res.factor();
        float out = amount;
        if (Float.isFinite(mult) && mult != 1f) {
            out = amount * mult;
        }
        if (attacker instanceof Player player) {
            RVP_VehicleHitboxFactorManager.INSTANCE.maybeSendHitboxDebug(player, self, amount, res);
        }
        if (!Float.isFinite(out) || out < 0f) {
            return amount;
        }
        return out;
    }
}
