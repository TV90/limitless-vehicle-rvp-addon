package org.ywzj.rvp.event;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 载具放置时自动添加创造模式弹药。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleSpawnHandler {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!RVP_CommonConfig.isSpawnVehicleWithCreativeAmmo()) {
            return;
        }

        // 获取载具物品栏，插入一组创造模式弹药
        vehicle.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .filter(cap -> cap.getSlots() > 0)
                .ifPresent(cap -> {
                    ItemStack creativeAmmo = new ItemStack(AllItems.AMMO_CREATIVE.get(), 64);
                    for (int i = 0; i < cap.getSlots(); i++) {
                        if (cap.getStackInSlot(i).isEmpty()) {
                            cap.insertItem(i, creativeAmmo.copy(), false);
                            return;
                        }
                    }
                });
    }
}
