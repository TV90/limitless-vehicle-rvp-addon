package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] 可变后掠翼参数管理器（审核方案模式 b：纯 {@link AddReloadListenerEvent}，零 Mixin）。
 *
 * <p>与 {@code RVP_VehicleHitboxFactorManager} 同构：数据包重载时扫描全部
 * {@code vehicles/*.json}，解析顶层可选 {@code wing_sweep} 块。仅服务端物理消费，
 * 无需 {@code S2CVehicleRvpConfig} 客户端同步（客户端不参与服务端气动计算）。</p>
 *
 * <p>注意：未声明 {@code wing_sweep} 的载具返回 {@link RVP_WingSweepConfig#DEFAULT}
 * 而非"不驱动"——可变后掠翼的能力信号是"载具存在 wing_sweep_manual/form 两个隐藏
 * 部件"（由 {@code RVP_WingSweepState} 运行时探测），防止漏配参数导致机翼卡死
 * （审核报告 P1）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_WingSweepConfigManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_WingSweepConfigManager INSTANCE = new RVP_WingSweepConfigManager();

    private Map<ResourceLocation, RVP_WingSweepConfig> configs = Map.of();

    private RVP_WingSweepConfigManager() {}

    @Override
    protected @NotNull Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // 目的：与本体数据仓库同批扫描载具 JSON 目录（含 VehiclePackLoader 注册的载具包）
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        // 客户端单机时 AddReloadListenerEvent 可能扫不到 rvp 包（VehiclePackLoader 以资源包注册），
        // 空 map 不覆盖，避免清空已填充配置（与 RVP_VehicleHitboxFactorManager 同口径）
        if (map == null || map.isEmpty()) {
            return;
        }
        Map<ResourceLocation, RVP_WingSweepConfig> loaded = new HashMap<>();
        map.forEach((vehicleId, json) -> {
            if (!json.isJsonObject() || !json.getAsJsonObject().has("wing_sweep")) {
                return;
            }
            JsonElement block = json.getAsJsonObject().get("wing_sweep");
            if (block.isJsonObject()) {
                JsonObject obj = GsonHelper.convertToJsonObject(block, "wing_sweep");
                loaded.put(vehicleId, RVP_WingSweepConfig.parse(obj));
            }
        });
        configs = Map.copyOf(loaded);
    }

    /**
     * 用指定资源仓库手动重载（{@code /rvp reload} 轻量热重载调用）：prepare/apply 为 protected，
     * 外部触发不了，此入口让 rvp 载具包的可变后掠翼参数在不重载渲染模型/贴图的前提下刷新。
     * apply 内空 map 不覆盖守卫保持不变。
     */
    public void reloadFrom(ResourceManager resourceManager) {
        apply(prepare(resourceManager, null), null, null);
    }

    /**
     * 取载具的可变后掠翼参数；未声明 {@code wing_sweep} 的载具返回
     * {@link RVP_WingSweepConfig#DEFAULT}（乘子全 1.0 = 不干预物理）。
     */
    public static RVP_WingSweepConfig get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return RVP_WingSweepConfig.DEFAULT;
        }
        return INSTANCE.configs.getOrDefault(vehicleId, RVP_WingSweepConfig.DEFAULT);
    }
}
