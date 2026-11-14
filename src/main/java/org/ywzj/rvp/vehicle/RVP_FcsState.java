package org.ywzj.rvp.vehicle;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.Set;

/** 火控系统（FCS）失效查询，同时覆盖服务端侧表与客户端同步快照。 */
public final class RVP_FcsState {

    private RVP_FcsState() {}

    public static boolean isDestroyed(@Nullable WeaponUnit unit) {
        return unit != null && isDestroyed(unit.getVehicle());
    }

    public static boolean isDestroyed(@Nullable AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        if (vehicle.level().isClientSide()) {
            Boolean result = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                    () -> () -> org.ywzj.rvp.client.state.RVP_ClientFcsState.isDestroyed(vehicle));
            return Boolean.TRUE.equals(result);
        }
        Map<String, Set<BoneModuleType>> modules =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        for (Map.Entry<String, Set<BoneModuleType>> entry : modules.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains(BoneModuleType.FCS)
                    && RVP_BoneModuleStateTable.isModuleDestroyed(
                            vehicle.getUUID(), entry.getKey(), BoneModuleType.FCS)) {
                return true;
            }
        }
        return false;
    }
}
