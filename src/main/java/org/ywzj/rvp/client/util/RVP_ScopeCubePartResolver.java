package org.ywzj.rvp.client.util;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonElement;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * HUD/AUI 的 cube→部件及真实结构骨反查缓存（纯客户端）：
 * 本体 {@code VehicleStatusOverlay.addHeadingCube} 只收 cube 与颜色、无部件上下文。
 * cube→部件归属是**静态结构关系**（部件与 partCubeOBBs 在载具 init 时构建、列表实例
 * 不随帧变化），故按载具缓存一份恒等映射、换车才重建——单次构建 O(部件×cube)，
 * 之后按恒等映射反查，模型重载和部件数量变化时重新关联。
 */
public final class RVP_ScopeCubePartResolver {

    /** 上次构建映射的载具（换车即重建）。 */
    private AbstractVehicle lastVehicle;
    /** cube→部件 恒等映射（载具级静态结构）。 */
    private final Map<VehicleCubeOBB, PartUnit<?>> cubeToPart = new IdentityHashMap<>();

    /** 实际骨组到结构骨名，独立炮管、座圈及具名子骨不混用部件 ID。 */
    private final Map<VehicleCubeGroup, String> groupBones = new IdentityHashMap<>();
    /** 模型重载或部件重建时使结构缓存失效。 */
    private BedrockModel lastModel;
    /** 模型骨名反查，重建时生成一次，供车体与全部部件共用。 */
    private final Map<BedrockBone, String> boneNames = new IdentityHashMap<>();
    private int lastPartCount = -1;
    /** 已同步的原始载具配置快照；引用变化时重读真实 structure_model。 */
    private Map<ResourceLocation, JsonElement> lastConfigs;
    /** 当前车型实际配置的结构模型 ID，不能使用本体已弃用的 entity/<id> 推导。 */
    private ResourceLocation modelId;

    public RVP_ScopeCubePartResolver() {
    }

    /** 反查 cube 所属部件（含子武器站/装饰件登记）；车体层等没有独立部件的 cube 返回 null，但仍能反查骨名。 */
    @Nullable
    public PartUnit<?> resolve(AbstractVehicle vehicle, VehicleCubeOBB cube) {
        if (vehicle == null || cube == null) {
            return null;
        }
        // 复用现有同步配置入口；本体 getStructureModel() 已弃用，会固定返回错误的 entity/<车型>。
        var configs = RVP_VehicleExtendedConfigManager.INSTANCE.getRawVehicleJson();
        if (vehicle != lastVehicle || configs != lastConfigs) {
            lastConfigs = configs;
            modelId = configuredModel(configs.get(vehicle.getVehicleId()));
        }
        BedrockModel model = modelId == null ? null : CommonAssetsManager.structureModelManager()
                .getStructureModel(modelId).orElse(null);
        if (vehicle != lastVehicle || model != lastModel || lastPartCount != vehicle.getPartUnits().size()) {
            lastModel = model;
            lastPartCount = vehicle.getPartUnits().size();
            rebuild(vehicle);
        }
        return cubeToPart.get(cube);
    }

    /** 读取载具数据的真实结构模型路径；不根据车型 ID 猜测目录。 */
    @Nullable
    static ResourceLocation configuredModel(@Nullable JsonElement config) {
        if (config == null || !config.isJsonObject()) return null;
        JsonElement id = config.getAsJsonObject().get("structure_model");
        return id == null || !id.isJsonPrimitive() ? null : ResourceLocation.tryParse(id.getAsString());
    }

    /** cube 对应的真实结构骨；匿名子组继承最近的具名父骨。 */
    @Nullable
    public String resolveBone(AbstractVehicle vehicle, VehicleCubeOBB cube) {
        resolve(vehicle, cube);
        return cube == null ? null : groupBones.get(cube.group);
    }

    /** 重建映射：部件 + 其子武器站 + 装饰件全部登记（putIfAbsent 保首个归属）。 */
    private void rebuild(AbstractVehicle vehicle) {
        lastVehicle = vehicle;
        cubeToPart.clear();
        groupBones.clear();
        boneNames.clear();
        if (lastModel != null) {
            lastModel.getBoneMap().forEach((name, bone) -> boneNames.put(bone, name));
            // 本体车体 OBB 独立于 PartUnit 构建，必须单独关联，否则车体和车体子骨会漏染色。
            registerBodyGroups(vehicle.getVehicleCubeOBBs(), lastModel.getBoneMap().get("vehicle_body"),
                    boneNames, groupBones);
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            register(partUnit);
            if (partUnit instanceof WeaponUnit weaponUnit) {
                for (WeaponUnit sub : weaponUnit.getSubWeaponUnits()) {
                    register(sub);
                }
            }
        }
        for (PartUnit<?> decoration : vehicle.getDecorationUnits().values()) {
            register(decoration);
        }
    }

    private void register(PartUnit<?> partUnit) {
        if (lastModel != null && partUnit.getData() != null) {
            String root = partUnit.getData().getStructureBone();
            BedrockBone bone = root == null ? null : lastModel.getBoneMap().get(root);
            if (bone != null) {
                registerGroups(partUnit.getStructureGroup(), bone, boneNames, root);
            }
        }
        for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
            cubeToPart.putIfAbsent(cube, partUnit);
        }
    }

    /** 沿实际车体 cube 找到根组；从子骨开始或多 cube 共组也只登记一次。 */
    static void registerBodyGroups(java.util.List<VehicleCubeOBB> cubes, @Nullable BedrockBone body,
                                   Map<BedrockBone, String> names, Map<VehicleCubeGroup, String> result) {
        if (body == null) return;
        for (VehicleCubeOBB cube : cubes) {
            VehicleCubeGroup root = cube.group;
            if (root == null) continue;
            while (root.parent != null) root = root.parent;
            if (!result.containsKey(root)) registerGroups(root, body, names, "vehicle_body", result);
        }
    }

    /** 本体与 SBM 按同一 Bedrock 子骨顺序构建 children，复用该顺序关联真实组。 */
    static void registerGroups(VehicleCubeGroup group, BedrockBone bone,
                               Map<BedrockBone, String> names, String inherited,
                               Map<VehicleCubeGroup, String> result) {
        if (group == null) return;
        String name = names.getOrDefault(bone, inherited);
        if (name != null) result.put(group, name);
        int index = 0;
        for (BedrockBone child : bone.getChildren()) {
            if (index < group.children.size()) registerGroups(group.children.get(index), child, names, name, result);
            index++;
        }
    }

    private void registerGroups(VehicleCubeGroup group, BedrockBone bone,
                                Map<BedrockBone, String> names, String inherited) {
        registerGroups(group, bone, names, inherited, groupBones);
    }
}
