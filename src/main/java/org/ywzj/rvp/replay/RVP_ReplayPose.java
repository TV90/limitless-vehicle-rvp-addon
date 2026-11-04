package org.ywzj.rvp.replay;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.*;

/** 沿用本体公开姿态与结构块接口，采样和应用只处理数值。 */
public final class RVP_ReplayPose {
    private RVP_ReplayPose() {}

    public static RVP_HitReplay.Frame capture(AbstractVehicle vehicle, long tick) {
        List<RVP_HitReplay.Part> parts = new ArrayList<>();
        Map<Integer, Float> damaged = new HashMap<>();
        Set<Integer> detached = new HashSet<>();
        for (var part : vehicle.getPartUnits()) {
            if (parts.size() < RVP_HitReplay.MAX_PARTS) {
                if (part instanceof RotatableUnit<?> rot) {
                    parts.add(new RVP_HitReplay.Part(part.getIndex(), rot.getXRot(), rot.getYRot(), -1));
                } else if (part instanceof SwitchableUnit<?> toggle) {
                    parts.add(new RVP_HitReplay.Part(part.getIndex(), 0, 0, toggle.isOn() ? 1 : 0));
                }
            }
            if (part.isDetached() && detached.size() < RVP_HitReplay.MAX_PARTS) detached.add(part.getIndex());
            if (part.getHealth() < part.getMaxHealth() && damaged.size() < RVP_HitReplay.MAX_PARTS)
                damaged.put(part.getIndex(), part.getHealth());
        }
        return new RVP_HitReplay.Frame(tick, vehicle.position(), vehicle.getDeltaMovement(),
                vehicle.getXRot(), vehicle.getYRot(), vehicle.getZRot(), vehicle.getHealth(), vehicle.isDestroyed(), parts, damaged, detached,
                RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID()));
    }

    /** 只能应用在不加入世界的回放代理；不 tick，避免驾驶/武器/音效副作用。 */
    public static void apply(AbstractVehicle proxy, RVP_HitReplay.Frame frame) {
        proxy.setPos(frame.position());
        proxy.xo = frame.position().x; proxy.yo = frame.position().y; proxy.zo = frame.position().z;
        proxy.setXRot(frame.pitch()); proxy.setYRot(frame.yaw()); proxy.setZRot(frame.roll());
        proxy.xRotO = frame.pitch(); proxy.yRotO = frame.yaw(); proxy.zRotO = frame.roll();
        proxy.setDeltaMovement(frame.velocity());
        if (frame.destroyed() && !proxy.isDestroyed()) proxy.setDestroyed();
        proxy.setHealth(frame.health());
        for (var part : proxy.getPartUnits()) {
            part.setHealth(frame.damagedParts().getOrDefault(part.getIndex(), part.getMaxHealth()));
            part.setDetached(frame.detached().contains(part.getIndex()));
        }
        for (var state : frame.parts()) {
            var part = proxy.getPartUnit(state.index()).orElse(null);
            if (part instanceof RotatableUnit<?> rot) {
                rot.setXRot(state.pitch()); rot.setYRot(state.yaw());
                rot.xRotO = state.pitch(); rot.yRotO = state.yaw();
                rot.setXAimRot(state.pitch()); rot.setYAimRot(state.yaw());
                // 公开更新接口同步结构骨姿态，随后统一更新历史 OBB。
                rot.updateRot();
            } else if (part instanceof SwitchableUnit<?> toggle && state.toggle() >= 0) {
                // 已有访问器直接落入状态，避免 Door/LandingGear 的 setOn 触发世界操作。
                ((org.ywzj.rvp.mixin.accessor.SwitchableUnitAccessor) toggle).setOnField(state.toggle() == 1);
            }
        }
        proxy.updateOBBs();
    }

    /** 使用命中表面的结构块坐标存标记，炮塔旋转时不会滑到别的装甲上。 */
    public static RVP_HitReplay.Anchor anchor(AbstractVehicle vehicle, Vec3 point, Vec3 incoming, String bone) {
        int partIndex = -2, cubeIndex = -1;
        VehicleCubeOBB chosen = null;
        double best = Double.POSITIVE_INFINITY;
        var preferred = vehicle.getPartUnit(bone == null ? "" : bone).orElse(null);
        for (var part : vehicle.getPartUnits()) {
            if (preferred != null && preferred != part) continue;
            var cubes = part.getPartCubeOBBs();
            for (int i = 0; i < cubes.size(); i++) {
                var cube = cubes.get(i);
                double distance = surfaceDistance(cube, point);
                if (distance < best) { best = distance; chosen = cube; partIndex = part.getIndex(); cubeIndex = i; }
            }
        }
        if (preferred == null) {
            var cubes = vehicle.getVehicleCubeOBBs();
            for (int i = 0; i < cubes.size(); i++) {
                double distance = surfaceDistance(cubes.get(i), point);
                if (distance < best) { best = distance; chosen = cubes.get(i); partIndex = -1; cubeIndex = i; }
            }
        }
        // 爆心以及未命中结构块的兜底只绑定车体；不把远处爆心吸到最近装甲表面。
        if (chosen == null || best > 0.02) {
            Vec3 center = vehicle.position().add(vehicle.centerOffset);
            Quaternionf inv = vehicle.rotYXZ().conjugate();
            return new RVP_HitReplay.Anchor(-2, -1, new Vec3(inv.transform(point.subtract(center).toVector3f())),
                    new Vec3(inv.transform(incoming.toVector3f())));
        }
        Quaternionf inv = new Quaternionf(chosen.rotation).conjugate();
        return new RVP_HitReplay.Anchor(partIndex, cubeIndex,
                new Vec3(inv.transform(point.subtract(chosen.position).toVector3f())),
                new Vec3(inv.transform(incoming.toVector3f())));
    }

    private static double surfaceDistance(VehicleCubeOBB cube, Vec3 point) {
        if (cube.position == null || cube.rotation == null) return Double.POSITIVE_INFINITY;
        var local = new Quaternionf(cube.rotation).conjugate().transform(point.subtract(cube.position).toVector3f());
        double dx = Math.abs(local.x) - cube.width / 2, dy = Math.abs(local.y) - cube.height / 2,
                dz = Math.abs(local.z) - cube.depth / 2;
        if (dx <= 0 && dy <= 0 && dz <= 0) return Math.min(-dx, Math.min(-dy, -dz));
        return Math.sqrt(Math.pow(Math.max(0, dx), 2) + Math.pow(Math.max(0, dy), 2) + Math.pow(Math.max(0, dz), 2));
    }

    /** 返回当前历史姿态下的世界命中点/来袭方向；同一锚点可跨多发命中的回放时间线使用。 */
    public static Vec3 resolve(AbstractVehicle vehicle, RVP_HitReplay.Anchor anchor, boolean direction) {
        VehicleCubeOBB cube = null;
        var part = anchor.part() >= 0 ? vehicle.getPartUnit(anchor.part()).orElse(null) : null;
        List<VehicleCubeOBB> cubes = anchor.part() == -1 ? vehicle.getVehicleCubeOBBs()
                : part != null ? part.getPartCubeOBBs() : List.of();
        if (anchor.cube() >= 0 && anchor.cube() < cubes.size()) cube = cubes.get(anchor.cube());
        Quaternionf rot = cube == null ? vehicle.rotYXZ() : new Quaternionf(cube.rotation);
        Vec3 offset = new Vec3(rot.transform((direction ? anchor.incoming() : anchor.point()).toVector3f()));
        return direction ? offset : offset.add(cube == null ? vehicle.position().add(vehicle.centerOffset) : cube.position);
    }
}
