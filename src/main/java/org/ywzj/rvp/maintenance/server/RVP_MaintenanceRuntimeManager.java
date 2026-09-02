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
     * 骨骼模块渐进恢复（方案 v2.1）：① 设备类逐台概率掷骰；② ERA 数量比例随机；
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

        // ① 设备类：逐台独立概率恢复（部分恢复有明确语义：APS 雷达骨 = 一个扫描扇区）。
        //    MAINTENANCE 自身不在设备恢复掷骰内（维修恢复维修设备由 ②/白名单控制，缺省不含）。
        for (Map.Entry<String, Set<BoneModuleType>> boneEntry : inactive.entrySet()) {
            for (BoneModuleType type : boneEntry.getValue()) {
                if (type != BoneModuleType.ERA && type != BoneModuleType.MAINTENANCE && cfg.isRepairable(type)
                        && random.nextFloat() < cfg.deviceRecoverChance) {
                    RVP_BoneModuleStateTable.restoreModule(vehicleId, boneEntry.getKey(), type);
                }
            }
        }
        // ② ERA：洗牌取 ceil(n * fraction)，至少 eraRecoverMin 块（MCHR 手感）
        List<String> destroyedEraBones = new ArrayList<>();
        for (Map.Entry<String, Set<BoneModuleType>> boneEntry : inactive.entrySet()) {
            if (boneEntry.getValue().contains(BoneModuleType.ERA) && cfg.isRepairable(BoneModuleType.ERA)) {
                destroyedEraBones.add(boneEntry.getKey());
            }
        }
        if (!destroyedEraBones.isEmpty()) {
            int n = (int) Math.ceil(destroyedEraBones.size() * cfg.eraRecoverFraction);
            n = Math.max(n, cfg.eraRecoverMin);
            n = Math.min(n, destroyedEraBones.size());
            // Fisher–Yates 洗牌（Level.random 为 RandomSource，不能用 Collections.shuffle）
            for (int i = destroyedEraBones.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                String tmp = destroyedEraBones.get(i);
                destroyedEraBones.set(i, destroyedEraBones.get(j));
                destroyedEraBones.set(j, tmp);
            }
            for (int i = 0; i < n; i++) {
                RVP_BoneModuleStateTable.restoreModule(vehicleId, destroyedEraBones.get(i), BoneModuleType.ERA);
            }
        }
        // ③ 一次广播：客户端动画恢复渲染骨、各消费端下 tick 自动重新生效
        RVP_VehicleHitboxFactorManager.syncBoneModuleState(vehicle);
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
