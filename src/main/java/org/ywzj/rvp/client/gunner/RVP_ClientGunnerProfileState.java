package org.ywzj.rvp.client.gunner;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.ywzj.rvp.item.GunnerSpawnerItem;
import org.ywzj.rvp.network.gunner.RVP_GunnerProfileClientEndpoint;
import org.ywzj.rvp.network.gunner.S2CGunnerProfileSnapshot;

import java.util.List;

/** 客户端 Gunner Profile ID 快照及对应创造栏物品变体。 */
public final class RVP_ClientGunnerProfileState implements RVP_GunnerProfileClientEndpoint.Listener {
    /** 客户端唯一的 Profile 快照状态容器。 */
    public static final RVP_ClientGunnerProfileState INSTANCE = new RVP_ClientGunnerProfileState();
    /** 最近一次服务端 Profile 发布代次。 */
    private volatile long generation;
    /** 服务端权威 Profile ID 不可变列表。 */
    private volatile List<ResourceLocation> profileIds = List.of();
    /** 与 Profile ID 一一对应的通用生成器物品变体。 */
    private volatile List<ItemStack> itemVariants = List.of();
    /** 是否需要在玩家进入世界后重建创造栏。 */
    private volatile boolean creativeRebuildPending;
    private RVP_ClientGunnerProfileState() {}
    @Override
    public void onProfiles(S2CGunnerProfileSnapshot message) {
        generation = message.generation();
        profileIds = message.profileIds();
        // 调用本项目通用生成器物品工厂：每个 JSON Profile 生成一个带 ProfileId 的 ItemStack 变体。
        itemVariants = profileIds.stream().map(GunnerSpawnerItem::createForProfile).toList();
        creativeRebuildPending = true;
    }
    public long generation() { return generation; }
    public List<ResourceLocation> profileIds() { return profileIds; }
    public List<ItemStack> itemVariants() { return itemVariants; }
    public void clientTick() {
        if (!creativeRebuildPending) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), true,
                minecraft.level.registryAccess());
        creativeRebuildPending = false;
    }
}
