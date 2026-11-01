package org.ywzj.rvp.client.handler;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_RenderLivingHandler {

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (shouldHide(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (shouldHide(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean shouldHide(LivingEntity entity) {
        if (!(entity.getVehicle() instanceof AbstractVehicle vehicle)) {
            return false;
        }
        // 玻璃已延后绘制，乘员统一走正常实体渲染；这里只保留载具显式隐藏乘员的配置。
        return RVP_VehicleHitboxFactorManager.INSTANCE.isHidePassenger(vehicle.getVehicleId());
    }
}
