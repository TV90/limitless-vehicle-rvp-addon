package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.C2SSetGPSTarget;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RVP_ClientGPSUtil {

    public static boolean isGPSBombSelected() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        return weaponUnit != null
                && weaponUnit.getCurrentWeapon().isPresent()
                && weaponUnit.getCurrentWeapon().get() instanceof RVP_WeaponBase weapon
                && usesDesignatedPoint(weapon);
    }

    public static boolean ensureGPSBombSelected(LocalPlayer player) {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.not_in_vehicle"), true);
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null
                || weaponUnit.getCurrentWeapon().isEmpty()
                || !(weaponUnit.getCurrentWeapon().get() instanceof RVP_WeaponBase weapon)
                || !usesDesignatedPoint(weapon)) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.switch_to_bomb"), true);
            return false;
        }
        return true;
    }

    private static boolean usesDesignatedPoint(RVP_WeaponBase weapon) {
        return weapon.getData().usesGuidanceType(RVP_EnumGuidanceType.GPS);
    }

    public static void setGpsTarget(LocalPlayer player, ResourceLocation dimension, Vec3 pos) {
        if (RVP_ClientGPSState.isMultiMode()) {
            addGpsPoint(player, dimension, pos);
            return;
        }
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.set(dimension, pos));
        RVP_ClientGPSState.set(dimension, pos);
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.set_target"), true);
    }

    public static void addGpsPoint(LocalPlayer player, ResourceLocation dimension, Vec3 pos) {
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.add(dimension, pos));
        RVP_ClientGPSState.addPoint(dimension, pos);
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.add_point", RVP_ClientGPSState.getPointCount()), true);
    }

    public static void clearGpsTarget(LocalPlayer player) {
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.clear());
        RVP_ClientGPSState.clear();
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.clear_all"), true);
    }

    public static void toggleGpsMode(LocalPlayer player) {
        setGpsMode(player, RVP_ClientGPSState.getMode().toggled());
    }

    public static void setGpsMode(LocalPlayer player, RVP_ClientGPSState.Mode mode) {
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.setMode(mode));
        RVP_ClientGPSState.setMode(mode);
        player.displayClientMessage(Component.translatable(
                mode == RVP_ClientGPSState.Mode.MULTI
                        ? "message.ywzj_rvp.gps.mode_multi"
                        : "message.ywzj_rvp.gps.mode_single"), true);
    }

    /** 火控稳定器键按下时切换 GPS 单点/多点模式。由按键消费方保证是 FIRE_CONTROL_STABILIZER 键。 */
    public static boolean tryHandleModeToggleKey() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !isGPSBombSelected()) {
            return false;
        }
        toggleGpsMode(player);
        return true;
    }

    public static String currentModeTag() {
        return RVP_ClientGPSState.isMultiMode() ? "MULTI" : "SINGLE";
    }

    public static String currentPointTag() {
        if (!RVP_ClientGPSState.isActive()) {
            return "GPS -";
        }
        return "GPS " + RVP_ClientGPSState.getArmedPointNumber();
    }

    public static boolean isMachinegunSelected() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        return weaponUnit != null
                && weaponUnit.getCurrentWeapon().isPresent()
                && weaponUnit.getCurrentWeapon().get() instanceof RVP_WeaponBase weapon
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN;
    }

    public static Vec3 raycastGPSTarget(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return null;
        }
        // 用载具当前视图的摄像机位置（含武器站光瞄/操作员视角），
        // 而非原版主摄像机（开镜瞄准时它指向玩家实体而非光瞄镜头）。
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
        PartUnit<?> operatorUnit = vehicle == null
                ? null
                : vehicle.getOwnOperatorUnit(LocalVehiclePlayer.instance.getPlayer());
        Vec3 start;
        if (operatorUnit != null) {
            start = LocalVehiclePlayer.instance.cameraPosition(operatorUnit, 1.0F);
        } else {
            start = mc.gameRenderer.getMainCamera().getPosition();
        }
        Quaternionf rotation = new Quaternionf();
        rotation.rotateYXZ(
                (float) -Math.toRadians(LocalVehiclePlayer.instance.cameraAimRotY),
                (float) Math.toRadians(LocalVehiclePlayer.instance.cameraAimRotX),
                (float) Math.toRadians(LocalVehiclePlayer.instance.cameraAimRotZ)
        );
        Vector3f direction = new Vector3f(0, 0, 1);
        rotation.transform(direction);
        Vec3 end = start.add(new Vec3(direction).scale(LocalVehiclePlayer.renderDistance()));
        BlockHitResult hit = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return Vec3.atCenterOf(hit.getBlockPos());
    }
}
