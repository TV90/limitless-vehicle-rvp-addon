package org.ywzj.rvp.maintenance.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.maintenance.network.S2CMaintenanceSync;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 *   <li>触发瞬间执行骨骼模块渐进恢复（方案 v2.1）：设备类（非 ERA）逐台按
 *       {@code device_recover_chance} 概率恢复；ERA 按数量比例（ceil(fraction)、至少 min 块）；
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
            if (state.useRemain == 0) {
                // 生效结束：写穿冷却 + 立即推一次，客户端及时切出"维修中"
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
     * 玩家在本车上 / 离地高度限制。通过则进入生效期并执行模块渐进恢复。
     *
     * @return true = 已触发
     */
    public static boolean tryStart(ServerPlayer player, AbstractVehicle vehicle) {
        if (player == null || vehicle == null || vehicle.level().isClientSide() || vehicle.isDestroyed()) {
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
        // 必须乘坐在本车上（防手持改装工具远程滥用）
        if (player.getVehicle() != vehicle) {
            return false;
        }
        BoneMaintenanceConfig config = binding.config();
        // 离地高度限制（默认不限）
        if (config.requireMaxAltitude() >= 0
                && vehicle.getY() - vehicle.level().getMinBuildHeight() > config.requireMaxAltitude()) {
            return false;
        }
        // 进入生效期
        state.cooldown = config.waitTimeTicks();
        state.useRemain = config.useTimeTicks();
        writeThrough(vehicle, state);
        // 模块渐进恢复（设备概率 + ERA 比例），恢复后一次广播即可
        recoverModules(vehicle, config);
        syncHud(vehicle, state);
        return true;
    }

    /**
     * 骨骼模块渐进恢复（方案 v2.1 + 维修顺序队列）：① 设备类先按"辅助设备维修顺序"队列
     * 逐台掷骰，队列外保持原有遍历掷骰；② ERA 恢复数量配额不变（ceil(n × fraction)、
     * 至少 eraRecoverMin），改为从"爆反维修顺序"队列头优先占配额，队列外仍洗牌补足——
     * 即只把"修哪些"的决定权交给玩家，恢复量 / 概率公式一律不动；
     * ③ 一次 {@code syncBoneModuleState} 广播——消费端全部查状态表，恢复即自动生效。
     */
    private static void recoverModules(AbstractVehicle vehicle, BoneMaintenanceConfig config) {
        BoneMaintenanceConfig.ModuleRepair cfg = config.moduleRepair() != null
                ? config.moduleRepair()
                : BoneMaintenanceConfig.ModuleRepair.defaults();
        UUID vehicleId = vehicle.getUUID();
        Map<String, Set<BoneModuleType>> inactive = RVP_BoneModuleStateTable.getInactiveModules(vehicleId);
        if (inactive.isEmpty()) {
            return;
        }
        var random = vehicle.level().random;
        // 玩家设置的维修顺序（未设置 = EMPTY，两条分支行为与改动前完全一致）
        RVP_RepairOrderTable.RepairOrder order = RVP_RepairOrderTable.getOrder(vehicleId);
        List<String> deviceQueue = order.deviceBones();
        List<String> eraQueue = order.eraBones();

        // ① 辅助设备（纯设备骨）：按骨掷骰一次，成功则该骨全部失效设备模块一起恢复（捆绑语义）。
        //    含 ERA 的双角色骨（如 t84bm 的 ERA+干扰机同骨）不在此列——它归入爆反配额，随 ERA 捆绑恢复。
        //    MAINTENANCE 自身不在设备恢复掷骰内。
        for (String queuedBone : deviceQueue) {
            Set<BoneModuleType> queuedTypes = inactive.get(queuedBone);
            if (queuedTypes == null || queuedTypes.contains(BoneModuleType.ERA)) {
                continue; // 双角色骨走爆反配额捆绑恢复
            }
            if (hasRepairableDevice(queuedTypes, cfg) && random.nextFloat() < cfg.deviceRecoverChance) {
                restoreBoneModules(vehicleId, queuedBone, queuedTypes, cfg, false);
            }
        }
        for (Map.Entry<String, Set<BoneModuleType>> boneEntry : inactive.entrySet()) {
            if (deviceQueue.contains(boneEntry.getKey()) || boneEntry.getValue().contains(BoneModuleType.ERA)) {
                continue; // 队列内已处理；双角色骨走爆反配额捆绑恢复
            }
            if (hasRepairableDevice(boneEntry.getValue(), cfg) && random.nextFloat() < cfg.deviceRecoverChance) {
                restoreBoneModules(vehicleId, boneEntry.getKey(), boneEntry.getValue(), cfg, false);
            }
        }
        // ② ERA：恢复数量配额不变（ceil(n * fraction)，至少 eraRecoverMin 块，MCHR 手感）；
        //    选择顺序为"爆反维修顺序"队列头优先，队列外剩余块仍 Fisher–Yates 洗牌补足。
        //    配额选中的骨按捆绑语义整骨恢复：ERA 连同骨上失效的可修设备模块（如干扰机）一起修回。
        List<String> destroyedEraBones = new ArrayList<>();
        for (Map.Entry<String, Set<BoneModuleType>> boneEntry : inactive.entrySet()) {
            if (boneEntry.getValue().contains(BoneModuleType.ERA) && cfg.isRepairable(BoneModuleType.ERA)) {
                destroyedEraBones.add(boneEntry.getKey());
            }
        }
        if (!destroyedEraBones.isEmpty()) {
            // 队列内且确实已毁的块按队列序排在最前（只保留一次，防客户端重复上报）
            List<String> ordered = new ArrayList<>();
            for (String queuedBone : eraQueue) {
                if (destroyedEraBones.contains(queuedBone) && !ordered.contains(queuedBone)) {
                    ordered.add(queuedBone);
                }
            }
            List<String> remaining = new ArrayList<>(destroyedEraBones);
            remaining.removeAll(ordered);
            // 队列外洗牌（Level.random 为 RandomSource，不能用 Collections.shuffle）
            for (int i = remaining.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                String tmp = remaining.get(i);
                remaining.set(i, remaining.get(j));
                remaining.set(j, tmp);
            }
            ordered.addAll(remaining);
            int n = (int) Math.ceil(destroyedEraBones.size() * cfg.eraRecoverFraction);
            n = Math.max(n, cfg.eraRecoverMin);
            n = Math.min(n, ordered.size());
            for (int i = 0; i < n; i++) {
                String bone = ordered.get(i);
                restoreBoneModules(vehicleId, bone, inactive.get(bone), cfg, true);
            }
        }
        // ③ 一次广播：客户端动画恢复渲染骨、各消费端下 tick 自动重新生效
        RVP_VehicleHitboxFactorManager.syncBoneModuleState(vehicle);
    }

    /** 骨上是否存在可修的非 ERA 设备模块（用于辅助设备分支按骨掷骰判定）。 */
    private static boolean hasRepairableDevice(Set<BoneModuleType> types, BoneMaintenanceConfig.ModuleRepair cfg) {
        for (BoneModuleType type : types) {
            if (type != BoneModuleType.ERA && type != BoneModuleType.MAINTENANCE && cfg.isRepairable(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 捆绑恢复一块骨上全部失效的可修模块（用户 2026-09-26 定版：同骨多角色一起修）。
     *
     * @param includeEra true = 连同 ERA 一起恢复（爆反配额路径）；false = 跳过 ERA（纯设备骨掷骰路径）
     */
    private static void restoreBoneModules(UUID vehicleId, String bone, Set<BoneModuleType> types,
                                           BoneMaintenanceConfig.ModuleRepair cfg, boolean includeEra) {
        for (BoneModuleType type : types) {
            if (type == BoneModuleType.MAINTENANCE) {
                continue; // 维修模块自身永不恢复
            }
            if (type == BoneModuleType.ERA && !includeEra) {
                continue;
            }
            if (cfg.isRepairable(type)) {
                RVP_BoneModuleStateTable.restoreModule(vehicleId, bone, type);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HUD 同步（S2CMaintenanceSync，每 10 tick 节流）
    // ─────────────────────────────────────────────────────────────

    private static void maybeSyncHud(AbstractVehicle vehicle, MaintenanceState state) {
        int now = vehicle.tickCount;
        if (now - state.lastHudSyncTick < 10) {
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
