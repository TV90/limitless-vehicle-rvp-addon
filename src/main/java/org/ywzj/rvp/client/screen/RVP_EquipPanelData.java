package org.ywzj.rvp.client.screen;

import net.minecraft.client.resources.language.I18n;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.client.state.RVP_ClientEngineDamageState;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureData;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureSystemData;
import org.ywzj.rvp.vehicle.BoneApsConfig;
import org.ywzj.rvp.vehicle.BoneDircmConfig;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneEngineConfig;
import org.ywzj.rvp.vehicle.BoneJammerConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 辅助设备面板数据聚合（纯客户端，无状态）：把载具的骨模块配置（服务端随
 * {@code S2CVehicleRvpConfig} 下发的全量 JSON，客户端已缓存）与各运行时 HUD 快照
 * 组装成面板栏目模型。零新增网络包——全部读现有客户端可见数据。
 *
 * <p>栏目按"无设备不画"门控：载具没配置的设备类型不生成栏目。行序号 = 该部件在
 * 载具数据中的出现顺序（以 {@code vehicle.getPartUnits()} 为准——它严格等于载具 JSON
 * {@code parts[]} 数组顺序；骨模块配置底层为 HashMap 不保序，故以部件顺序为锚）。
 * 队列路由：ERA 失效行入"爆反维修顺序"，其余骨模块设备失效行入"辅助设备维修顺序"；
 * 干扰物与部件血量行不可入队（前者无骨模块语义，后者由快修回血覆盖）。</p>
 */
public final class RVP_EquipPanelData {

    /** 队列路由键：爆反维修顺序。 */
    public static final String QUEUE_ERA = "era";
    /** 队列路由键：辅助设备维修顺序。 */
    public static final String QUEUE_DEV = "dev";

    /**
     * 栏目行：序号（载具数据顺序，1 起）/ 别名（无别名回退骨名）/ 是否生效 / 骨名 /
     * 队列路由（null=不可入队）/ 行尾附加文本（可空，如干扰物余弹）。
     */
    public record Row(int index, String alias, boolean active, String boneName, String queueKey, String extra) {
    }

    /** 栏目：标题（已本地化）/ 汇总行（已本地化）/ 行列表。 */
    public record Category(String title, String summary, List<Row> rows) {
    }

    private RVP_EquipPanelData() {
    }

    /** 组装全部栏目（仅生成载具实际配置了的设备类型栏目）。 */
    public static List<Category> buildCategories(AbstractVehicle vehicle) {
        List<Category> out = new ArrayList<>();
        int entityId = vehicle.getId();
        Map<String, Set<BoneModuleType>> modules =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        List<String> orderedBones = orderedBoneNames(vehicle, modules.keySet());

        // ERA 爆反装甲：骨模块含 ERA 的骨；失效状态即爆反块被打掉。
        // 双角色骨（ERA+设备同骨，如 t84bm）标注"兼辅助设备"，维修归入爆反配额捆绑恢复
        List<Row> eraRows = new ArrayList<>();
        int eraBad = 0;
        int index = 1;
        for (String bone : orderedBones) {
            Set<BoneModuleType> types = modules.get(bone);
            if (types == null || !types.contains(BoneModuleType.ERA)) {
                continue;
            }
            boolean active = RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.ERA);
            eraBad += active ? 0 : 1;
            String extra = hasDeviceRole(types) ? I18n.get("gui.ywzj_rvp.equipment.note_dual") : null;
            eraRows.add(new Row(index++, alias(vehicle, bone), active, bone, QUEUE_ERA, extra));
        }
        if (!eraRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_era"), summary(eraRows.size(), eraBad), eraRows));
        }

        // APS 主动防护：传感器骨失效 = 该扫描扇区失去拦截
        Map<String, BoneApsConfig> apsDevices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveApsDevices(vehicle);
        List<Row> apsRows = typeRows(vehicle, orderedBones, modules, apsDevices, BoneModuleType.APS, entityId);
        if (!apsRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_aps"), summary(apsRows), apsRows));
        }

        // ECM 主动干扰
        Map<String, BoneEcmActiveConfig> ecmDevices =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        List<Row> ecmRows = typeRows(vehicle, orderedBones, modules, ecmDevices, BoneModuleType.ECM_ACTIVE, entityId);
        if (!ecmRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_ecm_active"), summary(ecmRows), ecmRows));
        }

        // 软杀伤干扰机
        Map<String, BoneJammerConfig> jammerDevices =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveJammerDevices(vehicle);
        List<Row> jammerRows = typeRows(vehicle, orderedBones, modules, jammerDevices, BoneModuleType.JAMMER, entityId);
        if (!jammerRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_jammer"), summary(jammerRows), jammerRows));
        }

        // DIRCM 定向红外对抗
        Map<String, BoneDircmConfig> dircmDevices =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveDircmDevices(vehicle);
        List<Row> dircmRows = typeRows(vehicle, orderedBones, modules, dircmDevices, BoneModuleType.DIRCM, entityId);
        if (!dircmRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_dircm"), summary(dircmRows), dircmRows));
        }

        // [RVP] 雷达部件（2026-09-26）：骨模块含 RADAR 的骨（= 雷达 PartUnit id）；失效即该雷达
        // 被服务端强制关闭（多雷达载具按雷达骨分粒度）。失效行走辅助设备维修队列（修好自动开机）
        Map<String, Set<BoneModuleType>> radarBones = bonesOfType(modules, BoneModuleType.RADAR);
        List<Row> radarRows = typeRows(vehicle, orderedBones, modules, radarBones, BoneModuleType.RADAR, entityId);
        if (!radarRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_radar"), summary(radarRows), radarRows));
        }

        // [RVP] 炮管部件（2026-09-28）：骨模块含 BARREL 的骨（本体炮管骨命名约定
        // structure_bone + "_barrel"）；失效即整个炮管所在武器站禁止射击（累计伤害单档、
        // 无衰减永久）。失效行走辅助设备维修队列（快修恢复清累计）。
        Map<String, Set<BoneModuleType>> barrelBones = bonesOfType(modules, BoneModuleType.BARREL);
        List<Row> barrelRows = typeRows(vehicle, orderedBones, modules, barrelBones, BoneModuleType.BARREL, entityId);
        // [RVP] 炮管受损态标注（2026-09-28 两档炮管）：客户端按累计推导——两档配置且
        // 累计 ≥ threshold_light 且 BARREL 未失效 → extra 加"受损"（射击已进入三选一）
        var barrelConfigs = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBarrelModules(vehicle);
        if (barrelConfigs != null) {
            for (int i = 0; i < barrelRows.size(); i++) {
                Row row = barrelRows.get(i);
                var config = barrelConfigs.get(row.boneName());
                if (config == null || !config.hasDamagedStage() || !row.active()) {
                    continue;
                }
                float accumulated = org.ywzj.rvp.client.state.RVP_ClientBoneDamageProgress
                        .getAccumulated(entityId, row.boneName());
                if (accumulated + 1.0E-3f >= config.thresholdLight()) {
                    String extra = (row.extra() == null ? "" : row.extra() + " · ")
                            + I18n.get("gui.ywzj_rvp.equipment.barrel_damaged");
                    barrelRows.set(i, new Row(row.index(), row.alias(), row.active(), row.boneName(),
                            row.queueKey(), extra));
                }
            }
        }
        if (!barrelRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_barrel"), summary(barrelRows), barrelRows));
        }

        // [RVP] 引擎部件（2026-09-26；2026-09-28 重创进失效表）：三档显示——正常 /
        // 受损（功率降低：ENGINE_DAMAGED 进失效表=可修失效设备，可入维修顺序队列指定
        // 优先级；无衰减永久，唯一恢复=快修）/ 瘫痪（ENGINE 模块失效，动力清零，红框入队）。
        // 受损/瘫痪档读 RVP_ClientEngineDamageState 档位快照（S2CEngineDamageState 差分推送）
        Map<String, BoneEngineConfig> engineBones =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveEngineModules(vehicle);
        if (engineBones != null && !engineBones.isEmpty()) {
            List<Row> engineRows = new ArrayList<>();
            index = 1;
            for (String bone : orderedBones) {
                BoneEngineConfig config = engineBones.get(bone);
                if (config == null) {
                    continue;
                }
                // 失效判定（置顶+入维修队列）：瘫痪（ENGINE 失效）或重创（ENGINE_DAMAGED 失效）
                boolean disabled = !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.ENGINE);
                boolean damaged = disabled
                        || !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.ENGINE_DAMAGED);
                int stage = RVP_ClientEngineDamageState.getStage(entityId, bone);
                String extra;
                if (disabled) {
                    extra = I18n.get("gui.ywzj_rvp.equipment.engine_disabled");
                } else if (damaged || stage == 1) {
                    extra = I18n.get("gui.ywzj_rvp.equipment.engine_damaged");
                } else {
                    extra = null;
                }
                if (!disabled) {
                    String hp = virtualHpText(vehicle, entityId, bone);
                    if (hp != null) {
                        extra = (extra == null ? "" : extra + " · ") + hp;
                    }
                }
                engineRows.add(new Row(index++, alias(vehicle, bone), !damaged, bone, QUEUE_DEV, extra));
            }
            if (!engineRows.isEmpty()) {
                out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_engine"), summary(engineRows), engineRows));
            }
        }

        // 干扰物（热焰/箔条/烟雾）：余弹型，无失效语义，不可入维修队列
        RVP_CountermeasureData cmData =
                RVP_CountermeasureConfigManager.INSTANCE.resolve(vehicle.getVehicleId());
        if (cmData != null && cmData.isEnabled()) {
            RVP_CountermeasureHudState.Snapshot cm =
                    RVP_CountermeasureHudState.get(entityId);
            List<Row> cmRows = new ArrayList<>();
            index = 1;
            index = addCountermeasureRow(cmRows, index, I18n.get("gui.ywzj_rvp.equipment.cm_flare"),
                    cmData.getFlare(), cm == null ? -1 : cm.flareRemain(), cm == null ? -1 : cm.flareTotal());
            index = addCountermeasureRow(cmRows, index, I18n.get("gui.ywzj_rvp.equipment.cm_chaff"),
                    cmData.getChaff(), cm == null ? -1 : cm.chaffRemain(), cm == null ? -1 : cm.chaffTotal());
            index = addCountermeasureRow(cmRows, index, I18n.get("gui.ywzj_rvp.equipment.cm_smoke"),
                    cmData.getSmoke(), cm == null ? -1 : cm.smokeRemain(), cm == null ? -1 : cm.smokeTotal());
            if (!cmRows.isEmpty()) {
                out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_cm"),
                        I18n.get("gui.ywzj_rvp.equipment.summary_simple", cmRows.size()), cmRows));
            }
        }

        // [RVP] 部件血量栏目已按用户要求移除（2026-09-26，"没啥用现在"）；
        // 后续若要做"引擎/履带等部件损毁"展示，在此处按 getPartUnits() 顺序恢复即可。
        // 展示排序：失效行置顶（组内按载具数据序号升序），其余按序号升序
        for (int i = 0; i < out.size(); i++) {
            Category category = out.get(i);
            List<Row> sorted = new ArrayList<>(category.rows());
            sorted.sort((a, b) -> {
                if (a.active() != b.active()) {
                    return a.active() ? 1 : -1;
                }
                return Integer.compare(a.index(), b.index());
            });
            out.set(i, new Category(category.title(), category.summary(), sorted));
        }
        return out;
    }

    /**
     * 面板左上俯视图的骨块集合：全部骨模块骨（含虚拟骨 {@code __vehicle__} 之外的实体骨）。
     * 供 {@link RVP_EquipSkeletonRenderer} 着色（ERA 绿/红、设备骨蓝/红）。
     */
    public static Map<String, Set<BoneModuleType>> boneModules(AbstractVehicle vehicle) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
    }

    /** 维修顺序栏可用性：载具配置了快修（bone_modules 维修模块）才显示该栏。 */
    public static boolean hasMaintenance(AbstractVehicle vehicle) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle) != null;
    }

    /**
     * 预计维修量（辅助设备面板提示行）：ERA 为确定配额（与服务端 {@code recoverModules} 同公式）；
     * 辅助设备为概率制（每台每次快修 device_recover_chance 独立掷骰），
     * 预计修复数 = 失效可修数 × 单台概率（期望值，由调用方格式化小数）。
     */
    public record Forecast(int eraQuota, double deviceExpected, int deviceDestroyed, int deviceChancePercent) {
    }

    public static Forecast repairForecast(AbstractVehicle vehicle) {
        var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle);
        if (binding == null) {
            return new Forecast(0, 0, 0, 0);
        }
        org.ywzj.rvp.vehicle.BoneMaintenanceConfig config = binding.config();
        org.ywzj.rvp.vehicle.BoneMaintenanceConfig.ModuleRepair moduleRepair =
                config.moduleRepair() != null ? config.moduleRepair()
                        : org.ywzj.rvp.vehicle.BoneMaintenanceConfig.ModuleRepair.defaults();
        int entityId = vehicle.getId();
        Map<String, Set<BoneModuleType>> modules =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        int eraDestroyed = 0;
        Set<String> destroyedDeviceBones = new java.util.HashSet<>();
        for (Map.Entry<String, Set<BoneModuleType>> entry : modules.entrySet()) {
            String bone = entry.getKey();
            if ("__vehicle__".equals(bone)) {
                continue; // 虚拟骨（默认快修承载骨）永不禁用，不计入
            }
            Set<BoneModuleType> types = entry.getValue();
            // 捆绑口径（用户 2026-09-26 定版）：含 ERA 的双角色骨整体计入爆反维修数（随 ERA 一起修），
            // 不算辅助设备数；纯设备骨才计入辅助设备数
            if (types.contains(BoneModuleType.ERA) && moduleRepair.isRepairable(BoneModuleType.ERA)) {
                if (boneFailed(entityId, bone, types, moduleRepair)) {
                    eraDestroyed++;
                }
            } else if (boneFailed(entityId, bone, types, moduleRepair)) {
                destroyedDeviceBones.add(bone);
            }
        }
        int eraQuota = eraDestroyed == 0 ? 0
                : Math.min(Math.max(
                        (int) Math.ceil(eraDestroyed * moduleRepair.eraRecoverFraction),
                        moduleRepair.eraRecoverMin),
                eraDestroyed);
        int chancePercent = Math.round(moduleRepair.deviceRecoverChance * 100f);
        double deviceExpected = destroyedDeviceBones.size() * moduleRepair.deviceRecoverChance;
        return new Forecast(eraQuota, deviceExpected, destroyedDeviceBones.size(), chancePercent);
    }

    /** 骨上是否有任一可修模块处于失效（捆绑口径下按骨级判定）。 */
    private static boolean boneFailed(int entityId, String bone, Set<BoneModuleType> types,
                                      org.ywzj.rvp.vehicle.BoneMaintenanceConfig.ModuleRepair moduleRepair) {
        for (BoneModuleType type : types) {
            if (type == BoneModuleType.MAINTENANCE) {
                continue;
            }
            if (!moduleRepair.isRepairable(type)) {
                continue;
            }
            if (!RVP_ClientBoneModuleState.isModuleActive(entityId, bone, type)) {
                return true;
            }
        }
        // [RVP] 引擎重创档（ENGINE_DAMAGED）是运行时写入失效表的标记，不在 bone_modules
        // 配置类型集合里——按配置类型遍历会漏掉重创的引擎（2026-09-28 用户实机：损坏
        // 5 个部件只算 4 个）。配置含 ENGINE 的骨额外查重创标记失效状态。
        if (types.contains(BoneModuleType.ENGINE)
                && !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.ENGINE_DAMAGED)) {
            return true;
        }
        return false;
    }

    /**
     * [RVP] 模块虚拟血量文本（2026-09-28 全模块累计化配套）："现存/阈值"
     * （阈值 − 已累计 = 还能吃多少伤害失效）。无限（未配置阈值）返回 null 不显示。
     * 数据源：S2CBoneDamageProgress 客户端侧表（差分推送）。
     */
    private static String virtualHpText(AbstractVehicle vehicle, int entityId, String bone) {
        float threshold = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneDamageThreshold(vehicle, bone);
        if (threshold <= 0) {
            return null;
        }
        float accumulated = org.ywzj.rvp.client.state.RVP_ClientBoneDamageProgress.getAccumulated(entityId, bone);
        float remain = Math.max(0f, threshold - accumulated);
        return (int) Math.ceil(remain) + "/" + (int) threshold;
    }

    /** 行别名：命中箱别名优先，未配置别名回退骨名（"只显示别名"规则）。 */
    private static String alias(AbstractVehicle vehicle, String bone) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayNameLocalized(vehicle, bone);
    }

    /** 过滤出配置了指定模块类型的骨（骨名 → 原类型集合），供 typeRows 生成栏目行。 */
    private static Map<String, Set<BoneModuleType>> bonesOfType(Map<String, Set<BoneModuleType>> modules,
                                                                BoneModuleType type) {
        Map<String, Set<BoneModuleType>> out = new java.util.HashMap<>();
        for (Map.Entry<String, Set<BoneModuleType>> entry : modules.entrySet()) {
            if (entry.getValue().contains(type)) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** 双角色骨判定：同骨既有 ERA 又有其它设备模块（捆绑修复、显示标注用）。 */
    private static boolean hasDeviceRole(Set<BoneModuleType> types) {
        for (BoneModuleType type : types) {
            if (type != BoneModuleType.ERA && type != BoneModuleType.MAINTENANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全部骨模块骨的稳定顺序：先按部件（= 载具 JSON {@code parts[]}）顺序，
     * 再追加未对应部件的配置骨（如虚拟骨绑定、无部件的 ecm_bone），按名排序保证稳定。
     */
    private static List<String> orderedBoneNames(AbstractVehicle vehicle, Set<String> configBones) {
        List<String> ordered = new ArrayList<>();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            String bone = partUnit.getId();
            if (configBones.contains(bone) && !ordered.contains(bone)) {
                ordered.add(bone);
            }
        }
        List<String> leftovers = new ArrayList<>();
        for (String bone : configBones) {
            if (!ordered.contains(bone)) {
                leftovers.add(bone);
            }
        }
        Collections.sort(leftovers);
        ordered.addAll(leftovers);
        return ordered;
    }

    /** 通用设备栏目行：配置骨按全局顺序排列，生效状态查客户端骨模块侧表。
     *  双角色骨（同骨含 ERA）标注"随爆反修复"，入队路由到爆反队列（捆绑修复口径）。 */
    private static List<Row> typeRows(AbstractVehicle vehicle, List<String> orderedBones,
                                      Map<String, Set<BoneModuleType>> modules,
                                      Map<String, ?> devices, BoneModuleType type, int entityId) {
        if (devices == null || devices.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        int index = 1;
        for (String bone : orderedBones) {
            if (!devices.containsKey(bone)) {
                continue;
            }
            boolean active = RVP_ClientBoneModuleState.isModuleActive(entityId, bone, type);
            Set<BoneModuleType> boneTypes = modules.get(bone);
            boolean dualRole = boneTypes != null && boneTypes.contains(BoneModuleType.ERA);
            String extra = dualRole ? I18n.get("gui.ywzj_rvp.equipment.note_with_era") : null;
            // [RVP] 虚拟血量（2026-09-28 全模块累计化）：阈值−已累计，如 "22/30"
            String hp = virtualHpText(vehicle, entityId, bone);
            if (hp != null) {
                extra = (extra == null ? "" : extra + " · ") + hp;
            }
            rows.add(new Row(index++, alias(vehicle, bone), active, bone,
                    dualRole ? QUEUE_ERA : QUEUE_DEV, extra));
        }
        return rows;
    }

    /** 干扰物行：仅生成已配置且启用的系统；余弹缺快照时按满弹显示。 */
    private static int addCountermeasureRow(List<Row> rows, int index, String alias,
                                            RVP_CountermeasureSystemData system, int remain, int total) {
        if (system == null || !system.isEnabled()) {
            return index;
        }
        int shownTotal = total >= 0 ? total : system.getTotal();
        int shownRemain = remain >= 0 ? remain : shownTotal;
        String extra = I18n.get("gui.ywzj_rvp.equipment.ammo", shownRemain, shownTotal);
        rows.add(new Row(index++, alias, true, null, null, extra));
        return index;
    }

    /** 汇总："x/y 生效 · 失效 z"。 */
    private static String summary(int total, int bad) {
        int ok = total - bad;
        return bad > 0
                ? I18n.get("gui.ywzj_rvp.equipment.summary", ok, total, bad)
                : I18n.get("gui.ywzj_rvp.equipment.summary_ok", ok, total);
    }

    private static String summary(List<Row> rows) {
        int bad = 0;
        for (Row row : rows) {
            bad += row.active() ? 0 : 1;
        }
        return summary(rows.size(), bad);
    }
}
