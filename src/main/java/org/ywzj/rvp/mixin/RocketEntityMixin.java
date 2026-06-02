package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.RvpRocketBallistics;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;

@Mixin(value = RocketEntity.class, remap = false)
public abstract class RocketEntityMixin extends AmmoEntity {

    protected RocketEntityMixin(EntityType<? extends Projectile> entityType, Level level, ResourceLocation weaponId) {
        super(entityType, level, weaponId);
    }

    @Inject(
            method = "tickMove",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void ywzj_rvp$ballisticRocketTick(CallbackInfo ci) {
        RvpRocketBallistics.Params params = RvpRocketBallistics.resolve(this.getWeaponId());
        if (params == null) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
        this.setDeltaMovement(RvpRocketBallistics.stepVelocity(velocity, params));
        ci.cancel();
    }
}
