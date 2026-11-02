package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.EntityHitResult;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** TV 射线的真实载具 OBB 命中，避免后续把精确弱点丢回实体包围盒中心。 */
public final class RVP_HitlVehicleHitResult extends EntityHitResult {
    /** 本次射线选中的骨和 cube 索引。 */
    private final RVP_NamedVehicleObbs.Key key;
    public RVP_HitlVehicleHitResult(AbstractVehicle vehicle, RVP_NamedVehicleObbs.Hit hit) {
        super(vehicle, hit.point());
        key = hit.box().key();
    }
    public RVP_NamedVehicleObbs.Key key() { return key; }
}
