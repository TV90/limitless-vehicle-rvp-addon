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
import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.maintenance.RVP_RepairPlanner;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState;
import org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper;
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
 * 组装成面板栏目模型；维修顺序另由服务端同步。
 *
 * <p>栏目按"无设备不画"门控：载具没配置的设备类型不生成栏目。行序号 = 该部件在
 * 载具数据中的出现顺序（以 {@code vehicle.getPartUnits()} 为准——它严格等于载具 JSON
 * {@code parts[]} 数组顺序；骨模块配置底层为 HashMap 不保序，故以部件顺序为锚）。
 * 队列路由：ERA 行入"爆反维修顺序"，其余骨模块设备行入"辅助设备维修顺序"；
 * 绑定骨的干扰物逐骨入队；纯部件血量由快修回血覆盖，不单独入队。</p>
 */
public final class RVP_EquipPanelData {

    /** 队列路由键：爆反维修顺序。 */
    public static final String QUEUE_ERA = "era";
    /** 队列路由键：辅助设备维修顺序。 */
    public static final String QUEUE_DEV = "dev";

    /**
     * 面板行：index=栏目内序号、alias、active=生效（false=失效红框）、
     * damaged=部分受损黄标（2026-09-29：仅 active=true 时有意义——如烟雾弹坏一边）、
     * boneName/queueKey=入队路由（null=不可入队）、extra=行尾附加文本。
     */
    public record Row(int index, String alias, boolean active, boolean damaged,
                      String boneName, String queueKey, String extra) {
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
            // [RVP] ERA 虚拟血量（2026-09-28 全模块累计化）：阈值−已累计，如 "100/100"
            String hp = virtualHpText(vehicle, entityId, bone);
            if (hp != null) {
                extra = (extra == null ? "" : extra + " · ") + hp;
            }
            eraRows.add(new Row(index++, alias(vehicle, bone), active, false, bone, QUEUE_ERA, extra));
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
        // [RVP] 炮管行双档判定（2026-09-28 对齐引擎行）：彻底损坏（BARREL 失效）/"受损"
        //（BARREL_DAMAGED 失效，红框置顶入维修队列）——受损态标注改失效表直读（原客户端
        // 累计推导删除）；虚拟血量保留（分母 = threshold）
        var barrelConfigs = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBarrelModules(vehicle);
        List<Row> barrelRows = new ArrayList<>();
        int barrelIndex = 1;
        for (String bone : orderedBones) {
            if (!barrelBones.containsKey(bone)) {
                continue;
            }
            boolean burst = !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.BARREL);
            // 三态（2026-09-29 用户定版"炮管一档受损也用黄框"）：彻底损坏（BARREL 失效）=红失效；
            // 受损档（BARREL_DAMAGED 失效、未炸膛）=黄受损（仍可入维修队列）；正常=绿生效
            boolean hurt = !burst
                    && !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.BARREL_DAMAGED);
            String extra = hurt
                    ? I18n.get("gui.ywzj_rvp.equipment.barrel_damaged") : null;
            String hp = virtualHpText(vehicle, entityId, bone);
            if (hp != null) {
                extra = (extra == null ? "" : extra + " · ") + hp;
            }
            barrelRows.add(new Row(barrelIndex++, alias(vehicle, bone), !burst && !hurt, hurt, bone, QUEUE_DEV, extra));
        }
        if (!barrelRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_barrel"), summary(barrelRows), barrelRows));
        }

        // 方向机沿用设备维修队列，两档都能选择维修。
        List<Row> traverseRows = new ArrayList<>();
        for (String bone : orderedBones) {
            if (RVP_VehicleHitboxFactorManager.INSTANCE.resolveTurretTraverseConfig(vehicle, bone) == null) continue;
            boolean broken = !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.TURRET_TRAVERSE);
            boolean hurt = !broken && !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.TURRET_TRAVERSE_DAMAGED);
            traverseRows.add(new Row(traverseRows.size() + 1, alias(vehicle, bone), !broken && !hurt, hurt,
                    bone, QUEUE_DEV, virtualHpText(vehicle, entityId, bone)));
        }
        if (!traverseRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.module.turret_traverse"), summary(traverseRows), traverseRows));
        }

        // 火控系统（FCS）：通用 min_damage 累计模块，失效后整车火控降级。
        Map<String, Set<BoneModuleType>> fcsBones = bonesOfType(modules, BoneModuleType.FCS);
        List<Row> fcsRows = typeRows(vehicle, orderedBones, modules, fcsBones, BoneModuleType.FCS, entityId);
        if (!fcsRows.isEmpty()) {
            out.add(new Category(I18n.get("gui.ywzj_rvp.equipment.cat_fcs"), summary(fcsRows), fcsRows));
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
                boolean hurtStage = !RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.ENGINE_DAMAGED);
                int stage = RVP_ClientEngineDamageState.getStage(entityId, bone);
                // 三态（2026-09-29 用户定版"引擎一档受损也用黄框"）：瘫痪（ENGINE 失效）=红失效；
                // 重创（ENGINE_DAMAGED 失效或累计达受损阈 stage=1、未瘫痪）=黄受损；正常=绿生效
                boolean damaged = !disabled && (hurtStage || stage == 1);
                String extra;
                if (disabled) {
                    extra = I18n.get("gui.ywzj_rvp.equipment.engine_disabled");
                } else if (damaged) {
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
                engineRows.add(new Row(index++, alias(vehicle, bone), !disabled && !damaged, damaged, bone, QUEUE_DEV, extra));
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
                    vehicle, entityId, cmData.getFlare(), cm == null ? -1 : cm.flareRemain(), cm == null ? -1 : cm.flareTotal());
            index = addCountermeasureRow(cmRows, index, I18n.get("gui.ywzj_rvp.equipment.cm_chaff"),
                    vehicle, entityId, cmData.getChaff(), cm == null ? -1 : cm.chaffRemain(), cm == null ? -1 : cm.chaffTotal());
            index = addCountermeasureRow(cmRows, index, I18n.get("gui.ywzj_rvp.equipment.cm_smoke"),
                    vehicle, entityId, cmData.getSmoke(), cm == null ? -1 : cm.smokeRemain(), cm == null ? -1 : cm.smokeTotal());
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
            // 三档排序（2026-09-29 受损黄标顺延）：失效置顶 → 受损次之 → 正常（组内按序号）
            sorted.sort((a, b) -> {
                int rankA = !a.active() ? 0 : (a.damaged() ? 1 : 2);
                int rankB = !b.active() ? 0 : (b.damaged() ? 1 : 2);
                if (rankA != rankB) {
                    return Integer.compare(rankA, rankB);
                }
                return Integer.compare(a.index(), b.index());
            });
            out.set(i, new Category(category.title(), category.summary(), sorted));
        }
        return out;
    }

    /**
     * 面板左上俯视图的骨块集合：全部骨模块骨（含虚拟骨 {@code __vehicle__} 之外的实体骨）。
     * 供状态面板栏目缓存；OBB 绘制复用本体俯视图和统一模块档位配色。
     */
    public static Map<String, Set<BoneModuleType>> boneModules(AbstractVehicle vehicle) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
    }

    /** 维修顺序栏可用性：载具配置了快修（bone_modules 维修模块）才显示该栏。 */
    public static boolean hasMaintenance(AbstractVehicle vehicle) {
        return RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle) != null;
    }

    /** 调用当前载具配置，省略模块细则时使用统一默认值。 */
    public static BoneMaintenanceConfig.ModuleRepair repairConfig(AbstractVehicle vehicle) {
        var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle);
        return binding == null || binding.config().moduleRepair() == null
                ? BoneMaintenanceConfig.ModuleRepair.defaults() : binding.config().moduleRepair();
    }

    /** 调用公共排序器补齐正常部件，维修后仍保留用户指定顺序。 */
    public static RVP_ClientRepairOrderState.Order repairOrder(AbstractVehicle vehicle) {
        var saved = RVP_ClientRepairOrderState.get(vehicle.getId());
        var full = RVP_RepairPlanner.complete(
                new RVP_RepairPlanner.Order(saved.eraBones(), saved.deviceBones()),
                RVP_RepairPlanner.defaults(vehicle, repairConfig(vehicle)));
        return new RVP_ClientRepairOrderState.Order(full.eraBones(), full.deviceBones());
    }

    /** 预计修复的准确数量和骨集合，供面板提示与绿色预览共用。 */
    public record Forecast(int eraQuota, int deviceQuota, Set<String> bones) { }

    public static Forecast repairForecast(AbstractVehicle vehicle) {
        Map<String, Set<BoneModuleType>> inactive = new java.util.HashMap<>();
        for (String bone : boneModules(vehicle).keySet()) {
            Set<BoneModuleType> types = java.util.EnumSet.noneOf(BoneModuleType.class);
            // 同步表含运行时受损标记，不能只遍历配置中的基础类型。
            for (BoneModuleType type : BoneModuleType.values()) {
                if (!RVP_ClientBoneModuleState.isModuleActive(vehicle.getId(), bone, type)) types.add(type);
            }
            if (!types.isEmpty()) inactive.put(bone, types);
        }
        var order = repairOrder(vehicle);
        var targets = RVP_RepairPlanner.select(new RVP_RepairPlanner.Order(order.eraBones(), order.deviceBones()),
                inactive, repairConfig(vehicle));
        Set<String> bones = new java.util.HashSet<>(targets.eraBones());
        bones.addAll(targets.deviceBones());
        return new Forecast(targets.eraBones().size(), targets.deviceBones().size(), Set.copyOf(bones));
    }

    /** 复用俯视图的正常、受损、失效三档规则，确保清单与列表颜色一致。 */
    public static int boneStage(AbstractVehicle vehicle, String bone) {
        return RVP_DamagedPartWireframeHelper.stageFor(boneModules(vehicle).getOrDefault(bone, Set.of()),
                type -> RVP_ClientBoneModuleState.isModuleActive(vehicle.getId(), bone, type));
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
            rows.add(new Row(index++, alias(vehicle, bone), active, false, bone,
                    dualRole ? QUEUE_ERA : QUEUE_DEV, extra));
        }
        return rows;
    }

    /** 干扰物按绑定发射器骨逐项显示，正常或失效骨均可设置优先级；无绑定骨时仅显示余弹。 */
    private static int addCountermeasureRow(List<Row> rows, int index, String alias,
                                            AbstractVehicle vehicle, int entityId,
                                            RVP_CountermeasureSystemData system, int remain, int total) {
        if (system == null || !system.isEnabled()) {
            return index;
        }
        int shownTotal = total >= 0 ? total : system.getTotal();
        int shownRemain = remain >= 0 ? remain : shownTotal;
        String extra = I18n.get("gui.ywzj_rvp.equipment.ammo", shownRemain, shownTotal);
        List<String> bones = new ArrayList<>(system.getBoneModules());
        if (bones.isEmpty()) {
            rows.add(new Row(index++, alias, true, false, null, null, extra));
        }
        for (String bone : bones) {
            boolean active = RVP_ClientBoneModuleState.isModuleActive(entityId, bone, BoneModuleType.COUNTERMEASURE);
            String hp = virtualHpText(vehicle, entityId, bone);
            String route = boneModules(vehicle).getOrDefault(bone, Set.of()).contains(BoneModuleType.ERA)
                    ? QUEUE_ERA : QUEUE_DEV;
            rows.add(new Row(index++, alias(vehicle, bone), active, false, bone, route,
                    alias + " " + extra + (hp == null ? "" : " " + hp)));
        }
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
