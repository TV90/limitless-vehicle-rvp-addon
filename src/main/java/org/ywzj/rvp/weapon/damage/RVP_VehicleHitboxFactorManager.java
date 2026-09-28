package org.ywzj.rvp.weapon.damage;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.RVP_DefaultExplosionVisualService;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.network.S2CModuleHitNotify;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.rvp.radar.RVP_RadarModuleEnforcer;
import org.ywzj.rvp.vehicle.BoneApsConfig;
import org.ywzj.rvp.vehicle.BoneBarrelConfig;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;
import org.ywzj.rvp.vehicle.BoneDircmConfig;
import org.ywzj.rvp.vehicle.BoneEngineConfig;
import org.ywzj.rvp.vehicle.BoneJammerConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;

import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_BoneCumulativeDamageTable;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.util.ResourceScanner;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleHitboxFactorManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_VehicleHitboxFactorManager INSTANCE = new RVP_VehicleHitboxFactorManager();

    private Map<ResourceLocation, VehicleHitboxConfig> configs = Map.of();
    private Set<ResourceLocation> hidePassengerVehicles = Set.of();

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        // 客户端单机时 AddReloadListenerEvent（数据仓库）可能扫不到 rvp 包（VehiclePackLoader 以资源包注册），
        // 空 map 不覆盖，避免清空客户端资源重载 / 服务端同步已填充的配置。
        if (map == null || map.isEmpty()) {
            return;
        }
        Map<ResourceLocation, VehicleHitboxConfig> loaded = new HashMap<>();
        Set<ResourceLocation> hideSet = new HashSet<>();
        map.forEach((vehicleId, json) -> {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject obj = json.getAsJsonObject();
            if (GsonHelper.getAsBoolean(obj, "hide_passenger", false)) {
                hideSet.add(vehicleId);
            }
            VehicleHitboxConfig cfg = VehicleHitboxConfig.parse(obj);
            if (cfg != null) {
                loaded.put(vehicleId, cfg);
            }
        });
        configs = Map.copyOf(loaded);
        hidePassengerVehicles = Set.copyOf(hideSet);
    }

    /**
     * 供客户端在收到服务端同步的配置包（{@code S2CVehicleRvpConfig}）后填充配置。
     * 专用服务器下客户端不触发 {@link AddReloadListenerEvent}，隐藏乘员（hide_passenger）
     * 依赖该配置，故必须复用同一套解析逻辑。
     */
    public void applyFromJsonMap(Map<ResourceLocation, JsonElement> jsonMap) {
        apply(jsonMap, null, null);
    }

    /**
     * 用指定资源仓库手动重载（{@code /rvp reload} 轻量热重载调用）：prepare/apply 为 protected，
     * 外部触发不了，此入口让 rvp 载具包的载具 JSON（命中箱系数/骨模块等）在不重载渲染模型/贴图的前提下刷新。
     */
    public void reloadFrom(ResourceManager resourceManager) {
        apply(prepare(resourceManager, null), null, null);
    }

    public boolean isHidePassenger(ResourceLocation vehicleId) {
        return hidePassengerVehicles.contains(vehicleId);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    public float resolveHitboxDamageFactor(AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
        return resolveHitboxDamage(vehicle, segmentStart, segmentEnd).factor();
    }

    /**
     * 爆炸伤害倍率（2026-09-20 拆分自 hitbox_damage_factor，独立参数
     * {@code vehicle_explosion_damage_factor} / {@code vehicle_vehicle_explosion_damage_factor_default}）：
     * 以「爆心 → 目标载具包围盒中心」线段与各配置骨 OBB 求交，取最近命中骨的
     * 爆炸倍率（天然命中面向爆心的装甲面）；未配置爆炸倍率时返回 1（不缩放）。
     * 供 {@code RVP_VehicleHurtScalingHandler} 的爆炸分支乘算——爆炸伤害此前
     * 完全绕过命中箱倍率。
     */
    public float resolveVehicleExplosionHitboxDamageFactor(AbstractVehicle vehicle, Vec3 explosionPos, Vec3 targetCenter) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || !cfg.isEnabled()) {
            return 1f;
        }
        ResourceLocation structureModelId = cfg.structureModel();
        if (structureModelId == null) {
            return cfg.vehicleExplosionFactorDefault();
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(structureModelId).orElse(null);
        if (model == null) {
            return cfg.vehicleExplosionFactorDefault();
        }
        return cfg.resolve(model, vehicle, explosionPos, targetCenter, true).factor();
    }

    public float resolveCoreDistanceScaleMultiplier(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return 1f;
        }
        float m = cfg.coreDistanceScaleMultiplier;
        if (!Float.isFinite(m) || m < 0f) {
            return 1f;
        }
        return m;
    }

    /** 装甲固定扣减值；未配置 / 非法值返回 0（= 不启用装甲）。 */
    public float resolveArmorMinDamage(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return 0f;
        }
        float m = cfg.armorMinDamage;
        return Float.isFinite(m) && m > 0f ? m : 0f;
    }

    /** 最终伤害上限；<=0 表示不封顶。 */
    public float resolveArmorMaxDamage(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return 0f;
        }
        float m = cfg.armorMaxDamage;
        return Float.isFinite(m) && m > 0f ? m : 0f;
    }

    /** 该载具是否配置了装甲（armor_min_damage 或 armor_max_damage 任一 > 0）。 */
    public boolean isArmorConfigured(AbstractVehicle vehicle) {
        return resolveArmorMinDamage(vehicle) > 0f || resolveArmorMaxDamage(vehicle) > 0f;
    }

    public HitboxDamageResult resolveHitboxDamage(AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || !cfg.isEnabled()) {
            return HitboxDamageResult.disabled();
        }
        sanitizeModuleState(vehicle, cfg);
        ResourceLocation structureModelId = cfg.structureModel;
        if (structureModelId == null) {
            return HitboxDamageResult.defaulted(cfg.defaultFactor, null, 0, Double.NaN);
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(structureModelId).orElse(null);
        if (model == null) {
            return HitboxDamageResult.defaulted(cfg.defaultFactor, structureModelId, 0, Double.NaN);
        }
        return cfg.resolve(model, vehicle, segmentStart, segmentEnd);
    }

    public boolean isEraActive(AbstractVehicle vehicle, @Nullable String boneName) {
        return RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), boneName, BoneModuleType.ERA);
    }

    /** 骨块的某模块是否已失效（供候选穿透判定与叠加骨块多属性查询）。 */
    public boolean isModuleDestroyed(UUID vehicleId, @Nullable String boneName, BoneModuleType type) {
        return RVP_BoneModuleStateTable.isModuleDestroyed(vehicleId, boneName, type);
    }

    /**
     * 返回车辆所有声明了干扰机设备（{@code jammer} 子对象）的骨块配置（骨块名 → 配置）。
     * 设备是否存活（对应 JAMMER 骨块被击毁）由调用方通过
     * {@link RVP_BoneModuleStateTable#isModuleActive} 判定；未配置干扰机的车辆返回 null。
     */
    public @Nullable Map<String, BoneJammerConfig> resolveJammerDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneJammerConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneJammerConfig jammer = entry.getValue() == null ? null : entry.getValue().jammer();
            if (jammer != null) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), jammer);
            }
        }
        return out;
    }

    /**
     * 返回车辆所有声明了主动防护发射器（{@code aps} 子对象）的骨块配置（骨块名 → 配置）。
     * 设备是否存活（对应 APS 骨块被击毁）由调用方通过
     * {@link RVP_BoneModuleStateTable#isModuleActive} 判定；未配置 APS 的车辆返回 null。
     */
    public @Nullable Map<String, BoneApsConfig> resolveApsDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneApsConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneApsConfig aps = entry.getValue() == null ? null : entry.getValue().aps();
            if (aps != null && aps.isEnabled()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), aps);
            }
        }
        return out;
    }

    /**
     * 解析载具上全部存活的 DIRCM 照射模块（{@code bone_modules} 的 {@code dircm} 子对象）。
     * 返回 {@code Map<骨块名, 配置>}；无配置或全部禁用返回 null。
     * 供 {@code RVP_DircmRuntimeManager} 按骨块名 + {@code RVP_BoneModuleStateTable} 判定通道可用性。
     */
    public @Nullable Map<String, BoneDircmConfig> resolveDircmDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneDircmConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneDircmConfig dircm = entry.getValue() == null ? null : entry.getValue().dircm();
            if (dircm != null && dircm.isEnabled()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), dircm);
            }
        }
        return out;
    }

    /**
     * 解析载具上全部存活的主动电子战（{@code ecm_active}）骨块配置。
     *
     * <p>返回 {@code Map<骨块名, 配置>}；无配置返回 null。
     * 供 {@code RVP_EcmActiveManager} 判定载具是否具备主动ECM能力
     * （结合 {@code RVP_BoneModuleStateTable} 的骨块存活状态）。</p>
     */
    public @Nullable Map<String, BoneEcmActiveConfig> resolveEcmActiveDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneEcmActiveConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneEcmActiveConfig active = entry.getValue() == null ? null : entry.getValue().ecmActive();
            if (active != null) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), active);
            }
        }
        return out;
    }

    /**
     * 解析载具的快速维修模块绑定（{@code MAINTENANCE} 骨块模块 + {@code maintenance} 子配置）。
     *
     * <p>规范写法为 {@code bone_modules.__vehicle__.maintenance}（虚拟骨，载具级能力、
     * 永不可被击毁）；绑定实体骨时骨块被击毁则维修失效（可被快修的模块恢复修回）。
     * <b>2026-09-25 起全载具默认快修</b>：未显式配置的载具回退 {@link BoneMaintenanceConfig#defaults()}
     * （冷却 15 秒、一次修复 30% 最大血量，虚拟骨 {@code __vehicle__} 承载）；
     * 仅 {@code vehicle == null} 返回 null。供 {@code RVP_MaintenanceRuntimeManager} 使用。</p>
     */
    public @Nullable MaintenanceModuleBinding resolveMaintenanceModule(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg != null && cfg.moduleByBoneName != null && !cfg.moduleByBoneName.isEmpty()) {
            for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
                BoneModuleConfig moduleConfig = entry.getValue();
                if (moduleConfig != null && moduleConfig.maintenance() != null
                        && moduleConfig.modules() != null && moduleConfig.modules().contains(BoneModuleType.MAINTENANCE)) {
                    return new MaintenanceModuleBinding(entry.getKey(), moduleConfig.maintenance());
                }
            }
        }
        // [RVP] 2026-09-25 全载具默认快修（用户定版）：未显式配置 maintenance 的载具回退默认配置
        // （冷却 300t=15 秒，生效 20t × 1.5%/tick = 一次修复 30% 最大血量），挂在永不可毁的
        // 虚拟骨 __vehicle__ 上（虚拟骨不进任何击毁/失效路径，isModuleActive 缺省视为存活）；
        // 显式配置（含绑实体骨的对抗性写法）仍在上方优先返回。
        return new MaintenanceModuleBinding("__vehicle__", BoneMaintenanceConfig.defaults());
    }

    /**
     * 解析载具全部引擎部件骨（{@code modules} 含 ENGINE 的 {@code bone_modules} 条目）。
     * 返回 {@code Map<骨块名, 配置>}；未写 {@code engine} 子对象时回退 {@link BoneEngineConfig#defaults()}。
     * 无配置返回 null。供 {@code RVP_EngineDamageTable}（累计衰减）与
     * {@code RVP_EnginePowerHandler}（动力覆写）按骨取阈值/倍率。
     */
    public @Nullable Map<String, BoneEngineConfig> resolveEngineModules(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneEngineConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneModuleConfig moduleConfig = entry.getValue();
            if (moduleConfig != null && moduleConfig.hasModules()
                    && moduleConfig.modules().contains(BoneModuleType.ENGINE)) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), moduleConfig.engine() != null
                        ? moduleConfig.engine() : BoneEngineConfig.defaults());
            }
        }
        return out;
    }

    /** 单块引擎骨的配置（无配置返回 null）；供直击累计路径按命中骨取阈值。 */
    public @Nullable BoneEngineConfig resolveEngineConfig(AbstractVehicle vehicle, String boneName) {
        var modules = resolveEngineModules(vehicle);
        return modules == null ? null : modules.get(boneName);
    }

    /**
     * 解析载具全部炮管部件骨（{@code modules} 含 BARREL 的 {@code bone_modules} 条目）。
     * 返回 {@code Map<骨块名, 配置>}；未写 {@code barrel} 子对象时回退 {@link BoneBarrelConfig#defaults()}。
     * 无配置返回 null。供 {@code RVP_BarrelDamageTable}（累计）与射击 gate 按骨取阈值。
     */
    public @Nullable Map<String, BoneBarrelConfig> resolveBarrelModules(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneBarrelConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneModuleConfig moduleConfig = entry.getValue();
            if (moduleConfig != null && moduleConfig.hasModules()
                    && moduleConfig.modules().contains(BoneModuleType.BARREL)) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), moduleConfig.barrel() != null
                        ? moduleConfig.barrel() : BoneBarrelConfig.defaults());
            }
        }
        return out;
    }

    /** 单块炮管骨的配置（无配置返回 null）；供直击累计路径按命中骨取阈值。 */
    public @Nullable BoneBarrelConfig resolveBarrelConfig(AbstractVehicle vehicle, String boneName) {
        var modules = resolveBarrelModules(vehicle);
        return modules == null ? null : modules.get(boneName);
    }

    /**
     * [RVP] 部件失效冒烟开关（2026-09-28，用户定版按部件选配）：{@code bone_modules}
     * 条目 {@code smoke} 字段，缺省 true（不写即冒烟）。供
     * {@code RVP_DamagedPartSmokeEmitter} 决定该骨失效时是否生成黑烟。
     */
    public boolean isPartSmokeEnabled(AbstractVehicle vehicle, String boneName) {
        if (vehicle == null || boneName == null) {
            return true;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null) {
            return true;
        }
        BoneModuleConfig moduleConfig = cfg.moduleByBoneName.get(boneName);
        return moduleConfig == null || moduleConfig.smoke();
    }

    /**
     * [RVP] 武器站炮管骨解析（2026-09-28 炮管部件）：本体 {@code WeaponUnitData.initStructureModel}
     * 约定炮管骨 = {@code structure_bone + "_barrel"}（如 turret→turret_barrel，炮管俯仰组与
     * 炮管 OBB 挂该骨）；结构模型无该骨时回退 {@code structure_bone} 本名（同轴机枪站
     * {@code turret_machine_gun_barrel} 形态——structure_bone 本身即炮管骨）。
     * 结构模型不可用或两骨均不存在时返回 null（无炮管语义，射击 gate 放行）。
     */
    public @Nullable String resolveBarrelBone(AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        if (vehicle == null || weaponUnit == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return null;
        }
        String structureBone = weaponUnit.getData() == null ? null : weaponUnit.getData().getStructureBone();
        if (structureBone == null || structureBone.isBlank()) {
            return null;
        }
        String candidate = structureBone + "_barrel";
        if (boneExistsInStructureModel(cfg, candidate)) {
            return candidate;
        }
        if (boneExistsInStructureModel(cfg, structureBone)) {
            return structureBone;
        }
        return null;
    }

    /** 结构模型是否含指定骨（炮管骨候选存在性判定）。 */
    private boolean boneExistsInStructureModel(VehicleHitboxConfig cfg, String boneName) {
        if (cfg.structureModel == null) {
            return false;
        }
        BedrockModel model = CommonAssetsManager.structureModelManager()
                .getStructureModel(cfg.structureModel).orElse(null);
        return model != null && model.getBoneMap().containsKey(boneName);
    }

    /**
     * [RVP] 武器站炮管是否已损坏（BARREL 模块失效，2026-09-28）。
     * 炮管骨未配置 BARREL 部件时 {@code isModuleActive} 恒真 → 放行（未配置部件的炮管不会坏）。
     * 供 {@code RVP_WeaponBase.canShootOnServer} 射击 gate 与 gunner 选弹排除——站级判定，
     * 同武器站全部武器/弹种共享该结论。
     */
    public boolean isBarrelDestroyed(AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        String barrelBone = resolveBarrelBone(vehicle, weaponUnit);
        if (barrelBone == null) {
            return false;
        }
        return !RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), barrelBone, BoneModuleType.BARREL);
    }

    /** 快速维修模块绑定：骨块名（可为虚拟骨 {@code __vehicle__}）+ 配置。 */
    public record MaintenanceModuleBinding(String bone, BoneMaintenanceConfig config) {
    }

    /**
     * 解析载具上全部启用中的被动电子战（{@code ecm_passive}）骨块配置。
     *
     * <p>返回 {@code Map<骨块名, 配置>}；无配置返回 null。
     * 供 {@code RVP_EcmPassiveManager} 判定载具是否具备被动电子战能力
     * （结合 {@code RVP_BoneModuleStateTable} 的骨块存活状态）。</p>
     */
    public @Nullable Map<String, BoneEcmPassiveConfig> resolveEcmDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneEcmPassiveConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneEcmPassiveConfig ecm = entry.getValue() == null ? null : entry.getValue().ecmPassive();
            if (ecm != null && !ecm.bands().isEmpty()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), ecm);
            }
        }
        return out;
    }

    /**
     * 直击消耗骨块上全部"可被弹药击毁"的模块（机制一：OBB 单发命中）。
     *
     * <p>泛化了原 {@code tryTriggerEra}：命中骨块若挂多个模块（如 ERA + JAMMER 叠加），
     * 触发阈值判定通过后一次性全部置失效，各模块独立写状态、独立同步。
     * 仅当其中包含 ERA 模块时播放爆炸粒子/音效（ERA 专属观感）。</p>
     *
     * @param vehicle        目标载具
     * @param result         命中判定结果（必须携带骨块模块信息）
     * @param triggerDamage  触发判定用伤害值（低于 {@code minTriggerDamage} 不消耗）
     * @return 是否消耗了至少一个模块
     */
    public boolean tryDestroyBoneModules(AbstractVehicle vehicle, @Nullable HitboxDamageResult result, float triggerDamage) {
        return tryDestroyBoneModules(vehicle, result, triggerDamage, null, false, 0f);
    }

    /** 兼容入口：带射手、ERA 仍按模块前伤害判定（2026-09-27 前旧语义）。 */
    public boolean tryDestroyBoneModules(AbstractVehicle vehicle, @Nullable HitboxDamageResult result,
                                         float triggerDamage, @Nullable Entity shooter) {
        return tryDestroyBoneModules(vehicle, result, triggerDamage, shooter, false, 0f);
    }

    /**
     * 直击骨骼模块消耗（含引擎累计与部件战果通知）。
     *
     * <p>triggerDamage 语义（2026-09-27 用户定版分轨）：<b>ERA 用模块前伤害</b>（erode 骨倍率
     * 装甲前的值，保持旧平衡——装甲车 ERA 门槛按裸弹伤设计）；<b>其余模块（雷达/引擎/APS/
     * 干扰机等）用实际到骨伤害</b>（乘命中倍率、过装甲层后的最终入账值）——防弹衣逻辑：
     * 大倍率装甲骨上的雷达被小倍率弹蹭一下不该打坏。useFinalDamageForModules=true 时
     * triggerDamage 必须传入 applyArmor 之后的最终伤害。</p>
     *
     * @param shooter                   本次伤害的射手实体（弹体 owner / 激光射手 / 伤害源攻击者，
     *                                  可空——gunner 等非玩家射手不发展板通知）
     * @param useFinalDamageForModules  true = 非 ERA 模块（含引擎累计）按 finalBoneDamage
     *                                  （实际到骨伤害）判定，ERA 仍用 triggerDamage（模块前伤害）；
     *                                  false = 全模块按 triggerDamage（旧语义）
     * @param finalBoneDamage           实际到骨伤害：乘命中倍率、过装甲层后的最终入账值
     *                                  （useFinalDamageForModules=false 时不读）
     */
    public boolean tryDestroyBoneModules(AbstractVehicle vehicle, @Nullable HitboxDamageResult result,
                                         float triggerDamage, @Nullable Entity shooter,
                                         boolean useFinalDamageForModules, float finalBoneDamage) {
        // [RVP] 引擎部件（2026-09-26）：窗口累计直击伤害——必须在无模块/低伤早退之前累计，
        // 三个调用方（RVP_BaseBullet 直击 / RVP_LaserWeapon 激光 / RVP_VehicleHurtScalingHandler
        // 本体武器重放）全部经过本方法，一处埋点全覆盖；累计跨过重损阈值即触发 ENGINE 模块失效。
        // 引擎累计在 useFinalDamageForModules=true 时同样用实际到骨伤害（用户 2026-09-27 定版）
        accumulateEngineDamage(vehicle, result,
                useFinalDamageForModules ? finalBoneDamage : triggerDamage, shooter);
        // [RVP] 炮管部件（2026-09-28，单档累计）：埋点与引擎同位（早退之前，三调用方全覆盖），
        // 累计跨过阈值即 BARREL 模块失效（炮管所在武器站禁止射击）
        accumulateBarrelDamage(vehicle, result,
                useFinalDamageForModules ? finalBoneDamage : triggerDamage, shooter);
        if (vehicle == null || result == null || result.modules().isEmpty()) {
            return false;
        }
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        String boneName = result.hitBoneName();
        if (boneName == null) {
            return false;
        }
        UUID vehicleId = vehicle.getUUID();
        // [RVP] 2026-09-27 分轨判定（用户定版）：ERA 按模块前伤害（旧平衡，装甲车 ERA 门槛
        // 按裸弹伤设计）；其余模块（雷达/引擎/APS/干扰机等）按实际到骨伤害 finalBoneDamage——
        // 乘命中倍率、过装甲层后的最终入账值（防弹衣逻辑：大倍率装甲骨上的雷达被小倍率弹
        // 蹭一下不该打坏）。useFinalDamageForModules=false 时两轨同值（旧语义兼容）。
        float moduleTriggerDamage = useFinalDamageForModules ? finalBoneDamage : triggerDamage;
        if (!Float.isFinite(moduleTriggerDamage) || moduleTriggerDamage < 0f) {
            return false;
        }
        // [RVP] 全模块累计化（2026-09-28 用户定版）：命中配置模块骨即把实际到骨伤害累入通用
        // 累计表（含 ERA）——各模块按自身累计阈值（min_damage 字段，语义改为"累计失效阈值"）
        // 判定失效：小口径蹭伤可攒、大口径一发照旧超阈。引擎/炮管走下方专属累计段（子对象阈值）
        if (!result.modules().isEmpty()) {
            RVP_BoneCumulativeDamageTable.accumulate(vehicleId, boneName, moduleTriggerDamage);
            syncBoneDamageProgress(vehicle);
        }
        boolean anyDestroyed = false;
        boolean destroyedEra = false;
        String destroyedRadarBone = null;
        for (BoneModuleType type : result.modules()) {
            if (type == BoneModuleType.ENGINE || type == BoneModuleType.ENGINE_DAMAGED
                    || type == BoneModuleType.BARREL) {
                // 引擎/炮管不走单发直毁路径：失效完全由各自累计（engine/barrel 子对象阈值）驱动
                //（见 accumulateEngineDamage/accumulateBarrelDamage），循环里重复判定会跳档
                continue;
            }
            // [RVP] 累计失效判定（原单发 min_damage 门槛废除）：通用累计 ≥ 累计阈值（min_damage）
            // 即失效；min_damage 未配置（∞）＝该模块不可被直击打坏（保持现状）
            float threshold = result.minTriggerDamage();
            if (!Float.isFinite(threshold)
                    || RVP_BoneCumulativeDamageTable.getAccumulated(vehicleId, boneName) < threshold) {
                continue;
            }
            if (RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, type)) {
                anyDestroyed = true;
                if (type == BoneModuleType.ERA) {
                    destroyedEra = true;
                }
                if (type == BoneModuleType.RADAR) {
                    destroyedRadarBone = boneName;
                }
                // [RVP] 部件战果通知：摧毁部件推送给射手（展板下方 60 tick 文案）
                notifyModuleHit(shooter, vehicle, S2CModuleHitNotify.KIND_MODULE_DESTROYED, type);
            }
        }
        if (!anyDestroyed) {
            return false;
        }
        syncBoneModuleState(vehicle);
        if (destroyedEra) {
            // 命中点优先（ERA 块在命中位置爆），无命中点回退载具包围盒中心
            Vec3 hitPoint = result.hitPoint() != null ? result.hitPoint() : vehicle.getBoundingBox().getCenter();
            float explosionScale = result.explosion() > 0f ? result.explosion() : 1f;
            spawnMchrEraExplosion(serverLevel, hitPoint, explosionScale);
        }
        if (destroyedRadarBone != null) {
            // [RVP] 雷达部件：模块失效即强制关闭对应雷达（toggle(false) 清锁定目标）；
            // 巡检兜底见 RVP_RadarModuleEnforcer，外置中继链经 hasAnyRadarOn 自动断开
            RVP_RadarModuleEnforcer.forceRadarOff(vehicle, destroyedRadarBone);
        }
        return true;
    }

    /**
     * [RVP] 引擎部件累计段：命中骨配置了 ENGINE 模块时，把本次直击伤害累入
     * {@link RVP_BoneCumulativeDamageTable}（无衰减永久累计）；累计跨过重损阈值
     * 且模块仍存活时触发 ENGINE 模块失效（进失效表 → 瘫痪档，可维修恢复）。
     * 累计值变化随 {@code RVP_EnginePowerHandler} 的档位差分自动同步客户端。
     */
    private void accumulateEngineDamage(@Nullable AbstractVehicle vehicle,
                                        @Nullable HitboxDamageResult result, float triggerDamage,
                                        @Nullable Entity shooter) {
        if (vehicle == null || result == null || triggerDamage <= 0f || vehicle.level().isClientSide()) {
            return;
        }
        String boneName = result.hitBoneName();
        if (boneName == null) {
            return;
        }
        BoneEngineConfig engineConfig = resolveEngineConfig(vehicle, boneName);
        if (engineConfig == null) {
            return; // 命中骨未配置引擎部件：不累计
        }
        UUID vehicleId = vehicle.getUUID();
        float accumulated = RVP_BoneCumulativeDamageTable.accumulate(vehicleId, boneName, triggerDamage);
        syncBoneDamageProgress(vehicle);
        boolean moduleAlive = RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, BoneModuleType.ENGINE);
        // [RVP] 引擎部件诊断（/rvpdebug engine on）：每次直击入账的完整数值链——
        // 入账伤害（分轨后实际到骨值）、累计（无衰减，永久）、阈值、模块存活，写入专有日志
        // logs/rvp_engine_debug.log（不污染 latest.log），供"累计是裸伤还是实际伤害"的实机对账
        org.ywzj.rvp.debug.RVP_EngineDebug.log(String.format(
                "%s(%d) 引擎骨=%s 入账=%.1f 累计=%.1f 受损阈=%.0f 瘫痪阈=%.0f 模块存活=%s",
                vehicle.getVehicleId(), vehicle.getId(), boneName,
                triggerDamage, accumulated,
                engineConfig.thresholdLight(), engineConfig.thresholdHeavy(), moduleAlive));
        if (!moduleAlive) {
            return; // 已瘫痪：不再重复通知
        }
        // 档位跨越判定：before = 本次累计前值（accumulate 为同步加法，差值即本次伤害）
        float before = accumulated - triggerDamage;
        if (accumulated >= engineConfig.thresholdHeavy()) {
            // 跨过重损阈值：ENGINE 模块失效（瘫痪档），走标准失效广播（面板红框 / dev 队列 / 动画）
            RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, BoneModuleType.ENGINE);
            syncBoneModuleState(vehicle);
            // [RVP] 部件战果通知：单发跨两档时只报"摧毁引擎"，不叠报"重创发动机"
            notifyModuleHit(shooter, vehicle, S2CModuleHitNotify.KIND_MODULE_DESTROYED, BoneModuleType.ENGINE);
        } else if (before < engineConfig.thresholdLight() && accumulated >= engineConfig.thresholdLight()) {
            // 跨过受损阈值（功率降低）：ENGINE_DAMAGED 进入失效表（用户 2026-09-28 定版：重创=
            // 可修的失效设备）——随失效表持久化（跨退出重进保持）、维修面板失效置顶、
            // 可入维修顺序队列指定优先级、快修按设备配额恢复（恢复时清该骨累计）
            if (RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, BoneModuleType.ENGINE_DAMAGED)) {
                // 失效表变化广播（辅助设备面板/俯视图/冒烟/维修队列联动）
                syncBoneModuleState(vehicle);
            }
            // 同时向射手报"重创发动机"——一个受损窗期内只报一次
            // （tryMarkDamagedNotified 首次登记返回 true 即发；累计清零/快修后重新武装）
            if (RVP_BoneCumulativeDamageTable.tryMarkDamagedNotified(vehicleId, boneName)) {
                notifyModuleHit(shooter, vehicle, S2CModuleHitNotify.KIND_ENGINE_DAMAGED, BoneModuleType.ENGINE);
            }
        }
    }

    /**
     * [RVP] 炮管部件累计段（2026-09-28，单档）：命中骨配置了 BARREL 模块时，把本次直击
     * 实际到骨伤害累入 {@link RVP_BoneCumulativeDamageTable}（无衰减，只增不减）；累计跨过
     * {@link BoneBarrelConfig#getThreshold} 即 BARREL 模块失效（进失效表 → 整个炮管所在
     * 武器站禁止射击 + 持久化 + 冒烟 + 维修队列，战果通知"摧毁炮管"）。埋点与引擎累计
     * 同位（tryDestroyBoneModules 开头，弹体/激光/本体武器重放三调用方全覆盖）。
     */
    private void accumulateBarrelDamage(@Nullable AbstractVehicle vehicle,
                                        @Nullable HitboxDamageResult result, float triggerDamage,
                                        @Nullable Entity shooter) {
        if (vehicle == null || result == null || triggerDamage <= 0f || vehicle.level().isClientSide()) {
            return;
        }
        String boneName = result.hitBoneName();
        if (boneName == null) {
            return;
        }
        BoneBarrelConfig barrelConfig = resolveBarrelConfig(vehicle, boneName);
        if (barrelConfig == null) {
            return; // 命中骨未配置炮管部件：不累计
        }
        UUID vehicleId = vehicle.getUUID();
        float accumulated = RVP_BoneCumulativeDamageTable.accumulate(vehicleId, boneName, triggerDamage);
        syncBoneDamageProgress(vehicle);
        // [RVP] 累计诊断（/rvpdebug engine on 专有日志）：炮管入账同写，供实机对账
        org.ywzj.rvp.debug.RVP_EngineDebug.log(String.format(
                "%s(%d) 炮管骨=%s 入账=%.1f 累计=%.1f 损坏阈=%.0f 模块存活=%s",
                vehicle.getVehicleId(), vehicle.getId(), boneName,
                triggerDamage, accumulated, barrelConfig.threshold(),
                RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, BoneModuleType.BARREL)));
        if (accumulated >= barrelConfig.threshold()
                && RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, BoneModuleType.BARREL)) {
            // 跨过损坏阈值：BARREL 模块失效（标准失效广播——面板红框/维修队列/冒烟/持久化）
            syncBoneModuleState(vehicle);
            // [RVP] 部件战果通知：向射手报"摧毁炮管"
            notifyModuleHit(shooter, vehicle, S2CModuleHitNotify.KIND_MODULE_DESTROYED, BoneModuleType.BARREL);
        }
    }

    /**
     * [RVP] 部件战果通知：摧毁模块/引擎受损时向射手本人推送（命中展板下方 60 tick 文案）。
     * 射手非玩家（gunner/无人武器站）或被命中载具关闭 {@code hit_indicator_rvp} 时不发
     * （通知挂靠展板显隐，与展板一致）。
     */
    private static void notifyModuleHit(@Nullable Entity shooter, AbstractVehicle vehicle,
                                        int kind, BoneModuleType type) {
        if (!(shooter instanceof ServerPlayer player)) {
            return;
        }
        if (!INSTANCE.isHitIndicatorRvpEnabled(vehicle)) {
            return;
        }
        RVP_Network.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                S2CModuleHitNotify.create(kind, type.name()));
    }

    public String resolveHitboxDisplayName(AbstractVehicle vehicle, @Nullable String boneName) {
        if (boneName == null || boneName.isBlank()) {
            // 载具未配置 RVP 命中箱骨块（倍率骨骼）时，显示载具名称的翻译 key
            // （如 entity.rvp.t90m → 客户端渲染为 "T-90M 突破3"），取代原 "default"。
            // 非直击爆炸不经过本方法（boneDisplayName 为空串，客户端显示"爆炸"）。
            if (vehicle == null || vehicle.getVehicleId() == null) {
                return "default";
            }
            ResourceLocation id = vehicle.getVehicleId();
            return "entity." + id.getNamespace() + "." + id.getPath();
        }
        if (vehicle == null) {
            return boneName;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.aliasByBoneName == null || cfg.aliasByBoneName.isEmpty()) {
            return boneName;
        }
        return cfg.aliasByBoneName.getOrDefault(boneName, boneName);
    }

    /**
     * [RVP] 命中箱别名语言感知重载（2026-09-28 数据文件双语名机制）：中文环境（zh_*）
     * 且载具 JSON 配置了 {@code hitbox_display_name_CN}（与 hitbox_display_name 同构的
     * 顶层对象）时返回 CN 别名，否则回退英文名。服务端调用恒英文（isChineseUi dist 门）。
     * 回退链：CN 别名 → 英文别名 → 骨名/entity 翻译 key。
     */
    public String resolveHitboxDisplayNameLocalized(AbstractVehicle vehicle, @Nullable String boneName) {
        String fallback = resolveHitboxDisplayName(vehicle, boneName);
        if (boneName == null || boneName.isBlank() || !org.ywzj.rvp.client.util.RVP_LangHelper.isChineseUi()) {
            return fallback;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.aliasCnByBoneName == null || cfg.aliasCnByBoneName.isEmpty()) {
            return fallback;
        }
        return cfg.aliasCnByBoneName.getOrDefault(boneName, fallback);
    }

    /**
     * 解析载具全部骨模块配置的"骨名 → 模块类型集合"只读视图（辅助设备面板用）。
     *
     * <p>注意：底层 {@code moduleByBoneName} 为 HashMap 不保序，需要"载具数据出现顺序"
     * 的消费方（如辅助设备面板的行序号）应拿本表键集合去对照 {@code vehicle.getPartUnits()}
     * 的 JSON 顺序自行排序；无配置返回空 Map。</p>
     */
    public Map<String, Set<BoneModuleType>> resolveBoneModules(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<BoneModuleType>> out = new HashMap<>();
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneModuleConfig moduleConfig = entry.getValue();
            if (moduleConfig != null && moduleConfig.hasModules()) {
                out.put(entry.getKey(), Set.copyOf(moduleConfig.modules()));
            }
        }
        return out;
    }

    /** 载具 JSON 顶层 {@code hit_indicator_rvp}（默认 true）：是否对命中该载具显示 RVP 命中提示。 */
    public boolean isHitIndicatorRvpEnabled(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        return cfg == null || cfg.hitIndicatorRvp();
    }

    private void sanitizeModuleState(AbstractVehicle vehicle, VehicleHitboxConfig cfg) {
        if (RVP_BoneModuleStateTable.retainValidBones(vehicle.getUUID(), cfg.moduleByBoneName.keySet())
                && vehicle.level() instanceof ServerLevel) {
            syncBoneModuleState(vehicle);
        }
    }

    /**
     * 向跟踪该载具的客户端（含自身）广播骨骼模块失效状态。
     * 供本类与快速维修（{@code RVP_MaintenanceRuntimeManager}）在模块状态变化后统一调用：
     * 客户端 JS 动画（rvp_isEraActive / isModuleActive）与 HUD 据此恢复/隐藏渲染骨。
     */
    /** 累计进度差分缓存：UUID → 上次已推送快照（相同不发）。 */
    private static final Map<UUID, Map<String, Float>> LAST_DAMAGE_PROGRESS = new HashMap<>();

    /**
     * [RVP] 骨骼模块累计进度推送（2026-09-28 全模块累计化配套）：该载具全部骨累计快照
     * 差分发 {@code S2CBoneDamageProgress}（相同不发）——面板虚拟血量（阈值−已累计）消费。
     * 调用点：各累计段 + syncBoneModuleState（失效恢复全量补发）。
     */
    private static void syncBoneDamageProgress(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        java.util.UUID vehicleId = vehicle.getUUID();
        Map<String, Float> snapshot = RVP_BoneCumulativeDamageTable.snapshotOf(vehicleId);
        Map<String, Float> last = LAST_DAMAGE_PROGRESS.get(vehicleId);
        if (last != null && last.equals(snapshot)) {
            return;
        }
        LAST_DAMAGE_PROGRESS.put(vehicleId, new HashMap<>(snapshot));
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                org.ywzj.rvp.network.S2CBoneDamageProgress.create(vehicle.getId(), snapshot));
    }

    /** 载具离开世界：清累计进度差分缓存（实体 id 变化防陈旧推送）。 */
    public static void clearBoneDamageProgressCache(AbstractVehicle vehicle) {
        if (vehicle != null) {
            LAST_DAMAGE_PROGRESS.remove(vehicle.getUUID());
        }
    }

    /**
     * [RVP] 骨的累计失效阈值（面板虚拟血量分母）：bone_modules 骨 = 条目 min_damage；
     * 引擎骨 = 瘫痪阈（threshold_heavy）；炮管骨 = barrel threshold。未配置/∞ 返回 -1（无限）。
     */
    public float resolveBoneDamageThreshold(AbstractVehicle vehicle, String boneName) {
        if (vehicle == null || boneName == null) {
            return -1f;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return -1f;
        }
        BoneModuleConfig moduleConfig = cfg.moduleByBoneName.get(boneName);
        if (moduleConfig != null && Float.isFinite(moduleConfig.minTriggerDamage())) {
            return moduleConfig.minTriggerDamage();
        }
        BoneEngineConfig engine = resolveEngineConfig(vehicle, boneName);
        if (engine != null) {
            return engine.thresholdHeavy();
        }
        BoneBarrelConfig barrel = resolveBarrelConfig(vehicle, boneName);
        if (barrel != null) {
            return barrel.threshold();
        }
        return -1f;
    }

    public static void syncBoneModuleState(AbstractVehicle vehicle) {
        // [RVP] 累计进度全量补发（2026-09-28）：失效恢复（余弹回满场景）时客户端虚拟血量同步刷新
        syncBoneDamageProgress(vehicle);
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                S2CBoneModuleState.create(vehicle, RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID()))
        );
    }

    // ─────────────────────────────────────────────────────────────
    // 机制二：按爆炸半径百分比破坏 ERA（HE 高爆弹 + 附近爆炸）
    // ─────────────────────────────────────────────────────────────

    /**
     * 按爆炸半径查阈值表，百分比破坏载具骨块上的"参与爆炸破坏"的模块（默认仅 ERA；
     * 叠加骨块如 ERA+JAMMER 只破坏 ERA，保留 JAMMER 功能）。
     *
     * @param vehicle          目标载具
     * @param explosionRadius  爆炸配置半径
     * @param hitPos           直击命中位（非直击传 null）
     * @param isDirectHit      true = 直击（有至少1块保底），false = 附近爆炸（无保底/距离衰减）
     */
    public static void destroyModulesByExplosionRadius(
            AbstractVehicle vehicle,
            float explosionRadius,
            @Nullable Vec3 hitPos,
            boolean isDirectHit
    ) {
        destroyModulesByExplosionRadius(vehicle, explosionRadius, hitPos, isDirectHit, null);
    }

    /**
     * 爆炸波及骨骼模块百分比破坏（shooter = 爆炸弹射手，可空；用于部件战果通知）。
     */
    public static void destroyModulesByExplosionRadius(
            AbstractVehicle vehicle,
            float explosionRadius,
            @Nullable Vec3 hitPos,
            boolean isDirectHit,
            @Nullable Entity shooter
    ) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        VehicleHitboxConfig cfg = INSTANCE.configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return;
        }
        UUID vehicleId = vehicle.getUUID();
        // 收集"参与爆炸破坏"的候选骨块（骨块 → 其中参与爆炸破坏且仍激活的模块）
        List<String> activeBones = new ArrayList<>();
        for (String boneName : cfg.moduleByBoneName.keySet()) {
            boolean anyBlastActive = false;
            for (BoneModuleType type : cfg.moduleByBoneName.get(boneName).modules()) {
                if (type.participatesInBlastDestruction()
                        && RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, type)) {
                    anyBlastActive = true;
                    break;
                }
            }
            if (anyBlastActive) {
                activeBones.add(boneName);
            }
        }
        if (activeBones.isEmpty()) {
            return;
        }

        int totalActive = activeBones.size();
        int destroyCount = calcDestroyCount(explosionRadius, totalActive, isDirectHit);
        if (destroyCount <= 0) {
            return;
        }

        // 按位置排序或随机
        List<String> sorted;
        if (isDirectHit && hitPos != null) {
            sorted = sortBonesByDistance(cfg, vehicle, activeBones, hitPos);
        } else {
            sorted = new ArrayList<>(activeBones);
            java.util.Collections.shuffle(sorted);
        }

        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int destroyed = 0;
        for (int i = 0; i < Math.min(destroyCount, sorted.size()); i++) {
            String boneName = sorted.get(i);
            BoneModuleConfig eCfg = cfg.moduleByBoneName.get(boneName);
            boolean destroyedAny = false;
            for (BoneModuleType type : cfg.moduleByBoneName.get(boneName).modules()) {
                if (type.participatesInBlastDestruction()
                        && RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, type)) {
                    destroyedAny = true;
                    // [RVP] 部件战果通知：爆炸波及摧毁的部件同样推送给射手
                    notifyModuleHit(shooter, vehicle, S2CModuleHitNotify.KIND_MODULE_DESTROYED, type);
                }
            }
            if (destroyedAny) {
                float explosionScale = (eCfg != null && eCfg.explosion() > 0f) ? eCfg.explosion() : 1f;
                spawnEraEffect(serverLevel, vehicle, cfg, boneName, explosionScale);
                destroyed++;
            }
        }
        if (destroyed > 0) {
            INSTANCE.syncBoneModuleState(vehicle);
        }
    }

    /** 查阈值表决定破坏数量。 */
    private static int calcDestroyCount(float radius, int totalActive, boolean isDirectHit) {
        float percentage;
        if (radius <= 5f) {
            percentage = 0f;
        } else if (radius <= 8f) {
            percentage = 0.10f;
        } else if (radius <= 12f) {
            percentage = 0.25f;
        } else if (radius <= 18f) {
            percentage = 0.50f;
        } else {
            percentage = 1.0f; // 全毁
        }
        if (percentage <= 0f) {
            return 0;
        }
        int raw = (int) Math.floor(totalActive * percentage);
        if (isDirectHit && raw < 1) {
            raw = 1; // 直击至少 1 块保底
        }
        return Math.min(raw, totalActive);
    }

    /** 按每个 ERA bone 的 OBB 中心到 hitPos 的距离从小到大排序。 */
    private static List<String> sortBonesByDistance(
            VehicleHitboxConfig cfg, AbstractVehicle vehicle,
            List<String> boneNames, Vec3 hitPos
    ) {
        ResourceLocation structureId = cfg.structureModel;
        if (structureId == null) {
            return new ArrayList<>(boneNames);
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(structureId).orElse(null);
        if (model == null) {
            return new ArrayList<>(boneNames);
        }
        Map<String, BedrockBone> boneMap = model.getBoneMap();
        HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());

        Map<String, Double> distances = new HashMap<>();
        for (String name : boneNames) {
            List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, name, null);
            if (resolvedObbs.isEmpty()) {
                distances.put(name, Double.MAX_VALUE);
                continue;
            }
            double minDist = Double.MAX_VALUE;
            for (ResolvedObb resolvedObb : resolvedObbs) {
                Vector3f center = resolvedObb.obb().center();
                double d = new Vec3(center).distanceToSqr(hitPos);
                if (d < minDist) {
                    minDist = d;
                }
            }
            distances.put(name, minDist);
        }

        List<String> result = new ArrayList<>(boneNames);
        result.sort(Comparator.comparingDouble(distances::get));
        return result;
    }

    public String dumpResolveDebug(AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("vehicleId=").append(vehicle == null ? "<null>" : vehicle.getVehicleId()).append('\n');
        if (vehicle == null) {
            return sb.toString();
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            sb.append("config=<missing>\n");
            return sb.toString();
        }
        sb.append("structureModel=").append(cfg.structureModel).append('\n');
        sb.append("defaultFactor=").append(cfg.defaultFactor).append('\n');
        sb.append("loadedFactorKeys=").append(new ArrayList<>(cfg.factorByBoneName.keySet())).append('\n');
        for (var entry : cfg.factorByBoneName.entrySet()) {
            sb.append("factor[").append(entry.getKey()).append("]=").append(entry.getValue()).append('\n');
        }
        for (var entry : cfg.aliasByBoneName.entrySet()) {
            sb.append("alias[").append(entry.getKey()).append("]=").append(entry.getValue()).append('\n');
        }
        BedrockModel model = cfg.structureModel == null
                ? null
                : CommonAssetsManager.structureModelManager().getStructureModel(cfg.structureModel).orElse(null);
        if (model == null) {
            sb.append("model=<missing>\n");
            return sb.toString();
        }
        Map<String, BedrockBone> boneMap = model.getBoneMap();
        HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
        Set<String> allConfigBones = new LinkedHashSet<>();
        allConfigBones.addAll(cfg.factorByBoneName.keySet());
        allConfigBones.addAll(cfg.moduleByBoneName.keySet());
        for (String boneName : allConfigBones) {
            Optional<PartUnit<?>> partUnitOptional = vehicle.getPartUnit(boneName);
            int partUnitObbCount = partUnitOptional.map(partUnit -> partUnit.getOBBs().size()).orElse(0);
            BedrockBone bone = boneMap.get(boneName);
            int boneObbCount = bone == null ? 0 : OBB.getOBBsFromBone(bone, vehicle, namedBones).size();
            List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName, null);
            String source = resolvedObbs.isEmpty() ? "missing" : resolvedObbs.get(0).source();
            sb.append("bone=").append(boneName)
                    .append(" source=").append(source)
                    .append(" alias=").append(resolveHitboxDisplayName(vehicle, boneName))
                    .append(" resolvedObbs=").append(resolvedObbs.size())
                    .append(" partUnitPresent=").append(partUnitOptional.isPresent())
                    .append(" partUnitObbs=").append(partUnitObbCount)
                    .append(" boneExists=").append(bone != null)
                    .append(" boneObbs=").append(boneObbCount)
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<ResolvedObb> resolveBoneObbs(
            AbstractVehicle vehicle,
            Map<String, BedrockBone> boneMap,
            HashSet<BedrockBone> namedBones,
            String boneName,
            @Nullable Set<String> configBones
    ) {
        // [RVP] 武器站按组细分（2026-09-28 命中修复）：炮管骨/站骨从归属 WeaponUnit 的
        // 实时 OBB 按组拆分——原实现对炮管骨（structureBone+"_barrel"，无同名 PartUnit）
        // 走 bone_fallback（静态 bind-pose），炮塔转动后残留"初始朝前位置"的固定命中盒；
        // 且炮管组实时 OBB 整包归入站骨（炮塔）骨名。见 {@link #resolveWeaponUnitSplitObbs}。
        List<ResolvedObb> weaponUnitSplit = resolveWeaponUnitSplitObbs(vehicle, boneName, configBones);
        if (weaponUnitSplit != null) {
            return weaponUnitSplit;
        }
        Optional<PartUnit<?>> partUnitOptional = vehicle.getPartUnit(boneName);
        if (partUnitOptional.isPresent()) {
            List<OBB> partUnitObbs = partUnitOptional.get().getOBBs();
            if (partUnitObbs != null && !partUnitObbs.isEmpty()) {
                List<ResolvedObb> resolved = new ArrayList<>(partUnitObbs.size());
                for (OBB obb : partUnitObbs) {
                    resolved.add(new ResolvedObb(obb, "part_unit"));
                }
                return resolved;
            }
        }
        // [RVP] 部件 structureBone 匹配（2026-09-28）：部件 id ≠ 骨名的部件（如烟幕发射器
        // 部件 turret_smoke_grenade_l 的 structure_bone = turret_smoke_grenade_l_barrel）——
        // 直接走 bone_fallback 会得到静态 bind-pose 残留命中盒（§29 同款问题），此处优先
        // 用该部件的实时 OBB（每 tick 随炮塔/车体更新）
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit.getData() != null && boneName.equals(partUnit.getData().getStructureBone())) {
                List<OBB> structureObbs = partUnit.getOBBs();
                if (structureObbs != null && !structureObbs.isEmpty()) {
                    List<ResolvedObb> resolved = new ArrayList<>(structureObbs.size());
                    for (OBB obb : structureObbs) {
                        resolved.add(new ResolvedObb(obb, "part_structure"));
                    }
                    return resolved;
                }
            }
        }
        BedrockBone bone = boneMap.get(boneName);
        if (bone == null) {
            return List.of();
        }
        List<ResolvedObb> resolved = new ArrayList<>();
        for (OBB.CubeOBB cubeObb : OBB.getOBBsFromBone(bone, vehicle, namedBones)) {
            resolved.add(new ResolvedObb(cubeObb.obb(), "bone_fallback"));
        }
        return resolved;
    }

    /**
     * [RVP] 武器站按组细分 OBB 源（2026-09-28 命中修复，零 Mixin）。
     *
     * <p>本体 {@code WeaponUnitData.initStructureModel} 只把<b>炮塔组</b>（structureBone 骨，
     * yTurnGroup）与<b>炮管组</b>（structureBone+"_barrel" 骨，xTurnGroup）两组 cube 塞入
     * WeaponUnit 的 {@code partCubeOBBs}，两组实时跟随炮塔 yaw/炮管 pitch（每 tick
     * {@code updateOBBs} 刷新）。遍历 WeaponUnit：骨名匹配炮管骨 → 返回炮管组 OBB；
     * 匹配站骨 → 返回<b>非</b>炮管组 OBB（仅当炮管骨也是配置骨时才拆分，否则维持炮管组
     * 归站骨的旧行为保证兼容）。{@code partCubeOBBs} 经 {@code VehicleCubeOBB.update} 的
     * {@code group.globalTransform()} 吃到组旋转——实时正确。</p>
     *
     * <p>组判定零 Mixin：{@code PartUnit.getStructureGroup()} 公共、
     * {@code VehicleCubeOBB.group}/{@code VehicleCubeGroup.parent} 公共字段——
     * cube 的 group 沿 parent 链可达 structureGroup 且不等于它，即为炮管俯仰组。</p>
     *
     * @return 匹配武器站时返回细分 OBB；未匹配任何武器站（Engine 等非武器骨，其骨不随
     *         炮塔转、静态 fallback 正确）返回 null 走原逻辑
     */
    private static @Nullable List<ResolvedObb> resolveWeaponUnitSplitObbs(
            AbstractVehicle vehicle, String boneName, @Nullable Set<String> configBones) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            String structureBone = weaponUnit.getData() == null
                    ? null : weaponUnit.getData().getStructureBone();
            if (structureBone == null || structureBone.isBlank()) {
                continue;
            }
            String barrelBone = structureBone + "_barrel";
            boolean wantBarrel = boneName.equals(barrelBone);
            if (!wantBarrel && !boneName.equals(structureBone)) {
                continue;
            }
            if (!wantBarrel && (configBones == null || !configBones.contains(barrelBone))) {
                // 站骨且炮管骨未配置为独立命中骨：炮管组维持归站骨（旧行为），走原 part_unit 全量
                return null;
            }
            List<ResolvedObb> out = new ArrayList<>();
            for (VehicleCubeOBB cube : weaponUnit.getPartCubeOBBs()) {
                if (wantBarrel == isBarrelGroupCube(cube, weaponUnit)) {
                    out.add(new ResolvedObb(cube.obb(),
                            wantBarrel ? "weapon_unit_barrel" : "weapon_unit_structure"));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
            // 该站无对应组 cube（异常结构）：继续尝试其它站 / 走原逻辑
        }
        return null;
    }

    /**
     * cube 是否属于武器站炮管组（xTurnGroup 及其子组）：group 沿 parent 链可达 structureGroup
     * 且非其本身。公共：俯视图（{@code RVP_EquipSkeletonRenderer}）按组拆分炮管线框复用。
     */
    public static boolean isBarrelGroupCube(VehicleCubeOBB cube, WeaponUnit weaponUnit) {
        VehicleCubeGroup group = cube.group;
        VehicleCubeGroup structureGroup = weaponUnit.getStructureGroup();
        if (group == null || structureGroup == null || group == structureGroup) {
            return false;
        }
        for (VehicleCubeGroup g = group.parent; g != null; g = g.parent) {
            if (g == structureGroup) {
                return true;
            }
        }
        return false;
    }

    /**
     * [RVP] 骨名 → 实时命中 OBB 列表（客户端部件损坏冒烟取样公共入口，2026-09-28）。
     *
     * <p>与命中判定同源：首选部件实例实时 OBB（{@code PartUnit.getOBBs()}，本体
     * {@code AbstractVehicle.tick → updateOBBs()} 每 tick 随载具旋转/位移更新，双端有效）；
     * 无部件实例的骨（如 Engine 结构骨）回退 {@link OBB#getOBBsFromBone}——namedBones 传
     * 全部具名骨，即<b>仅该骨自身 cubes + 匿名子骨</b>（不含具名子骨骼的 OBB，用户定版），
     * 且叠加 {@code vehicle.rotYXZ()/position()} 跟随车体实时变换（骨姿势为静态，车体变换实时）。
     * 无任何配置/模型时返回空列表（调用方跳过冒烟）。</p>
     */
    public List<OBB> resolveBoneObbsForSampling(AbstractVehicle vehicle, String boneName) {
        if (vehicle == null || boneName == null) {
            return List.of();
        }
        // 无 RVP 命中配置的载具：仍可尝试部件实例 OBB（部件 id=骨名时可用）
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return vehicle.getPartUnit(boneName)
                    .map(partUnit -> partUnit.getOBBs() == null ? List.<OBB>of() : partUnit.getOBBs())
                    .orElse(List.of());
        }
        BedrockModel model = cfg.structureModel == null
                ? null
                : CommonAssetsManager.structureModelManager().getStructureModel(cfg.structureModel).orElse(null);
        if (model == null) {
            // 无结构模型：部件实例 OBB 兜底
            return vehicle.getPartUnit(boneName)
                    .map(partUnit -> partUnit.getOBBs() == null ? List.<OBB>of() : partUnit.getOBBs())
                    .orElse(List.of());
        }
        Map<String, BedrockBone> boneMap = model.getBoneMap();
        HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
        // [RVP] 冒烟骨都来自 bone_modules 配置键——传配置键集合启用武器站按组细分
        // （炮管骨 → 实时炮管组 OBB，冒烟取样点随炮塔/炮管转动正确）
        java.util.Set<String> samplingBones = new java.util.HashSet<>();
        if (cfg.moduleByBoneName != null) {
            samplingBones.addAll(cfg.moduleByBoneName.keySet());
        }
        if (cfg.factorByBoneName != null) {
            samplingBones.addAll(cfg.factorByBoneName.keySet());
        }
        List<ResolvedObb> resolved = resolveBoneObbs(vehicle, boneMap, namedBones, boneName, samplingBones);
        List<OBB> result = new ArrayList<>(resolved.size());
        for (ResolvedObb resolvedObb : resolved) {
            result.add(resolvedObb.obb());
        }
        return result;
    }

    /**
     * 播放 ERA 模块爆炸特效（2026-09-19 用户定版：MCHR 爆炸特效，烟雾规模对齐
     * 武器爆炸半径 3 × 模块 {@code explosion} 缩放）。
     *
     * <p>特效位置优先取失效骨块的 OBB 中心（比旧实现的车体包围盒中心更符合
     * "这一块 ERA 爆了"的观感，多块连爆也不在同一点叠加）；结构模型不可解析时
     * 回退车体中心。音效由 {@code rvp:mchr_explosion} 事件在客户端按声速延迟播放
     * 近/远音（MISSILE 战斗部音色），服务端不再补播 {@code GENERIC_EXPLODE}。</p>
     */
    private static void spawnEraEffect(ServerLevel serverLevel, AbstractVehicle vehicle,
                                        VehicleHitboxConfig cfg, String boneName, float explosionScale) {
        spawnMchrEraExplosion(serverLevel, resolveBoneEffectPos(vehicle, cfg, boneName), explosionScale);
    }

    /** 解析失效骨块 OBB 的世界系中心（复用 {@link #resolveBoneObbs}）；不可解析时回退车体包围盒中心。 */
    private static Vec3 resolveBoneEffectPos(AbstractVehicle vehicle, VehicleHitboxConfig cfg, String boneName) {
        ResourceLocation structureId = cfg.structureModel;
        if (structureId != null) {
            BedrockModel model = CommonAssetsManager.structureModelManager()
                    .getStructureModel(structureId).orElse(null);
            if (model != null) {
                Map<String, BedrockBone> boneMap = model.getBoneMap();
                HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
                List<ResolvedObb> resolved = resolveBoneObbs(vehicle, boneMap, namedBones, boneName, null);
                if (!resolved.isEmpty()) {
                    Vector3f center = resolved.get(0).obb().center();
                    return new Vec3(center.x(), center.y(), center.z());
                }
            }
        }
        return vehicle.getBoundingBox().getCenter();
    }

    /**
     * ERA 爆炸统一发布 MCHR 爆炸视觉事件：烟雾规模 = 武器爆炸半径 3 × 模块
     * {@code explosion} 缩放（未配置即 3，与武器 JSON {@code explosion_radius: 3} 的
     * 爆炸烟雾同规模）；MISSILE 音色（战斗部爆轰观感）。
     */
    private static void spawnMchrEraExplosion(ServerLevel serverLevel, Vec3 pos, float explosionScale) {
        // ERA 模块爆炸无武器爆炸配置，自定义音效传 null 按MISSILE 默认音色。
        RVP_DefaultExplosionVisualService.spawn(serverLevel, pos,
                3.0f * Math.max(explosionScale, 0.25f), RVP_EnumWeaponKind.MISSILE, null);
    }

    private static String fmt(float v) {
        if (!Float.isFinite(v)) {
            return "NaN";
        }
        return String.format("%.2f", v);
    }

    public record HitboxDamageResult(
            boolean enabled,
            float factor,
            float defaultFactor,
            @Nullable String hitBoneName,
            @Nullable ResourceLocation structureModel,
            int missingConfigBones,
            double hitDistance,
            @Nullable Vec3 hitPoint,
            Set<BoneModuleType> modules,
            Set<BoneModuleType> destroyedModules,
            float minTriggerDamage,
            float explosion
    ) {
        public static HitboxDamageResult disabled() {
            return new HitboxDamageResult(false, 1f, 1f, null, null, 0, Double.NaN,
                    null, Set.of(), Set.of(), Float.POSITIVE_INFINITY, 0f);
        }

        public static HitboxDamageResult defaulted(float defaultFactor, @Nullable ResourceLocation structureModel,
                                                   int missingBones, double hitDistance) {
            float def = Float.isFinite(defaultFactor) ? defaultFactor : 1f;
            return new HitboxDamageResult(true, Math.max(0f, def), def, null, structureModel,
                    missingBones, hitDistance, null, Set.of(), Set.of(), Float.POSITIVE_INFINITY, 0f);
        }

        /** 命中骨块是否挂有模块（ERA/TRACK/JAMMER 等）。 */
        public boolean hasModules() {
            return !modules.isEmpty();
        }

        /** 命中骨块是否仍存在"可被弹药击毁"的激活模块。 */
        public boolean hasActiveModules() {
            return modules.size() > destroyedModules.size();
        }

        /**
         * 骨块是否已失去对后续弹药的阻挡（ERA 模块失效 → 穿透）。
         * 由 resolve 候选循环用 passThrough() 判定，其余模块失效不穿透。
         */
        public boolean passThrough() {
            return destroyedModules.contains(BoneModuleType.ERA);
        }

        /** 兼容旧语义：该骨块是否包含 ERA 模块。 */
        public boolean era() {
            return modules.contains(BoneModuleType.ERA);
        }

        /** 触发阈值判定：有可击毁的激活模块且伤害超过阈值。 */
        public boolean shouldTriggerModules(float triggerDamage) {
            return hasActiveModules() && Float.isFinite(triggerDamage) && triggerDamage > minTriggerDamage;
        }
    }

    private record VehicleHitboxConfig(
            @Nullable ResourceLocation structureModel,
            float defaultFactor,
            Map<String, Float> factorByBoneName,
            Map<String, Float> vehicleExplosionFactorByBoneName,
            float vehicleExplosionFactorDefault,
            Map<String, BoneModuleConfig> moduleByBoneName,
            Map<String, String> aliasByBoneName,
            Map<String, String> aliasCnByBoneName,
            float coreDistanceScaleMultiplier,
            float armorMinDamage,
            float armorMaxDamage,
            boolean hitIndicatorRvp
    ) {
        boolean isEnabled() {
            return defaultFactor != 1f
                    || (factorByBoneName != null && !factorByBoneName.isEmpty())
                    || (vehicleExplosionFactorByBoneName != null && !vehicleExplosionFactorByBoneName.isEmpty())
                    || vehicleExplosionFactorDefault != 1f
                    || (aliasByBoneName != null && !aliasByBoneName.isEmpty())
                    || (moduleByBoneName != null && !moduleByBoneName.isEmpty());
        }

        HitboxDamageResult resolve(BedrockModel model, AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
            return resolve(model, vehicle, segmentStart, segmentEnd, false);
        }

        /**
         * useVehicleExplosionFactor=true：倍率取爆炸伤害倍率（{@code vehicle_explosion_damage_factor}，
         * 供爆炸伤害乘算，2026-09-20 拆分）；false：原直击命中箱倍率。
         */
        HitboxDamageResult resolve(BedrockModel model, AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd,
                boolean useVehicleExplosionFactor) {
            if ((factorByBoneName == null || factorByBoneName.isEmpty())
                    && (moduleByBoneName == null || moduleByBoneName.isEmpty())
                    && (!useVehicleExplosionFactor
                        || vehicleExplosionFactorByBoneName == null || vehicleExplosionFactorByBoneName.isEmpty())) {
                return HitboxDamageResult.defaulted(
                        useVehicleExplosionFactor ? vehicleExplosionFactorDefault : defaultFactor,
                        structureModel, 0, Double.NaN);
            }
            if (!RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(vehicle).isEmpty()
                    && RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(vehicle, segmentStart, segmentEnd) == null
                    && RVP_PhysicsOnlyCollisionHelper.closestPhysicsOnlyHitPosition(vehicle, segmentStart, segmentEnd) != null) {
                return HitboxDamageResult.disabled();
            }
            Vector3f from = segmentStart.toVector3f();
            Vector3f to = segmentEnd.toVector3f();
            Map<String, BedrockBone> boneMap = model.getBoneMap();
            HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
            int missingBones = 0;

            List<HitCandidate> candidates = new ArrayList<>();
            Set<String> allConfigBones = new LinkedHashSet<>();
            allConfigBones.addAll(factorByBoneName.keySet());
            if (useVehicleExplosionFactor && vehicleExplosionFactorByBoneName != null) {
                allConfigBones.addAll(vehicleExplosionFactorByBoneName.keySet());
            }
            allConfigBones.addAll(moduleByBoneName.keySet());

            for (String boneName : allConfigBones) {
                BoneModuleConfig moduleConfig = moduleByBoneName.get(boneName);
                float factor;
                if (useVehicleExplosionFactor) {
                    // 爆炸伤害倍率：per-bone vehicle_explosion_damage_factor → 模块骨共用骨倍率 → 默认
                    factor = vehicleExplosionFactorByBoneName != null
                            ? vehicleExplosionFactorByBoneName.getOrDefault(boneName, vehicleExplosionFactorDefault)
                            : vehicleExplosionFactorDefault;
                } else {
                    // [RVP] 2026-09-27 倍率统合：直击倍率唯一来源 = hitbox_damage_factor。
                    // bone_modules 条目不再携带 damage_factor（模块骨的倍率也统一写顶层映射），
                    // 根除"条目值静默覆盖顶层倍率"的双配置陷阱
                    factor = factorByBoneName.getOrDefault(boneName, defaultFactor);
                }
                List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName, allConfigBones);
                if (resolvedObbs.isEmpty()) {
                    if (boneMap.get(boneName) == null && vehicle.getPartUnit(boneName).isEmpty()) {
                        missingBones++;
                    }
                    continue;
                }
                for (ResolvedObb resolvedObb : resolvedObbs) {
                    Vector3f hit = resolvedObb.obb().clip(from, to).orElse(null);
                    if (hit == null) {
                        continue;
                    }
                    Vec3 hitPoint = new Vec3(hit);
                    double distance = hitPoint.distanceTo(segmentStart);
                    candidates.add(new HitCandidate(
                            distance,
                            hitPoint,
                            boneName,
                            factor,
                            moduleConfig,
                            vehicle.getUUID()
                    ));
                }
            }

            if (candidates.isEmpty()) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
            }
            candidates.sort(Comparator.comparingDouble(HitCandidate::distance));
            for (HitCandidate candidate : candidates) {
                if (candidate.passThrough()) {
                    // ERA 模块失效 → 该骨块不再阻拦弹药（穿透）；其余模块失效不穿透
                    continue;
                }
                float factor = Float.isFinite(candidate.factor()) ? candidate.factor() : defaultFactor;
                BoneModuleConfig moduleConfig = candidate.moduleConfig();
                if (moduleConfig != null && moduleConfig.hasModules()) {
                    Set<BoneModuleType> destroyed = candidate.destroyedModules();
                    return new HitboxDamageResult(
                            true,
                            Math.max(0f, factor),
                            defaultFactor,
                            candidate.boneName(),
                            structureModel,
                            missingBones,
                            candidate.distance(),
                            candidate.hitPoint(),
                            moduleConfig.modules(),
                            destroyed,
                            moduleConfig.minTriggerDamage(),
                            moduleConfig.explosion()
                    );
                }
                return new HitboxDamageResult(
                        true,
                        Math.max(0f, factor),
                        defaultFactor,
                        candidate.boneName(),
                        structureModel,
                        missingBones,
                        candidate.distance(),
                        candidate.hitPoint(),
                        Set.of(),
                        Set.of(),
                        Float.POSITIVE_INFINITY,
                        0f
                );
            }
            return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
        }

        static @Nullable VehicleHitboxConfig parse(JsonObject obj) {
            ResourceLocation structure = parseId(GsonHelper.getAsString(obj, "structure_model", null));
            float def = GsonHelper.getAsFloat(obj, "hitbox_damage_factor_default", 1f);
            Map<String, Float> map = parseFactorMap(obj.get("hitbox_damage_factor"));
            // 爆炸伤害倍率（2026-09-20 拆分）：与直击 hitbox_damage_factor 同构，独立配置
            float explosionDef = GsonHelper.getAsFloat(obj, "vehicle_vehicle_explosion_damage_factor_default", 1f);
            Map<String, Float> explosionMap = parseFactorMap(obj.get("vehicle_explosion_damage_factor"));
            // 新配置 bone_modules：骨骼模块体系（模块/门槛/爆炸档位/设备子对象）；
            // 命中倍率统一由 hitbox_damage_factor 提供（2026-09-27 统合，条目不再带 damage_factor）
            Map<String, BoneModuleConfig> moduleMap = parseBoneModuleMap(obj.get("bone_modules"));
            Map<String, String> aliasMap = parseAliasMap(obj.get("hitbox_display_name"));
            // 中文名映射（可选，2026-09-28）：与 hitbox_display_name 同构，缺省 null=未配置
            Map<String, String> aliasCnMap = parseAliasMap(obj.get("hitbox_display_name_CN"));
            float coreM = GsonHelper.getAsFloat(obj, "core_distance_scale_multiplier", 1f);
            // 装甲：armor_min_damage 与本体 damage_threshold 互斥（同时存在仅装甲生效）；
            // armor_max_damage 封在全部系数算完之后（最终伤害上限）。<=0 均视为未配置。
            float armorMin = Math.max(0f, GsonHelper.getAsFloat(obj, "armor_min_damage", 0f));
            float armorMax = Math.max(0f, GsonHelper.getAsFloat(obj, "armor_max_damage", 0f));
            boolean hitIndicatorRvp = GsonHelper.getAsBoolean(obj, "hit_indicator_rvp", true);
            // 顶层无骨骼的 ecm_active（无骨骼ECM）：直接挂到虚拟骨骼 __vehicle__，始终存活
            BoneEcmActiveConfig vehicleEcmActive = BoneEcmActiveConfig.parse(obj.get("ecm_active"));
            if (vehicleEcmActive != null) {
                if (moduleMap == null) {
                    moduleMap = new HashMap<>();
                }
                java.util.Set<BoneModuleType> modules = java.util.EnumSet.of(BoneModuleType.ECM_ACTIVE);
                BoneModuleConfig synthetic = new BoneModuleConfig(Float.POSITIVE_INFINITY, 0f, modules,
                        null, null, null, null, vehicleEcmActive, null, null, null, true);
                moduleMap.put("__vehicle__", synthetic);
            }
            // 顶层无骨骼的 maintenance（无骨骼快速维修）：同款挂到虚拟骨骼 __vehicle__，始终存活；
            // 规范写法是 bone_modules.__vehicle__.maintenance（MAINTENANCE 模块），顶层块为别名
            BoneMaintenanceConfig vehicleMaintenance = BoneMaintenanceConfig.parse(obj.get("maintenance"));
            if (vehicleMaintenance != null && (moduleMap == null || !moduleMap.containsKey("__vehicle__"))) {
                if (moduleMap == null) {
                    moduleMap = new HashMap<>();
                }
                java.util.Set<BoneModuleType> modules = java.util.EnumSet.of(BoneModuleType.MAINTENANCE);
                BoneModuleConfig synthetic = new BoneModuleConfig(Float.POSITIVE_INFINITY, 0f, modules,
                        null, null, null, null, null, vehicleMaintenance, null, null, true);
                moduleMap.put("__vehicle__", synthetic);
            }
            if ((map == null || map.isEmpty())
                    && (moduleMap == null || moduleMap.isEmpty())
                    && (aliasMap == null || aliasMap.isEmpty())
                    && (aliasCnMap == null || aliasCnMap.isEmpty())
                    && def == 1f
                    && (explosionMap == null || explosionMap.isEmpty())
                    && explosionDef == 1f
                    && coreM == 1f
                    && armorMin <= 0f
                    && armorMax <= 0f
                    && hitIndicatorRvp) {
                return null;
            }
            return new VehicleHitboxConfig(
                    structure,
                    def,
                    map == null ? Map.of() : Map.copyOf(map),
                    explosionMap == null ? Map.of() : Map.copyOf(explosionMap),
                    explosionDef,
                    moduleMap == null ? Map.of() : Map.copyOf(moduleMap),
                    aliasMap == null ? Map.of() : Map.copyOf(aliasMap),
                    aliasCnMap == null ? Map.of() : Map.copyOf(aliasCnMap),
                    coreM,
                    armorMin,
                    armorMax,
                    hitIndicatorRvp
            );
        }

        private static @Nullable ResourceLocation parseId(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String s = raw.trim();
            if (!s.contains(":")) {
                s = RVP_MOD.MOD_ID + ":" + s;
            }
            return ResourceLocation.tryParse(s);
        }

        private static @Nullable Map<String, Float> parseFactorMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, Float> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                Optional<Float> val = tryFloat(entry.getValue());
                if (val.isPresent()) {
                    map.put(key, val.get());
                }
            }
            return map;
        }

        private static @Nullable Map<String, String> parseAliasMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String alias = entry.getValue().getAsString();
                if (alias == null) {
                    continue;
                }
                alias = alias.trim();
                if (!alias.isEmpty()) {
                    map.put(key, alias);
                }
            }
            return map;
        }

        /** 解析新配置 {@code bone_modules}：每个骨块可挂多个模块（era/track/jammer 叠加）。 */
        private static @Nullable Map<String, BoneModuleConfig> parseBoneModuleMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, BoneModuleConfig> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                BoneModuleConfig config = BoneModuleConfig.parse(entry.getValue());
                if (config != null && config.hasModules()) {
                    map.put(key, config);
                }
            }
            return map.isEmpty() ? null : map;
        }

        private static @Nullable String normalizeBone(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static Optional<Float> tryFloat(@Nullable JsonElement element) {
            if (element == null) {
                return Optional.empty();
            }
            try {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    return Optional.of(element.getAsFloat());
                }
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String s = element.getAsString();
                    if (s == null || s.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(Float.parseFloat(s.trim()));
                }
            } catch (Exception ignored) {
            }
            return Optional.empty();
        }
    }

    private record HitCandidate(
            double distance,
            Vec3 hitPoint,
            String boneName,
            float factor,
            @Nullable BoneModuleConfig moduleConfig,
            UUID vehicleId
    ) {
        /** 骨块是否已失去对后续弹药的阻挡（ERA 模块失效 → 穿透）。 */
        boolean passThrough() {
            return moduleConfig != null
                    && moduleConfig.modules().contains(BoneModuleType.ERA)
                    && INSTANCE.isModuleDestroyed(vehicleId, boneName, BoneModuleType.ERA);
        }

        /** 该骨块已失效的模块集合（用于结果携带，供 tryDestroyBoneModules 判定剩余激活模块）。 */
        Set<BoneModuleType> destroyedModules() {
            if (moduleConfig == null) {
                return Set.of();
            }
            Set<BoneModuleType> destroyed = java.util.EnumSet.noneOf(BoneModuleType.class);
            for (BoneModuleType type : moduleConfig.modules()) {
                if (INSTANCE.isModuleDestroyed(vehicleId, boneName, type)) {
                    destroyed.add(type);
                }
            }
            return destroyed;
        }
    }

    /**
     * 单块骨块的模块配置：伤害倍率 + 触发阈值 + ERA 特效 + 模块集合。
     * 一个骨块可挂多个模块（如 {@code ["era","jammer"]} 叠加），各模块独立失效。
     */
    private record BoneModuleConfig(
            float minTriggerDamage,
            float explosion,
            Set<BoneModuleType> modules,
            @Nullable BoneJammerConfig jammer,
            @Nullable BoneApsConfig aps,
            @Nullable BoneDircmConfig dircm,
            @Nullable BoneEcmPassiveConfig ecmPassive,
            @Nullable BoneEcmActiveConfig ecmActive,
            @Nullable BoneMaintenanceConfig maintenance,
            @Nullable BoneEngineConfig engine,
            @Nullable BoneBarrelConfig barrel,
            boolean smoke
    ) {
        boolean hasModules() {
            return modules != null && !modules.isEmpty();
        }

        /**
         * 新配置 {@code bone_modules} 条目：模块集合 + 失效门槛 + ERA 爆炸档位 + 设备子对象。
         * ★命中倍率不在条目内（2026-09-27 统合）——统一由顶层 {@code hitbox_damage_factor} 提供；
         * 显式 modules 数组缺省视为 [ERA]（兼容纯爆反骨简写）。
         * {@code smoke} 为部件失效冒烟开关（缺省 true，用户 2026-09-28 定版按部件选配）。
         */
        static @Nullable BoneModuleConfig parse(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            float minTriggerDamage = parseMinTriggerDamage(obj);
            float explosion = GsonHelper.getAsFloat(obj, "explosion", 0f);
            Set<BoneModuleType> modules = parseModules(obj.get("modules"));
            if (modules == null || modules.isEmpty()) {
                modules = java.util.EnumSet.noneOf(BoneModuleType.class);
                modules.add(BoneModuleType.ERA);
            }
            if (!Float.isFinite(minTriggerDamage) || minTriggerDamage < 0f) {
                minTriggerDamage = Float.POSITIVE_INFINITY;
            }
            if (!Float.isFinite(explosion) || explosion < 0f) {
                explosion = 0f;
            }
            return new BoneModuleConfig(minTriggerDamage, explosion, modules,
                    BoneJammerConfig.parse(obj.get("jammer")), BoneApsConfig.parse(obj.get("aps")),
                    BoneDircmConfig.parse(obj.get("dircm")),
                    BoneEcmPassiveConfig.parse(obj.get("ecm_passive")),
                    BoneEcmActiveConfig.parse(obj.get("ecm_active")),
                    BoneMaintenanceConfig.parse(obj.get("maintenance")),
                    BoneEngineConfig.parse(obj.get("engine")),
                    BoneBarrelConfig.parse(obj.get("barrel")),
                    GsonHelper.getAsBoolean(obj, "smoke", true));
        }

        /** 通用触发阈值：优先 {@code min_damage}（新通用字段），回退 {@code min_trigger_damage}（旧 ERA 字段）。 */
        private static float parseMinTriggerDamage(JsonObject obj) {
            if (obj.has("min_damage")) {
                return GsonHelper.getAsFloat(obj, "min_damage", Float.POSITIVE_INFINITY);
            }
            return GsonHelper.getAsFloat(obj, "min_trigger_damage", Float.POSITIVE_INFINITY);
        }

        private static @Nullable Set<BoneModuleType> parseModules(@Nullable JsonElement element) {
            if (element == null || !element.isJsonArray()) {
                return null;
            }
            Set<BoneModuleType> modules = java.util.EnumSet.noneOf(BoneModuleType.class);
            for (JsonElement item : element.getAsJsonArray()) {
                if (item == null || !item.isJsonPrimitive()) {
                    continue;
                }
                BoneModuleType type = BoneModuleType.byName(item.getAsString());
                if (type != null) {
                    modules.add(type);
                }
            }
            return modules;
        }
    }

    private static final class ResolvedObb {
        private final OBB obb;
        private final String source;

        private ResolvedObb(OBB obb, String source) {
            this.obb = obb;
            this.source = source;
        }

        private OBB obb() {
            return obb;
        }

        private String source() {
            return source;
        }
    }
}
