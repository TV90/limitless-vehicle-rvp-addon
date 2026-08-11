package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import org.ywzj.rvp.client.resource.vehicle.RVP_LodModel;
import org.ywzj.rvp.client.state.RVP_ClientZoomState;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整模型 LOD：
 * <p>
 * 依据 display JSON 的 {@code lod_models} 配置，在当前玩家与载具的距离（或载具离地状态）
 * 达到阈值时，用低面数模型整体替换载具主体渲染：
 * <ul>
 *   <li>全静态：LOD 级使用烘焙后的静态 bind pose 实例，跳过 {@code applyAnimationPose}；</li>
 *   <li>贴图随 LOD 更换（{@code texture} 缺省沿用原贴图）；</li>
 *   <li>离地判定：载具相对地面高度 {@code >= air_height} 视为飞行，使用 {@code air_distance} 阈值；</li>
 *   <li>滞后防抖：进入用配置阈值 T，退回用 {@code T × 0.85}；</li>
 *   <li>评估频率：每 20 tick（1 秒）一次；</li>
 *   <li>每车独立 LOD 实例缓存（{@link WeakHashMap}，载具卸载自动回收）。</li>
 * </ul>
 * 渲染热路径（{@link #resolve}）只做缓存读取；规则绑定、LOD 模型烘焙与贴图注册由
 * {@link #rebindAll()} 在资源 reload 后一次性完成。
 */
public final class RVP_LodModelManager {

    /** display → LOD 规则列表（仅配置了 lod_models 的 display）。 */
    private static final Map<BaseDisplay, List<RVP_LodModel>> DISPLAY_RULES = new ConcurrentHashMap<>();

    /** LOD 模型资源 ID → 静态烘焙模型（资源 reload 时统一构建，多车共享）。 */
    private static final Map<ResourceLocation, VehicleBedrockModel> MODEL_CACHE = new HashMap<>();

    /** 载具 → 当前激活的 LOD 状态（每车独立实例）。 */
    private static final Map<AbstractVehicle, VehicleLodState> VEHICLE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final int EVALUATE_INTERVAL = 20;
    private static final double HYSTERESIS_RETREAT = 0.85;

    private RVP_LodModelManager() {}

    /**
     * 每车当前激活的 LOD 状态；未激活时 {@code model}/{@code instance} 为 null。
     * 渲染 mixin 只读该状态以重定向模型/实例/贴图/动画。
     */
    public static final class VehicleLodState {
        public final int levelIndex;
        public final boolean air;
        public final VehicleBedrockModel model;
        public final BakedModelInstance instance;
        public final ResourceLocation texture;

        public VehicleLodState(int levelIndex, boolean air, VehicleBedrockModel model,
                               BakedModelInstance instance, ResourceLocation texture) {
            this.levelIndex = levelIndex;
            this.air = air;
            this.model = model;
            this.instance = instance;
            this.texture = texture;
        }
    }

    /** 资源 reload 完成后重建全部规则、烘焙 LOD 模型并注册 LOD 贴图。 */
    public static void rebindAll() {
        DISPLAY_RULES.clear();
        MODEL_CACHE.clear();
        VEHICLE_STATES.clear();
        Map<?, BaseDisplay> vehicleDisplays = ClientAssetsManager.INSTANCE.getVehicleDisplays();
        if (vehicleDisplays != null) {
            bindDisplays(vehicleDisplays.values());
        }
        bindDisplays(ClientAssetsManager.INSTANCE.getDecorationDisplays());
        registerLodTextures();
        bound = true;
    }

    /** 规则表是否已绑定；首次渲染时若未绑定则补一次重建（资源重载挂钩漏掉时的兜底）。 */
    private static volatile boolean bound = false;

    private static void ensureBound() {
        if (!bound) {
            rebindAll();
        }
    }

    private static void bindDisplays(Iterable<? extends BaseDisplay> displays) {
        if (displays == null) {
            return;
        }
        for (BaseDisplay display : displays) {
            if (display == null || display.getModel() == null) {
                continue;
            }
            List<RVP_LodModel> rules = resolveLodModels(display);
            if (rules.isEmpty()) {
                continue;
            }
            List<RVP_LodModel> valid = new ArrayList<>();
            for (RVP_LodModel rule : rules) {
                boolean modelOk = bakeLodModel(rule.model);
                boolean airOk = rule.modelAir == null || bakeLodModel(rule.modelAir);
                if (modelOk && airOk) {
                    valid.add(rule);
                }
            }
            if (!valid.isEmpty()) {
                DISPLAY_RULES.put(display, List.copyOf(valid));
            }
        }
    }

    /** 按资源 ID 烘焙 LOD 静态模型（已存在则跳过）；成功返回 true。 */
    private static boolean bakeLodModel(ResourceLocation modelId) {
        if (MODEL_CACHE.containsKey(modelId)) {
            return true;
        }
        VehicleBedrockModel lodModel = buildLodModel(modelId);
        if (lodModel == null || !lodModel.hasBakedModel()) {
            return false;
        }
        MODEL_CACHE.put(modelId, lodModel);
        return true;
    }

    /**
     * LOD 全静态：无动画骨骼、无特殊骨骼，烘焙静态几何，实例即静态 bind pose。
     * <p>
     * 注意：不走 {@code ClientAssetsManager.getModel}——车辆资源包（limitless_vehicle）通过
     * SecureJar 挂载，其目录扫描（listResources）不进入深层子目录（如 entity/lod/），
     * 直接按文件路径读取不受影响。
     */
    private static VehicleBedrockModel buildLodModel(ResourceLocation modelId) {
        ResourceLocation fileRl = ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(),
                "models/bedrock/" + modelId.getPath() + ".json");
        var rm = Minecraft.getInstance().getResourceManager();
        var resourceOpt = rm.getResource(fileRl);
        if (resourceOpt.isEmpty()) {
            return null;
        }
        try (InputStream in = resourceOpt.get().open()) {
            BedrockModelPOJO pojo = GsonUtil.GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    BedrockModelPOJO.class);
            if (pojo == null) {
                return null;
            }
            BakerOptions options = new BakerOptions(Set.of(), Set.of(), Set.of(), true, false, true);
            return new VehicleBedrockModel(pojo, List.of(), options);
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    private static void registerLodTextures() {
        var textureManager = Minecraft.getInstance().textureManager;
        for (List<RVP_LodModel> rules : DISPLAY_RULES.values()) {
            for (RVP_LodModel rule : rules) {
                if (rule.texture != null) {
                    textureManager.register(rule.texture, new SimpleTexture(rule.texture));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RVP_LodModel> resolveLodModels(BaseDisplay display) {
        try {
            Method getter = display.getClass().getMethod("getLodModels");
            Object result = getter.invoke(display);
            if (result instanceof List<?> list) {
                return list.stream()
                        .filter(x -> x instanceof RVP_LodModel)
                        .map(x -> (RVP_LodModel) x)
                        .toList();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return List.of();
    }

    /**
     * 渲染热路径（每帧调用）：按评估周期刷新选择结果，返回当前激活的 LOD 状态；
     * 未激活时返回 null（渲染方继续使用原模型/实例/贴图/动画）。
     */
    public static VehicleLodState resolve(AbstractVehicle vehicle) {
        ensureBound();
        long tick = currentTick();
        if (tick % EVALUATE_INTERVAL == 0) {
            evaluate(vehicle);
        }
        VehicleLodState state = VEHICLE_STATES.get(vehicle);
        if (state == null) {
            return null;
        }
        if (state.model == null || state.instance == null) {
            // 配置被 reload 清除后的兜底：视为未激活
            VEHICLE_STATES.remove(vehicle);
            return null;
        }
        return state;
    }

    private static void evaluate(AbstractVehicle vehicle) {
        BaseDisplay display = ClientAssetsManager.INSTANCE
                .getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null) {
            VEHICLE_STATES.remove(vehicle);
            return;
        }
        List<RVP_LodModel> rules = DISPLAY_RULES.get(display);
        if (rules == null || rules.isEmpty()) {
            VEHICLE_STATES.remove(vehicle);
            return;
        }
        boolean air = isAirborne(vehicle, rules);
        double dist = distanceToPlayer(vehicle);
        VehicleLodState current = VEHICLE_STATES.get(vehicle);
        int currentLevel = current != null ? current.levelIndex : -1;
        int target = selectLevel(rules, air, dist, currentLevel);
        // 级别与离地状态都无变化时才保持（离地切换可能导致地面/空中模型不同）
        if (target == currentLevel && current != null && current.air == air) {
            return; // 选择结果无变化
        }
        if (target < 0) {
            VEHICLE_STATES.remove(vehicle);
            return;
        }
        RVP_LodModel rule = rules.get(target);
        ResourceLocation modelId = rule.modelId(air);
        VehicleBedrockModel lodModel = MODEL_CACHE.get(modelId);
        if (lodModel == null) {
            return;
        }
        BakedModelInstance instance = lodModel.createBakedInstance();
        ResourceLocation texture = rule.texture != null ? rule.texture : display.getTexture();
        VEHICLE_STATES.put(vehicle, new VehicleLodState(target, air, lodModel, instance, texture));
    }

    /** 从最远级向最近级选择：激活中的级用退回阈值（T×0.85）保持，未激活的级用进入阈值（T）。 */
    private static int selectLevel(List<RVP_LodModel> rules, boolean air, double dist, int currentLevel) {
        // 缩放过滤：缩放中视场载具少、压力低，阈值放大（更远才替换 LOD 模型）；
        // 某级配置了显式缩放阈值（zoom_distance/zoom_air_distance）则优先使用
        boolean zoomed = RVP_ClientZoomState.isZoomed();
        double factor = RVP_ClientZoomState.lodDistanceMultiplier();
        for (int i = rules.size() - 1; i >= 0; i--) {
            RVP_LodModel rule = rules.get(i);
            double threshold;
            if (zoomed && rule.hasZoomDistance(air)) {
                threshold = rule.zoomThreshold(air);
            } else {
                threshold = rule.threshold(air) * factor;
            }
            if (i == currentLevel) {
                if (dist > threshold * HYSTERESIS_RETREAT) {
                    return i;
                }
                // 本层已不满足保持条件，继续向更近的层判断
            } else if (dist > threshold) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAirborne(AbstractVehicle vehicle, List<RVP_LodModel> rules) {
        double airHeight;
        VehicleLodState current = VEHICLE_STATES.get(vehicle);
        if (current != null && current.levelIndex >= 0 && current.levelIndex < rules.size()) {
            airHeight = rules.get(current.levelIndex).airHeight;
        } else {
            airHeight = rules.get(0).airHeight;
        }
        var level = vehicle.level();
        int x = vehicle.getBlockX();
        int z = vehicle.getBlockZ();
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return vehicle.getY() - ground >= airHeight;
    }

    private static double distanceToPlayer(AbstractVehicle vehicle) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0.0;
        }
        return player.position().distanceTo(vehicle.position());
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }
}
