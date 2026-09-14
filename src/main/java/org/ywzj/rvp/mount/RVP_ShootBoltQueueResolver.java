package org.ywzj.rvp.mount;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.mixin.accessor.BaseVehicleDataAccessor;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.custom.part.PartUnitEntry;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [RVP] 出弹点数据层定版（挂骨方案，2026-09-14）：配置了出弹骨
 * （{@code shoot_structure_bones}）的武器站，其模板出弹 bolts 在数据包加载期
 * 直接改写为出弹骨位置（出弹骨成为挂骨）——运行时零劫持、零竞态，
 * 存档重进天然正常（模板随数据包重载重建）。
 *
 * <p>语义（2026-09-14 定版）：配置出弹骨 → 出弹骨成为挂骨，数据层从出弹骨
 * 计算出弹 bolts（同骨去重）；未配置出弹骨 → 挂骨同时作为出弹骨与视觉挂骨
 * （原语义，barrel 推算不变）。原 barrel 挂骨在配置出弹骨后不再参与出弹。</p>
 *
 * <p>数据流：数据包加载（{@code VehicleDataManagerMixin.apply} TAIL，此时结构模型/
 * 部件模板/挂架条目三者百分百在册）→ 按站聚合出弹骨条目 → 复用骨→Bolt 数学
 * 计算出弹 bolts（同骨去重）→ 写入 {@code WeaponUnitData} 模板 bolts 列表 →
 * 实体构造时拷贝。双端数据包各自 apply，无需 S2C 同步。</p>
 *
 * <p>历史：本类曾实现"运行时出弹队列 + 拉取式应用器"方案（PENDING/ensureApplied/
 * MISS_RETRY/S2C 整表同步），因配置/队列表就绪时序竞态导致"放置后不应用/
 * 重进回退挂点"而废弃（存档于 gitee 提交 {@code 709815d7}）；应用器
 * {@code RVP_ShootBoltQueueApplier} 与 {@code S2CShootBoltQueueSync} 已删除。</p>
 *
 * <p>Cube→Bolt 数学与本体 {@code buildBolts} 完全同构（Cube 前端面中心为炮闩、
 * Z+ 为炮管轴、depth 为管长）；跨组偏移用骨骼 bind 枢轴差换算到锚定骨坐标系。</p>
 */
public final class RVP_ShootBoltQueueResolver {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 骨链回溯深度保护：结构模型骨层级异常（成环）时中断，防止死循环。 */
    private static final int MAX_BONE_DEPTH = 32;

    private RVP_ShootBoltQueueResolver() {}

    /**
     * [RVP] 数据层出弹点写入（数据包 apply TAIL 调用，双端各自执行）。
     *
     * <p>对配置了出弹骨的武器站：把出弹骨 bolts 写入 {@code WeaponUnitData}
     * 模板的 bolts 列表（实体构造时拷贝）——替代运行时队列劫持，重进存档
     * 天然正常（模板随数据包重载重建）。</p>
     */
    public static void applyTemplateBolts(Map<ResourceLocation, JsonElement> resources) {
        if (resources == null) {
            return;
        }
        int appliedStations = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation vehicleId = entry.getKey();
            // 目的：读取本载具的挂架条目；未配置自定义挂架的载具直接跳过
            List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicleId);
            if (configs.isEmpty()) {
                continue;
            }
            BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId).orElse(null);
            if (data == null) {
                LOGGER.warn("[RVP] 出弹点数据层写入跳过：载具数据缺失 vehicle={}", vehicleId);
                continue;
            }
            JsonObject root = GsonHelper.convertToJsonObject(entry.getValue(), "vehicle data");
            // 目的：解析部件表（part id → structure_bone，锚定骨 = structure_bone + "_barrel"）
            Map<String, String> stationBones = parseStationBones(root);
            BedrockModel model = resolveStructureModel(root, vehicleId);
            if (model == null) {
                continue;
            }
            // 目的：按武器站聚合出弹骨条目（仅含配置了 shoot_structure_bones 的条目）
            Map<String, List<RVP_CustomMountConfig>> byStation = groupByStation(configs);
            if (byStation.isEmpty()) {
                continue;
            }
            // 目的：遍历部件模板（accessor 读 protected parts），命中站 id 的写入出弹 bolts
            List<PartUnitEntry<?, ?>> parts = ((BaseVehicleDataAccessor) (Object) data).ywzj_rvp$getParts();
            for (PartUnitEntry<?, ?> partEntry : parts) {
                if (!(partEntry.data() instanceof WeaponUnitData weaponData)) {
                    continue;
                }
                List<RVP_CustomMountConfig> stationConfigs = byStation.get(weaponData.getId());
                if (stationConfigs == null) {
                    continue;
                }
                String structureBone = stationBones.getOrDefault(weaponData.getId(), "");
                String anchorBoneName = structureBone.isEmpty() ? "" : structureBone + "_barrel";
                BedrockBone anchorBone = model.getBoneMap().get(anchorBoneName);
                if (anchorBone == null) {
                    LOGGER.warn("[RVP] 出弹点数据层写入：锚定骨缺失 station={} bone={}，跳过",
                            weaponData.getId(), anchorBoneName);
                    continue;
                }
                List<Bolt> bolts = computeStationBolts(stationConfigs, anchorBone, model, vehicleId);
                if (bolts.isEmpty()) {
                    continue;
                }
                List<Bolt> template = weaponData.getBolts();
                if (template == null) {
                    LOGGER.warn("[RVP] 出弹点数据层写入：模板 bolts 为 null station={}，跳过", weaponData.getId());
                    continue;
                }
                template.clear();
                template.addAll(bolts);
                appliedStations++;
                Bolt first = bolts.get(0);
                LOGGER.info("[RVP] 出弹点数据层写入 vehicle={} station={} bolts={} first=({},{},{}) 管长={}",
                        vehicleId, weaponData.getId(), bolts.size(),
                        String.format(java.util.Locale.ROOT, "%.3f", first.offset.x),
                        String.format(java.util.Locale.ROOT, "%.3f", first.offset.y),
                        String.format(java.util.Locale.ROOT, "%.3f", first.offset.z),
                        String.format(java.util.Locale.ROOT, "%.3f", first.barrelLength));
            }
        }
        if (appliedStations > 0) {
            LOGGER.info("[RVP] 出弹点数据层写入完成：站 {} 个", appliedStations);
        }
    }

    /** 解析部件表（part id → structure_bone）。 */
    private static Map<String, String> parseStationBones(JsonObject root) {
        Map<String, String> stationBones = new HashMap<>();
        if (root.has("parts")) {
            for (JsonElement partElement : root.getAsJsonArray("parts")) {
                if (!partElement.isJsonObject()) {
                    continue;
                }
                JsonObject partObj = partElement.getAsJsonObject();
                String id = GsonHelper.getAsString(partObj, "id", "").trim();
                if (!id.isEmpty()) {
                    stationBones.put(id, GsonHelper.getAsString(partObj, "structure_bone", "").trim());
                }
            }
        }
        return stationBones;
    }

    /** 解析结构模型（structure_model 字段 → BedrockModel）；缺失时返回 null 并告警。 */
    @Nullable
    private static BedrockModel resolveStructureModel(JsonObject root, ResourceLocation vehicleId) {
        String structureModelId = GsonHelper.getAsString(root, "structure_model", "");
        ResourceLocation structureModelIdRl = ResourceLocation.tryParse(structureModelId);
        BedrockModel model = structureModelIdRl == null ? null
                : CommonAssetsManager.structureModelManager().getStructureModel(structureModelIdRl).orElse(null);
        if (model == null) {
            LOGGER.warn("[RVP] 出弹点数据层写入跳过：结构模型缺失 vehicle={} model={}", vehicleId, structureModelId);
        }
        return model;
    }

    /** 按武器站聚合出弹骨条目（仅含配置了 shoot_structure_bones 的条目）。 */
    private static Map<String, List<RVP_CustomMountConfig>> groupByStation(List<RVP_CustomMountConfig> configs) {
        Map<String, List<RVP_CustomMountConfig>> byStation = new HashMap<>();
        for (RVP_CustomMountConfig config : configs) {
            if (config.shootStructureBones().isEmpty()) {
                continue;
            }
            byStation.computeIfAbsent(config.partUnitId(), key -> new ArrayList<>()).add(config);
        }
        return byStation;
    }

    /**
     * [RVP] 计算单站的出弹 bolts：条目按（ammoSlot 升序 → 配置顺序）排序，
     * 逐条目展开 shoot_structure_bones（同骨去重），合并为该站的出弹 Bolt 列表。
     */
    private static List<Bolt> computeStationBolts(List<RVP_CustomMountConfig> stationConfigs,
                                                  BedrockBone anchorBone, BedrockModel model,
                                                  ResourceLocation vehicleId) {
        List<RVP_CustomMountConfig> entries = new ArrayList<>(stationConfigs);
        entries.sort(Comparator
                .comparingInt((RVP_CustomMountConfig config) -> config.ammoSlot() > 0 ? config.ammoSlot() : Integer.MAX_VALUE)
                .thenComparingInt(RVP_CustomMountConfig::configOrder));
        // 目的：同一骨骼被红外/激光等重复条目引用时只展开一次（按条目顺序保序）
        Set<String> usedBones = new LinkedHashSet<>();
        List<Bolt> bolts = new ArrayList<>();
        for (RVP_CustomMountConfig config : entries) {
            for (String boneName : config.shootStructureBones()) {
                if (boneName == null || boneName.isBlank() || !usedBones.add(boneName)) {
                    continue;
                }
                BedrockBone bone = model.getBoneMap().get(boneName);
                if (bone == null) {
                    LOGGER.warn("[RVP] 出弹骨不存在，跳过 vehicle={} bone={}",
                            vehicleId, boneName);
                    continue;
                }
                // 目的：出弹骨坐标系 → 锚定骨坐标系（xTurnGroup 空间）的平移差
                Vec3 delta = bindPivot(bone).subtract(bindPivot(anchorBone));
                appendBoneBolts(bone, anchorBone, delta, bolts, model);
            }
        }
        return bolts;
    }

    /**
     * 反射读取武器站私有 {@code xTurnGroup}（挂架锚点骨组）。
     *
     * <p>与 {@code RVP_CustomMountRenderLogic} 既有逻辑一致：结构模型中常只有
     * {@code structure_bone + "_barrel"} 骨而无本体骨，导致 structureGroup 恒空、
     * 挂架整体不渲染，故必须读实例 xTurnGroup；反射不可用时回退 structureGroup
     * （仅功能降级，不崩溃）。</p>
     */
    public static VehicleCubeGroup findXTurnGroup(WeaponUnit unit) {
        java.lang.reflect.Field field;
        try {
            field = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(WeaponUnit.class, "xTurnGroup");
            field.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.warn("[RVP] 无法解析 WeaponUnit.xTurnGroup 字段，出弹锚点退化为 structureGroup", t);
            return unit.getStructureGroup();
        }
        try {
            VehicleCubeGroup group = (VehicleCubeGroup) field.get(unit);
            if (group != null) {
                return group;
            }
        } catch (Throwable t) {
            LOGGER.debug("[RVP] 读取 WeaponUnit.xTurnGroup 失败，退化为 structureGroup", t);
        }
        return unit.getStructureGroup();
    }

    /**
     * 条目 {@code part_unit_id} 是否指向本武器站。
     * 与渲染侧 {@code matchesConfiguredPartUnit} 同口径：本站 id 命中，或条目指向
     * 本站任一母武器站（子站继承母站条目）。
     */
    public static boolean matchesStation(WeaponUnit station, String partUnitId) {
        WeaponUnit current = station;
        while (current != null) {
            if (partUnitId.equals(current.getId())) {
                return true;
            }
            current = current.getParentWeaponUnit();
        }
        return false;
    }

    // ==================================================================
    // 调试输出（/rvpdebug custommount bolts）
    // ==================================================================

    /**
     * dump 单载具出弹点全状态：实体出弹 bolts（= 数据层模板写入结果）、当前出弹点
     * 世界坐标。供 {@code /rvpdebug custommount bolts} 调试命令与排查使用；仅读公共状态，
     * 双端安全。
     */
    public static String dumpBoltState(AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RVP 出弹点状态 ===\n");
        sb.append("vehicleId=").append(vehicle.getVehicleId())
          .append(" entityId=").append(vehicle.getId()).append('\n');
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicle.getVehicleId());
        sb.append("挂架条目: ").append(configs.size()).append(" 条\n");
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit station)) {
                continue;
            }
            boolean managed = false;
            for (RVP_CustomMountConfig config : configs) {
                if (!config.shootStructureBones().isEmpty()
                        && matchesStation(station, config.partUnitId())) {
                    managed = true;
                    break;
                }
            }
            if (!managed) {
                continue;
            }
            List<Bolt> applied = station.getBolts();
            sb.append("== 站 ").append(station.getId()).append("\n");
            sb.append("  出弹 Bolt: ").append(applied.size()).append(" 根\n");
            for (int i = 0; i < applied.size(); i++) {
                Bolt bolt = applied.get(i);
                sb.append("    #").append(i)
                  .append(" offset=[").append(fmt(bolt.offset.x)).append(',').append(fmt(bolt.offset.y)).append(',').append(fmt(bolt.offset.z)).append(']')
                  .append(" 管长=").append(fmt(bolt.barrelLength))
                  .append(" xRot=").append(fmt(bolt.xRot)).append(" yRot=").append(fmt(bolt.yRot))
                  .append('\n');
            }
            Vec3 world = station.worldCurrentBoltPosition();
            sb.append("  当前出弹点世界坐标: [").append(fmt(world.x)).append(',').append(fmt(world.y)).append(',').append(fmt(world.z)).append("]\n");
            // 目的：SARH 半主动弹锁状态（定位"无锁制导"泄漏来源）；当前武器制导类型
            String currentGuidance = "<none>";
            java.util.Optional<AbstractVehicleWeapon<?>> operated = station.getCurrentWeapon();
            if (operated.isPresent() && operated.get().getData() != null
                    && operated.get().getData().getWeaponId() != null) {
                currentGuidance = operated.get().getData().getWeaponId().toString();
            }
            org.ywzj.vehicle.vehicle.part.RadarUnit lockRadar =
                    org.ywzj.rvp.radar.RVP_RadarRoleHelper.getLockedRadar(station);
            Entity radarLock = lockRadar == null ? null : lockRadar.getLockedEntity();
            sb.append("  雷达TWS锁: ").append(radarLock == null ? "<无>"
                    : radarLock.getName().getString() + " #" + radarLock.getId()).append('\n');
            Entity unitLock = station.getLockedEntity();
            sb.append("  武器站锁: ").append(unitLock == null ? "<无>"
                    : unitLock.getName().getString() + " #" + unitLock.getId()).append('\n');
        }
        sb.append("=== 结束 ===\n");
        return sb.toString();
    }

    /** 三位小数格式化（调试输出用）。 */
    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    // ==================================================================
    // 骨骼数学（Cube→Bolt，与本体 buildBolts 同构）
    // ==================================================================

    /**
     * 把出弹骨（含匿名子骨）的每个 Cube 展开为一根炮管 Bolt，追加进队列。
     * 锚定骨自身的 Bolt 不叠加骨骼自转（与本体主骨口径一致）；
     * 非锚定骨的骨骼自转写入 bolt.xRot/yRot（支持斜置发射）。
     */
    private static void appendBoneBolts(BedrockBone bone, BedrockBone anchorBone, Vec3 delta,
                                        List<Bolt> out, BedrockModel model) {
        boolean isAnchorBone = bone == anchorBone;
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z)));
            boltOffset = boltOffset.add(delta);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            if (isAnchorBone) {
                out.add(new Bolt(boltOffset, barrelLength, 0, 0));
            } else {
                bone.rotation.getEulerAnglesYXZ(selfRot);
                out.add(new Bolt(boltOffset, barrelLength,
                        (float) Math.toDegrees(selfRot.x), (float) Math.toDegrees(-selfRot.y)));
            }
        }
        // 目的：与本体 buildBolts 一致，递归展开出弹骨下的匿名子骨（命名骨属于其他部件，跳过）
        for (BedrockBone child : bone.getChildren()) {
            if (child == null || model.getBoneMap().containsValue(child)) {
                continue;
            }
            appendChildBolts(child, delta.add(new Vec3(child.x / 16, child.y / 16, child.z / 16)),
                    out, model);
        }
    }

    /** 子骨递归展开（见 {@link #appendBoneBolts}），偏移按子骨局部平移累加。 */
    private static void appendChildBolts(BedrockBone bone, Vec3 offset,
                                         List<Bolt> out, BedrockModel model) {
        for (BedrockCube cube : bone.cubes) {
            float x = cube.x() + cube.width() / 2;
            float y = cube.y() + cube.height() / 2;
            float z = cube.z();
            Vec3 boltOffset = new Vec3(bone.rotation.transform(new Vector3f(x, y, z)));
            boltOffset = boltOffset.add(offset);
            float barrelLength = cube.depth();
            Vector3f selfRot = new Vector3f();
            bone.rotation.getEulerAnglesYXZ(selfRot);
            out.add(new Bolt(boltOffset, barrelLength,
                    (float) Math.toDegrees(selfRot.x), (float) Math.toDegrees(-selfRot.y)));
        }
    }

    /**
     * 骨骼 bind 枢轴（模型空间，格单位）：自根向下组合父链平移与旋转。
     * 与 simplebedrockmodel 组全局变换同口径，用于把出弹骨平移差换算到锚定骨坐标系
     * （替代不可公开访问的 vehiclePartGroups 查表，避免新增 @Accessor）。
     */
    private static Vec3 bindPivot(@Nullable BedrockBone bone) {
        if (bone == null) {
            return Vec3.ZERO;
        }
        // 迭代回溯根骨，避免深层骨树递归栈风险；沿途记录各骨局部枢轴
        List<BedrockBone> chain = new ArrayList<>();
        BedrockBone current = bone;
        while (current != null && chain.size() <= MAX_BONE_DEPTH) {
            chain.add(current);
            current = current.parent;
        }
        Vec3 offset = Vec3.ZERO;
        Quaternionf rotation = new Quaternionf();
        for (int i = chain.size() - 1; i >= 0; i--) {
            BedrockBone node = chain.get(i);
            Vec3 local = new Vec3(node.x / 16, node.y / 16, node.z / 16);
            // 目的：父链累计旋转作用于本骨局部枢轴，得到该骨 bind 全局枢轴
            offset = offset.add(rotate(rotation, local));
            rotation = new Quaternionf(rotation).mul(node.rotation);
        }
        return offset;
    }

    /** 用四元数旋转 Vec3 的轻量封装（JOML 的 transform 是原地操作，避免误解）。 */
    private static Vec3 rotate(Quaternionf rotation, Vec3 vec) {
        Vector3f out = rotation.transform(new Vector3f((float) vec.x, (float) vec.y, (float) vec.z));
        return new Vec3(out.x, out.y, out.z);
    }
}
