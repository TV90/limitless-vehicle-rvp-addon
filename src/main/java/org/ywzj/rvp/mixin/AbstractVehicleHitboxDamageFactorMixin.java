package org.ywzj.rvp.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.damage.RVP_HitboxDamageContext;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.structure.OBB;

@Mixin(AbstractVehicle.class)
public class AbstractVehicleHitboxDamageFactorMixin {

    @Unique
    private float rvp$hitboxHealthBefore;

    @Unique
    private RVP_VehicleHitboxFactorManager.HitboxDamageResult rvp$hitboxRes;

    @Unique
    private boolean rvp$hitboxArmed;

    @Unique
    private float rvp$coreFalloffScale;

    @Unique
    private float rvp$coreDistanceScaleMultiplier;

    @Unique
    private boolean rvp$skipHitboxScaling;

    @Unique
    private float rvp$predictedBaseDamage;

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"))
    private void rvp$hbxCapture(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        rvp$hitboxArmed = false;
        rvp$hitboxRes = null;
        rvp$hitboxHealthBefore = self.getHealth();
        rvp$coreFalloffScale = Float.NaN;
        rvp$coreDistanceScaleMultiplier = 1f;
        rvp$skipHitboxScaling = false;
        rvp$predictedBaseDamage = 0f;
        if (amount <= 0f) return;
        if (self.level() != null && self.level().isClientSide()) return;
        rvp$skipHitboxScaling = RVP_HitboxDamageContext.shouldSkipHitboxScaling();

        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();
        if (direct == null && attacker == null) return;

        Vec3 segmentStart = null;
        Vec3 segmentEnd = null;

        Vec3 vehicleCenter = self.getBoundingBox().getCenter();

        if (direct instanceof Projectile) {
            Projectile projectile = (Projectile) direct;
            segmentStart = projectile.position();
            Vec3 delta = projectile.getDeltaMovement();
            if (delta.lengthSqr() > 1.0E-8) {
                segmentEnd = segmentStart.add(delta);
            } else {
                Vec3 old = new Vec3(projectile.xOld, projectile.yOld, projectile.zOld);
                if (old.distanceToSqr(segmentStart) > 1.0E-6) {
                    segmentStart = old;
                    segmentEnd = projectile.position();
                }
            }
        }

        if (segmentStart == null && attacker instanceof LivingEntity) {
            segmentStart = ((LivingEntity) attacker).getEyePosition();
        }
        if (segmentStart == null && direct instanceof AbstractVehicle) {
            LivingEntity driver = ((AbstractVehicle) direct).getDriver();
            if (driver != null) {
                segmentStart = driver.getEyePosition();
            }
        }
        if (segmentStart == null && attacker != null) {
            segmentStart = attacker.position().add(0, attacker.getBbHeight() * 0.5, 0);
        }
        if (segmentStart == null && direct != null) {
            Vec3 end = direct.position();
            Vec3 delta = direct.getDeltaMovement();
            if (delta.lengthSqr() > 1.0E-8) {
                segmentStart = end.subtract(delta);
            } else {
                segmentStart = end;
            }
        }

        if (segmentEnd == null) {
            segmentEnd = vehicleCenter;
        }
        if (segmentStart == null || segmentEnd == null) return;

        rvp$coreDistanceScaleMultiplier = RVP_VehicleHitboxFactorManager.INSTANCE.resolveCoreDistanceScaleMultiplier(self);
        if (!rvp$skipHitboxScaling) {
            rvp$hitboxRes = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(self, segmentStart, segmentEnd);
        }
        rvp$predictedBaseDamage = rvp$predictBaseDamage(self, source, amount);
        boolean hitboxEnabled = !rvp$skipHitboxScaling && rvp$hitboxRes != null && rvp$hitboxRes.enabled();

        boolean explosion = "ywzj_vehicle.explosion".equals(source.getMsgId());
        if (!explosion
                && direct != null
                && amount >= self.defenseStats.damageThreshold
                && source.getDirectEntity() instanceof Projectile) {
            Vec3 hitPos = direct.position();
            Vec3 hitVec = direct.getDeltaMovement();
            if (hitPos != null && hitVec != null && hitVec.lengthSqr() > 1.0E-8) {
                OBB obb = self.getMainCubeOBB().obb();
                Vec3 corePos = self.relativeRotPos(new Vec3(obb.center()), false);
                Vec3 diff = corePos.subtract(hitPos);
                Vec3 cross = diff.cross(hitVec);
                double distanceToCore = cross.length() / hitVec.length();
                double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
                if (Double.isFinite(distanceMax) && distanceMax != 0) {
                    double s = (distanceMax - distanceToCore) / distanceMax;
                    if (Double.isFinite(s)) {
                        rvp$coreFalloffScale = (float) s;
                    }
                }
            }
        }

        rvp$hitboxArmed = hitboxEnabled || rvp$coreDistanceScaleMultiplier != 1f;
    }

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/DamageSystem;hurt(Lnet/minecraft/world/damagesource/DamageSource;FLorg/ywzj/vehicle/entity/vehicle/AbstractVehicle;)V",
                    remap = false,
                    shift = At.Shift.AFTER
            )
    )
    private void rvp$hbxApplyAfterBase(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!rvp$hitboxArmed) {
            return;
        }

        float healthAfterBase = self.getHealth();
        float deltaBase = rvp$hitboxHealthBefore - healthAfterBase;
        if (healthAfterBase <= 0f && rvp$predictedBaseDamage > deltaBase) {
            deltaBase = rvp$predictedBaseDamage;
        }
        if (!(deltaBase > 0f)) {
            return;
        }

        float deltaAfterCore = deltaBase;
        if (rvp$coreDistanceScaleMultiplier != 1f
                && Float.isFinite(rvp$coreFalloffScale)
                && Math.abs(rvp$coreFalloffScale) > 1.0E-6f) {
            float deltaNoFalloff = deltaBase / rvp$coreFalloffScale;
            float effectiveScale = 1f + (rvp$coreFalloffScale - 1f) * rvp$coreDistanceScaleMultiplier;
            if (Float.isFinite(deltaNoFalloff) && Float.isFinite(effectiveScale)) {
                deltaAfterCore = deltaNoFalloff * effectiveScale;
            }
        }

        float hitboxMult = 1f;
        if (!rvp$skipHitboxScaling && rvp$hitboxRes != null && rvp$hitboxRes.enabled()) {
            hitboxMult = rvp$hitboxRes.factor();
        }
        if (!Float.isFinite(hitboxMult)) {
            return;
        }

        float deltaAfterAll = deltaAfterCore * hitboxMult;
        if (!Float.isFinite(deltaAfterAll)) {
            return;
        }
        float newHealth = rvp$hitboxHealthBefore - deltaAfterAll;
        self.setHealth(newHealth);

        Entity attacker = source.getEntity();
        Player debugPlayer = null;
        if (attacker instanceof Player p) {
            debugPlayer = p;
        } else if (attacker instanceof AbstractVehicle attackerVehicle && attackerVehicle.getDriver() instanceof Player p) {
            debugPlayer = p;
        }
        if (!rvp$skipHitboxScaling && debugPlayer != null) {
            RVP_VehicleHitboxFactorManager.HitboxDamageResult dbgRes = (rvp$hitboxRes != null && rvp$hitboxRes.enabled())
                    ? rvp$hitboxRes
                    : RVP_VehicleHitboxFactorManager.HitboxDamageResult.defaulted(1f, null, 0, Double.NaN);
            RVP_VehicleHitboxFactorManager.INSTANCE.maybeSendHitboxDebug(
                    debugPlayer, self,
                    deltaBase,
                    deltaAfterAll,
                    dbgRes,
                    rvp$coreFalloffScale,
                    rvp$coreDistanceScaleMultiplier
            );
        }
    }

    @Unique
    private static float rvp$predictBaseDamage(AbstractVehicle self, DamageSource source, float amount) {
        float effectiveAmount = amount;
        double scale = 1d;
        boolean explosion = "ywzj_vehicle.explosion".equals(source.getMsgId());
        Entity direct = source.getDirectEntity();
        Vec3 hitPos = null;
        if (direct instanceof Projectile) {
            Projectile projectile = (Projectile) direct;
            hitPos = VectorUtil.closestHitObbPosition(
                    self,
                    projectile.position(),
                    projectile.position().add(projectile.getDeltaMovement())
            );
        }
        if (explosion && direct != null) {
            hitPos = direct.position();
        }
        if (effectiveAmount < 0.1f) {
            effectiveAmount = 0f;
        } else if (effectiveAmount < self.defenseStats.damageThreshold) {
            effectiveAmount = 0.1f;
        } else if (hitPos == null) {
            scale = 0.2d;
        } else if (!explosion && direct != null) {
            Vec3 hitVec = direct.getDeltaMovement();
            if (hitVec.lengthSqr() > 1.0E-8) {
                OBB obb = self.getMainCubeOBB().obb();
                Vec3 corePos = self.relativeRotPos(new Vec3(obb.center()), false);
                Vec3 diff = corePos.subtract(hitPos);
                Vec3 cross = diff.cross(hitVec);
                double distanceToCore = cross.length() / hitVec.length();
                double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
                if (Double.isFinite(distanceMax) && distanceMax != 0d) {
                    scale = (distanceMax - distanceToCore) / distanceMax;
                }
            }
        }
        return effectiveAmount * (float) scale;
    }
}
