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

}
