package org.ywzj.rvp.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.gunner.RVP_ClientGunnerProfileState;
import org.ywzj.rvp.config.RVP_CreativeHiddenVehicles;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.all.AllItems;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.ywzj.vehicle.api.entity.ICustomVehicle.TAG_VEHICLE_ID;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_CreativeTabEvents {

    @SubscribeEvent
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        removeHiddenVehicles(event);
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

    /**
     * 把 {@code rvp_hide_in_creative} 标记的载具生成物从当前创造栏移除（搜索页同样覆盖）。
     *
     * <p>事件在标签页构建完成后、内容拷入展示列表之前触发，改动 entries 即可生效；
     * 载具实体与母车部署链路不受影响。先收集再删，避免迭代器并发修改。</p>
     */
    private static void removeHiddenVehicles(BuildCreativeModeTabContentsEvent event) {
        if (RVP_CreativeHiddenVehicles.isEmpty()) {
            return;
        }
        List<ItemStack> hidden = null;
        for (Map.Entry<ItemStack, CreativeModeTab.TabVisibility> entry : event.getEntries()) {
            ItemStack stack = entry.getKey();
            if (stack.getItem() != AllItems.VEHICLE_SPAWN_ITEM.get()) {
                continue;
            }
            ResourceLocation vehicleId = vehicleIdOf(stack);
            if (vehicleId == null || !RVP_CreativeHiddenVehicles.isHidden(vehicleId)) {
                continue;
            }
            if (hidden == null) {
                hidden = new ArrayList<>(2);
            }
            hidden.add(stack);
        }
        if (hidden == null) {
            return;
        }
        for (ItemStack stack : hidden) {
            event.getEntries().remove(stack);
        }
    }

    private static ResourceLocation vehicleIdOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VEHICLE_ID)) {
            return null;
        }
        return ResourceLocation.tryParse(tag.getString(TAG_VEHICLE_ID));
    }
}
