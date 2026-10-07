package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 残件隐藏骨配置：vehicleId -> (partId -> 渲染骨名列表)。数据包重载时整表重建。
 * <p>
 * 数据来源：载具 JSON 顶层 {@code rvp_part_hidden_bones}（键 = parts[].id，值 = 渲染模型骨名数组），
 * 由 {@code VehicleDataManagerMixin} 在 apply 阶段解析填充。
 * 消费方：{@code RVP_VehiclePartRender}（残件渲染器）在渲染前把指定骨 visible 置 false，
 * 渲染库 {@code BakedBedrockModel.renderBone} 会在绘制与子树递归之前检查该标志，连带整棵子树跳过。
 * 专用服场景边界：客户端进程不执行数据包 apply，本表在专用服客户端为空（同挂架配置根因，已与用户确认暂不下发）。
 * </p>
 */
public final class RVP_PartHiddenBonesCache {

    /** vehicleId -> (partId -> 骨名列表) 的不可变快照；volatile 保证渲染线程无锁读到最新整表 */
    private static volatile Map<ResourceLocation, Map<String, List<String>>> TABLE = Map.of();

    private RVP_PartHiddenBonesCache() {}

    /** 数据包重载前清空整表（与其它载具级缓存同生命周期） */
    public static void clear() {
        TABLE = Map.of();
    }

    /** 填入单个载具的按部件隐藏骨表；写时复制替换快照，空表也要 put 以覆盖旧配置 */
    public static void put(ResourceLocation vehicleId, Map<String, List<String>> byPartId) {
        Map<ResourceLocation, Map<String, List<String>>> copy = new HashMap<>(TABLE);
        copy.put(vehicleId, Map.copyOf(byPartId));
        TABLE = Map.copyOf(copy);
    }

    /** 取该载具该部件的隐藏骨；无配置返回空表。渲染热路径只读 volatile 字段，不额外分配。 */
    public static List<String> get(ResourceLocation vehicleId, String partUnitId) {
        Map<String, List<String>> byPartId = TABLE.get(vehicleId);
        if (byPartId == null) return List.of();
        List<String> bones = byPartId.get(partUnitId);
        return bones == null ? List.of() : bones;
    }
}
