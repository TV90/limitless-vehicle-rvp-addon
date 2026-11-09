package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gunner 的模块 OBB 瞄准点缓存：每 40 tick 抽一块模块，抽中后按稳定骨键逐 tick
 * 解析实时 OBB，模块被打坏或目标切换时立即重抽。空中目标仍用最大 OBB，
 * 避免小模块 OBB 让高亚音速目标更难命中。
 */
public final class RVP_GunnerModuleAim {

    /** 模块瞄准点重抽间隔，单位 tick。 */
    private static final int SLICE_TICK = 40;

    private static final Map<GunnerEntity, State> STATES = new WeakHashMap<>();

    private RVP_GunnerModuleAim() {}

    /** 返回本 tick 的瞄准基点：模块 OBB 中心，或最大 OBB 回退。 */
    public static Vec3 resolve(GunnerEntity gunner, Entity target) {
        if (gunner == null || target == null) {
            return target == null ? Vec3.ZERO : target.getBoundingBox().getCenter();
        }
        if (!(target instanceof AbstractVehicle vehicle)
                || target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle) {
            // 非载具/空中目标保持旧的最大 OBB 初始瞄准；机炮解算偏移另用 resolveAimOffset 归零。
            return RVP_AimPointResolver.resolveLargestObbCenter(target);
        }
        State state = STATES.computeIfAbsent(gunner, key -> new State());
        long slice = gunner.tickCount / SLICE_TICK;
        boolean needPick = state.targetId != target.getId()
                || state.slice != slice
                || state.key == null
                || !RVP_AimPointResolver.isModuleAimKeyUsable(vehicle, state.key);
        if (needPick) {
            long seed = RVP_AimPointResolver.moduleAimSeed(gunner.getId(), target, slice);
            state.key = RVP_AimPointResolver.pickModuleObbKey(target, seed);
            state.targetId = target.getId();
            state.slice = slice;
        }
        if (state.key != null) {
            RVP_NamedVehicleObbs.Box box = RVP_NamedVehicleObbs.resolve(vehicle, state.key);
            if (box != null) {
                return box.center();
            }
            // 骨块已被移除/拆下：清键，下一 tick 立即重抽，别一直回退最大 OBB。
            state.key = null;
        }
        return RVP_AimPointResolver.resolveLargestObbCenter(target);
    }

    private static final class State {
        private int targetId = -1;
        private long slice = Long.MIN_VALUE;
        private RVP_NamedVehicleObbs.Key key;
    }

    /**
     * 机炮提前量解算的模块偏移：仅地面载具目标返回“模块 OBB 中心 - 实体 AABB 中心”，
     * 空中/非载具目标返回零，保持原机炮打 AABB 中心的行为。
     */
    public static Vec3 resolveAimOffset(GunnerEntity gunner, Entity target) {
        if (!(target instanceof AbstractVehicle)
                || target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle) {
            return Vec3.ZERO;
        }
        return resolve(gunner, target).subtract(target.getBoundingBox().getCenter());
    }
}
