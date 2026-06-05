package org.ywzj.rvp.weapon.submunition;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionPayloadKind;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Explosion;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionSpreadData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Spawns RVP projectiles or arbitrary entities for {@link RVP_SubmunitionRunner}.
 */
public final class RVP_SubmunitionSpawner {

    public static final int MAX_DEPTH = 4;

    private RVP_SubmunitionSpawner() {}

    public static int spawnReleaseWave(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release, int eventsThisTick) {
        if (parent.level().isClientSide() || parent.getRvpData() == null) {
            return 0;
        }
        if (parent.getSubmunitionDepth() >= MAX_DEPTH) {
            return 0;
        }
        int spawned = 0;
        int globalPellet = 0;
        for (int event = 0; event < eventsThisTick; event++) {
            for (RVP_SubmunitionPayloadData payload : release.getPayloads()) {
                int count = payload.getCount();
                for (int i = 0; i < count; i++) {
                    if (spawnPayload(parent, payload, globalPellet, count)) {
                        spawned++;
                    }
                    globalPellet++;
                }
            }
        }
        return spawned;
    }

    private static boolean spawnPayload(RVP_BaseBullet parent, RVP_SubmunitionPayloadData payload,
                                        int pelletIndex, int pelletCount) {
        return switch (payload.getKind()) {
            case RVP_WEAPON -> spawnRvpWeapon(parent, payload, pelletIndex, pelletCount);
            case ENTITY -> spawnEntity(parent, payload, pelletIndex, pelletCount);
        };
    }

    private static boolean spawnRvpWeapon(RVP_BaseBullet parent, RVP_SubmunitionPayloadData payload,
                                          int pelletIndex, int pelletCount) {
        ResourceLocation parentId = parent.getWeaponId();
        ResourceLocation childId = payload.resolveWeaponId(parentId);
        if (childId == null) {
            return false;
        }
        RVP_WeaponData childData = loadWeaponData(childId);
        if (childData == null) {
            return false;
        }
        RVP_EnumWeaponKind kind = childData.getWeaponKind();
        EntityType<? extends Projectile> entityType = entityTypeFor(kind);
        if (entityType == null) {
            return false;
        }
        Level level = parent.level();
        RVP_BaseBullet child = createProjectile(kind, entityType, level, childData);
        if (child == null) {
            return false;
        }
        LivingEntity shooter = parent.getOwner() instanceof LivingEntity living ? living : null;
        AbstractVehicle vehicle = parent.getShooterVehicle();
        Vec3 pos = RVP_SubmunitionSpreadApplicator.applyPositionOffset(
                parent.position(), parent.getXRot(), parent.getYRot(),
                payload.getSpread(), pelletIndex, pelletCount, level.getRandom());
        Vec3 velocity = buildVelocity(parent, payload, pelletIndex, pelletCount);
        child.setSubmunitionDepth(parent.getSubmunitionDepth() + 1);
        child.initFromWeapon(childData, kind, vehicle, shooter, pos,
                new RVP_BaseBullet.AimRot(parent.getXRot(), parent.getYRot()), velocity);
        child.setShooterWeaponUnit(parent.getShooterWeaponUnit());
        // allow_submunition on the *spawn payload*: child may run its own weapon submunition_data (multi-stage).
        if (!payload.isAllowSubmunition()) {
            child.disableSubmunitionReleases();
        }
        Float damageMult = payload.getDamageMultiplier();
        if (damageMult != null) {
            child.damage = Math.max(0.01f, child.damage * damageMult);
        }
        if (payload.isSuppressExplosion() && child.explosion != null) {
            child.explosion = RVP_Explosion.disabled();
        }
        child.setDeltaMovement(velocity);
        child.finalizeSpawnOrientation(new RVP_BaseBullet.AimRot(child.getXRot(), child.getYRot()));
        level.addFreshEntity(child);
        return true;
    }

    private static boolean spawnEntity(RVP_BaseBullet parent, RVP_SubmunitionPayloadData payload,
                                       int pelletIndex, int pelletCount) {
        ResourceLocation typeId = payload.resolveEntityType();
        if (typeId == null) {
            return false;
        }
        Level level = parent.level();
        EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null) {
            return false;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }
        Vec3 pos = RVP_SubmunitionSpreadApplicator.applyPositionOffset(
                parent.position(), parent.getXRot(), parent.getYRot(),
                payload.getSpread(), pelletIndex, pelletCount, level.getRandom());
        entity.moveTo(pos.x, pos.y, pos.z, parent.getYRot(), parent.getXRot());
        applyEntityNbt(entity, payload.getEntityNbt());
        Vec3 velocity = buildVelocity(parent, payload, pelletIndex, pelletCount);
        entity.setDeltaMovement(velocity);
        if (entity instanceof Projectile projectile) {
            projectile.setOwner(parent.getOwner());
        }
        level.addFreshEntity(entity);
        return true;
    }

    private static Vec3 buildVelocity(RVP_BaseBullet parent, RVP_SubmunitionPayloadData payload,
                                    int pelletIndex, int pelletCount) {
        Vec3 velocity = Vec3.ZERO;
        if (payload.isInheritParentVelocity()) {
            velocity = parent.getDeltaMovement();
        }
        if (payload.isInheritVehicleVelocity() && parent.getShooterVehicle() != null) {
            velocity = velocity.add(parent.getShooterVehicle().getDeltaMovement());
        }
        float scale = payload.getVelocityScale();
        if (scale != 1f) {
            velocity = velocity.scale(scale);
        }
        if (velocity.lengthSqr() < 1.0E-8) {
            velocity = parent.getDeltaMovement().normalize().scale(0.5);
        }
        return RVP_SubmunitionSpreadApplicator.applyVelocitySpread(
                velocity, parent.getXRot(), parent.getYRot(),
                payload.getSpread(), pelletIndex, pelletCount, parent.level().getRandom());
    }

    private static void applyEntityNbt(Entity entity, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            entity.load(tag);
        } catch (CommandSyntaxException ignored) {
            // Invalid SNBT in JSON — skip silently.
        }
    }

    @Nullable
    public static RVP_WeaponData loadWeaponData(ResourceLocation weaponId) {
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(VehicleWeaponIndex::data)
                .filter(RVP_WeaponData.class::isInstance)
                .map(RVP_WeaponData.class::cast)
                .orElse(null);
    }

    @Nullable
    public static EntityType<? extends Projectile> entityTypeFor(RVP_EnumWeaponKind kind) {
        return switch (kind) {
            case MISSILE -> RVP_Entities.RVP_MISSILE.get();
            case ROCKET -> RVP_Entities.RVP_ROCKET.get();
            case MACHINEGUN -> RVP_Entities.RVP_BULLET.get();
            case BOMB -> RVP_Entities.RVP_BOMB.get();
            case DISPENSER -> RVP_Entities.RVP_DISPENSED.get();
            case LASER, TARGETING_POD -> null;
        };
    }

    @Nullable
    public static RVP_BaseBullet createProjectile(RVP_EnumWeaponKind kind, EntityType<? extends Projectile> type,
                                                  Level level, RVP_WeaponData data) {
        ResourceLocation id = data.getWeaponId();
        return switch (kind) {
            case MISSILE -> new RVP_MissileEntity(type, level, id);
            case ROCKET -> new RVP_RocketEntity(type, level, id);
            case MACHINEGUN -> new RVP_BulletEntity(type, level, id);
            case BOMB -> new RVP_BombEntity(type, level, id);
            case DISPENSER -> new RVP_DispensedEntity(type, level, id);
            case LASER, TARGETING_POD -> null;
        };
    }
}
