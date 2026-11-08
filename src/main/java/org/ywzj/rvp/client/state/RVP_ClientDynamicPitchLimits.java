package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.sight.RVP_DynamicPitchLimits;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 客户端动态俯角上限入口：本地观瞄站的摄像机俯角补偿，以及所有已加载载具的
 * 车尾分段无俯角（两者都写 {@code x_rot_max}，负角仍是仰角、不受影响）。
 * 放在 ClientTickEvent START，先于本 tick 的武器站旋转与鼠标门限。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientDynamicPitchLimits {

    private RVP_ClientDynamicPitchLimits() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        RVP_DynamicPitchLimits.tickClient(level == null ? null : level.entitiesForRendering(),
                scopedRootWeaponUnit());
    }

    private static WeaponUnit scopedRootWeaponUnit() {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (local == null || local.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            return null;
        }
        AbstractVehicle vehicle = local.vehicle;
        Player player = local.getPlayer();
        if (vehicle == null || player == null) {
            return null;
        }
        PartUnit<?> operator = vehicle.getOwnOperatorUnit(player);
        return operator instanceof WeaponUnit unit ? unit.getRootParentWeaponUnit() : null;
    }
}
