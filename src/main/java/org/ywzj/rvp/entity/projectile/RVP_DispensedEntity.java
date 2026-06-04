package org.ywzj.rvp.entity.projectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.effects.RVP_DispenserPlacement;

/**
 * Payload entity produced by {@code rvp:dispenser}. On impact it can place/use configured items
 * on terrain (MCH {@code MCH_EntityDispensedItem}).
 */
public class RVP_DispensedEntity extends RVP_BaseBullet {

    @Nullable
    private BlockHitResult lastBlockHit;

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
    protected boolean handleBlockImpact(BlockHitResult result) {
        lastBlockHit = result;
        if (shouldAttemptDispenser()) {
            tryPlacePayload(result);
            spawnAmmoBlockImpactEffects(result);
            discard();
            return true;
        }
        return super.handleBlockImpact(result);
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

    @Override
    protected void explodeAndDiscard(Vec3 pos) {
        if (shouldAttemptDispenser()) {
            tryPlacePayloadAt(pos);
            discard();
            return;
        }
        super.explodeAndDiscard(pos);
    }

    private boolean shouldAttemptDispenser() {
        if (rvpData == null) {
            return false;
        }
        RVP_DispenserPayloadData payload = rvpData.getDispenserData();
        return payload.hasItem() && payload.isPlaceOnImpact();
    }

    private int tryPlacePayload(BlockHitResult result) {
        if (level().isClientSide() || rvpData == null) {
            return 0;
        }
        RVP_DispenserPayloadData payload = rvpData.getDispenserData();
        if (!payload.hasItem() || !payload.isPlaceOnImpact()) {
            return 0;
        }
        if (level() instanceof ServerLevel serverLevel) {
            return RVP_DispenserPlacement.placeAtHit(serverLevel, result, payload, getOwner());
        }
        return 0;
    }

    private int tryPlacePayloadAt(Vec3 pos) {
        if (level().isClientSide() || rvpData == null) {
            return 0;
        }
        RVP_DispenserPayloadData payload = rvpData.getDispenserData();
        if (!payload.hasItem() || !payload.isPlaceOnImpact()) {
            return 0;
        }
        if (level() instanceof ServerLevel serverLevel) {
            return RVP_DispenserPlacement.placeAtPosition(serverLevel, pos, payload, getOwner(), lastBlockHit);
        }
        return 0;
    }
}
