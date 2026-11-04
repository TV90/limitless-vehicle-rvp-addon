package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.event.RVP_TurretTraverseHandler;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 受损方向机的观瞄跟随实际炮口；输入仍然驱动炮塔，不能用相机反向绕过转速限制。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT)
public final class RVP_ClientTurretTraverseSight {
    private RVP_ClientTurretTraverseSight() {}

    private static WeaponUnit constrainedSight() {
        var mc = Minecraft.getInstance();
        var local = LocalVehiclePlayer.instance;
        if (mc.player == null || mc.getCameraEntity() != mc.player || local.vehicle == null
                || !mc.options.getCameraType().isFirstPerson() || InputHandler.freeCamera
                || local.viewType == LocalVehiclePlayer.ViewType.THIRD_PERSON
                || RVP_ClientHitlState.isActive()) return null;
        if (local.vehicle.getOwnOperatorUnit(mc.player) instanceof WeaponUnit unit
                && RVP_TurretTraverseHandler.isLimited(unit)) return unit;
        return null;
    }

    /** 仅本地受损观瞄沿实际炮口；供已有意图圈绘制入口使用，不改写鼠标目标角。 */
    public static boolean followsActualSight(WeaponUnit unit) {
        return unit != null && unit == constrainedSight();
    }

    /** 与绿色弹道准星共享两帧落点及投影，避免装填圈继续追逐受损炮塔无法达到的目标角。 */
    public static net.minecraft.world.phys.Vec3 actualHitScreenPosition(WeaponUnit unit, float partial) {
        if (LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE
                || !followsActualSight(unit) || unit.weaponHitPos == null || unit.weaponHitPosO == null) return null;
        var previous = org.ywzj.vehicle.util.VectorUtil.worldToScreen(unit.weaponHitPosO);
        var current = org.ywzj.vehicle.util.VectorUtil.worldToScreen(unit.weaponHitPos);
        return previous.lerp(current, partial);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforePlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.side != net.minecraftforge.fml.LogicalSide.CLIENT) return;
        var mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || event.player != mc.player) return;
        if (event.player.getVehicle() instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle
                && vehicle.getOwnOperatorUnit(event.player) instanceof WeaponUnit unit) {
            // 本体的 NORMAL PlayerTick 会先 tickHit、测距、更新视线，再瞄准子站；必须先于整个链路。
            RVP_TurretTraverseHandler.constrain(unit);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        WeaponUnit unit = constrainedSight();
        if (unit == null) return;
        // 在本体 PlayerTick 观瞄更新和本项目炮塔限速之后，以实际炮口更新瞄准射线。
        RVP_TurretTraverseHandler.constrain(unit);
        var local = LocalVehiclePlayer.instance;
        local.cameraAimWith(unit);
        local.cameraAimRotXO = local.cameraAimRotX - Mth.wrapDegrees(local.cameraAimRotX - local.cameraAimRotXO);
        local.cameraAimRotYO = local.cameraAimRotY - Mth.wrapDegrees(local.cameraAimRotY - local.cameraAimRotYO);
        local.cameraAimRotZO = local.cameraAimRotZ - Mth.wrapDegrees(local.cameraAimRotZ - local.cameraAimRotZO);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        WeaponUnit unit = constrainedSight();
        if (unit == null) return;
        // 连续吊舱由独立相机事件读取同一实际姿态，不能再用极点不稳定的拆角覆盖它。
        if (unit instanceof org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit) return;
        float partial = (float) event.getPartialTick();
        Quaternionf rotation = RVP_TurretTraverseHandler.sightRotation(unit, partial);
        Vector3f euler = rotation.getEulerAnglesYXZ(new Vector3f());
        event.setYaw((float) Math.toDegrees(-euler.y));
        event.setPitch((float) Math.toDegrees(euler.x));
        event.setRoll((float) Math.toDegrees(euler.z));
        // 本体后续仍会用这个公开值设置滚转；保留正常的开火/爆炸镜头震动叠加顺序。
        org.ywzj.vehicle.client.handler.FirstPersonHandler.zRot = event.getRoll();
    }
}
