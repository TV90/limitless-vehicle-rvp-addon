package org.ywzj.rvp.countermeasure;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 雷达箔条判定公共助手（双端安全，客户端/服务端复用同一逻辑）。
 *
 * <p>模型：雷达**当前锁定目标**周围 `radarJamRadius` 内箔条实体数 ≥ `radarJamCount` 时，
 * 雷达脱锁，目标进入 `radarJamCooldownTick` 禁锁期（可被扫描、不可被选中/锁定）。</p>
 *
 * <p>计数带 2 秒记忆：目标机速过快时箔条快速脱离目标，单帧半径内数量不足。
 * 记录每枚箔条最近一次进入目标周围半径的时间，2 秒内被"看到过"的箔条持续计入。
 * 服务端与客户端都会调用本类，记忆表须线程安全。</p>
 */
public final class RVP_ChaffJamHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 雷达箔条 2 秒记忆：targetId → (chaffId → 最近一次进入目标周围半径的时间 tick)。 */
    private static final Map<Integer, Map<Integer, Long>> RADAR_CHAFF_MEMORY = new ConcurrentHashMap<>();
    private static final long CHAFF_MEMORY_TICKS = 40; // 2 秒

    private RVP_ChaffJamHelper() {
    }

    /** 返回目标载具的箔条子系统配置（用于雷达判定）；非载具或未启用返回 null。 */
    @Nullable
    public static RVP_CountermeasureSystemData resolveChaffConfig(@Nullable Entity target) {
        if (target instanceof AbstractVehicle tv) {
            RVP_CountermeasureData config = RVP_CountermeasureConfigManager.INSTANCE.resolve(tv.getVehicleId());
            RVP_CountermeasureSystemData chaff = config == null ? null : config.getChaff();
            return chaff != null && chaff.isEnabled() ? chaff : null;
        }
        return null;
    }

    /** 统计目标周围 {@code radius} 内指定类型的干扰物实体数。 */
    public static int countDecoysNear(@Nullable Entity target, double radius, RVP_EnumCountermeasureType type) {
        if (target == null || radius <= 0) {
            return 0;
        }
        AABB box = target.getBoundingBox().inflate(radius);
        int count = 0;
        for (Entity entity : target.level().getEntities(target, box, e -> e instanceof RVP_Decoy && e.isAlive())) {
            if (((RVP_Decoy) entity).rvp$decoyType() == type && entity.distanceTo(target) <= radius) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计目标周围箔条数（带 2 秒记忆累计）。
     * 目标机速过快时箔条快速脱离目标，单帧半径内数量不足以触发脱锁；
     * 记录每枚箔条最近一次进入目标周围半径的时间，2 秒内被"看到过"的箔条持续计入。
     * 每 tick 调用（不能节流）：高速机抛出的箔条在目标附近只停留极短时间，
     * 节流会漏掉它"刚好在目标旁"的那个瞬间，导致脱锁时灵时不灵。
     */
    private static int countChaffWithMemory(Entity target, double radius, long gameTime) {
        if (target == null || radius <= 0) {
            return 0;
        }
        // 以目标膨胀 AABB 为扫描/计数区域：尾喷口/翼下发射的箔条可能离目标中心超过 radius，
        // 但仍紧贴机体（属于雷达杂波应计入），按中心距离判断会漏
        AABB targetBox = target.getBoundingBox().inflate(radius);
        for (Entity entity : target.level().getEntities(target, targetBox, e -> e instanceof RVP_Decoy && e.isAlive())) {
            if (((RVP_Decoy) entity).rvp$decoyType() == RVP_EnumCountermeasureType.CHAFF
                    && entity.getBoundingBox().intersects(targetBox)) {
                RADAR_CHAFF_MEMORY.computeIfAbsent(target.getId(), k -> new ConcurrentHashMap<>()).put(entity.getId(), gameTime);
            }
        }
        Map<Integer, Long> memory = RADAR_CHAFF_MEMORY.get(target.getId());
        if (memory == null) {
            return 0;
        }
        memory.entrySet().removeIf(e -> gameTime - e.getValue() >= CHAFF_MEMORY_TICKS);
        int count = memory.size();
        if (memory.isEmpty()) {
            RADAR_CHAFF_MEMORY.remove(target.getId());
        }
        return count;
    }

    /**
     * 统一箔条干扰处理：锁定目标被箔条遮蔽（超阈值）时脱锁并写目标禁锁期。
     *
     * @param radarOwnerVehicle 拥有该雷达的载具（用于清理其武器锁定）
     * @param radar             被干扰的雷达
     * @param locked            被锁定目标
     * @param gameTime          当前游戏 tick
     * @return 是否发生了干扰脱锁
     */
    public static boolean tryJamLock(Entity radarOwnerVehicle, RadarUnit radar, Entity locked, long gameTime) {
        RVP_CountermeasureSystemData chaff = resolveChaffConfig(locked);
        if (chaff == null) {
            return false;
        }
        int count = countChaffWithMemory(locked, chaff.getRadarJamRadius(), gameTime);
        if (count < chaff.getRadarJamCount()) {
            return false;
        }
        // 箔条触发脱锁日志（开关：/rvpdebug flags cm；脱锁逻辑不受影响）
        if (RVP_DebugFlags.CM.isEnabled()) {
            LOGGER.info("[RVP-ChaffJam] 雷达={} 锁定目标={} 箔条数={} 触发脱锁（禁锁{}tick）",
                    radar.getId(), locked.getId(), count, chaff.getRadarJamCooldownTick());
        }
        breakLock(radarOwnerVehicle, radar, locked);
        RVP_ChaffJamState.setCooldown(locked.getUUID(), gameTime, chaff.getRadarJamCooldownTick());
        return true;
    }

    /**
     * 箔条干扰<b>外置雷达（中继雷达）</b>锁定：目标周围箔条超阈值时清除外置锁定并写禁锁期。
     * 外置锁定存于 {@link RVP_WeaponLockStateTable}（非本地 RadarUnit），SARH/ARH 中继引导读取它。
     *
     * @param radarOwnerVehicle 拥有外置雷达链路的载具
     * @param root              根武器站（外置锁定挂载处）
     * @param locked            被外置雷达锁定的目标
     * @param gameTime          当前游戏 tick
     * @return 是否发生了干扰脱锁
     */
    public static boolean tryJamExternalLock(Entity radarOwnerVehicle, WeaponUnit root, Entity locked, long gameTime) {
        if (root == null || locked == null || !locked.isAlive()) {
            return false;
        }
        RVP_CountermeasureSystemData chaff = resolveChaffConfig(locked);
        if (chaff == null) {
            return false;
        }
        int count = countChaffWithMemory(locked, chaff.getRadarJamRadius(), gameTime);
        if (count < chaff.getRadarJamCount()) {
            return false;
        }
        // 外置雷达箔条脱锁日志（开关：/rvpdebug flags cm）
        if (RVP_DebugFlags.CM.isEnabled()) {
            LOGGER.info("[RVP-ChaffJam] 外置雷达 目标={} 记忆累计箔条数={} 触发脱锁（禁锁{}tick）",
                    locked.getId(), count, chaff.getRadarJamCooldownTick());
        }
        // 清除外置雷达锁定与请求（服务端权威状态），并写目标禁锁期
        RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
        RVP_WeaponLockStateTable.clearExternalRadarRequestedEntityId(root);
        RVP_ChaffJamState.setCooldown(locked.getUUID(), gameTime, chaff.getRadarJamCooldownTick());
        return true;
    }

    /** 清除指向目标的所有雷达/武器锁定与 pending/外部锁定。 */
    private static void breakLock(Entity radarOwnerVehicle, RadarUnit radar, Entity target) {
        radar.setLockedEntity(null);
        if (radarOwnerVehicle instanceof AbstractVehicle vehicle) {
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit wu)) {
                    continue;
                }
                WeaponUnit root = wu.getRootParentWeaponUnit();
                if (root == null) {
                    continue;
                }
                if (root.getLockedEntity() == target) {
                    root.setLockedEntity(null);
                }
                if (RVP_WeaponLockStateTable.getPendingRadarLockEntityId(root) == target.getId()) {
                    RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(root);
                }
                if (RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root) == target.getId()) {
                    RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
                }
            }
        }
    }
}
