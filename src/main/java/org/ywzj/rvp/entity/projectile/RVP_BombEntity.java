package org.ywzj.rvp.entity.projectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * Bomb entity with gravity-first motion for {@code rvp:bomb}.
 */
public class RVP_BombEntity extends RVP_BaseBullet {

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_BombEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_BOMB.get(), level);
    }

    @Override
    protected void tickMotion() {
        // 仅简化弹道且未配置 gravity 时补默认重力；推进模式在 tickPropulsionMotion 内处理，避免重复叠加 G
        if (rvpData != null && !rvpData.usesPropulsion() && rvpData.getGravity() == 0f) {
            setDeltaMovement(getDeltaMovement().add(0, -PhysicsEngine.G, 0));
        }
        super.tickMotion();
    }
}
