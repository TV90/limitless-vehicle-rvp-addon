package org.ywzj.rvp.network;

import com.google.gson.JsonElement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 服务端 → 客户端：同步含 RVP 扩展字段的载具 JSON。
 *
 * <p>专用服务器下客户端不触发 {@code AddReloadListenerEvent}，RVP 的挂架配置
 * （{@link RVP_CustomMountConfigCache}）与改装工具弹种配置
 * （{@link RVP_VehicleExtendedConfigManager}）无法像单机一样由资源重载填充，
 * 故由服务端在 {@code OnDatapackSyncEvent} 时把所需载具 JSON 打包下发，客户端复用同一套解析逻辑。
 * 不使用 mixin 注入 {@code VehicleDataManager}。</p>
 */
public class S2CVehicleRvpConfig {

    public Map<ResourceLocation, String> vehicleJsonMap = Map.of();

    public S2CVehicleRvpConfig() {}

    public S2CVehicleRvpConfig(Map<ResourceLocation, String> vehicleJsonMap) {
        this.vehicleJsonMap = vehicleJsonMap;
    }

    public static void encode(S2CVehicleRvpConfig msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleJsonMap.size());
        for (Map.Entry<ResourceLocation, String> entry : msg.vehicleJsonMap.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeByteArray(gzip(entry.getValue()));
        }
    }

    public static S2CVehicleRvpConfig decode(FriendlyByteBuf buf) {
        S2CVehicleRvpConfig msg = new S2CVehicleRvpConfig();
        int size = buf.readVarInt();
        Map<ResourceLocation, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            ResourceLocation id = buf.readResourceLocation();
            map.put(id, gunzip(buf.readByteArray()));
        }
        msg.vehicleJsonMap = map;
        return msg;
    }

    public static void handle(S2CVehicleRvpConfig msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> apply(msg)));
    }

    private static void apply(S2CVehicleRvpConfig msg) {
        if (msg.vehicleJsonMap.isEmpty()) {
            return;
        }
        Map<ResourceLocation, JsonElement> jsonMap = new HashMap<>();
        List<ResourceLocation> order = new ArrayList<>();
        for (Map.Entry<ResourceLocation, String> entry : msg.vehicleJsonMap.entrySet()) {
            try {
                JsonElement element = GsonUtil.GSON.fromJson(entry.getValue(), JsonElement.class);
                if (element != null) {
                    jsonMap.put(entry.getKey(), element);
                    order.add(entry.getKey());
                }
            } catch (Exception ignored) {
            }
        }
        if (jsonMap.isEmpty()) {
            return;
        }
        RVP_VehicleExtendedConfigManager.INSTANCE.applyFromJsonMap(jsonMap);
        // hide_passenger 等命中箱/乘员显示配置同样只在服务端有完整数据，客户端需同步填充
        RVP_VehicleHitboxFactorManager.INSTANCE.applyFromJsonMap(jsonMap);
        Map<ResourceLocation, List<RVP_CustomMountConfig>> mounts = new HashMap<>();
        for (ResourceLocation id : order) {
            JsonElement element = jsonMap.get(id);
            if (!element.isJsonObject()) {
                continue;
            }
            try {
                List<RVP_CustomMountConfig> list = RVP_CustomMountConfig.parseList(element.getAsJsonObject());
                if (list != null && !list.isEmpty()) {
                    mounts.put(id, list);
                }
            } catch (Exception ignored) {
            }
        }
        if (!mounts.isEmpty()) {
            RVP_CustomMountConfigCache.replace(mounts);
        }
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String gunzip(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            try (GZIPInputStream gz = new GZIPInputStream(bais)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = gz.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                return baos.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }
}
