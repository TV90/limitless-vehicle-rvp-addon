package org.ywzj.rvp.network;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端：在 {@code OnDatapackSyncEvent}（玩家加入 / 数据包重载）时，把含 RVP 扩展字段的载具 JSON
 * 下发给客户端，供客户端填充挂架配置与改装工具弹种配置。
 *
 * <p>与本体 {@code CommonAssetsManager.onDatapackSync} 同一事件、同一时机，保证 RVP 配置
 * 与本体的 vehicles 数据同步到达客户端。客户端侧 {@code OnDatapackSyncEvent} 不会触发，
 * 本监听只在服务端逻辑执行。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleConfigSyncHandler {

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Map<ResourceLocation, JsonElement> raw = RVP_VehicleExtendedConfigManager.INSTANCE.getRawVehicleJson();
        if (raw.isEmpty()) {
            return;
        }
        // 全量下发所有载具原始 JSON，而非只下发"扩展配置非 EMPTY"的载具：
        // hide_passenger / hitbox_damage_factor / hitbox_era / core_distance_scale_multiplier 等字段
        // 与 physics_only_bone / modding_only_multi 相互独立，按扩展配置过滤会漏掉只配置了
        // hide_passenger / hitbox 的载具（如 m142，无 physics_only_bone，扩展配置为 EMPTY）。
        Map<ResourceLocation, String> payload = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : raw.entrySet()) {
            payload.put(entry.getKey(), entry.getValue().toString());
        }
        if (payload.isEmpty()) {
            return;
        }
        S2CVehicleRvpConfig packet = new S2CVehicleRvpConfig(payload);
        if (event.getPlayer() != null) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
        } else {
            RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
        }
        // [RVP] 出弹队列随数据同步一并发往客户端（载具 JSON 变化 → 队列重算 → 客户端刷新）
        S2CShootBoltQueueSync queueSync = new S2CShootBoltQueueSync();
        if (event.getPlayer() != null) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), queueSync);
        } else {
            RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), queueSync);
        }
    }
}
