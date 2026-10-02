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
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.gps.RVP_GpsModeSupport;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import static org.ywzj.rvp.client.state.RVP_ClientGPSState.Mode;

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

    /** 当前操作的武器站；未在载具返回 null。 */
    private static WeaponUnit currentWeaponUnit() {
        return LocalVehiclePlayer.instance.onVehicle()
                ? LocalVehiclePlayer.instance.getWeaponUnit()
                : null;
    }

    /** 当前选中武器的 RVP 数据；非 RVP/未选中返回 null（模式/上限走默认）。 */
    private static RVP_WeaponData currentRvpWeaponData() {
        return RVP_GpsModeSupport.currentRvpWeaponData(currentWeaponUnit());
    }

    // [RVP] 原 ensureGPSBombSelected（提示"未在载具/请切换 GPS 炸弹"）已删除：
    // 全项目零调用的死代码，其"不适用弹提示"行为也不符合静默约定。

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
        int maxPoints = RVP_GpsModeSupport.multiMaxPoints(currentRvpWeaponData());
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.add(dimension, pos));
        RVP_ClientGPSState.addPoint(dimension, pos, maxPoints);
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.add_point", RVP_ClientGPSState.getPointCount()), true);
    }

    public static void clearGpsTarget(LocalPlayer player) {
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.clear());
        RVP_ClientGPSState.clear();
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.clear_all"), true);
    }

    /**
     * 循环切换 GPS 模式（原"单点/多点"二值切换的参数化版）：沿当前武器
     * {@code gps_modes} 配置序列切到下一个<b>过传感器门禁</b>的模式——FAST 需要
     * {@code rvp_fire_control_sensor_mode: "eo_ccip"}、RADAR 需要 RF 传感器，不满足即跳过。
     */
    public static void toggleGpsMode(LocalPlayer player) {
        Mode next = RVP_GpsModeSupport.nextAvailable(currentWeaponUnit(), currentRvpWeaponData(),
                RVP_ClientGPSState.getMode());
        setGpsMode(player, next);
    }

    public static void setGpsMode(LocalPlayer player, Mode mode) {
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.setMode(mode));
        RVP_ClientGPSState.setMode(mode);
        player.displayClientMessage(Component.translatable(modeMessageKey(mode)), true);
    }

    private static String modeMessageKey(Mode mode) {
        return switch (mode) {
            case SINGLE -> "message.ywzj_rvp.gps.mode_single";
            case MULTI -> "message.ywzj_rvp.gps.mode_multi";
            case FAST -> "message.ywzj_rvp.gps.mode_fast";
            case RADAR -> "message.ywzj_rvp.gps.mode_radar";
        };
    }

    /** 火控稳定器键按下时循环切换 GPS 模式（可用性感知）。由按键消费方保证是 FIRE_CONTROL_STABILIZER 键。 */
    public static boolean tryHandleModeToggleKey() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !isGPSBombSelected()) {
            return false;
        }
        toggleGpsMode(player);
        return true;
    }

    /**
     * [RVP] FAST 开火接管判定：当前模式为 FAST，且本武器就是本地玩家当前操作武器站的
     * 选中 GPS 武器（以 {@code getCurrentWeapon()} 身份对齐为准——full_salvo 白名单/其它
     * 站武器不满足）。由 {@code RVP_WeaponBase.doClientShoot()} 继承覆写调用（无 Mixin）。
     */
    public static boolean isFastActiveFor(RVP_WeaponBase weapon) {
        if (RVP_ClientGPSState.getMode() != Mode.FAST || !LocalVehiclePlayer.instance.onVehicle()) {
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        return weaponUnit != null
                && weaponUnit.getCurrentWeapon().isPresent()
                && weaponUnit.getCurrentWeapon().get() == weapon
                && usesDesignatedPoint(weapon);
    }

    /**
     * [RVP] FAST 开火前写点：观瞄射线命中方块 → 写 FAST 装订点（保持 FAST 模式）并返回
     * true；落空 → 提示并返回 false（调用方拒止发射）。写点后由调用方继续原发射流程。
     */
    public static boolean tryFastGpsMark() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Vec3 target = raycastGPSTarget(Minecraft.getInstance());
        if (target == null) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.fast_no_block"), true);
            return false;
        }
        ResourceLocation dimension = player.level().dimension().location();
        RVP_Network.CHANNEL.sendToServer(C2SSetGPSTarget.fastSet(dimension, target));
        RVP_ClientGPSState.fastSet(dimension, target);
        return true;
    }

    public static String currentModeTag() {
        return RVP_ClientGPSState.getMode().name();
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
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
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
