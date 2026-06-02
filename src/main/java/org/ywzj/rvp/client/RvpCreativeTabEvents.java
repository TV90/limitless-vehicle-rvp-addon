package org.ywzj.rvp.client;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.all.RvpItems;
import org.ywzj.vehicle.YwzjVehicle;

@Mod.EventBusSubscriber(modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RvpCreativeTabEvents {

    @SubscribeEvent
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SEARCH
                || event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES
                || event.getTabKey() == CreativeModeTabs.SPAWN_EGGS
                || event.getTabKey().location().equals(YwzjVehicle.resourceLocation("tab_rvp"))
                || event.getTabKey().location().equals(YwzjVehicle.resourceLocation("tab_misc"))) {
            event.accept(RvpItems.GUNNER_SPAWNER.get());
            event.accept(RvpItems.FRIENDLY_GUNNER.get());
            event.accept(RvpItems.ENEMY_GUNNER.get());
            event.accept(RvpItems.TEAM_GUNNER.get());
        }
    }
}
