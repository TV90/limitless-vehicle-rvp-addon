package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/** 飞机离开观瞄时恢复座位默认前方，不沿用吊舱当前瞄准点。 */
public final class RVP_ClientAircraftViewReset {
    /** 工具类不允许实例化。 */
    private RVP_ClientAircraftViewReset() {}

    /** 复用已有视角切换入口，在本体写入新视角前同步恢复相机和玩家朝向。 */
    public static void beforeViewChange(boolean toScope, boolean toOperator) {
        // 调用电视导弹会话门，远程弹体仍拥有原有观察与退出行为。
        if (toScope || RVP_ClientHitlState.isActive()) return;
        // 调用独立复位逻辑，只修改仍在同一架飞机上的本地玩家。
        reset(LocalVehiclePlayer.instance, Minecraft.getInstance().player, toScope, toOperator);
    }

    /** 执行真实复位；分离参数入口，便于无窗口测试视角状态与重复切换。 */
    static boolean reset(LocalVehiclePlayer local, Player player, boolean toScope, boolean toOperator) {
        if (toScope || local == null || player == null || local.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || local.vehicle == null || local.seat == null || local.seat.partUnit == null
                || player.getVehicle() != local.vehicle || local.seat.partUnit.getVehicle() != local.vehicle
                || !(local.vehicle instanceof FixedWingVehicle || local.vehicle instanceof RotaryWingVehicle)) {
            return false;
        }
        var vehicle = local.vehicle;
        // 调用本体座位默认航向，通常座位偏角为零，即机头方向；特殊座位保留自身默认朝向。
        float seatYaw = Mth.wrapDegrees(local.seat.partUnit.getSeatRot() - vehicle.getYRot());
        Quaternionf forward = vehicle.rotYXZ().rotateY((float) Math.toRadians(-seatYaw));
        RVP_OpticalSightMath.Angles defaults;
        if (toOperator) {
            // 调用完整姿态解角，座舱沿机体默认前方，并保留本体的观察偏角和机体倾斜。
            defaults = RVP_OpticalSightMath.cameraAngles(
                    forward.rotateX((float) Math.toRadians(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE)), null);
        } else {
            // 调用本体方向解角，第三人称朝机头并保持画面水平，不继承吊舱横滚。
            Vec2 nose = VectorUtil.vecToRot(new Vec3(forward.transform(new Vector3f(0, 0, 1))));
            defaults = new RVP_OpticalSightMath.Angles(
                    Mth.wrapDegrees(nose.x + LocalVehiclePlayer.CAMERA_UPWARD_ANGLE), Mth.wrapDegrees(nose.y), 0);
        }

        // 同时重置两帧相机与玩家角度，下一帧不能再从旧的吊舱方向插值回来。
        local.cameraAimRotX = local.cameraAimRotXO = defaults.pitch();
        local.cameraAimRotY = local.cameraAimRotYO = defaults.yaw();
        local.cameraAimRotZ = local.cameraAimRotZO = defaults.roll();
        player.setXRot(defaults.pitch());
        player.xRotO = defaults.pitch();
        player.setYRot(defaults.yaw());
        player.yRotO = defaults.yaw();
        player.setYHeadRot(defaults.yaw());
        player.yHeadRotO = defaults.yaw();
        player.setYBodyRot(defaults.yaw());
        player.yBodyRotO = defaults.yaw();
        local.playerLerpXRot = defaults.pitch();
        local.playerLerpYRot = defaults.yaw();
        local.playerLerpSteps = 0;
        local.playerLocalXRot = LocalVehiclePlayer.CAMERA_UPWARD_ANGLE;
        local.playerLocalYRot = seatYaw;
        local.mouseTurnedAfterScope = false;
        FirstPersonHandler.zRot = defaults.roll();
        // 调用显示会话清理，保留原有世界锁点，只丢弃观瞄画面与解锁回交缓存。
        RVP_ClientFocusLock.clear();
        RVP_ClientOpticalSight.clearView();
        // 调用默认关闭的退出诊断，便于实机核对最终第三人称/座舱落点与默认朝向。
        org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.aircraftViewReset(
                toOperator, defaults.pitch(), defaults.yaw(), defaults.roll());
        return true;
    }
}
