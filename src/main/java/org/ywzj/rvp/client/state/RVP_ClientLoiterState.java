package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户端盘旋状态缓存，存储从服务端同步的盘旋圆信息供战术地图渲染。
 */
public final class RVP_ClientLoiterState {

    public record LoiterCircle(ResourceLocation dimension, double centerX, double centerZ,
                                double radius, boolean active, int vehicleEntityId) {}

    private static List<LoiterCircle> circles = List.of();

    private RVP_ClientLoiterState() {}

    public static void applySnapshot(List<LoiterCircle> newCircles) {
        circles = newCircles != null ? newCircles : List.of();
    }

    public static List<LoiterCircle> getCircles(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null) {
            return List.of();
        }
        return circles.stream().filter(c -> currentDimension.equals(c.dimension())).toList();
    }

    /** 当前载具是否在盘旋 */
    public static boolean isVehicleLoitering(int vehicleEntityId) {
        return circles.stream().anyMatch(c -> c.active && c.vehicleEntityId == vehicleEntityId);
    }
}
