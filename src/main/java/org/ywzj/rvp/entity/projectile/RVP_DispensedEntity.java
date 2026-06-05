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

}
