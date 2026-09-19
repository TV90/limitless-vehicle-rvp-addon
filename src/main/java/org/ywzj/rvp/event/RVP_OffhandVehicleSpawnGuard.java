package org.ywzj.rvp.event;

import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.item.VehicleSpawnItem;

/**
 * 载具物品副手放置静默拦截（2026-09-20 用户需求）：载具/武器站切换常按 F 键
 * （原版主副手切换），载具物品容易被切到副手，此后上车等右键操作会把载具
 * 顺手放出来。本守卫在载具物品处于<b>副手</b>时静默取消右键放置——不放置、
 * 不消耗、无任何提示。
 *
 * <p><b>判定</b>：本体与 RVP 的全部载具物品共用 {@link VehicleSpawnItem}
 * （以 NBT {@code vehicleId} 区分车种），类级 instanceof 一类即全覆盖，
 * 且天然覆盖未来沿用该机制的新增载具；{@code GunnerSpawnerItem} 等
 * 非载具物品不受影响。</p>
 *
 * <p><b>链路</b>：{@code VehicleSpawnItem} 只覆写 {@code use}（未覆写 useOn），
 * 放置仅经 {@code RightClickItem} 一条链，{@code HIGHEST + cancel} 即完整拦截；
 * 双端订阅（客户端取消阻止交互包上行、服务端兜底防改包客户端）；主手先行
 * return，零影响。模式与 {@link RVP_ModdingInteractGuard} 同构。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_OffhandVehicleSpawnGuard {

    private RVP_OffhandVehicleSpawnGuard() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.OFF_HAND) {
            return;
        }
        if (!(event.getItemStack().getItem() instanceof VehicleSpawnItem)) {
            return;
        }
        event.setCanceled(true);
    }
}
