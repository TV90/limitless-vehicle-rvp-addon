package org.ywzj.rvp.event;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.RVP_GroupedWeaponSlotAssembler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * B4：组合挂架恢复（替代已移除的 {@code WeaponUnitGroupedSlotMixin}）。
 *
 * <p>原 mixin 在 {@code WeaponUnit.combineAndInit} HEAD 拦截并取消本体默认武器组装，
 * 改为 RVP 分组组装（{@code merge_into_previous_slot} 槽位合并为
 * {@code VehicleMultiWeapons}）。移除后本体照常组装（无分组），组合挂架功能静默失效。</p>
 *
 * <p>本 handler 在载具加入世界时（{@link EntityJoinLevelEvent}，此时本体
 * {@code initData() -> createPartUnits() -> combineAndInit()} 已完成，部件与默认武器列表
 * 均已就绪）对配置了分组挂架的武器站重新组装。客户端 / 服务端都需要执行：
 * 原 mixin 在公共数组，双端生效；两侧各自的载具实体都会触发该事件。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GroupedSlotSpawnHandler {

    private RVP_GroupedSlotSpawnHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        assembleGroupedSlots(vehicle);
    }

    private static void assembleGroupedSlots(AbstractVehicle vehicle) {
        Map<String, PartUnit<?>> partUnitsView = new HashMap<>();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            partUnitsView.put(partUnit.getId(), partUnit);
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit
                    && RVP_GroupedWeaponSlotAssembler.shouldHandle(weaponUnit)) {
                RVP_GroupedWeaponSlotAssembler.assemble(weaponUnit, partUnitsView, vehicle);
            }
        }
    }
}
