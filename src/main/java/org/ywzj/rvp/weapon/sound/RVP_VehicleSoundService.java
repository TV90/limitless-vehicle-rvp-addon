package org.ywzj.rvp.weapon.sound;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVehicleSound;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.data.RVP_FireSoundData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/** 显式包配置的发射声远距兜底；无 fire_sound_data 的武器完全不参与。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleSoundService {
    private RVP_VehicleSoundService() {}

    @SubscribeEvent public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!(event.getVehicle().level() instanceof ServerLevel level)) return;
        // 调用实际弹种解析，再读取包内字段；不读取武器类型、口径或载具分类。
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        var config = resolveConfiguredSound(weapon);
        if (config == null || weapon.getWeaponUnit() == null) return;
        var vehicle = event.getVehicle();
        var position = weapon.getWeaponUnit().worldPivotPosition();
        var message = new S2CVehicleSound(level.dimension().location(), vehicle.getUUID(), vehicle.getId(), position, config);
        double tracking = effectiveTrackingBlocks(vehicle.getType().clientTrackingRange(), level.getServer().getPlayerList().getViewDistance());
        double maximum = (double) config.getMaxDistance() * config.getMaxDistance();
        for (var player : level.players()) {
            double distance = player.distanceToSqr(position);
            if (distance <= maximum && needsFallback(distance, tracking))
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    /** 仅显式且合法的配置可接管，普通导弹/火箭不会再自动匹配任何默认声音。 */
    @Nullable public static RVP_FireSoundData resolveConfiguredSound(@Nullable AbstractVehicleWeapon<?> weapon) {
        return weapon == null ? null : configuredSound(weapon.getData());
    }
    /** 数据入口供双端和解析测试共用；本体武器数据缺少该声明时自然走原播放。 */
    @Nullable public static RVP_FireSoundData configuredSound(Object data) {
        if (!(data instanceof RVP_WeaponData weapon)) return null;
        var sound = weapon.getFireSoundData();
        return sound != null && sound.isConfigured() ? sound : null;
    }
    static double trackingRangeBlocks(int chunks) { return Math.max(16.0D, chunks * 16.0D); }
    static double effectiveTrackingBlocks(int chunks, int viewDistance) {
        return Math.min(trackingRangeBlocks(chunks), trackingRangeBlocks(viewDistance));
    }
    static boolean needsFallback(double distanceSquared, double trackingBlocks) {
        return distanceSquared > trackingBlocks * trackingBlocks;
    }
}
