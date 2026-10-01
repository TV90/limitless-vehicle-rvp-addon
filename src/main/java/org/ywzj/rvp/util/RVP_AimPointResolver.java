package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

/**
 * [RVP] 锁定/制导瞄准点统一解析（2026-10-01 用户定版 §46）：对载具目标取**体积最大的那一块
 * 实时 OBB** 的中心，取代此前各消费点各自取的 AABB 中心——长/斜载具的 AABB 中心会悬空或
 * 偏离视觉主体，弹着点分散；最大 OBB 即目标主体（船体/机身），锁定框与弹着集中。
 *
 * <p>回退规则（保持既有行为）：
 * <ul>
 *   <li>非载具目标（玩家/生物/导弹）：无 OBB 概念 → fallback（调用方传 AABB 中心）；</li>
 *   <li>广播克隆（{@code remote}，超视距）：克隆体不参与 tick，cube 世界坐标是创建时的
 *       陈旧值（{@code RVP_HitIndicatorOverlay.modelExtents} 同款实证）→ fallback；</li>
 *   <li>OBB 列表为空/未初始化 → fallback。</li>
 * </ul></p>
 *
 * <p>"哪块最大"按 {@link VehicleCubeOBB#volume()}（bind-pose 尺寸，不随旋转/朝向变化）判定，
 * 选择结果稳定不抖；OBB 中心 {@code obb().center()} 已由本体 {@code VehicleCubeOBB.update}
 * 每 tick 应用载具位置与朝向（{@code AbstractVehicle.updateOBBs}），双端实时。已脱离的部件
 * （炮塔/吊舱 detach，{@code PartUnit.isDetached()} 含 basePartUnit 传递）不参与候选，
 * 避免瞄准已飞离的残块。</p>
 *
 * <p>双端安全：只引用本体公共双端类型，无任何客户端类型引用；服务端制导
 * （{@code guidance/}）与客户端 HUD/火控共用本入口，保证锁定框与弹着同源。</p>
 */
public final class RVP_AimPointResolver {

    private RVP_AimPointResolver() {}

    /**
     * 目标"最大体积 OBB 中心"，不可用时返回 fallback。
     *
     * @param target    锁定/跟踪目标实体
     * @param fallback  回退点（通常传 {@code target.getBoundingBox().getCenter()} 保持旧行为）
     */
    public static Vec3 resolveLargestObbCenter(Entity target, Vec3 fallback) {
        if (!(target instanceof AbstractVehicle vehicle) || vehicle.isRemoved() || vehicle.remote) {
            return fallback;
        }
        VehicleCubeOBB best = null;
        double bestVolume = -1.0;
        // 车体结构骨（含主物理块）
        List<VehicleCubeOBB> vehicleCubes = vehicle.getVehicleCubeOBBs();
        if (vehicleCubes != null) {
            for (VehicleCubeOBB cube : vehicleCubes) {
                if (cube == null) {
                    continue;
                }
                double volume = cube.volume();
                if (volume > bestVolume) {
                    bestVolume = volume;
                    best = cube;
                }
            }
        }
        // 部件骨（炮塔/雷达/发射架等），脱离部件跳过
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit == null || partUnit.isDetached()) {
                continue;
            }
            List<VehicleCubeOBB> partCubes = partUnit.getPartCubeOBBs();
            if (partCubes == null) {
                continue;
            }
            for (VehicleCubeOBB cube : partCubes) {
                if (cube == null) {
                    continue;
                }
                double volume = cube.volume();
                if (volume > bestVolume) {
                    bestVolume = volume;
                    best = cube;
                }
            }
        }
        if (best == null) {
            return fallback;
        }
        // OBB.center() 为 Vector3f 世界坐标（float 精度，够用）
        return new Vec3(best.obb().center().x, best.obb().center().y, best.obb().center().z);
    }

    /** 重载：fallback 缺省为目标 AABB 中心（旧行为），消费点零改动成本接入。 */
    public static Vec3 resolveLargestObbCenter(Entity target) {
        Vec3 fallback = target == null ? Vec3.ZERO : target.getBoundingBox().getCenter();
        return resolveLargestObbCenter(target, fallback);
    }
}
