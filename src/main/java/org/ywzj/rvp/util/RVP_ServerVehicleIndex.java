package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [RVP] 服务端载具注册表（2026-10-01，§48 巨型载具命中候选"分节盲区"修复）：
 * 原版 {@code Level.getEntities} 的实体存储是<b>单分节注册</b>（
 * {@code PersistentEntitySectionManager.addEntityWithoutEvent} 只用 {@code entity.blockPosition()}
 * 定一个 16³ 分节），查询时只遍历查询盒 ±2 分节（X/Z）/Y 向下扩 4 范围内的分节——
 * AABB 横跨几十个分节的巨型载具（120 格的船），其船体远离注册分节的部分对
 * "小查询盒 + getEntities" <b>确定性不可见</b>：弹的段盒落在那些区域时候选收集返回空，
 * 本体命中链 {@code findEntityOnPathForSegment} 无候选 → 导弹/机炮弹完整过穿不爆
 * （用户实测"瞄 F3+B 绿框打、弹穿过船体"，卸 RVP 仍复现 = 本体 getEntities 固有语义）。
 *
 * <p>本注册表在 {@code EntityJoinLevelEvent}/{@code EntityLeaveLevelEvent} 维护每维度
 * 服务端全部 {@link AbstractVehicle}（数量级 &lt;100），供命中链在原版查询结果之外
 * <b>按载具包围盒（makeBoundingBox 包络，与原版粗筛同标准）补筛</b>：原版分节查得到的
 * 照旧、查不到的巨型载具由注册表兜底——小目标/正常路径零变化。</p>
 *
 * <p>双端安全：仅服务端事件路径写入（{@code level.isClientSide} 过滤），静态表按维度
 * 分桶；纯服务端使用。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_ServerVehicleIndex {

    /**
     * [RVP] §48 补丁总开关（硬编码，2026-10-02 用户要求）：本补丁是对本体/原版
     * {@code getEntities} 单分节注册盲区的 RVP 侧兜底——本体（ywzj_vehicle）侧已有意向
     * 修复该缺陷。本体修复落地后（EntityUtil.findEntityOnPath 或 getOBBs 侧），把此开关
     * 改为 {@code false} 即完全关闭补筛（直通原版查询结果），无需其它改动；
     * 注册表本身仍随事件维护，开销可忽略。
     *
     * <p>2026-10-02 关闭后用户复测：本体单独正常（本体修复覆盖其 {@code AmmoEntity} 链），
     * 但 RVP 弹走自己的 {@code findEntityOnPathForSegment} 候选收集，未受益于本体修复
     * → 加 RVP 又过穿。<b>重新开启（{@code true}）</b>。待本体作者上传修复源码后核实
     * 其修复点是否可让 RVP 弹受益，再评估关闭。</p>
     */
    private static final boolean PATCH_ENABLED = true;

    /** 每维度（按 Level 的 dimension key 区分）的载具弱语义列表。 */
    private static final Map<String, List<AbstractVehicle>> VEHICLES_BY_LEVEL = new ConcurrentHashMap<>();

    private RVP_ServerVehicleIndex() {}

    private static String keyOf(Level level) {
        return level.dimension().location().toString();
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        // 服务端线程串行写入；载具数量级小，直接列表 + 线性查重
        String key = keyOf(event.getLevel());
        List<AbstractVehicle> list = VEHICLES_BY_LEVEL.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(vehicle)) {
            list.add(vehicle);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        List<AbstractVehicle> list = VEHICLES_BY_LEVEL.get(keyOf(event.getLevel()));
        if (list == null) {
            return;
        }
        Iterator<AbstractVehicle> it = list.iterator();
        while (it.hasNext()) {
            if (it.next() == vehicle) {
                it.remove();
                break;
            }
        }
    }

    /**
     * 当前维度已注册的服务端载具快照（含已死亡但尚未离场的条目，消费方按 isAlive 过滤）。
     * 返回新列表，调用方可安全迭代/追加。
     */
    public static List<AbstractVehicle> getVehicles(Level level) {
        List<AbstractVehicle> list = VEHICLES_BY_LEVEL.get(keyOf(level));
        if (list == null) {
            return new ArrayList<>();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * [RVP] §48 命中候选补盲区核心：把原版 {@code getEntities} 查询结果与注册表逐载具
     * 包围盒粗筛合并。载具的 {@code getBoundingBox()} 即 makeBoundingBox 包络
     * （OBB 集合 + 主物理块的轴对齐包络），与原版分节查询的粗筛标准一致——
     * 注册表只补"原版查询因分节盲区漏掉"的载具，不改变任何过滤语义。
     *
     * @param level       服务端维度
     * @param queryBox    命中查询盒（原版 getEntities 用的同一盒子）
     * @param baseResults 原版 getEntities 的结果（保持原顺序与语义）
     * @param extraFilter 追加候选的额外过滤（与原版 predicate 同款，由调用方传入）
     */
    public static List<Entity> mergeWithVehicleIndex(Level level, AABB queryBox,
                                                     List<? extends Entity> baseResults, Predicate<Entity> extraFilter) {
        if (!PATCH_ENABLED) {
            // 补丁关闭：直通原版查询结果（本体已修复分节盲区时使用）
            return new ArrayList<>(baseResults);
        }
        List<AbstractVehicle> vehicles = getVehicles(level);
        if (vehicles.isEmpty()) {
            return new ArrayList<>(baseResults);
        }
        List<Entity> merged = new ArrayList<>(baseResults);
        for (AbstractVehicle vehicle : vehicles) {
            if (!vehicle.isAlive() || vehicle.isRemoved() || vehicle.isSpectator()) {
                continue;
            }
            if (!vehicle.getBoundingBox().intersects(queryBox)) {
                continue;
            }
            boolean already = false;
            for (Entity existing : merged) {
                if (existing == vehicle) {
                    already = true;
                    break;
                }
            }
            if (already || !extraFilter.test(vehicle)) {
                continue;
            }
            merged.add(vehicle);
        }
        return merged;
    }
}
