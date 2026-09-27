package org.ywzj.rvp.client;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.gunner.RVP_ClientGunnerProfileState;
import org.ywzj.vehicle.YwzjVehicle;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_CreativeTabEvents {

    @SubscribeEvent
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SEARCH
                || event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES
                || event.getTabKey() == CreativeModeTabs.SPAWN_EGGS
                || event.getTabKey().location().equals(YwzjVehicle.resourceLocation("tab_rvp"))
                || event.getTabKey().location().equals(YwzjVehicle.resourceLocation("tab_misc"))) {
            event.accept(RVP_Items.GUNNER_SPAWNER.get());
            event.accept(RVP_Items.FRIENDLY_GUNNER.get());
            event.accept(RVP_Items.ENEMY_GUNNER.get());
            event.accept(RVP_Items.TEAM_GUNNER.get());
            // 调用客户端 Gunner Profile 快照：每个 schema v2 JSON 展示一个通用生成器 NBT 变体。
            RVP_ClientGunnerProfileState.INSTANCE.itemVariants().forEach(event::accept);
            // 调用本项目客户端 profile 状态：为当前服务端快照中的每个 profile 展示一个 ItemStack 变体。
            RVP_ClientFireSupportState.INSTANCE.itemVariants().forEach(event::accept);
        }
    }
}
