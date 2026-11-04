package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.event.RVP_TurretTraverseHandler;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** 在 Forge 相机事件中用吊舱实际完整姿态更新画面，避开本体相机在竖直方向的拆角跳变。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
public final class RVP_ClientOpticalSight {
    /** 最近显示的观瞄站，用对象身份隔离换车、换座和换世界。 */
    @Nullable private static RVP_OpticalWeaponUnit station;
    /** 上一帧完整相机角度，用于保持穿过竖直方向时的表示连续。 */
    @Nullable private static RVP_OpticalSightMath.Angles previous;
    /** 稳定画面的完整姿态；机械偏航在竖直处变化时不应让画面跟着翻滚。 */
    @Nullable private static Quaterniond viewRotation;
    /** 同一渲染帧内累加的鼠标请求姿态，不能被尚未跟上的实际炮口覆盖。 */
    @Nullable private static Quaterniond inputRotation;
    /** 原类型观瞄站每个对象只提示一次；弱键不阻止已卸载载具回收。 */
    private static final Set<WeaponUnit> WARNED = Collections.newSetFromMap(new WeakHashMap<>());

    /** 工具类不允许实例化。 */
    private RVP_ClientOpticalSight() {}

    /** 退出观瞄时立即释放画面姿态，避免同一刻再次开镜继承旧方向。 */
    public static void clearView() {
        station = null;
        previous = null;
        viewRotation = inputRotation = null;
    }

    /** 按既有能力判断是否需要过竖直方向输入；不按载具或武器 ID 分支。 */
    public static boolean usesContinuousInput(WeaponUnit unit) {
        return unit != null && unit.withFocusLocker() && (unit instanceof RVP_OpticalWeaponUnit
                || unit.xRotMax - unit.xSelfRot > 90 || unit.xRotMin - unit.xSelfRot < -90);
    }

    /** 读取玩家上一帧看到的完整姿态，首帧回退到真实相机，不把机械局部角当成屏幕角。 */
    public static Quaterniond currentRotation(WeaponUnit unit) {
        if (station == unit && viewRotation != null) return new Quaterniond(viewRotation);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        // 调用完整相机姿态构造；Camera 对象自身没有事件附加的横滚，需使用本体实际横滚值。
        return RVP_OpticalSightMath.rotation(camera.getXRot(), camera.getYRot(), FirstPersonHandler.zRot);
    }

    /** 普通吊舱观瞄按屏幕轴转动，只改变目标角，不绕过本体机械限速。 */
    public static boolean tryHandleTurn(double yawInput, double pitchInput) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (minecraft.player == null || minecraft.screen != null || minecraft.isPaused()
                || !minecraft.options.getCameraType().isFirstPerson() || local == null || local.vehicle == null
                || local.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || !(local.getWeaponUnit() instanceof RVP_OpticalWeaponUnit current)
                || current.getOwner() != minecraft.player || current.getFocusLockPos() != null
                || RVP_ClientHitlState.isActive() || (yawInput == 0 && pitchInput == 0)) return false;
        if (!Double.isFinite(yawInput) || !Double.isFinite(pitchInput)) return true;
        if (!local.mouseTurnedAfterScope) {
            if (Math.abs(yawInput) < 0.5D && Math.abs(pitchInput) < 0.5D) return true;
            // 调用本体公开目标角入口，保留开镜后的首次手动接管规则。
            current.setXAimRot(current.getXRot());
            current.setYAimRot(current.getYRot());
            local.mouseTurnedAfterScope = true;
        }
        // 调用本体同规则的追赶灵敏度，只把最终增量映射到屏幕轴，不改变原输入尺度。
        double pitchDelta = pitchInput * 0.15D * sensitivity(current.getXRotSpeed(), current.getXAimRot() - current.getXRot());
        double yawDelta = yawInput * 0.15D * sensitivity(current.getYRotSpeed(), current.getYAimRot() - current.getYRot());
        // 调用观瞄站公开目标角换算，同一帧多次输入从上次请求姿态继续累加。
        Quaterniond source = station == current && inputRotation != null ? inputRotation : currentRotation(current);
        inputRotation = current.turnInView(source, yawDelta, pitchDelta);
        local.playerLerpSteps = 0.0F;
        return true;
    }

    /** 与本体 scopeAimSensitivity 保持相同的转速和误差规则。 */
    private static float sensitivity(float speed, float error) {
        if (speed <= 0) return 0;
        float effective = Math.max(speed, 1);
        float trackingTime = Math.abs(Mth.wrapDegrees(error)) / effective;
        return Math.min(effective / 16, effective / Math.max(trackingTime * 10, 1));
    }

    /** 焦点镜头与普通观瞄共享已显示姿态，解锁时不重建一个横轴相反的机械镜头。 */
    public static void acceptFocusRotation(WeaponUnit unit, Quaterniondc rotation, RVP_OpticalSightMath.Angles angles) {
        if (!(unit instanceof RVP_OpticalWeaponUnit)) return;
        viewRotation = new Quaterniond(rotation);
        inputRotation = new Quaterniond(rotation);
        previous = angles;
    }

    /** 先恢复连续机械镜头，随后焦点服务与原有震屏按原顺序叠加。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        // 调用本体座位入口，仅处理本地第一人称观瞄；电视导弹保持原有最高控制权。
        if (minecraft.player == null || local == null || local.vehicle == null
                || event.getCamera().getEntity() != minecraft.player
                || !minecraft.options.getCameraType().isFirstPerson()
                || local.viewType != LocalVehiclePlayer.ViewType.SCOPE || RVP_ClientHitlState.isActive()) {
            // 调用统一会话清理，不保留已离开的观瞄画面。
            clearView();
            return;
        }
        WeaponUnit selected = local.getWeaponUnit();
        if (!(selected instanceof RVP_OpticalWeaponUnit current)
                || current.getOwner() != minecraft.player || current.getVehicle() != local.vehicle) {
            if (minecraft.screen == null && !(selected instanceof RVP_OpticalWeaponUnit)
                    && usesContinuousInput(selected) && WARNED.add(selected)) {
                minecraft.player.displayClientMessage(Component.literal(
                        "[RVP观瞄] 当前仍为原版观瞄站，过竖直方向修复未启用。请在客户端和服务器同步权威载具配置，并重进世界。"), false);
            }
            // 调用统一会话清理，隔离不再属于本地玩家的站。
            clearView();
            return;
        }
        if (station != current) {
            station = current;
            previous = null;
            viewRotation = inputRotation = null;
        }
        // 调用默认关闭的只读日志，保留连续姿态接管之前的相机角度，不改事件。
        org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.opticalCamera("OPTICAL_BEFORE", current, event);
        // 调用已有实际姿态入口，画面仍沿实际机械视线；稳定器开启时只补偿看向目标所需的旋转。
        Quaternionf actual = RVP_TurretTraverseHandler.sightRotation(current, (float) event.getPartialTick());
        viewRotation = viewRotation == null || !current.withStabilizer() ? new Quaterniond(actual)
                : RVP_OpticalSightMath.followDirection(viewRotation, RVP_OpticalSightMath.direction(new Quaterniond(actual)));
        previous = RVP_OpticalSightMath.cameraAngles(new Quaternionf(viewRotation), previous);
        inputRotation = new Quaterniond(viewRotation);
        event.setYaw(previous.yaw());
        event.setPitch(previous.pitch());
        event.setRoll(previous.roll());
        // 本体随后会以这个公开值设置横滚，再叠加爆炸震屏；在其原有入口之前提供同一姿态。
        FirstPersonHandler.zRot = previous.roll();
        // 调用同帧接管后观测，与最终相机和鼠标输入编号关联；仅开启指令时写专用文件。
        org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.opticalCamera("OPTICAL_AFTER", current, event);
    }
}
