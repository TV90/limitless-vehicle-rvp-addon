package org.ywzj.rvp.weapon.core;

import org.joml.Quaternionf;
import org.ywzj.rvp.debug.RVP_HitboxDebug;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 替代被删 {@code WeaponUnitFollowParentRotationMixin} 的非 mixin 实现。
 *
 * <p>原 mixin 在 {@code WeaponUnit.updateRot()} TAIL 将配置了 {@code follow_parent_only}
 * 的部件旋转重置回 baseRotation（只跟随父节点、自身不转）。本实现挂在
 * {@link RVP_WeaponBase#tick()}（武器 tick 在本体 {@code WeaponUnit.tick()} 的
 * {@code super.tick()/updateRot()} 之后执行，时序一致），内部按 unit + tick 去重。</p>
 */
public final class RVP_FollowParentRotationHelper {

    private RVP_FollowParentRotationHelper() {}

    /** unit -> 本 tick 已处理标记。 */
    private static final Map<WeaponUnit, Integer> LAST_TICK = new HashMap<>();

    public static void tick(WeaponUnit unit) {
        if (unit == null || unit.getVehicle() == null) {
            return;
        }
        int tickCount = unit.getVehicle().tickCount;
        Integer lastTick = LAST_TICK.get(unit);
        if (lastTick != null && lastTick == tickCount) {
            return;
        }
        LAST_TICK.put(unit, tickCount);

        if (!(unit.getData() instanceof WeaponUnitDataExt ext)) {
            return;
        }
        List<String> ids = ext.ywzj_rvp$getFollowParentOnlyPartUnitIds();
        if (ids.isEmpty()) {
            RVP_HitboxDebug.noteEmptyConfig(unit.getId());
            return;
        }
        RVP_HitboxDebug.noteUpdateEnter(unit.getId(), ids);
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            unit.getVehicle().getPartUnit(id).ifPresentOrElse(
                    RVP_FollowParentRotationHelper::resetLocalRotation,
                    () -> RVP_HitboxDebug.notePartMissing(id)
            );
        }
    }

    private static void resetLocalRotation(PartUnit<?> partUnit) {
        VehicleCubeGroup group = partUnit.getStructureGroup();
        if (group == null || group.baseRotation == null) {
            RVP_HitboxDebug.noteGroupMissing(partUnit.getId());
            return;
        }
        String before = String.valueOf(group.rotation.getEulerAnglesYXZ(new org.joml.Vector3f()));
        group.rotation = new Quaternionf(group.baseRotation);
        RVP_HitboxDebug.noteReset(partUnit.getId(), before,
                String.valueOf(group.rotation.getEulerAnglesYXZ(new org.joml.Vector3f())));
    }
}
