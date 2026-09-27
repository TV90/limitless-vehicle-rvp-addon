package org.ywzj.rvp.maintenance.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BarrelDamageTable;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_EngineDamageTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [RVP] 焊枪部件修复（2026-09-28 新增，零 Mixin）。
 *
 * <p>语义（用户定版）：手持本体焊枪（{@code ywzj_vehicle:repair_tool}）对准**损坏部件的
 * 骨骼**持续照射 5 秒（100 tick）→ 恢复该骨全部失效模块——载具/部件回血由本体焊枪
 * 自带（{@code RepairToolItem.onUseTick} 服务端已做，此处不重复）。必须持续对准同一
 * 损坏骨：换目标、丢失瞄准、骨上无失效模块即清空进度重新累计。</p>
 *
 * <p>实现：{@link LivingEntityUseItemEvent.Tick} 服务端分支（与本体焊枪 onUseTick 逐 tick
 * 同步，双端事件、无需扫描全玩家）+ 本体公共 {@code VectorUtil#hitPartUnit}（服务端安全，
 * 服务端路径已被本体三处实证）定位命中部件 → RVP 自有
 * {@link RVP_VehicleHitboxFactorManager#resolveHitboxDamage} 骨射线（实时命中 OBB，§29
 * 组细分修复后炮管骨同样可命中）拿骨名。判定完成后走快修同款恢复语义
 * （{@code restoreModule} + RADAR 自动开机 + ENGINE/BARREL 清累计 + 一次失效广播）。
 * 带毒类（AbstractVehicle/WeaponUnit/PartUnit）零接触。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WrenchRepairHandler {

    /** 部件修复所需持续照射时长（tick）：5 秒（用户定版）。 */
    private static final int REQUIRED_TICKS = 100;
    /** 射线长度（格）：与本体焊枪 onUseTick 同参数（3 格对准距离）。 */
    private static final double RAY_LENGTH = 3.0;

    /** 单玩家瞄准状态：目标载具 + 目标骨 + 已连续照射 tick。 */
    private static final class AimState {
        UUID vehicleId;
        String boneName;
        int ticks;
    }

    private static final Map<UUID, AimState> AIMING = new HashMap<>();

    private RVP_WrenchRepairHandler() {
    }

    @SubscribeEvent
    public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.level().isClientSide()) {
            return;
        }
        ItemStack used = event.getItem();
        if (used.isEmpty() || !used.is(AllItems.REPAIR_TOOL.get())) {
            return;
        }
        tickAim(player);
    }

    /** 焊枪瞄准状态机：射线 → 命中载具部件骨 → 查失效模块 → 连续 100t 完成修复。 */
    private static void tickAim(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(RAY_LENGTH));
        // 目的：本体公共 API（服务端安全）拿准星命中的部件——已摧毁部件也参与命中（维修场景）
        PartUnit<?> hitPart = VectorUtil.hitPartUnit(player, eye, end);
        String boneName = null;
        AbstractVehicle vehicle = null;
        if (hitPart != null && hitPart.getVehicle() != null) {
            vehicle = hitPart.getVehicle();
            // 骨名判定走 RVP 命中链（实时 OBB，含武器站按组细分）；部件型失效骨（部件 id=骨名）
            // resolve 未命中时回退部件 id
            var res = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(vehicle, eye, end);
            if (res != null && res.hitBoneName() != null && !res.hitBoneName().isBlank()) {
                boneName = res.hitBoneName();
            } else {
                boneName = hitPart.getId();
            }
        }
        UUID playerId = player.getUUID();
        if (vehicle == null || boneName == null) {
            AIMING.remove(playerId);
            return;
        }
        // 骨上无失效模块：无维修目标，清进度（部件血量回补由本体焊枪自行处理）
        Set<BoneModuleType> inactive = RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID())
                .get(boneName);
        if (inactive == null || inactive.isEmpty()) {
            AIMING.remove(playerId);
            return;
        }
        AimState state = AIMING.get(playerId);
        if (state == null || !vehicle.getUUID().equals(state.vehicleId)
                || !boneName.equals(state.boneName)) {
            // 换目标：重新累计
            state = new AimState();
            state.vehicleId = vehicle.getUUID();
            state.boneName = boneName;
            state.ticks = 0;
            AIMING.put(playerId, state);
        }
        state.ticks++;
        if (state.ticks < REQUIRED_TICKS) {
            return;
        }
        // 持续照射满 5 秒：恢复该骨全部失效模块（快修同款恢复语义）
        AIMING.remove(playerId);
        boolean restored = false;
        for (BoneModuleType type : inactive) {
            if (type == BoneModuleType.MAINTENANCE) {
                continue; // 维修模块自身永不恢复
            }
            if (RVP_BoneModuleStateTable.restoreModule(vehicle.getUUID(), boneName, type)) {
                restored = true;
                // 恢复联动（与快修 restoreBoneModules 同款）：RADAR 自动开机；引擎/炮管清累计
                if (type == BoneModuleType.RADAR) {
                    org.ywzj.rvp.radar.RVP_RadarModuleEnforcer.restoreRadar(vehicle, boneName);
                } else if (type == BoneModuleType.ENGINE || type == BoneModuleType.ENGINE_DAMAGED) {
                    RVP_EngineDamageTable.clear(vehicle.getUUID(), boneName);
                } else if (type == BoneModuleType.BARREL) {
                    RVP_BarrelDamageTable.clear(vehicle.getUUID(), boneName);
                }
            }
        }
        if (restored) {
            // 一次失效广播：面板/冒烟/俯视图/禁射 gate 等消费端自动生效
            RVP_VehicleHitboxFactorManager.syncBoneModuleState(vehicle);
            player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.part_repaired"), true);
        }
    }
}
