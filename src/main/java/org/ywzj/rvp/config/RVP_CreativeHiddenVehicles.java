package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * 创造模式物品栏隐藏表：标记「仅由母车部署、不鼓励直接刷出」的载具（雷达车、随车无人机等）。
 *
 * <p>数据来源：载具 JSON 顶层 {@code rvp_hide_in_creative}（布尔，默认 false），
 * 由 {@link RVP_VehicleSupplementListener} 在数据重载 / S2C 同步时解析，整表重建。</p>
 *
 * <p>消费方：{@code RVP_CreativeTabEvents} 在 {@code BuildCreativeModeTabContentsEvent}
 * （仅客户端触发）里把对应载具生成物的 ItemStack 从创造栏移除，搜索页同样生效。</p>
 *
 * <p>只影响创造栏展示：载具实体、数据与母车部署链路不变，仍可由母车放飞或 {@code /summon} 生成。</p>
 */
public final class RVP_CreativeHiddenVehicles {

    /** 写时复制快照；创造栏重建在客户端主线程读取，volatile 保证可见性 */
    private static volatile Set<ResourceLocation> HIDDEN = Set.of();

    private RVP_CreativeHiddenVehicles() {}

    /** 数据包重载前清空整表（与其它载具级缓存同生命周期） */
    public static void clear() {
        HIDDEN = Set.of();
    }

    public static void add(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return;
        }
        Set<ResourceLocation> copy = new HashSet<>(HIDDEN);
        copy.add(vehicleId);
        HIDDEN = Set.copyOf(copy);
    }

    public static boolean isHidden(ResourceLocation vehicleId) {
        return vehicleId != null && HIDDEN.contains(vehicleId);
    }

    public static boolean isEmpty() {
        return HIDDEN.isEmpty();
    }
}
