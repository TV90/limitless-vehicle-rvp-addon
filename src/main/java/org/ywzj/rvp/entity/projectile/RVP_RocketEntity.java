package org.ywzj.rvp.entity.projectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

/**
 * Ballistic rocket entity for {@code rvp:rocket} and submunition payloads.
 */
public class RVP_RocketEntity extends RVP_BaseBullet {

    public RVP_RocketEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_RocketEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_RocketEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_ROCKET.get(), level);
    }

    @Override
    protected void sprinkleSubmunition() {
        if (level().isClientSide() || rvpData == null || shooterVehicle == null) {
            return;
        }
        RVP_RocketEntity child = new RVP_RocketEntity(RVP_Entities.RVP_ROCKET.get(), level(), rvpData.getWeaponId());
        Vec3 velocity = getDeltaMovement();
        LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
        child.initFromWeapon(rvpData, RVP_EnumWeaponKind.ROCKET, shooterVehicle, shooter,
                position(), new AimRot(getXRot(), getYRot()), velocity);
        child.setShooterWeaponUnit(getShooterWeaponUnit());
        child.submunitionFlag = 1;
        RandomSource rand = level().getRandom();
        float spread = rvpData.getBombletDiff();
        child.setDeltaMovement(velocity.add(
                (rand.nextDouble() - 0.5) * spread,
                (rand.nextDouble() - 0.5) * spread,
                (rand.nextDouble() - 0.5) * spread
        ));
        level().addFreshEntity(child);
    }
}
