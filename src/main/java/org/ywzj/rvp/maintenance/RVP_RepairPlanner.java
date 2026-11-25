package org.ywzj.rvp.maintenance;

import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 双端共用的完整优先队列和维修目标选择；正常部件占排序位置，结算只取失效模块。 */
public final class RVP_RepairPlanner {
    /** 网络队列长度保护；不再用 64 截断多块爆反载具的完整队列。 */
    public static final int MAX_BONES = 4096;

    /** 两类独立队列；同骨的爆反和设备统一归入爆反队列。 */
    public record Order(List<String> eraBones, List<String> deviceBones) {
        public Order {
            eraBones = List.copyOf(eraBones);
            deviceBones = List.copyOf(deviceBones);
        }
    }

    private RVP_RepairPlanner() { }

    /** 调用载具模块配置和部件顺序，得到稳定的全车默认排序。 */
    public static Order defaults(AbstractVehicle vehicle, BoneMaintenanceConfig.ModuleRepair config) {
        Map<String, Set<BoneModuleType>> modules =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        List<String> parts = vehicle.getPartUnits().stream().map(part -> part.getId()).toList();
        return defaults(modules, parts, config);
    }

    /** 纯数据入口：JSON 部件序为同优先级的顺序，非部件骨按名字补齐。 */
    public static Order defaults(Map<String, Set<BoneModuleType>> modules, List<String> parts,
                                 BoneMaintenanceConfig.ModuleRepair config) {
        LinkedHashSet<String> stable = new LinkedHashSet<>();
        for (String bone : parts) if (modules.containsKey(bone)) stable.add(bone);
        modules.keySet().stream().sorted().forEach(stable::add);
        List<String> era = new ArrayList<>(), devices = new ArrayList<>();
        for (String bone : stable) {
            Set<BoneModuleType> types = modules.get(bone);
            if (types.contains(BoneModuleType.ERA)) {
                if (config.isRepairable(BoneModuleType.ERA)) era.add(bone);
            } else if (types.stream().anyMatch(type -> type != BoneModuleType.MAINTENANCE
                    && config.isRepairable(type))) {
                devices.add(bone);
            }
        }
        // 稳定排序保留同类别内部的 JSON 次序；默认类型优先级与服务器原规则相同。
        devices.sort(Comparator.comparingInt(bone ->
                RVP_VehicleHitboxFactorManager.resolveDevicePriorityRank(modules.get(bone))));
        return new Order(era, devices);
    }

    /** 保留用户已有次序，去除失效配置项，补齐新增部件；正常/受损状态不影响队列保留。 */
    public static Order complete(Order requested, Order defaults) {
        return new Order(complete(requested.eraBones(), defaults.eraBones()),
                complete(requested.deviceBones(), defaults.deviceBones()));
    }

    private static List<String> complete(List<String> requested, List<String> defaults) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String bone : requested) if (defaults.contains(bone)) result.add(bone);
        result.addAll(defaults);
        return List.copyOf(result);
    }

    /** 点击置顶和拖拽共用的保序移动，目标索引指移动后的索引。 */
    public static List<String> move(List<String> bones, String bone, int destination) {
        List<String> result = new ArrayList<>(bones);
        if (!result.remove(bone)) return bones;
        result.add(Math.max(0, Math.min(destination, result.size())), bone);
        return List.copyOf(result);
    }

    /** 对完整顺序按当前失效表过滤并分配配额，预览和真正结算调用同一方法。 */
    public static Order select(Order order, Map<String, Set<BoneModuleType>> inactive,
                               BoneMaintenanceConfig.ModuleRepair config) {
        List<String> era = order.eraBones().stream()
                .filter(bone -> inactive.getOrDefault(bone, Set.of()).contains(BoneModuleType.ERA))
                .filter(bone -> config.isRepairable(BoneModuleType.ERA)).toList();
        List<String> devices = order.deviceBones().stream()
                .filter(bone -> inactive.getOrDefault(bone, Set.of()).stream().anyMatch(type ->
                        type != BoneModuleType.MAINTENANCE && config.isRepairable(type))).toList();
        int eraCount = era.isEmpty() ? 0 : Math.min(era.size(),
                Math.max(config.eraRecoverMin, (int) Math.ceil(era.size() * (double) config.eraRecoverFraction)));
        return new Order(era.subList(0, eraCount),
                devices.subList(0, config.deviceQuota(devices.size())));
    }
}
