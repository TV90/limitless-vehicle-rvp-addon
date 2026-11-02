package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.uav.RVP_DeployableUavStateSavedData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按母车车型保存的辅助载具候选目录；候选自身保留完整有效部署策略。 */
public final class RVP_DeployableUavCatalog {
    /** 单个候选及其完整有效部署策略。
     * @param id 稳定候选 ID
     * @param name UI 展示名称
     * @param vehicleId 子载具数据 ID
     * @param role 联动角色
     * @param spawnOffset 相对母车生成偏移（格）
     * @param spawnYawMode 生成朝向策略
     * @param initialSpeed 初速度（blocks/tick）
     * @param effectiveConfig 继承母车公共策略后的单候选有效配置
     */
    public record Option(String id, String name, ResourceLocation vehicleId, String role,
                         Vec3 spawnOffset, String spawnYawMode, float initialSpeed,
                         RVP_DeployableUavConfig effectiveConfig) {}

    /** 母车车型对应的候选与默认候选。 */
    private record Entry(List<Option> options, String defaultOptionId) {}
    private static final Map<ResourceLocation, Entry> CATALOG = new ConcurrentHashMap<>();

    private RVP_DeployableUavCatalog() {}

    /** 全量替换候选缓存，避免资源重载后残留旧车型。 */
    public static void replace(Map<ResourceLocation, List<Option>> optionsByParent,
                               Map<ResourceLocation, String> defaultsByParent) {
        CATALOG.clear();
        optionsByParent.forEach((parentId, options) -> {
            if (parentId == null || options == null || options.isEmpty()) return;
            List<Option> valid = options.stream().filter(option -> option != null && option.id() != null
                    && !option.id().isBlank() && option.vehicleId() != null && option.effectiveConfig() != null).toList();
            if (valid.isEmpty()) return;
            String requested = defaultsByParent.get(parentId);
            String chosen = valid.stream().anyMatch(option -> option.id().equals(requested))
                    ? requested : valid.get(0).id();
            CATALOG.put(parentId, new Entry(valid, chosen));
        });
    }

    /** 清除候选表，供数据包资源重载时重建。 */
    public static void clear() { CATALOG.clear(); }

    /** 读取母车车型的只读候选表；无配置返回空列表。 */
    public static List<Option> getOptions(@Nullable ResourceLocation parentVehicleId) {
        Entry entry = parentVehicleId == null ? null : CATALOG.get(parentVehicleId);
        return entry == null ? List.of() : entry.options();
    }

    /** 读取母车车型默认候选 ID；无配置返回空字符串。 */
    public static String getDefaultOptionId(@Nullable ResourceLocation parentVehicleId) {
        Entry entry = parentVehicleId == null ? null : CATALOG.get(parentVehicleId);
        return entry == null ? "" : entry.defaultOptionId();
    }

    /** 读取母车车型默认候选，确保无状态时选择结果稳定。 */
    @Nullable public static Option getDefaultOption(@Nullable ResourceLocation parentVehicleId) {
        String id = getDefaultOptionId(parentVehicleId);
        return getOption(parentVehicleId, id);
    }

    /** 在候选列表中按稳定 ID 查找，不接受客户端直接提交的配置字段。 */
    @Nullable public static Option getOption(@Nullable ResourceLocation parentVehicleId, @Nullable String optionId) {
        if (optionId == null) return null;
        return getOptions(parentVehicleId).stream().filter(option -> option.id().equals(optionId)).findFirst().orElse(null);
    }

    /** 以服务器 SavedData 中的母车 UUID 选择解析部署配置，不依赖全局当前服务器。 */
    @Nullable public static Option getSelectedOption(ServerLevel level, AbstractVehicle parent) {
        String selected = getSelectedOptionId(level, parent.getUUID(), parent.getVehicleId());
        return getOption(parent.getVehicleId(), selected);
    }

    /** 返回当前母车的单候选有效参数；未知或未配置时回退 DISABLED。 */
    public static RVP_DeployableUavConfig getEffectiveConfig(ServerLevel level, AbstractVehicle parent) {
        Option option = getSelectedOption(level, parent);
        return option == null ? RVP_DeployableUavConfig.DISABLED : option.effectiveConfig();
    }

    /** 从母车实体及其 ServerLevel 推导有效配置，不依赖隐式全局服务器。 */
    public static RVP_DeployableUavConfig getEffectiveConfig(AbstractVehicle parent) {
        return parent.level() instanceof ServerLevel level ? getEffectiveConfig(level, parent)
                : RVP_DeployableUavConfig.DISABLED;
    }

    /** 从母车实体与其服务端维度读取已选候选 ID。 */
    public static String getSelectedOptionId(AbstractVehicle parent) {
        return parent.level() instanceof ServerLevel level
                ? getSelectedOptionId(level, parent.getUUID(), parent.getVehicleId()) : "";
    }

    /** 按已选状态返回 stable ID；无存档覆盖时使用车型默认值。 */
    public static String getSelectedOptionId(ServerLevel level, java.util.UUID parentUuid,
                                             ResourceLocation parentVehicleId) {
        String fallback = getDefaultOptionId(parentVehicleId);
        String selected = RVP_DeployableUavStateSavedData.getSelectedOptionId(level, parentUuid, parentVehicleId, fallback);
        return getOption(parentVehicleId, selected) == null ? fallback : selected;
    }
}