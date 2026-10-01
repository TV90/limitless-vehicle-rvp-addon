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
    }

    /**
     * [RVP] 服务端配置刷新后向全服客户端重发同步包（2026-10-02）：{@code /rvp reload} 在服务端段
     * 手动重载 RVP 数据管理器后调用——多人环境下客户端的载具扩展配置（含
     * {@code audio_info.camera_relative_fire_sound_distance} 等客户端消费字段）只在
     * {@code OnDatapackSyncEvent}（玩家登录 / vanilla {@code /reload} 触发 {@code PlayerList.reload}）
     * 时更新，轻量热重载必须显式补发，否则客户端侧消费（如开火声相机相对路径）拿到的仍是登录快照。
     */
    public static void syncToAll() {
        Map<ResourceLocation, JsonElement> raw = RVP_VehicleExtendedConfigManager.INSTANCE.getRawVehicleJson();
        if (raw.isEmpty()) {
            return;
        }
        Map<ResourceLocation, String> payload = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : raw.entrySet()) {
            payload.put(entry.getKey(), entry.getValue().toString());
        }
        if (payload.isEmpty()) {
            return;
        }
        RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), new S2CVehicleRvpConfig(payload));
    }
}
