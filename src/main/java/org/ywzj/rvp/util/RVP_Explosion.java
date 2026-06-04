package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_DamageFactor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VehicleExplosion;

import java.util.List;

/**
 * 继承本体 {@link VehicleExplosion}，在爆炸波及伤害上应用 {@link RVP_DamageFactor}。
 *
 * <p>方块破坏、客户端特效仍由父类处理；实体伤害经 {@link RVP_ExplosionDamageContext}
 * + {@link org.ywzj.rvp.mixin.VehicleExplosionMixin} 缩放。</p>
 */
public class RVP_Explosion extends VehicleExplosion {

    private final RVP_DamageFactor damageFactor;

    public RVP_Explosion(Level level, Entity source, AbstractVehicle vehicle, Vec3 position,
                         float radius, float damage, RVP_DamageFactor damageFactor) {
        super(level, source, vehicle, position, radius, damage);
        this.damageFactor = damageFactor == null ? RVP_DamageFactor.DEFAULT : damageFactor;
    }

    public RVP_Explosion(Level level, Entity source, AbstractVehicle vehicle, Vec3 position,
                         float radius, float damage, boolean destroyBlocks, RVP_DamageFactor damageFactor) {
        super(level, source, vehicle, position, radius, damage, destroyBlocks);
        this.damageFactor = damageFactor == null ? RVP_DamageFactor.DEFAULT : damageFactor;
    }

    public RVP_DamageFactor getDamageFactor() {
        return damageFactor;
    }

    @Override
    public void explode() {
        explode(null);
    }

    @Override
    public void explode(List<Entity> excludedEntities) {
        RVP_ExplosionDamageContext.run(damageFactor, () -> super.explode(excludedEntities));
    }
}
