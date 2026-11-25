package org.ywzj.rvp.maintenance.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.maintenance.network.S2CMaintenanceSync;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.radar.RVP_RadarModuleEnforcer;
import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_BoneCumulativeDamageTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 快速维修运行时（服务端），设计参考 MCH-Reforged 的 MCH_Maintenance：
 * <ul>
 *   <li>配置：{@code bone_modules.__vehicle__.maintenance}（MAINTENANCE 模块，缺省虚拟骨、
 *       永不可被击毁；绑定实体骨时骨块被击毁则维修失效）；</li>
 *   <li>状态机：冷却 {@code cooldown} 递减 → 触发时进入生效期 {@code useRemain}，
 *       每 tick 回 {@code heal_per_tick_percent}% 最大血量；</li>
 *   <li>维修结束时按完整优先队列恢复受损模块：设备按比例向上取整，超过两台时保底两台；
 *       ERA 按数量比例（ceil(fraction)、至少 min 块）；
 *       恢复后经 {@link RVP_VehicleHitboxFactorManager#syncBoneModuleState} 一次广播，
 *       JS 动画 / HUD / 各 Runtime Manager（全部查 {@link RVP_BoneModuleStateTable}）自动重新生效；</li>
 *   <li>持久化：冷却 / 生效剩余写载具 {@code getPersistentData()}（实体 NBT，随存档），载具
 *       加入世界时惰性恢复；</li>
 *   <li>HUD：每 10 tick 节流推送 {@link S2CMaintenanceSync}（含 hasMaintenance 标志），
 *       客户端干扰物 HUD 按缺省递补规则追加"维修"行。</li>
 * </ul>
 * 驱动见 {@link RVP_MaintenanceEventHandler}（加入 / 离开世界 / 服务端 tick）。
 */
public final class RVP_MaintenanceRuntimeManager {

    /** NBT 键：冷却剩余 tick。 */
    private static final String NBT_COOLDOWN = "rvp_maintenance_cooldown";
    /** NBT 键：生效剩余 tick。 */
    private static final String NBT_USE_REMAIN = "rvp_maintenance_use_remain";

    /** 内存运行态：载具 UUID → 状态。 */
    private static final Map<UUID, MaintenanceState> STATES = new HashMap<>();

    private RVP_MaintenanceRuntimeManager() {
    }

    /** 单台载具的维修状态机。 */
    private static final class MaintenanceState {
        int cooldown;
        int useRemain;
        /** HUD 节流：上次同步的 vehicle.tickCount。 */
        int lastHudSyncTick = -100;
    }

    // ─────────────────────────────────────────────────────────────
    // 生命周期（RVP_MaintenanceEventHandler 调用）
    // ─────────────────────────────────────────────────────────────

    /** 载具加入世界：从实体 NBT 惰性恢复冷却状态。 */
    public static void onVehicleJoin(AbstractVehicle vehicle) {
        var data = vehicle.getPersistentData();
        if (data.contains(NBT_COOLDOWN) || data.contains(NBT_USE_REMAIN)) {
            MaintenanceState state = new MaintenanceState();
            state.cooldown = Math.max(0, data.getInt(NBT_COOLDOWN));
            state.useRemain = Math.max(0, data.getInt(NBT_USE_REMAIN));
            STATES.put(vehicle.getUUID(), state);
        }
    }

    /** 载具离开世界：状态写回实体 NBT 并清理内存。 */
    public static void onVehicleLeave(AbstractVehicle vehicle) {
        MaintenanceState state = STATES.remove(vehicle.getUUID());
        if (state != null) {
            writeThrough(vehicle, state);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 每 tick 推进（服务端）
    // ─────────────────────────────────────────────────────────────

    /**
     * 每 tick 推进状态机；生效期每 tick 回血。未配置维修的载具一次查表即返回，零额外开销。
     * MAINTENANCE 模块被击毁（实体骨绑定）时生效期立即中断且不可再触发。
     */
    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide() || vehicle.isDestroyed()) {
            return;
        }
        var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle);
        if (binding == null) {
            return;
        }
        if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), binding.bone(), BoneModuleType.MAINTENANCE)) {
            return; // 维修模块被击毁（实体骨绑定）：维修不可用
        }
        BoneMaintenanceConfig config = binding.config();
        MaintenanceState state = STATES.computeIfAbsent(vehicle.getUUID(), k -> new MaintenanceState());
        if (state.cooldown > 0) {
            --state.cooldown;
        }
        if (state.useRemain > 0) {
            --state.useRemain;
            float heal = vehicle.getMaxHealth() * config.healPerTickPercent() / 100f;
            vehicle.heal(heal);
            if (config.healParts()) {
                healParts(vehicle);
            }
            // [RVP] 维修音效（2026-09-28）：扳手音每 ~25t 一次循环（wrench1/2/3 随机变体）
            if (state.useRemain % 25 == 0) {
                vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        RVP_Sounds.MAINTENANCE_WRENCH.get(), net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
            }
            if (state.useRemain == 0) {
                // [RVP] 生效结束（2026-09-28 定版；2026-11-06 累计结算改为按修复结果）：
                // ① 冷却从此刻才开始计时；② 骨骼模块（部件）此刻才恢复——按维修不再瞬时恢复；
                // ③ 累计伤害此刻结算：本次修复的骨清空（回满），其余骨按 1/3 阈值回血
                state.cooldown = config.waitTimeTicks();
                recoverModules(vehicle, config);
                // 写穿 + 立即推一次，客户端及时切出"维修中"并显示冷却倒计时
                writeThrough(vehicle, state);
                syncHud(vehicle, state);
                return;
            }
        }
        maybeSyncHud(vehicle, state);
    }

    /** 生效期同步回部件血量（与扳手同款：每部件 +10% 上限）。 */
    private static void healParts(AbstractVehicle vehicle) {
        for (var partUnit : vehicle.getPartUnits()) {
            if (partUnit.isDetached()) {
                continue;
            }
            float max = partUnit.getMaxHealth();
            if (max > 0 && partUnit.getHealth() < max) {
                partUnit.setHealth(Math.min(max, partUnit.getHealth() + max / 10f));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 触发（C2SUseMaintenance → 服务端权威校验）
    // ─────────────────────────────────────────────────────────────

    /**
     * 玩家尝试触发快速维修。校验：已配置 / 维修模块存活 / 未被摧毁 / 冷却就绪 /
     * 玩家在本车一号位 / 离地高度限制。通过则进入生效期并执行模块渐进恢复。
     *
     * @return true = 已触发
     */
    public static boolean tryStart(ServerPlayer player, AbstractVehicle vehicle) {
        // 权限门先于配置查询和状态分配，伪造请求不得改变冷却表。
        if (!org.ywzj.rvp.util.RVP_VehicleSeatAccess.isPrimaryPassenger(player, vehicle)
                || vehicle.level().isClientSide() || vehicle.isDestroyed()) {
            return false;
        }
        var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle);
        if (binding == null) {
            return false;
        }
        if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), binding.bone(), BoneModuleType.MAINTENANCE)) {
            return false; // 维修模块被击毁
        }
        MaintenanceState state = STATES.computeIfAbsent(vehicle.getUUID(), k -> new MaintenanceState());
        if (state.cooldown > 0 || state.useRemain > 0) {
            return false;
        }
        BoneMaintenanceConfig config = binding.config();
        // 离地高度限制（默认不限）
        if (config.requireMaxAltitude() >= 0
                && vehicle.getY() - vehicle.level().getMinBuildHeight() > config.requireMaxAltitude()) {
            return false;
        }
        // 进入生效期（2026-09-28 定版：冷却在维修持续结束后才开始计时——见 tick() 结束分支）
        state.useRemain = config.useTimeTicks();
        writeThrough(vehicle, state);
        syncHud(vehicle, state);
        return true;
    }

    /**
     * 完成维修时调用共用计划器筛选失效设备和爆反，按队列分配独立名额；
     * 修复骨清空累计，其余骨扣减三分之一阈值，再同步模块状态。
     */
    private static void recoverModules(AbstractVehicle vehicle, BoneMaintenanceConfig config) {
        BoneMaintenanceConfig.ModuleRepair cfg = config.moduleRepair() != null
                ? config.moduleRepair()
                : BoneMaintenanceConfig.ModuleRepair.defaults();
        UUID vehicleId = vehicle.getUUID();
        // [RVP] 本次真正修复（命中恢复配额）的骨：累计结算时只有这些骨回满（2026-11-06 用户定版）
        Set<String> repairedBones = new java.util.HashSet<>();
        Map<String, Set<BoneModuleType>> inactive = RVP_BoneModuleStateTable.getInactiveModules(vehicleId);
        if (inactive.isEmpty()) {
            // 无失效模块也执行累计结算：其余受损部件按 1/3 阈值回血
            applyResidualDamageRecovery(vehicle, repairedBones);
            RVP_VehicleHitboxFactorManager.syncBoneModuleState(vehicle);
            return;
        }
        // 调用完整队列和共用目标选择器；当前受损状态只在结算时筛选。
        var order = RVP_RepairOrderTable.getOrder(vehicle);
        var targets = org.ywzj.rvp.maintenance.RVP_RepairPlanner.select(
                new org.ywzj.rvp.maintenance.RVP_RepairPlanner.Order(order.eraBones(), order.deviceBones()),
                inactive, cfg);
        for (String bone : targets.deviceBones()) {
            restoreBoneModules(vehicle, bone, inactive.get(bone), cfg, false);
            repairedBones.add(bone);
        }
        for (String bone : targets.eraBones()) {
            restoreBoneModules(vehicle, bone, inactive.get(bone), cfg, true);
            repairedBones.add(bone);
        }
        // ③ 累计伤害结算（2026-11-06 用户定版）：本次修复的骨清空累计（虚拟血量回满）；
        //    其余骨按自身阈值 1/3 扣减累计（虚拟血量 +1/3），不再全车清零
        applyResidualDamageRecovery(vehicle, repairedBones);
        // ④ 一次广播：客户端动画恢复渲染骨、各消费端下 tick 自动重新生效（含最终累计快照）
        RVP_VehicleHitboxFactorManager.syncBoneModuleState(vehicle);
    }

    /**
     * [RVP] 快修结束的累计伤害结算（2026-11-06 用户定版）：只有本次真正修复的骨清空累计
     * （面板虚拟血量回满）；其余仍有累计的骨按自身阈值的 1/3 回血（累计 −= 阈值/3，封底 0）。
     * 替代原 {@code clearVehicle} 全车清零——未修复部件不再"自动满血"。
     */
    private static void applyResidualDamageRecovery(AbstractVehicle vehicle, Set<String> repairedBones) {
        UUID vehicleId = vehicle.getUUID();
        for (Map.Entry<String, Float> entry : RVP_BoneCumulativeDamageTable.snapshotOf(vehicleId).entrySet()) {
            String bone = entry.getKey();
            if (repairedBones.contains(bone)) {
                RVP_BoneCumulativeDamageTable.clear(vehicleId, bone);
                continue;
            }
            if (entry.getValue() <= 0f) {
                continue;
            }
            float threshold = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneDamageThreshold(vehicle, bone);
            if (!Float.isFinite(threshold) || threshold <= 0f) {
                continue; // 未配置累计阈值：无虚拟血量语义，保持原累计
            }
            RVP_BoneCumulativeDamageTable.reduce(vehicleId, bone, threshold / 3f);
        }
    }

    /**
     * 捆绑恢复一块骨上全部失效的可修模块（用户 2026-09-26 定版：同骨多角色一起修）。
     *
     * @param includeEra true = 连同 ERA 一起恢复（爆反配额路径）；false = 跳过 ERA（设备配额路径）
     */
    private static void restoreBoneModules(AbstractVehicle vehicle, String bone, Set<BoneModuleType> types,
                                           BoneMaintenanceConfig.ModuleRepair cfg, boolean includeEra) {
        UUID vehicleId = vehicle.getUUID();
        for (BoneModuleType type : types) {
            if (type == BoneModuleType.MAINTENANCE) {
                continue; // 维修模块自身永不恢复
            }
            if (type == BoneModuleType.ERA && !includeEra) {
                continue;
            }
            if (cfg.isRepairable(type) && RVP_BoneModuleStateTable.restoreModule(vehicleId, bone, type)) {
                // [RVP] 新类型恢复联动（2026-09-26）：
                // RADAR——修好自动开机（用户定版，与 gunner 自动开机语义一致）；
                // ENGINE/ENGINE_DAMAGED——清空引擎累计，防止恢复后残存累计立即再次跨阈值
                // （ENGINE_DAMAGED 重创档 2026-09-28 进失效表，恢复=重创解除，同样清累计）；
                // BARREL——清空炮管累计（同口径，2026-09-28）
                if (type == BoneModuleType.RADAR) {
                    RVP_RadarModuleEnforcer.restoreRadar(vehicle, bone);
                } else if (type == BoneModuleType.ENGINE || type == BoneModuleType.ENGINE_DAMAGED
                        || type == BoneModuleType.BARREL || type == BoneModuleType.BARREL_DAMAGED
                        || type.isTurretTraverse()) {
                    RVP_BoneCumulativeDamageTable.clear(vehicleId, bone);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HUD 同步（S2CMaintenanceSync，每 10 tick 节流）
    // ─────────────────────────────────────────────────────────────

    private static void maybeSyncHud(AbstractVehicle vehicle, MaintenanceState state) {
        // 生效期 5t 节流（维修中倒计时 0.25s 粒度），其余 10t
        int interval = state.useRemain > 0 ? 5 : 10;
        int now = vehicle.tickCount;
        if (now - state.lastHudSyncTick < interval) {
            return;
        }
        state.lastHudSyncTick = now;
        syncHud(vehicle, state);
    }

    private static void syncHud(AbstractVehicle vehicle, MaintenanceState state) {
        int useTimeTotal = 0;
        var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle);
        if (binding != null) {
            useTimeTotal = binding.config().useTimeTicks();
        }
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                new S2CMaintenanceSync(vehicle.getId(), true, state.cooldown, state.useRemain, useTimeTotal));
    }

    /** 状态写回实体 NBT（随载具存档）。 */
    private static void writeThrough(AbstractVehicle vehicle, MaintenanceState state) {
        var data = vehicle.getPersistentData();
        data.putInt(NBT_COOLDOWN, state.cooldown);
        data.putInt(NBT_USE_REMAIN, state.useRemain);
    }
}
