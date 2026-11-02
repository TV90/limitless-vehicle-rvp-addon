package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
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
 *
 * <p>§49（2026-11-01 用户定版）在此之上加了一个<b>红外族专用</b>入口
 * {@link #resolveIrLockPoint}：大目标（舰船）继续锁最大 OBB 中心，其余目标在剔除
 * 极小骨后的候选里按锁定种子抽一块，增加弹着随机感。其它制导类型（雷达/电视/SACLOS/
 * GPS/HITL）仍只走 {@link #resolveLargestObbCenter}，行为零变化。</p>
 */
public final class RVP_AimPointResolver {

    /** AABB 对角线超过该值（格）视为舰船类大目标：仍锁最大 OBB 中心，不参与随机抽点。 */
    private static final double IR_LOCK_WHOLE_TARGET_DIAGONAL = 35.0;

    /** 候选下界：体积不足最大 OBB 该比例的极小骨（履带链节/滚轮）不参与抽点。 */
    private static final double IR_LOCK_MIN_VOLUME_RATIO = 0.03;

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
        List<VehicleCubeOBB> cubes = new ArrayList<>();
        collectCubes(vehicle, cubes);
        VehicleCubeOBB best = null;
        double bestVolume = -1.0;
        for (VehicleCubeOBB cube : cubes) {
            double volume = cube.volume();
            if (volume > bestVolume) {
                bestVolume = volume;
                best = cube;
            }
        }
        if (best == null) {
            return fallback;
        }
        return centerOf(best);
    }

    /** 重载：fallback 缺省为目标 AABB 中心（旧行为），消费点零改动成本接入。 */
    public static Vec3 resolveLargestObbCenter(Entity target) {
        Vec3 fallback = target == null ? Vec3.ZERO : target.getBoundingBox().getCenter();
        return resolveLargestObbCenter(target, fallback);
    }

    /**
     * [RVP] §49（2026-11-01 用户定版）：红外族（IR/AIR）锁点。
     *
     * <p>舰船类大目标（AABB 对角线超过 35 格）仍锁最大 OBB 中心；其余目标在候选 OBB 中
     * 抽一块，让同一目标的不同弹分别命中车体/炮塔/发动机等部位。抽取只用
     * {@link #lockSeed(int, Entity)} 的种子，整段锁定期间结果不变——"每次锁定抽一次"，
     * 不会逐 Tick 抖动；弹体换目标时种子随之改变，等价于换锁重新抽。</p>
     *
     * <p>候选剔除体积不足最大值 3% 的极小骨：履带链节/滚轮（track_link_L、track_link_r、
     * track_roller*）这类纯定位骨只有主体的几百分之一，锁上去既无伤害意义，也会把弹着点
     * 拽到履带边缘。按全部 40 个载具结构实测，3% 下界对履带骨零漏选，坦克仍保留 7–10 块候选。</p>
     *
     * <p>非载具（玩家/生物/热焰弹）、广播克隆、OBB 列表为空一律回退 {@code fallback}，
     * 与 {@link #resolveLargestObbCenter} 的回退口径一致。</p>
     *
     * @param target   锁定目标
     * @param seed     锁定种子（同一发弹对同一目标必须恒定）
     * @param fallback 无 OBB 候选时的回退点
     */
    public static Vec3 resolveIrLockPoint(Entity target, long seed, Vec3 fallback) {
        if (!(target instanceof AbstractVehicle vehicle) || vehicle.isRemoved() || vehicle.remote) {
            return fallback;
        }
        AABB box = vehicle.getBoundingBox();
        double dx = box.getXsize();
        double dy = box.getYsize();
        double dz = box.getZsize();
        if (dx * dx + dy * dy + dz * dz
                > IR_LOCK_WHOLE_TARGET_DIAGONAL * IR_LOCK_WHOLE_TARGET_DIAGONAL) {
            return resolveLargestObbCenter(vehicle, fallback);
        }
        List<VehicleCubeOBB> cubes = new ArrayList<>();
        collectCubes(vehicle, cubes);
        if (cubes.isEmpty()) {
            return fallback;
        }
        double largestVolume = 0.0;
        for (VehicleCubeOBB cube : cubes) {
            largestVolume = Math.max(largestVolume, cube.volume());
        }
        double volumeFloor = largestVolume * IR_LOCK_MIN_VOLUME_RATIO;
        List<VehicleCubeOBB> candidates = new ArrayList<>(cubes.size());
        for (VehicleCubeOBB cube : cubes) {
            if (cube.volume() > 0.0 && cube.volume() >= volumeFloor) {
                candidates.add(cube);
            }
        }
        if (candidates.isEmpty()) {
            return fallback;
        }
        return centerOf(candidates.get(pickIndex(seed, candidates.size())));
    }

    /**
     * 锁点种子：(弹实体 ID, 目标 UUID) 的 64 位混合值。
     *
     * <p>只用双端都能拿到的稳定量，服务端逐 Tick、读取存档后重算都得到同一结果；不发包、
     * 不新增同步字段。弹体沿用同一目标重锁时结果保持，换目标才换点。</p>
     */
    public static long lockSeed(int projectileId, Entity target) {
        long targetBits = 0L;
        if (target != null) {
            targetBits = target.getUUID().getLeastSignificantBits()
                    ^ Long.rotateLeft(target.getUUID().getMostSignificantBits(), 17);
        }
        return mix(targetBits + projectileId * 0x9E3779B97F4A7C15L);
    }

    /** 把种子映射到 [0, size) 的候选下标；包内可见供选择分布回归测试。 */
    static int pickIndex(long seed, int size) {
        return size <= 1 ? 0 : (int) Long.remainderUnsigned(mix(seed), size);
    }

    /** MurmurHash3 末轮混合：低位分布均匀，避免直接用实体 ID 取模造成相邻 ID 总抽同一块。 */
    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    /** 车体结构骨（含主物理块）+ 未脱离部件骨（炮塔/雷达/发射架等）。 */
    private static void collectCubes(AbstractVehicle vehicle, List<VehicleCubeOBB> out) {
        List<VehicleCubeOBB> vehicleCubes = vehicle.getVehicleCubeOBBs();
        if (vehicleCubes != null) {
            for (VehicleCubeOBB cube : vehicleCubes) {
                if (cube != null) {
                    out.add(cube);
                }
            }
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit == null || partUnit.isDetached()) {
                continue;
            }
            List<VehicleCubeOBB> partCubes = partUnit.getPartCubeOBBs();
            if (partCubes == null) {
                continue;
            }
            for (VehicleCubeOBB cube : partCubes) {
                if (cube != null) {
                    out.add(cube);
                }
            }
        }
    }

    /** OBB.center() 为 Vector3f 世界坐标（float 精度，够用）。 */
    private static Vec3 centerOf(VehicleCubeOBB cube) {
        return new Vec3(cube.obb().center().x, cube.obb().center().y, cube.obb().center().z);
    }
}
