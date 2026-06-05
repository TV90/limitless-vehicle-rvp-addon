package org.ywzj.rvp.entity.projectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

/**
 * Payload entity produced by {@code rvp:dispenser}. Dispenser placement is handled in
 * {@link RVP_BaseBullet} for all projectile types that define {@code dispenser_data}.
 */
public class RVP_DispensedEntity extends RVP_BaseBullet {

    public RVP_DispensedEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_DispensedEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_DispensedEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_DISPENSED.get(), level);
    }

    @Override
    protected void sprinkleSubmunition() {
        if (level().isClientSide() || rvpData == null || shooterVehicle == null) {
            return;
        }
        RVP_DispensedEntity child = new RVP_DispensedEntity(RVP_Entities.RVP_DISPENSED.get(), level(), rvpData.getWeaponId());
        Vec3 velocity = getDeltaMovement();
        LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
        child.initFromWeapon(rvpData, RVP_EnumWeaponKind.DISPENSER, shooterVehicle, shooter,
                position(), new AimRot(getXRot(), getYRot()), velocity);
        child.setShooterWeaponUnit(getShooterWeaponUnit());
        child.submunitionFlag = 1;
        child.submunitionsRemaining = 0;
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
