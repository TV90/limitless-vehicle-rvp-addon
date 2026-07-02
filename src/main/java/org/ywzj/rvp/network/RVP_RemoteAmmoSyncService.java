package org.ywzj.rvp.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteAmmoSyncService {

    private static final double SYNC_RANGE = 6144.0;
    private static final int SYNC_INTERVAL_TICKS = 5;

    private RVP_RemoteAmmoSyncService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.serverLevel() == null) {
                continue;
            }
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
        }
    }

    private static S2CRemoteAmmoSnapshot buildSnapshot(ServerPlayer player) {
        S2CRemoteAmmoSnapshot msg = new S2CRemoteAmmoSnapshot();
        msg.dimension = player.serverLevel().dimension().location();
        AABB rangeBox = player.getBoundingBox().inflate(SYNC_RANGE);
        List<S2CRemoteAmmoSnapshot.Entry> entries = new ArrayList<>();
        for (RVP_BaseBullet bullet : player.serverLevel().getEntitiesOfClass(RVP_BaseBullet.class, rangeBox,
                bullet -> !bullet.isRemoved() && shouldSync(bullet))) {
            entries.add(toEntry(player, bullet));
        }
        msg.entries = entries;
        return msg;
    }

    private static boolean shouldSync(RVP_BaseBullet bullet) {
        RVP_EnumWeaponKind kind = bullet.getWeaponKind();
        return kind == RVP_EnumWeaponKind.MISSILE || kind == RVP_EnumWeaponKind.BOMB;
    }

    private static S2CRemoteAmmoSnapshot.Entry toEntry(ServerPlayer player, RVP_BaseBullet bullet) {
        Vec3 guidancePos = bullet.getTargetPos() != null ? bullet.getTargetPos() : bullet.getLastGuidancePos();
        return new S2CRemoteAmmoSnapshot.Entry(
                bullet.getId(),
                bullet.getWeaponId(),
                resolveDisplayName(bullet),
                bullet.getWeaponKind(),
                classify(player, bullet),
                isGpsAmmo(bullet),
                bullet.getCurrentSpeed() * 72.0,
                bullet.getX(),
                bullet.getY(),
                bullet.getZ(),
                bullet.getYRot(),
                guidancePos
        );
    }

    private static S2CRemoteAmmoSnapshot.Affiliation classify(ServerPlayer player, RVP_BaseBullet bullet) {
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        if (bullet.getOwner() == player || (playerVehicle != null && bullet.getShooterVehicle() == playerVehicle)) {
            return S2CRemoteAmmoSnapshot.Affiliation.OWN;
        }
        Entity owner = bullet.getOwner();
        if (isAllied(player, owner != null ? owner.getTeam() : bullet.getTeam())) {
            return S2CRemoteAmmoSnapshot.Affiliation.FRIEND;
        }
        return S2CRemoteAmmoSnapshot.Affiliation.HOSTILE;
    }

    private static boolean isAllied(Player player, @Nullable net.minecraft.world.scores.Team team) {
        return player.getTeam() != null && team != null && team.isAlliedTo(player.getTeam());
    }

    private static boolean isGpsAmmo(RVP_BaseBullet bullet) {
        if (bullet.getWeaponId() == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(bullet.getWeaponId())
                .map(index -> index.data() instanceof RVP_WeaponData weaponData
                        && weaponData.usesGuidanceType(RVP_EnumGuidanceType.GPS))
                .orElse(false);
    }

    private static String resolveDisplayName(RVP_BaseBullet bullet) {
        if (bullet.getWeaponId() == null) {
            return bullet.getType().getDescription().getString();
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(bullet.getWeaponId())
                .map(index -> index.data().getName())
                .filter(name -> name != null && !name.isBlank())
                .orElse(bullet.getWeaponId().toString());
    }
}
