package org.ywzj.rvp.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper;
import org.ywzj.vehicle.client.gui.VehicleStatusOverlay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** 状态 AUI 复用本体当前俯视图，几何、炮塔朝向、缩放和模块配色与左下角 HUD 完全同源。 */
public final class RVP_EquipSkeletonRenderer {
    /** 本体私有静态绘制入口，只解析一次；类属模组源码，方法名不参与 Minecraft 混淆。 */
    private static final Method HEADING = findHeading();
    /** 接口不兼容只警告一次，避免每帧刷日志。 */
    private static boolean warned;

    private RVP_EquipSkeletonRenderer() { }

    private static Method findHeading() {
        try {
            Method method = VehicleStatusOverlay.class.getDeclaredMethod("renderVehicleHeading", GuiGraphics.class,
                    float.class, AbstractVehicle.class, float.class, float.class, float.class, float.class, float.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            LogUtils.getLogger().warn("[RVP] 无法复用本体状态俯视图入口", error);
            return null;
        }
    }

    /** 调用方保留现有 scissor、AUI 坐标换算与 z=400，入口内使用同一套本体线框绘制。 */
    public static void render(GuiGraphics graphics, AbstractVehicle vehicle,
                              int x0, int y0, int x1, int y1, float partialTick) {
        if (HEADING == null || x1 - x0 <= 12 || y1 - y0 <= 12) return;
        try (var scope = RVP_DamagedPartWireframeHelper.displaying(vehicle)) {
            // 复用本体原绘制与既有 RVP 配色转发；不复制旧几何算法或创建另一份描边规则。
            HEADING.invoke(null, graphics, partialTick, vehicle, x0 + 6f, y0 + 6f,
                    x1 - x0 - 12f, y1 - y0 - 12f, 1f);
        } catch (IllegalAccessException | InvocationTargetException error) {
            if (!warned) LogUtils.getLogger().warn("[RVP] 状态面板俯视图绘制失败", error);
            warned = true;
        }
    }
}
