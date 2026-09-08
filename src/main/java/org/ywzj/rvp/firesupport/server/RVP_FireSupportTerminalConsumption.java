package org.ywzj.rvp.firesupport.server;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ywzj.rvp.RVP_MOD;

/**
 * 炮火终端实例消耗服务：按 terminal_instance/profile_id 精确删除，不把同 profile 的其他终端当作消耗对象。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportTerminalConsumption {
    /** 终端消耗服务日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("RVP-FireSupport-TerminalConsumption");
    /** 按服务器实例隔离当前会话内的已消耗终端墓碑。 */
    private static final Map<MinecraftServer, Set<TerminalKey>> CONSUMED = new IdentityHashMap<>();

    private RVP_FireSupportTerminalConsumption() {}

    /**
     * 标记并立即删除指定任务绑定的终端；同一 key 在一个服务器会话内只处理一次。
     * 调用目的：任务进入 COMPLETED/CEASED 后阻止终端被丢出或重新捡回继续使用。
     */
    public static void consume(MinecraftServer server, UUID terminalInstanceId,
                               net.minecraft.resources.ResourceLocation profileId) {
        if (server == null || terminalInstanceId == null || profileId == null) return;
        Set<TerminalKey> keys = CONSUMED.computeIfAbsent(server,
                ignored -> Collections.synchronizedSet(new LinkedHashSet<>()));
        TerminalKey key = new TerminalKey(terminalInstanceId, profileId);
        if (!keys.add(key)) return;

        int removed = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            removed += removeFromInventory(player, key);
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity itemEntity && matches(itemEntity.getItem(), key)) {
                    itemEntity.discard();
                    removed++;
                }
            }
        }
        if (removed == 0) {
            LOGGER.warn("炮火支援终端实例已标记消耗但当前未找到物品: instance={}, profile={}",
                    terminalInstanceId, profileId);
        } else {
            LOGGER.info("炮火支援终端实例已标记消耗: instance={}, profile={}, immediateRemoved={}",
                    terminalInstanceId, profileId, removed);
        }
    }

    /** 掉落实体加入世界后立刻删除已消耗实例，覆盖未加载区块重新加载的情况。 */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        MinecraftServer server = level.getServer();
        if (isConsumed(server, item.getItem())) item.discard();
    }

    /** 拾取事件中先删除已消耗终端并取消拾取，避免客户端先拿到旧 ItemStack。 */
    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemEntity item = event.getItem();
        if (isConsumed(player.server, item.getItem())) {
            item.discard();
            event.setCanceled(true);
        }
    }

    /** 玩家重新登录时扫描完整玩家库存，覆盖终端在任务结束后暂存于背包的情况。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (TerminalKey key : snapshot(player.server)) removeFromInventory(player, key);
        }
    }

    /** 服务器停止时清空会话墓碑；记录不跨重启持久化。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CONSUMED.remove(event.getServer());
    }

    /** @return 指定物品是否命中当前会话已消耗的实例和 profile。 */
    public static boolean isConsumed(MinecraftServer server, ItemStack stack) {
        if (server == null || stack == null || stack.isEmpty()) return false;
        UUID instanceId = RVP_FireSupportTerminalIdentity.read(stack);
        net.minecraft.resources.ResourceLocation profileId = RVP_FireSupportTerminalIdentity.readProfileId(stack);
        return instanceId != null && profileId != null
                && CONSUMED.getOrDefault(server, Set.of()).contains(new TerminalKey(instanceId, profileId));
    }

    /** 删除玩家库存中的所有精确匹配副本；扫描 items、armor、offhand 对应的全部容器槽位。 */
    private static int removeFromInventory(ServerPlayer player, TerminalKey key) {
        int removed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack, key)) continue;
            removed += stack.getCount();
            inventory.removeItemNoUpdate(slot);
        }
        if (removed > 0) inventory.setChanged();
        return removed;
    }

    /** 严格校验通用终端、实例 UUID 和 profile ID；翻译缓存不参与匹配。 */
    private static boolean matches(ItemStack stack, TerminalKey key) {
        return RVP_FireSupportTerminalIdentity.isTerminal(stack)
                && key.instanceId().equals(RVP_FireSupportTerminalIdentity.read(stack))
                && key.profileId().equals(RVP_FireSupportTerminalIdentity.readProfileId(stack));
    }

    private static Set<TerminalKey> snapshot(MinecraftServer server) {
        Set<TerminalKey> keys = CONSUMED.get(server);
        return keys == null ? Set.of() : Set.copyOf(keys);
    }

    /** 会话内的精确终端墓碑键。 */
    private record TerminalKey(
            /** 被任务冻结的终端实例 UUID。 */ UUID instanceId,
            /** 被任务冻结的 profile 资源 ID。 */ net.minecraft.resources.ResourceLocation profileId) {}
}
