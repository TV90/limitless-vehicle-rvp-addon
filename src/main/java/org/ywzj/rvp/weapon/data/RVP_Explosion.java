package org.ywzj.rvp.weapon.data;

import org.ywzj.vehicle.vehicle.pojo.Explosion;

/**
 * RVP 武器 {@code detonate_data.explosion_data}，继承载具包 {@link Explosion} 字段。
 *
 * <p>运行时引爆使用本体 {@link org.ywzj.vehicle.util.VehicleExplosion}。</p>
 */
public class RVP_Explosion extends Explosion {

    public static RVP_Explosion disabled() {
        RVP_Explosion explosion = new RVP_Explosion();
        explosion.explode = false;
        return explosion;
    }
}
