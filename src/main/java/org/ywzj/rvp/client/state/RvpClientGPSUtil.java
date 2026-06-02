package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.weapon.VehicleGPSBomb;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class RvpClientGPSUtil {

    public static boolean isGPSBombSelected() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        return weaponUnit != null
                && weaponUnit.getCurrentWeapon().isPresent()
                && weaponUnit.getCurrentWeapon().get() instanceof VehicleGPSBomb;
    }

    public static boolean ensureGPSBombSelected(LocalPlayer player) {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.not_in_vehicle"), true);
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.getCurrentWeapon().isEmpty() || !(weaponUnit.getCurrentWeapon().get() instanceof VehicleGPSBomb)) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.switch_to_bomb"), true);
            return false;
        }
        return true;
    }

    public static Vec3 raycastGPSTarget(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return null;
        }
        Vec3 start = new Vec3(LocalVehiclePlayer.instance.cameraX, LocalVehiclePlayer.instance.cameraY, LocalVehiclePlayer.instance.cameraZ);
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
