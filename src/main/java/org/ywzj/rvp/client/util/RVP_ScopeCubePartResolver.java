package org.ywzj.rvp.client.util;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 观瞄俯视图 cube→部件 反查缓存（2026-09-29 失效骨近黑线框配套，纯客户端）：
 * 本体 {@code VehicleScopeOverlay.renderCubeOBB} 只收 cube 与颜色、无部件上下文。
 * cube→部件归属是**静态结构关系**（部件与 partCubeOBBs 在载具 init 时构建、列表实例
 * 不随帧变化），故按载具缓存一份恒等映射、换车才重建——单次构建 O(部件×cube)，
 * 之后每次反查 O(1)，逐帧零开销。
 */
public final class RVP_ScopeCubePartResolver {

    /** 上次构建映射的载具（换车即重建）。 */
    private AbstractVehicle lastVehicle;
    /** cube→部件 恒等映射（载具级静态结构）。 */
    private final Map<VehicleCubeOBB, PartUnit<?>> cubeToPart = new IdentityHashMap<>();

    public RVP_ScopeCubePartResolver() {
    }

    /** 反查 cube 所属部件（含子武器站/装饰件登记）；未知 cube 返回 null（车体层等）。 */
    @Nullable
    public PartUnit<?> resolve(AbstractVehicle vehicle, VehicleCubeOBB cube) {
        if (vehicle == null || cube == null) {
            return null;
        }
        if (vehicle != lastVehicle || cubeToPart.isEmpty()) {
            rebuild(vehicle);
        }
        return cubeToPart.get(cube);
    }

    /** 重建映射：部件 + 其子武器站 + 装饰件全部登记（putIfAbsent 保首个归属）。 */
    private void rebuild(AbstractVehicle vehicle) {
        lastVehicle = vehicle;
        cubeToPart.clear();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            register(partUnit);
            if (partUnit instanceof WeaponUnit weaponUnit) {
                for (WeaponUnit sub : weaponUnit.getSubWeaponUnits()) {
                    register(sub);
                }
            }
        }
        for (PartUnit<?> decoration : vehicle.getDecorationUnits().values()) {
            register(decoration);
        }
    }

    private void register(PartUnit<?> partUnit) {
        for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
            cubeToPart.putIfAbsent(cube, partUnit);
        }
    }
}
