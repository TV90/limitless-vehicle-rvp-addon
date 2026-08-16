package org.ywzj.rvp.client.state;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;

/**
 * Client-only SACLOS in-flight detection. Avoids {@link RVP_BaseBullet#rvp$isInSaclosGuidanceStage()}
 * which mutates guidance state and may disagree with the server on the client copy.
 */
public final class RVP_ClientSaclosGuidance {

    private RVP_ClientSaclosGuidance() {}

    public static boolean isOperatorGuiding(LocalPlayer player, Level level) {
        if (player == null || level == null || !(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel)) {
            return false;
        }
        // O(实体) 遍历客户端已加载实体，替代 ±4096 立方体 getEntitiesOfClass（8192³，客户端掉帧）
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)
                    || !bullet.isAlive()
                    || bullet.position().distanceToSqr(player.position()) > 4096.0 * 4096.0) {
                continue;
            }
            if (isOperatorProjectile(bullet, player) && isInSaclosPhase(bullet)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static RVP_BaseBullet findGuidingMissile(LocalPlayer player, Level level) {
        if (player == null || level == null || !(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel)) {
            return null;
        }
        // O(实体) 遍历客户端已加载实体，替代 ±4096 立方体 getEntitiesOfClass（8192³，客户端掉帧）
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)
                    || !bullet.isAlive()
                    || bullet.position().distanceToSqr(player.position()) > 4096.0 * 4096.0) {
                continue;
            }
            if (isOperatorProjectile(bullet, player) && isInSaclosPhase(bullet)) {
                return bullet;
            }
        }
        return null;
    }

    public static boolean isInSaclosPhase(RVP_BaseBullet bullet) {
        RVP_WeaponData data = resolveWeaponData(bullet);
        if (data == null) {
            return false;
        }
        RVP_GuidanceActiveConfig config = RVP_GuidanceModelResolver.resolveActive(
                data, bullet.getGuidancePhaseState().phase());
        if (config.tickRange() != null && !config.tickRange().contains(bullet.getFlightTickCount())) {
            return false;
        }
        RVP_EnumGuidanceType active = config.guidanceType();
        return active == RVP_EnumGuidanceType.LH
                || active == RVP_EnumGuidanceType.SALH
                || active == RVP_EnumGuidanceType.LBR;
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
