package org.ywzj.rvp.helidock;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.api.event.VehicleAttackEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.UUID;

/**
 * 着舰/接管期两车撞击伤害拦截（2026-10-07）。
 * <p>直升机与绑定舰船在 APPROACH/DOCKED/TAKEOFF 期间 OBB 重叠，本体
 * {@code AbstractVehicle.impact} 的撞击伤害无同队豁免且按吨位计伤——驱逐舰蹭直升机必毁。
 * 此处在 {@code VehicleAttackEvent}（{@code DamageSystem.hurt} 前触发）拦截
 * "着舰/接管直升机 ↔ 其绑定舰船"互相造成的<b>撞击</b>伤害；武器伤害的伤害源不是载具本体，
 * 不经此判定照常生效（着舰态保留可被打，用户定版）。双向都拦。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_HeliDockEventHandler {

    private RVP_HeliDockEventHandler() {
    }

    @SubscribeEvent
    public static void onVehicleAttack(VehicleAttackEvent event) {
        AbstractVehicle target = event.getVehicle();
        if (target == null) {
            return;
        }
        var source = event.getSource();
        Entity direct = source == null ? null : source.getDirectEntity();
        Entity causing = source == null ? null : source.getEntity();
        UUID targetId = target.getUUID();
        for (Entity attacker : new Entity[]{direct, causing}) {
            if (!(attacker instanceof AbstractVehicle attackerVehicle)) {
                continue;
            }
            UUID attackerId = attackerVehicle.getUUID();
            // 攻击者是着舰/接管直升机且目标是其绑定舰船 → 拦
            var attackerState = RVP_HeliDockManager.stateOf(attackerId);
            if (attackerState != null && attackerState.shipUuid().equals(targetId)) {
                event.setCanceled(true);
                return;
            }
            // 目标是着舰/接管直升机且攻击者是其绑定舰船 → 拦
            var targetState = RVP_HeliDockManager.stateOf(targetId);
            if (targetState != null && targetState.shipUuid().equals(attackerId)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
