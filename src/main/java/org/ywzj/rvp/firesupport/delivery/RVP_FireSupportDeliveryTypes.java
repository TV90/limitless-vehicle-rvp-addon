package org.ywzj.rvp.firesupport.delivery;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryFactory;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportJson;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportProblemCollector;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

/** 内建投送工厂注册表；统一提供严格配置、武器能力校验和阶段 B 服务端实现。 */
public final class RVP_FireSupportDeliveryTypes {
    /** 首版垂直真实弹体投送 ID。 */ public static final ResourceLocation VERTICAL_PROJECTILE = id("vertical_projectile");
    /** 固定虚拟地面阵位的真实弹体投送 ID。 */ public static final ResourceLocation GROUND_LAUNCHED_PROJECTILE = id("ground_launched_projectile");
    /** 虚拟空中释放点的真实炸弹投送 ID。 */ public static final ResourceLocation AIR_LAUNCHED_PROJECTILE = id("air_launched_projectile");
    /** 注册阶段可变工厂表。 */ private static final Map<ResourceLocation, RVP_FireSupportDeliveryFactory> MUTABLE = new LinkedHashMap<>();
    /** 冻结后的不可变工厂表。 */ private static Map<ResourceLocation, RVP_FireSupportDeliveryFactory> factories;

    static {
        register(new VerticalProjectileFactory());
        register(new GroundLaunchedProjectileFactory());
        register(new AirLaunchedProjectileFactory());
        factories = Map.copyOf(MUTABLE);
    }

    private RVP_FireSupportDeliveryTypes() {}

    private static void register(RVP_FireSupportDeliveryFactory factory) {
        if (factories != null) throw new IllegalStateException("炮火投送注册表已经冻结");
        if (MUTABLE.putIfAbsent(factory.typeId(), factory) != null) throw new IllegalStateException("重复投送类型 " + factory.typeId());
    }

    /** @return 已冻结的工厂；未知类型返回 null。 */
    public static RVP_FireSupportDeliveryFactory get(ResourceLocation id) { return factories.get(id); }

    /** @return 已注册投送类型的不可变视图。 */
    public static Map<ResourceLocation, RVP_FireSupportDeliveryFactory> all() { return factories; }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("rvp", path); }

    /** 垂直投送在阶段 B 使用的不可变参数。 */
    public record VerticalProjectileData(
            /** 相对计划落点地面的生成高度，单位格，默认 120。 */ double spawnHeightAboveImpactMeters,
            /** 入场初速度，单位格/Tick，默认 0；0 表示使用武器初速。 */ double entrySpeedMetersPerTick,
            /** 生成前预加载窗口，单位 Tick，默认 10。 */ int preloadTicks,
            /** 入场方向随机扰动最大角，单位度，默认 0。 */ double headingJitterDegrees) {}

    /** 固定地面炮位投送的不可变参数。 */
    public record GroundLaunchedProjectileData(
            /** 期望炮位至任务锚点的水平距离，单位格，默认 768。 */ double launchDistanceMeters,
            /** 世界边界钳制后允许的最小水平距离，单位格，默认 256。 */ double minLaunchDistanceMeters,
            /** 炮口相对炮位地表的高度，单位格，默认 2.5。 */ double launchHeightAboveGroundMeters,
            /** 允许高弹道顶点高于目标地表的最大高度，单位格，默认 512。 */ double maxApexAboveImpactMeters,
            /** 生成点 Chunk 的提前准备窗口，单位 Tick，默认 80。 */ int preloadTicks,
            /** 整个任务固定来向相对请求方位的最大确定性扰动，单位度，默认 0。 */ double headingJitterDegrees,
            /** 本发地射初速覆盖，单位格/Tick，默认 0；0 表示使用武器解析初速。 */ double entrySpeedMetersPerTick) {

        /** 保留旧版六参数构造形式；新增初速覆盖默认关闭。 */
        public GroundLaunchedProjectileData(double launchDistanceMeters, double minLaunchDistanceMeters,
                                             double launchHeightAboveGroundMeters, double maxApexAboveImpactMeters,
                                             int preloadTicks, double headingJitterDegrees) {
            this(launchDistanceMeters, minLaunchDistanceMeters, launchHeightAboveGroundMeters,
                    maxApexAboveImpactMeters, preloadTicks, headingJitterDegrees, 0.0D);
        }
    }

    /** 空中释放投送的不可变参数。 */
    public record AirLaunchedProjectileData(
            /** 指定生成的本体载具资源 ID；必须在服务端载具索引中存在。 */ ResourceLocation aircraftId,
            /** 飞机本地挂架坐标，单位格；由飞机姿态变换到世界坐标。 */ LocalOffset rackOffset,
            /** 飞机相对首个投放点的入场距离，单位格，默认 1280。 */ double entryDistanceMeters,
            /** 飞机相对末个投放点的出场距离，单位格，默认 768。 */ double exitDistanceMeters,
            /** 期望释放点相对目标地表的高度，单位格，默认 256。 */ double releaseAltitudeAboveImpactMeters,
            /** 世界高度/边界钳制后允许的最小释放高度，单位格，默认 96。 */ double minReleaseAltitudeAboveImpactMeters,
            /** GPS 弹相对目标沿入场反方向的固定释放距离，单位格，默认 768；非 GPS 弹忽略。 */ double gpsReleaseDistanceMeters,
            /** 虚拟载机赋予弹体的水平速度，单位格/Tick，默认 2.5。 */ double carrierSpeedMetersPerTick,
            /** 生成点 Chunk 的提前准备窗口，单位 Tick，默认 80。 */ int preloadTicks,
            /** 整个任务航线相对请求方位的最大确定性扰动，单位度，默认 0。 */ double headingJitterDegrees) {

        /** 保留原九参数构造形式；GPS 固定前置释放距离使用 768 格默认值。 */
        public AirLaunchedProjectileData(ResourceLocation aircraftId, LocalOffset rackOffset,
                                         double entryDistanceMeters, double exitDistanceMeters,
                                         double releaseAltitudeAboveImpactMeters,
                                         double minReleaseAltitudeAboveImpactMeters,
                                         double carrierSpeedMetersPerTick, int preloadTicks,
                                         double headingJitterDegrees) {
            this(aircraftId, rackOffset, entryDistanceMeters, exitDistanceMeters,
                    releaseAltitudeAboveImpactMeters, minReleaseAltitudeAboveImpactMeters,
                    768.0D, carrierSpeedMetersPerTick, preloadTicks, headingJitterDegrees);
        }
    }

    /** 飞机本地坐标中的挂架偏移。 */
    public record LocalOffset(
            /** 本地 X 偏移，单位格。 */ double x,
            /** 本地 Y 偏移，单位格。 */ double y,
            /** 本地 Z 偏移，单位格。 */ double z) {}

    private static final class VerticalProjectileFactory implements RVP_FireSupportDeliveryFactory {
        @Override public ResourceLocation typeId() { return VERTICAL_PROJECTILE; }

        @Override
        public Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path) {
            RVP_FireSupportJson.keys(data, Set.of("spawn_height_above_impact_m", "entry_speed_m_per_tick",
                    "preload_ticks", "heading_jitter_deg"), path, problems);
            double height = RVP_FireSupportJson.number(data, "spawn_height_above_impact_m", 120.0, path, problems);
            double speed = RVP_FireSupportJson.number(data, "entry_speed_m_per_tick", 0.0, path, problems);
            int preload = RVP_FireSupportJson.integer(data, "preload_ticks", 10, path, problems);
            double jitter = RVP_FireSupportJson.number(data, "heading_jitter_deg", 0.0, path, problems);
            if (height <= 0 || height > 2048) problems.add(path + ".spawn_height_above_impact_m", "必须在 (0, 2048] 内");
            if (speed < 0 || speed > 64) problems.add(path + ".entry_speed_m_per_tick", "必须在 [0, 64] 内；0 表示使用武器初速");
            if (preload < 0 || preload > 1200) problems.add(path + ".preload_ticks", "必须在 [0, 1200] 内");
            if (jitter < 0 || jitter > 45) problems.add(path + ".heading_jitter_deg", "必须在 [0, 45] 内");
            return new VerticalProjectileData(height, speed, preload, jitter);
        }

        @Override
        public RVP_FireSupportDelivery create(Object parsedData) {
            if (!(parsedData instanceof VerticalProjectileData data)) {
                throw new IllegalArgumentException("vertical_projectile 需要 VerticalProjectileData");
            }
            return new RVP_VerticalProjectileDelivery(data);
        }

        @Override
        public JsonObject encode(Object parsedData) {
            if (!(parsedData instanceof VerticalProjectileData data)) {
                throw new IllegalArgumentException("vertical_projectile 需要 VerticalProjectileData");
            }
            JsonObject out = new JsonObject();
            out.addProperty("spawn_height_above_impact_m", data.spawnHeightAboveImpactMeters());
            out.addProperty("entry_speed_m_per_tick", data.entrySpeedMetersPerTick());
            out.addProperty("preload_ticks", data.preloadTicks());
            out.addProperty("heading_jitter_deg", data.headingJitterDegrees());
            return out;
        }

        @Override
        public void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                   RVP_FireSupportProblemCollector problems, String path) {
            RVP_EnumWeaponKind kind = data.kind();
            if (kind == null || kind == RVP_EnumWeaponKind.LASER || kind == RVP_EnumWeaponKind.TARGETING_POD) {
                problems.add(path, "武器 " + weaponId + " 不是可投送的 RVP 实体弹体类型");
            }
            // 只按实体弹体能力拒绝；依赖操作手的制导弹可作为无制导垂直弹体使用，不再阻止整个 profile 发布。
        }
    }

    private static final class GroundLaunchedProjectileFactory implements RVP_FireSupportDeliveryFactory {
        @Override public ResourceLocation typeId() { return GROUND_LAUNCHED_PROJECTILE; }

        @Override
        public Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path) {
            RVP_FireSupportJson.keys(data, Set.of("launch_distance_m", "min_launch_distance_m",
                    "launch_height_above_ground_m", "max_apex_above_impact_m", "preload_ticks",
                    "heading_jitter_deg", "entry_speed_m_per_tick"), path, problems);
            double distance = RVP_FireSupportJson.number(data, "launch_distance_m", 768.0, path, problems);
            double minimum = RVP_FireSupportJson.number(data, "min_launch_distance_m", 256.0, path, problems);
            double height = RVP_FireSupportJson.number(data, "launch_height_above_ground_m", 2.5, path, problems);
            double apex = RVP_FireSupportJson.number(data, "max_apex_above_impact_m", 512.0, path, problems);
            int preload = RVP_FireSupportJson.integer(data, "preload_ticks", 80, path, problems);
            double jitter = RVP_FireSupportJson.number(data, "heading_jitter_deg", 0.0, path, problems);
            double entrySpeed = RVP_FireSupportJson.number(data, "entry_speed_m_per_tick", 0.0, path, problems);
            if (distance <= 0 || distance > 2048) problems.add(path + ".launch_distance_m", "必须在 (0, 2048] 内");
            if (minimum <= 0 || minimum > distance) problems.add(path + ".min_launch_distance_m", "必须在 (0, launch_distance_m] 内");
            if (height <= 0 || height > 64) problems.add(path + ".launch_height_above_ground_m", "必须在 (0, 64] 内");
            if (apex < 16 || apex > 2048) problems.add(path + ".max_apex_above_impact_m", "必须在 [16, 2048] 内");
            if (entrySpeed < 0 || entrySpeed > 64) {
                problems.add(path + ".entry_speed_m_per_tick", "必须在 [0, 64] 内；0 表示使用武器解析初速");
            }
            validatePreloadAndJitter(preload, jitter, path, problems);
            return new GroundLaunchedProjectileData(distance, minimum, height, apex, preload, jitter, entrySpeed);
        }

        @Override
        public RVP_FireSupportDelivery create(Object parsedData) {
            if (!(parsedData instanceof GroundLaunchedProjectileData data)) {
                throw new IllegalArgumentException("ground_launched_projectile 需要 GroundLaunchedProjectileData");
            }
            return new RVP_GroundLaunchedProjectileDelivery(data);
        }

        @Override
        public JsonObject encode(Object parsedData) {
            if (!(parsedData instanceof GroundLaunchedProjectileData data)) {
                throw new IllegalArgumentException("ground_launched_projectile 需要 GroundLaunchedProjectileData");
            }
            JsonObject out = new JsonObject();
            out.addProperty("launch_distance_m", data.launchDistanceMeters());
            out.addProperty("min_launch_distance_m", data.minLaunchDistanceMeters());
            out.addProperty("launch_height_above_ground_m", data.launchHeightAboveGroundMeters());
            out.addProperty("max_apex_above_impact_m", data.maxApexAboveImpactMeters());
            out.addProperty("preload_ticks", data.preloadTicks());
            out.addProperty("heading_jitter_deg", data.headingJitterDegrees());
            out.addProperty("entry_speed_m_per_tick", data.entrySpeedMetersPerTick());
            return out;
        }

        @Override
        public void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                   RVP_FireSupportProblemCollector problems, String path) {
            if (data.gpsGuided()) {
                validateGpsProjectile(weaponId, data, problems, path);
                return;
            }
            if (data.kind() != RVP_EnumWeaponKind.MACHINEGUN && data.kind() != RVP_EnumWeaponKind.ROCKET) {
                problems.add(path, "地面弹道投送只接受 MACHINEGUN 或 ROCKET 实体弹体: " + weaponId);
            }
            validateUnguidedBallistic(weaponId, data, problems, path);
        }
    }

    private static final class AirLaunchedProjectileFactory implements RVP_FireSupportDeliveryFactory {
        @Override public ResourceLocation typeId() { return AIR_LAUNCHED_PROJECTILE; }

        @Override
        public Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path) {
            RVP_FireSupportJson.keys(data, Set.of("aircraft_id", "rack_offset", "entry_distance_m",
                    "exit_distance_m", "release_altitude_above_impact_m",
                    "min_release_altitude_above_impact_m", "gps_release_distance_m",
                    "carrier_speed_m_per_tick", "preload_ticks",
                    "heading_jitter_deg"), path, problems);
            ResourceLocation aircraftId = RVP_FireSupportJson.resource(
                    RVP_FireSupportJson.string(data, "aircraft_id", path, problems),
                    path + ".aircraft_id", problems);
            JsonObject rack = RVP_FireSupportJson.object(data, "rack_offset", path, problems, true);
            String rackPath = path + ".rack_offset";
            RVP_FireSupportJson.keys(rack, Set.of("x", "y", "z"), rackPath, problems);
            LocalOffset rackOffset = new LocalOffset(
                    RVP_FireSupportJson.number(rack, "x", 0.0, rackPath, problems),
                    RVP_FireSupportJson.number(rack, "y", -2.0, rackPath, problems),
                    RVP_FireSupportJson.number(rack, "z", 0.0, rackPath, problems));
            double entry = RVP_FireSupportJson.number(data, "entry_distance_m", 1280.0, path, problems);
            double exit = RVP_FireSupportJson.number(data, "exit_distance_m", 768.0, path, problems);
            double altitude = RVP_FireSupportJson.number(data, "release_altitude_above_impact_m", 256.0, path, problems);
            double minimum = RVP_FireSupportJson.number(data, "min_release_altitude_above_impact_m", 96.0, path, problems);
            double gpsDistance = RVP_FireSupportJson.number(data, "gps_release_distance_m", 768.0, path, problems);
            double speed = RVP_FireSupportJson.number(data, "carrier_speed_m_per_tick", 2.5, path, problems);
            int preload = RVP_FireSupportJson.integer(data, "preload_ticks", 80, path, problems);
            double jitter = RVP_FireSupportJson.number(data, "heading_jitter_deg", 0.0, path, problems);
            if (entry <= 0 || entry > 4096) problems.add(path + ".entry_distance_m", "必须在 (0, 4096] 内");
            if (exit <= 0 || exit > 4096) problems.add(path + ".exit_distance_m", "必须在 (0, 4096] 内");
            if (Math.abs(rackOffset.x()) > 128 || Math.abs(rackOffset.y()) > 128 || Math.abs(rackOffset.z()) > 128) {
                problems.add(path + ".rack_offset", "各轴绝对值不得超过 128 格");
            }
            if (altitude <= 0 || altitude > 2048) problems.add(path + ".release_altitude_above_impact_m", "必须在 (0, 2048] 内");
            if (minimum <= 0 || minimum > altitude) problems.add(path + ".min_release_altitude_above_impact_m", "必须在 (0, release_altitude_above_impact_m] 内");
            if (gpsDistance <= 0 || gpsDistance > 4096) problems.add(path + ".gps_release_distance_m", "必须在 (0, 4096] 内");
            if (speed <= 0 || speed > 64) problems.add(path + ".carrier_speed_m_per_tick", "必须在 (0, 64] 内");
            validatePreloadAndJitter(preload, jitter, path, problems);
            return new AirLaunchedProjectileData(aircraftId, rackOffset, entry, exit,
                    altitude, minimum, gpsDistance, speed, preload, jitter);
        }

        @Override
        public RVP_FireSupportDelivery create(Object parsedData) {
            if (!(parsedData instanceof AirLaunchedProjectileData data)) {
                throw new IllegalArgumentException("air_launched_projectile 需要 AirLaunchedProjectileData");
            }
            return new RVP_AirLaunchedProjectileDelivery(data);
        }

        @Override
        public JsonObject encode(Object parsedData) {
            if (!(parsedData instanceof AirLaunchedProjectileData data)) {
                throw new IllegalArgumentException("air_launched_projectile 需要 AirLaunchedProjectileData");
            }
            JsonObject out = new JsonObject();
            out.addProperty("aircraft_id", data.aircraftId().toString());
            JsonObject rack = new JsonObject();
            rack.addProperty("x", data.rackOffset().x());
            rack.addProperty("y", data.rackOffset().y());
            rack.addProperty("z", data.rackOffset().z());
            out.add("rack_offset", rack);
            out.addProperty("entry_distance_m", data.entryDistanceMeters());
            out.addProperty("exit_distance_m", data.exitDistanceMeters());
            out.addProperty("release_altitude_above_impact_m", data.releaseAltitudeAboveImpactMeters());
            out.addProperty("min_release_altitude_above_impact_m", data.minReleaseAltitudeAboveImpactMeters());
            out.addProperty("gps_release_distance_m", data.gpsReleaseDistanceMeters());
            out.addProperty("carrier_speed_m_per_tick", data.carrierSpeedMetersPerTick());
            out.addProperty("preload_ticks", data.preloadTicks());
            out.addProperty("heading_jitter_deg", data.headingJitterDegrees());
            return out;
        }

        @Override
        public void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                   RVP_FireSupportProblemCollector problems, String path) {
            if ((!data.gpsGuided() && data.kind() != RVP_EnumWeaponKind.BOMB)
                    && data.kind() != RVP_EnumWeaponKind.MISSILE
                    && data.kind() != RVP_EnumWeaponKind.ROCKET) {
                problems.add(path, "空中投送只接受 BOMB、MISSILE 或 ROCKET 实体弹体: " + weaponId);
            }
            // 调用本项目统一实体工厂判断 GPS 弹是否能落到类型化 RVP 实体，拒绝射线类伪弹体。
            if (data.gpsGuided() && !RVP_ProjectileEntityFactory.supports(data.kind())) {
                problems.add(path, "GPS 定点空射只接受可生成的 RVP 实体弹体: " + weaponId);
            }
            if (data.airGuided() || data.humanInTheLoop()
                    || data.operatorGuided() || data.hitlClosTvGuided()) {
                problems.add(path, "空中投送不接受空对空或需要操作员持续控制的制导武器: " + weaponId);
            }
        }
    }

    private static void validateUnguidedBallistic(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                                   RVP_FireSupportProblemCollector problems, String path) {
        if (data.guidanceType() != RVP_EnumGuidanceType.NONE || data.humanInTheLoop()
                || data.operatorGuided() || data.hitlClosTvGuided()) {
            problems.add(path, "首期真实入场只接受完全无制导武器: " + weaponId);
        }
        if (data.propulsion()) problems.add(path, "首期真实入场不接受推进弹体: " + weaponId);
        if (data.constantSpeed()) problems.add(path, "首期真实入场不接受恒速弹体: " + weaponId);
    }

    /** GPS 已提供固定世界目标，允许弹体保留本体推进/恒速逻辑，但仍拒绝空对空和人工控制链路。 */
    private static void validateGpsProjectile(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                               RVP_FireSupportProblemCollector problems, String path) {
        if (data.kind() == null || data.kind() == RVP_EnumWeaponKind.LASER
                || data.kind() == RVP_EnumWeaponKind.TARGETING_POD) {
            problems.add(path, "GPS 定点投送只接受可生成的 RVP 实体弹体: " + weaponId);
        }
        if (data.airGuided() || data.humanInTheLoop() || data.operatorGuided() || data.hitlClosTvGuided()) {
            problems.add(path, "GPS 定点投送不接受空对空或需要操作员持续控制的制导武器: " + weaponId);
        }
    }

    private static void validatePreloadAndJitter(int preload, double jitter, String path,
                                                  RVP_FireSupportProblemCollector problems) {
        if (preload < 0 || preload > 1200) problems.add(path + ".preload_ticks", "必须在 [0, 1200] 内");
        if (jitter < 0 || jitter > 45) problems.add(path + ".heading_jitter_deg", "必须在 [0, 45] 内");
    }
}
