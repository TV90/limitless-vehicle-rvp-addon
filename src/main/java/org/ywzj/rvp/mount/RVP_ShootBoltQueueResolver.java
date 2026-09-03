package org.ywzj.rvp.mount;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockCube;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * [RVP] 自定义挂架出弹队列构建器（公共层，双端可用）。
 *
 * <p>按挂架条目 {@code shoot_structure_bones}（载具结构模型骨骼）构建武器站的出弹
 * Bolt 队列：命中当前武器的条目按 {@code (ammo_slot, 配置顺序)} 排序，逐条目把出弹骨
 * 的 Cube 按"逐 Cube 一根炮管"展开，与 {@code missile_bones}（消失渲染骨骼）顺序
 * 一一对应，保证开火轮转与弹体消失构造性对齐。</p>
 *
 * <p>Cube→Bolt 数学与本体 {@code WeaponUnitData.buildBolts} / RVP 既有
 * {@code appendBoltsFromBone} 完全同构（Cube 前端面中心为炮闩、Z+ 为炮管轴、
 * depth 为管长），跨组偏移用骨骼 bind 枢轴差换算到锚定骨坐标系。</p>
 *
 * <p>纪律说明（agents.md）：本类为普通公共辅助类（非 mixin 包），不引用任何
 * {@code @OnlyIn(CLIENT)} 类型，可在服务端与客户端安全加载。</p>
 */
public final class RVP_ShootBoltQueueResolver {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 骨链回溯深度保护：结构模型骨层级异常（成环）时中断，防止死循环。 */
    private static final int MAX_BONE_DEPTH = 32;

    private RVP_ShootBoltQueueResolver() {}

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
     * 构建武器站当前武器的出弹 Bolt 队列。
     *
     * @param vehicle 载具实体（提供 vehicleId 与结构模型定位）
     * @param station 武器站（条目 {@code part_unit_id} 指向的部件）
     * @return 出弹队列；该武器站没有任何配置 {@code shoot_structure_bones} 的条目时
     *         返回 {@code null}（调用方应保持本体原 Bolt 不动，即回退现状行为）
     */
    @Nullable
    public static List<Bolt> buildQueue(AbstractVehicle vehicle, WeaponUnit station) {
        ResourceLocation vehicleId = vehicle.getVehicleId();
        if (vehicleId == null) {
            return null;
        }
        // 目的：取本武器站的挂架条目（沿用缓存中按 vehicleId 聚合的数据）
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicleId);
        List<RVP_CustomMountConfig> stationConfigs = new ArrayList<>();
        for (RVP_CustomMountConfig config : configs) {
            // 目的：仅取指向本武器站的条目（与渲染侧 matchesConfiguredPartUnit 同口径，含母站链）
            if (matchesStation(station, config.partUnitId()) && !config.shootStructureBones().isEmpty()) {
                stationConfigs.add(config);
            }
        }
        if (stationConfigs.isEmpty()) {
            return null;
        }

        // 目的：按当前选中武器过滤条目（与渲染侧消失逻辑同武器口径，保证对齐）；
        // 当前武器未定（无人乘骑等）时回退为全部条目并集，保证无人状态也有合理出弹点
        ResourceLocation currentWeaponId = currentWeaponId(station);
        List<RVP_CustomMountConfig> matched = new ArrayList<>();
        for (RVP_CustomMountConfig config : stationConfigs) {
            if (currentWeaponId != null && currentWeaponId.equals(config.weaponId())) {
                matched.add(config);
            }
        }
        if (matched.isEmpty()) {
            matched = stationConfigs;
        }

        // 目的：条目按 (ammo_slot, 配置顺序) 排序后拼接 shoot 骨——与消失渲染的
        // 条目顺序（assignAmmoVisibility 同排序）严格一致，这是对齐的构造性来源
        matched.sort(Comparator
                .comparingInt((RVP_CustomMountConfig config) -> config.ammoSlot() > 0 ? config.ammoSlot() : Integer.MAX_VALUE)
                .thenComparingInt(RVP_CustomMountConfig::configOrder));

        // 目的：解析结构模型与锚定骨，供 Cube→Bolt 的坐标系换算
        BedrockModel model = CommonAssetsManager.structureModelManager()
                .getStructureModel(vehicle.getStructureModel()).orElse(null);
        if (model == null) {
            LOGGER.warn("[RVP] 出弹队列构建失败：结构模型缺失 vehicle={}", vehicleId);
            return null;
        }
        String anchorBoneName = anchorBoneName(station);
        BedrockBone anchorBone = anchorBoneName.isEmpty() ? null : model.getBoneMap().get(anchorBoneName);
        VehicleCubeGroup anchorGroup = findXTurnGroup(station);

        List<Bolt> queue = new ArrayList<>();
        Set<String> usedBones = new LinkedHashSet<>();
        for (RVP_CustomMountConfig config : matched) {
            for (String boneName : config.shootStructureBones()) {
                // 目的：同一骨骼被多武器条目（红外/激光版挂架共用）重复引用时只展开一次
                if (boneName == null || boneName.isBlank() || !usedBones.add(boneName)) {
                    continue;
                }
                BedrockBone bone = model.getBoneMap().get(boneName);
                if (bone == null) {
                    LOGGER.warn("[RVP] 出弹骨不存在，跳过 vehicle={} bone={}（请核对结构模型）", vehicleId, boneName);
                    continue;
                }
                // 目的：出弹骨坐标系 → 锚定骨坐标系（xTurnGroup 空间）的平移差
                Vec3 delta = bindPivot(bone).subtract(bindPivot(anchorBone));
                appendBoneBolts(bone, anchorBone, delta, queue, model);
            }
        }
        return queue.isEmpty() ? null : queue;
    }

    /**
     * 武器站锚定骨名（本体约定 {@code structure_bone + "_barrel"}）。
     * 结构骨定义在父类 PartUnitData，经 {@link PartUnit#getData()} 公共方法读取。
     */
    private static String anchorBoneName(WeaponUnit station) {
        String structureBone = station.getData() == null ? null : station.getData().getStructureBone();
        return (structureBone == null || structureBone.isEmpty()) ? "" : structureBone + "_barrel";
    }

    /**
     * 当前选中武器的 weaponId（字符串口径，供应用器做换弹种检测）。
     * 无当前武器时返回空串。
     */
    public static String currentWeaponKey(WeaponUnit station) {
        ResourceLocation id = currentWeaponId(station);
        return id == null ? "" : id.toString();
    }

    /**
     * 当前选中武器的 weaponId。
     * 仅做公共类型解包（多弹种取当前选中子武器）；解包失败返回 null，
     * 由调用方回退为条目并集，不引入对客户端专属类型的引用。
     */
    @Nullable
    private static ResourceLocation currentWeaponId(WeaponUnit station) {
        Optional<AbstractVehicleWeapon<?>> weapon = station.getCurrentWeapon();
        if (weapon.isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> current = weapon.get();
        // 目的：多弹种武器站（VehicleMultiWeapons）出弹口径应跟随当前选中的子武器
        while (current instanceof VehicleMultiWeapons multi) {
            AbstractVehicleWeapon<?> selected = multi.getSelectedWeapon();
            if (selected == null || selected == current) {
                break;
            }
            current = selected;
        }
        return current.getData() == null ? null : current.getData().getWeaponId();
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

    /**
     * 把出弹骨（含匿名子骨）的每个 Cube 展开为一根炮管 Bolt，追加进队列。
     * 数学与本体 {@code buildBolts} / RVP {@code appendBoltsFromBone} 同构：
     * Cube 前端面中心为炮闩、Z+ 为炮管轴、depth 为管长；子骨平移按像素/16 累加；
     * 非锚定骨的骨骼自转写入 bolt.xRot/yRot（支持斜置发射）。
     */
    private static void appendBoneBolts(BedrockBone bone, BedrockBone anchorBone, Vec3 delta,
                                        List<Bolt> out, BedrockModel model) {
        // 目的：与本体 buildBolts 的主骨口径一致——锚定骨自身的 Bolt 不叠加骨骼自转
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
        for (BedrockBone child : bone.getChildren()) {
            if (child == null || model.getBoneMap().containsValue(child)) {
                continue;
            }
            appendChildBolts(child, offset.add(new Vec3(child.x / 16, child.y / 16, child.z / 16)),
                    out, model);
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
