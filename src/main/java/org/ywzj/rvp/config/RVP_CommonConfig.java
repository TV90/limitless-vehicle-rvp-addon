package org.ywzj.rvp.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.NormalizationResult;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleVisibilityPolicy.VehicleCategory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RVP 双端 common 配置（{@code ywzj_rvp-common.toml}）。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RVP_CommonConfig {
    /** 配置规范化告警日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已注册的 common 配置实例。 */
    private static RVP_CommonConfig INSTANCE;
    /** 已注册的 common 配置规范，用于过滤其他配置加载事件。 */
    private static ForgeConfigSpec SPEC;

    /** 远距载具可见性授权模式。 */
    public enum VisibilityMode {
        /** 关闭载具超视距同步。 */
        OFF,
        /** 观察者必须乘载具，远距离目标还必须被雷达或授权数据链发现。 */
        RADAR_DETECTED,
        /** 所有乘坐可分类完整载具的玩家按类型白名单接收。 */
        VEHICLE_OCCUPANTS,
        /** 同维度所有玩家均可接收，乘载具玩家仍应用类型白名单。 */
        ALL_PLAYERS
    }

    /** 远距载具区块租约模式。 */
    public enum ChunkLoadingMode {
        /** 不为远距载具新增区块租约。 */
        OFF,
        /** 仅为 Gunner AI 驾驶载具和 UAV 提交租约。 */
        AI_UAV_ONLY,
        /** 为至少向一名玩家授权的载具提交租约。 */
        VISIBLE_TARGETS
    }

    /** 放置载具时是否自动补充创造弹药。 */
    private final ForgeConfigSpec.BooleanValue spawnVehicleWithCreativeAmmo;
    /** 是否启用服务端权威的载具超视距同步。 */
    private final ForgeConfigSpec.BooleanValue remoteVehicleRenderingEnabled;
    /** 服务端采用的载具超视距授权模式。 */
    private final ForgeConfigSpec.EnumValue<VisibilityMode> remoteVehicleVisibilityMode;
    /** 载具超视距同步最大水平距离，单位格。 */
    private final ForgeConfigSpec.DoubleValue remoteVehicleMaxDistance;
    /** 载具视觉完整集合同步周期，单位 tick。 */
    private final ForgeConfigSpec.IntValue remoteVehicleSyncIntervalTicks;
    /** 单个玩家每份快照允许的最大载具目标数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleMaxTargetsPerPlayer;
    /** 服务端采用的远距载具区块租约模式。 */
    private final ForgeConfigSpec.EnumValue<ChunkLoadingMode> remoteVehicleChunkLoadingMode;
    /** 单个维度允许保持区块加载的最大远距载具数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleMaxChunkLoadedVehiclesPerDimension;
    /** 按速度规划远距载具区块前探路径的 tick 数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleChunkLookAheadTicks;
    /** 直升机观察者允许看见的目标载具类型 token。 */
    private final ForgeConfigSpec.ConfigValue<List<? extends String>> helicopterVisibleTargets;
    /** 固定翼观察者允许看见的目标载具类型 token。 */
    private final ForgeConfigSpec.ConfigValue<List<? extends String>> aircraftVisibleTargets;
    /** 地面车辆观察者允许看见的目标载具类型 token。 */
    private final ForgeConfigSpec.ConfigValue<List<? extends String>> groundVehicleVisibleTargets;
    /** 配置加载后生成的不可变观察者类型白名单。 */
    private volatile Map<VehicleCategory, Set<VehicleCategory>> normalizedVisibilityMatrix = defaultVisibilityMatrix();

    public RVP_CommonConfig(ForgeConfigSpec.Builder builder) {
        builder.push("spawning");

        spawnVehicleWithCreativeAmmo = builder
                .comment(
                        "When placing a vehicle with the spawn item, automatically add a stack of creative ammo",
                        "to the vehicle's inventory so it can be used immediately, and instantly refill ALL weapons",
                        "to full ammo once at the moment the vehicle spawns (skipping long reload times).",
                        "This refill is a one-time operation at spawn only and will not repeat as ammo is consumed.",
                        "Default: false"
                )
                .define("spawnVehicleWithCreativeAmmo", false);

        builder.pop();
        builder.push("remoteVehicleRendering");

        remoteVehicleRenderingEnabled = builder
                .comment("是否启用服务端权威的载具超视距视觉同步。默认：true")
                .define("enabled", true);
        remoteVehicleVisibilityMode = builder
                .comment("载具超视距授权模式：OFF、RADAR_DETECTED、VEHICLE_OCCUPANTS、ALL_PLAYERS。",
                        "默认：VEHICLE_OCCUPANTS")
                .defineEnum("visibilityMode", VisibilityMode.VEHICLE_OCCUPANTS);
        remoteVehicleMaxDistance = builder
                .comment("载具超视距同步最大水平距离，单位格。范围：512..65536，默认：4096")
                .defineInRange("maxDistance", 4096.0D, 512.0D, 65_536.0D);
        remoteVehicleSyncIntervalTicks = builder
                .comment("载具视觉完整集合同步周期，单位 tick。范围：1..200，默认：5")
                .defineInRange("syncIntervalTicks", 5, 1, 200);
        remoteVehicleMaxTargetsPerPlayer = builder
                .comment("单个玩家每份快照允许的最大载具目标数。范围：0..1024，默认：32")
                .defineInRange("maxTargetsPerPlayer", 32, 0, 1024);
        remoteVehicleChunkLoadingMode = builder
                .comment("远距载具区块租约模式：OFF、AI_UAV_ONLY、VISIBLE_TARGETS。",
                        "默认：VISIBLE_TARGETS")
                .defineEnum("chunkLoadingMode", ChunkLoadingMode.VISIBLE_TARGETS);
        remoteVehicleMaxChunkLoadedVehiclesPerDimension = builder
                .comment("单个维度允许保持区块加载的最大远距载具数。范围：0..1024，默认：32")
                .defineInRange("maxChunkLoadedVehiclesPerDimension", 32, 0, 1024);
        remoteVehicleChunkLookAheadTicks = builder
                .comment("按载具速度规划区块前探路径的 tick 数。范围：0..200，默认：5")
                .defineInRange("chunkLookAheadTicks", 5, 0, 200);

        builder.push("visibilityByObserverVehicleType");
        helicopterVisibleTargets = defineVehicleTypeList(builder, "helicopter",
                List.of("helicopter", "aircraft", "ground_vehicles"));
        aircraftVisibleTargets = defineVehicleTypeList(builder, "aircraft",
                List.of("helicopter", "aircraft", "ground_vehicles"));
        groundVehicleVisibleTargets = defineVehicleTypeList(builder, "ground_vehicles",
                List.of("helicopter", "aircraft"));
        builder.pop();
        builder.pop();
    }

    /** 定义允许空数组且由加载阶段执行严格 token 规范化的载具类型列表。 */
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> defineVehicleTypeList(
            ForgeConfigSpec.Builder builder, String key, List<String> defaults) {
        return builder
                .comment("允许该观察者载具类型看见的目标类型。仅接受 helicopter、aircraft、ground_vehicles。")
                .defineListAllowEmpty(List.of(key), () -> defaults, value -> value instanceof String);
    }

    /** 返回是否在放置载具时补充创造弹药。 */
    public static boolean isSpawnVehicleWithCreativeAmmo() {
        return INSTANCE != null && INSTANCE.spawnVehicleWithCreativeAmmo.get();
    }

    /** 返回服务端是否启用载具超视距同步。 */
    public static boolean isRemoteVehicleRenderingEnabled() {
        return INSTANCE != null && INSTANCE.remoteVehicleRenderingEnabled.get();
    }

    /** 返回服务端权威的载具超视距授权模式。 */
    public static VisibilityMode getRemoteVehicleVisibilityMode() {
        return INSTANCE != null
                ? INSTANCE.remoteVehicleVisibilityMode.get()
                : VisibilityMode.VEHICLE_OCCUPANTS;
    }

    /** 返回载具超视距同步最大水平距离，单位格。 */
    public static double getRemoteVehicleMaxDistance() {
        return INSTANCE != null ? INSTANCE.remoteVehicleMaxDistance.get() : 4096.0D;
    }

    /** 返回载具视觉完整集合同步周期，单位 tick。 */
    public static int getRemoteVehicleSyncIntervalTicks() {
        return INSTANCE != null ? INSTANCE.remoteVehicleSyncIntervalTicks.get() : 5;
    }

    /** 返回单个玩家每份快照允许的最大载具目标数。 */
    public static int getRemoteVehicleMaxTargetsPerPlayer() {
        return INSTANCE != null ? INSTANCE.remoteVehicleMaxTargetsPerPlayer.get() : 32;
    }

    /** 返回服务端权威的远距载具区块租约模式。 */
    public static ChunkLoadingMode getRemoteVehicleChunkLoadingMode() {
        return INSTANCE != null
                ? INSTANCE.remoteVehicleChunkLoadingMode.get()
                : ChunkLoadingMode.VISIBLE_TARGETS;
    }

    /** 返回单个维度允许保持区块加载的最大远距载具数。 */
    public static int getRemoteVehicleMaxChunkLoadedVehiclesPerDimension() {
        return INSTANCE != null ? INSTANCE.remoteVehicleMaxChunkLoadedVehiclesPerDimension.get() : 32;
    }

    /** 返回远距载具区块前探 tick 数。 */
    public static int getRemoteVehicleChunkLookAheadTicks() {
        return INSTANCE != null ? INSTANCE.remoteVehicleChunkLookAheadTicks.get() : 5;
    }

    /** 返回指定观察者载具类型允许接收的不可变目标类型集合。 */
    public static Set<VehicleCategory> getRemoteVehicleVisibleTargetTypes(VehicleCategory observerType) {
        if (observerType == null) {
            return Set.of();
        }
        Map<VehicleCategory, Set<VehicleCategory>> matrix = INSTANCE == null
                ? defaultVisibilityMatrix()
                : INSTANCE.normalizedVisibilityMatrix;
        return matrix.getOrDefault(observerType, Set.of());
    }

    /** 监听 common 配置加载与重载，刷新类型白名单缓存。 */
    @SubscribeEvent
    public static void onConfigEvent(ModConfigEvent event) {
        if (INSTANCE == null || SPEC == null || event.getConfig().getSpec() != SPEC) {
            return;
        }
        INSTANCE.refreshVisibilityMatrix();
    }

    /** 规范化三类观察者白名单，并为本次加载合并输出一次未知 token 告警。 */
    private void refreshVisibilityMatrix() {
        EnumMap<VehicleCategory, Set<VehicleCategory>> matrix = new EnumMap<>(VehicleCategory.class);
        java.util.LinkedHashSet<String> unknownTokens = new java.util.LinkedHashSet<>();
        normalizeEntry(matrix, unknownTokens, VehicleCategory.HELICOPTER, helicopterVisibleTargets.get());
        normalizeEntry(matrix, unknownTokens, VehicleCategory.AIRCRAFT, aircraftVisibleTargets.get());
        normalizeEntry(matrix, unknownTokens, VehicleCategory.GROUND_VEHICLES, groundVehicleVisibleTargets.get());
        normalizedVisibilityMatrix = Map.copyOf(matrix);
        if (!unknownTokens.isEmpty()) {
            LOGGER.warn("远距载具类型白名单包含未知或非严格小写 token，已忽略：{}", unknownTokens);
        }
    }

    /** 规范化单个观察者类型的目标 token 列表。 */
    private static void normalizeEntry(Map<VehicleCategory, Set<VehicleCategory>> matrix,
                                       Set<String> unknownTokens,
                                       VehicleCategory observerType,
                                       List<? extends String> configuredTokens) {
        // 调用服务端远距载具策略，集中解析并去重三类配置 token。
        NormalizationResult result = RVP_RemoteVehicleVisibilityPolicy.normalizeConfiguredTypes(configuredTokens);
        matrix.put(observerType, result.accepted());
        unknownTokens.addAll(result.unknown());
    }

    /** 创建配置尚未加载时使用的权威默认类型矩阵。 */
    private static Map<VehicleCategory, Set<VehicleCategory>> defaultVisibilityMatrix() {
        Set<VehicleCategory> all = Set.of(
                VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT, VehicleCategory.GROUND_VEHICLES);
        return Map.of(
                VehicleCategory.HELICOPTER, all,
                VehicleCategory.AIRCRAFT, all,
                VehicleCategory.GROUND_VEHICLES,
                Set.of(VehicleCategory.HELICOPTER, VehicleCategory.AIRCRAFT));
    }

    /** 注册 common 配置；必须在模组构造阶段调用。 */
    public static void register(ModLoadingContext context) {
        Pair<RVP_CommonConfig, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_CommonConfig::new);
        INSTANCE = specPair.getLeft();
        SPEC = specPair.getRight();
        context.registerConfig(ModConfig.Type.COMMON, SPEC, "ywzj_rvp-common.toml");
    }
}
