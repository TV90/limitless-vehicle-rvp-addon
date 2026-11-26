package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_ThermalImagerType;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.lang.ref.WeakReference;
import java.util.List;

/** 本地观瞄站的调色选择。热像开关与第三人称夜视仍走各自原有通道。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public final class RVP_ThermalImagerSelection {
    /** 绑定武器站实例，换座或重进世界从本站第一种热像开始。 */
    private static WeakReference<WeaponUnit> station = new WeakReference<>(null);
    /** 当前类型在本站列表中的下标。 */
    private static int index;

    private RVP_ThermalImagerSelection() {}

    public static void bind(WeaponUnit unit) {
        if (station.get() != unit) {
            station = new WeakReference<>(unit);
            index = 0;
        }
    }

    public static RVP_ThermalImagerType selected(WeaponUnit unit) {
        bind(unit);
        if (unit == null || !(unit.getData() instanceof WeaponUnitDataExt data)) return RVP_ThermalImagerType.WHITE_HOT;
        List<RVP_ThermalImagerType> types = data.ywzj_rvp$getThermalImagerTypes();
        return types.get(index < types.size() ? index : 0);
    }

    /** 本体先处理物理按下；同一事件内决定是否继续显示下一种热像，避免两个调色之间闪回白光。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && AllKeys.TOGGLE_THERMAL_IMAGING.matches(event.getKey(), event.getScanCode())) {
            afterNativeToggle();
        }
    }

    /** 鼠标改绑与键盘走同一条切换路径。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMouse(InputEvent.MouseButton.Pre event) {
        if (event.getAction() == GLFW.GLFW_PRESS && AllKeys.TOGGLE_THERMAL_IMAGING.matchesMouse(event.getButton())) {
            afterNativeToggle();
        }
    }

    private static void afterNativeToggle() {
        Minecraft mc = Minecraft.getInstance();
        LocalVehiclePlayer player = LocalVehiclePlayer.instance;
        if (mc.screen != null || mc.player == null || mc.player.isSpectator() || mc.gameMode == null
                || player == null || !player.onVehicle() || player.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || RVP_ClientHitlState.isActive()) return;
        WeaponUnit unit = player.getWeaponUnit();
        if (unit == null || !unit.withThermalImager() || !(unit.getData() instanceof WeaponUnitDataExt data)
                || RVP_ClientFcsState.isDestroyed(unit)) return;
        bind(unit);
        // 本体刚开启时选首项；刚关闭时仅在还有下一项的情况下接续热像。
        if (!player.thermalImaging && index + 1 < data.ywzj_rvp$getThermalImagerTypes().size()) {
            index++;
            player.thermalImaging = true;
        } else {
            index = 0;
        }
    }
}
