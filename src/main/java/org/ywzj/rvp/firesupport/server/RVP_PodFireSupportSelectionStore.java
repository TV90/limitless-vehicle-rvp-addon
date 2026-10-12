package org.ywzj.rvp.firesupport.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.S2CPodFireSupportSelection;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_PodFireSupportEntryData;

import java.util.HashMap;
import java.util.Map;

/**
 * 吊舱火力支援"已保存选择"的服务端持久化存储（2026-10-13 整合批）。
 *
 * <p>键 = 吊舱武器 path，值 = 内联条目 id；存于 {@code ServerPlayer.getPersistentData()}
 * 的 {@code rvp_pod_fire_support} 子标签（项目既有持久化先例：飞机任务标签/载具冷却），
 * 跨会话存活。玩家登录与数据包 reload 后把已存选择回发客户端（UI 显示当前选择）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_PodFireSupportSelectionStore {

    /** 玩家 PersistentData 中的子标签名。 */
    private static final String NBT_KEY = "rvp_pod_fire_support";

    private RVP_PodFireSupportSelectionStore() {}

    /** 保存（或清除，entryId 传空）指定武器的选择并立即回发确认。 */
    public static void save(ServerPlayer player, String weaponPath, String entryId) {
        CompoundTag root = player.getPersistentData().getCompound(NBT_KEY);
        if (entryId == null || entryId.isBlank()) {
            root.remove(weaponPath);
        } else {
            root.putString(weaponPath, entryId);
        }
        // 调用本体玩家持久化数据：put 回写（getCompound 返回的是副本，必须显式 put 才生效）。
        player.getPersistentData().put(NBT_KEY, root);
        sendSelection(player, weaponPath, entryId == null ? "" : entryId);
    }

    /** @return 已保存的条目 id；未保存返回 null。 */
    public static String getSelection(ServerPlayer player, String weaponPath) {
        CompoundTag root = player.getPersistentData().getCompound(NBT_KEY);
        String value = root.getString(weaponPath);
        return value == null || value.isBlank() ? null : value;
    }

    /** 把已存选择回发客户端（保存 ACK 与登录同步共用）。 */
    private static void sendSelection(ServerPlayer player, String weaponPath, String entryId) {
        RVP_Network.CHANNEL.sendTo(
                new S2CPodFireSupportSelection(weaponPath, entryId),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    /** 玩家登录：把该玩家全部已存吊舱选择逐条下发（UI 显示当前选择）。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        CompoundTag root = player.getPersistentData().getCompound(NBT_KEY);
        // 调用本体 NBT 键集：逐条回发已保存的武器→条目映射。
        for (String weaponPath : root.getAllKeys()) {
            String entryId = root.getString(weaponPath);
            if (entryId != null && !entryId.isBlank()) {
                sendSelection(player, weaponPath, entryId);
            }
        }
    }

    /** 数据包 reload：吊舱内联条目可能增删，重建运行时 profile 注册表。 */
    @SubscribeEvent
    public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        // 调用本项目吊舱火力注册表：从最新武器数据重建（reload 与登录均触发本事件，重建幂等）。
        RVP_PodFireSupportRegistry.rebuild();
        if (event.getPlayer() != null) {
            // 单玩家同步（/reload 后）：回发该玩家全部已存选择。
            syncAll(event.getPlayer());
        } else {
            // 全员同步（首次 reload 完成）：向所有在线玩家回发。
            event.getPlayerList().getPlayers().forEach(RVP_PodFireSupportSelectionStore::syncAll);
        }
    }

    /** 把玩家全部已存选择逐条下发。 */
    private static void syncAll(ServerPlayer player) {
        CompoundTag root = player.getPersistentData().getCompound(NBT_KEY);
        for (String weaponPath : root.getAllKeys()) {
            String entryId = root.getString(weaponPath);
            if (entryId != null && !entryId.isBlank()) {
                sendSelection(player, weaponPath, entryId);
            }
        }
    }

    /** @return 保存前校验：武器存在该内联条目时返回 true（防客户端伪造任意 id）。 */
    public static boolean isSelectionValid(String weaponPath, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return true; // 空值 = 清除选择，恒合法
        }
        ResourceLocation weaponId = ResourceLocation.tryParse("rvp:" + weaponPath);
        if (weaponId == null) {
            return false;
        }
        var index = org.ywzj.vehicle.custom.CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId);
        if (index.isEmpty() || !(index.get().data() instanceof RVP_WeaponData data)
                || data.getTargetingPodData() == null) {
            return false;
        }
        RVP_PodFireSupportEntryData entry = data.getTargetingPodData().findFireSupportEntry(entryId);
        return entry != null && entry.isValid();
    }
}
