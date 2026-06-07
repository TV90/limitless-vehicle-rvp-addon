package org.ywzj.rvp.client.state;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;

/**
 * Client-only SACLOS in-flight detection. Avoids {@link RVP_BaseBullet#rvp$isInSaclosGuidanceStage()}
 * which mutates guidance state and may disagree with the server on the client copy.
 */
public final class RVP_ClientSaclosGuidance {

    private RVP_ClientSaclosGuidance() {}

    public static boolean isOperatorGuiding(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return false;
        }
        for (RVP_BaseBullet bullet : level.getEntitiesOfClass(
                RVP_BaseBullet.class, player.getBoundingBox().inflate(4096))) {
            if (!bullet.isAlive() || !isOperatorProjectile(bullet, player)) {
                continue;
            }
            if (isInSaclosPhase(bullet)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static RVP_BaseBullet findGuidingMissile(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return null;
        }
        for (RVP_BaseBullet bullet : level.getEntitiesOfClass(
                RVP_BaseBullet.class, player.getBoundingBox().inflate(4096))) {
            if (!bullet.isAlive() || !isOperatorProjectile(bullet, player)) {
                continue;
            }
            if (isInSaclosPhase(bullet)) {
                return bullet;
            }
        }
        return null;
    }

    public static boolean isInSaclosPhase(RVP_BaseBullet bullet) {
        RVP_WeaponData data = resolveWeaponData(bullet);
        if (data == null || !data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)) {
            return false;
        }
        int tick = bullet.tickCount;
        for (RVP_GuidanceStageData stage : data.getGuidanceData().getStages()) {
            boolean saclos = stage.getSources().stream()
                    .anyMatch(source -> source.getType() == RVP_EnumGuidanceType.SACLOS);
            if (!saclos) {
                continue;
            }
            RVP_GuidanceActivationData activation = stage.getActivation();
            if (tick < activation.getStartTick()) {
                continue;
            }
            if (activation.getEndTick() >= 0 && tick > activation.getEndTick()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isOperatorProjectile(RVP_BaseBullet bullet, LocalPlayer player) {
        Entity owner = bullet.getOwner();
        if (owner == player) {
            return true;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null && owner == vehicle) {
            return true;
        }
        if (vehicle != null && owner == null && bullet.distanceTo(vehicle) < 512.0D) {
            return true;
        }
        return false;
    }

    @Nullable
    public static RVP_WeaponData resolveWeaponData(RVP_BaseBullet bullet) {
        RVP_WeaponData data = bullet.getRvpData();
        if (data != null) {
            return data;
        }
        ResourceLocation id = bullet.getWeaponId();
        if (id == null) {
            return null;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(id)
                .map(index -> index.data() instanceof RVP_WeaponData weaponData ? weaponData : null)
                .orElse(null);
    }
}
