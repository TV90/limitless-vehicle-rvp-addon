package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.S2CVehicleSound;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.config.RVP_DisplayFireSound;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.rvp.client.resource.vehicle.RVP_BaseDisplay;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 现有相机相对开火声入口与远距通知共用的 display 播放辅助类；音频和变体由声音资源系统解析，Java 不再列举采样路径或类型音效组。 */
public final class RVP_ClientVehicleSound {
    private RVP_ClientVehicleSound() {}

    public static void accept(S2CVehicleSound message) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(message.dimension())) return;
        var entity = mc.level.getEntity(message.entityId());
        var vehicle = entity instanceof AbstractVehicle v && v.getUUID().equals(message.vehicleId()) ? v : null;
        var config = resolveDisplay(message.weaponId());
        if (config != null) play(config, message.position(), vehicle);
    }
    static boolean playLocalLaunch(VehicleFireEvent.Post event) {
        var config = config(event);
        if (config == null) return false;
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        return play(config, weapon.getWeaponUnit().worldPivotPosition(), event.getVehicle());
    }
    static boolean willTakeOver(VehicleFireEvent.Post event) {
        var config = config(event);
        if (config == null) return false;
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        return available(config, weapon.getWeaponUnit().worldPivotPosition());
    }
    @Nullable private static RVP_DisplayFireSound config(VehicleFireEvent.Post event) {
        if (event == null || !event.isClientSide()
                || event.getWeapon() instanceof org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent) return null;
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        return weapon == null || weapon.getWeaponUnit() == null ? null : resolveDisplay(weapon.getData().getWeaponId());
    }
    @Nullable public static RVP_DisplayFireSound resolveDisplay(net.minecraft.resources.ResourceLocation weaponId) {
        if (weaponId == null) return null;
        return ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId)
                .filter(RVP_BaseDisplay.class::isInstance).map(RVP_BaseDisplay.class::cast)
                .map(RVP_BaseDisplay::getFireSoundSettings).orElse(null);
    }
    /** 按真实相机距离选取包声明事件，缺少资源时让出本体原声。 */
    private static boolean available(RVP_DisplayFireSound config, Vec3 position) {
        var mc = Minecraft.getInstance();
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(position);
        var event = mc.getSoundManager().getSoundEvent(config.soundAt(distance));
        return event != null && event.getWeight() > 0;
    }
    private static boolean play(RVP_DisplayFireSound config, Vec3 position, @Nullable AbstractVehicle vehicle) {
        if (!available(config, position)) return false;
        var mc = Minecraft.getInstance();
        double listener = mc.gameRenderer.getMainCamera().getPosition().distanceTo(position);
        float relative = vehicle == null ? 0F : RVP_VehicleExtendedConfigManager.getCameraRelativeFireSoundDistance(vehicle);
        // 范围外仍接管为静音，防本体 volume=4 把同一事件的可听距离再次放大；骑乘者保留相机相对听感。
        boolean rider = vehicle != null && mc.player != null && mc.player.getVehicle() == vehicle;
        if (listener > config.getMaxDistance() && !(rider && relative > 0)) return true;
        if (relative > 0) {
            var sound = new ConfiguredVehicleSound(config, listener, vehicle, position, relative);
            sound.tick(); sound.play();
        } else mc.getSoundManager().play(createPositionSound(config, listener, position));
        return true;
    }
    /** 原版解析器从 sounds.json 随机选样，不在 Java 中创建内存声音组。 */
    static SoundInstance createPositionSound(RVP_DisplayFireSound config, double distance, Vec3 position) {
        return new SimpleSoundInstance(config.soundAt(distance), SoundSource.PLAYERS, config.getVolume(), config.getPitch(),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                position.x, position.y, position.z, false);
    }
    /** 仅骑乘者沿用本体相机相对定位；旁观者仍使用真实声源位置。 */
    private static final class ConfiguredVehicleSound extends VehicleSound {
        private ConfiguredVehicleSound(RVP_DisplayFireSound config, double distance, AbstractVehicle vehicle, Vec3 position, float relative) {
            super(SoundEvent.createVariableRangeEvent(config.soundAt(distance)), position.subtract(vehicle.position()),
                    config.getVolume(), relative, config.getPitch(), false, 0, false, false, vehicle.getId());
        }
        @Override public Vec3 calRelativePos(Vec3 soundPos, Vec3 targetPos, boolean cameraEntityOnVehicle) {
            return cameraEntityOnVehicle ? super.calRelativePos(soundPos, targetPos, true) : soundPos;
        }
    }
}
