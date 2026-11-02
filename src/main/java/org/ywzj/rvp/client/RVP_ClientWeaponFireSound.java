package org.ywzj.rvp.client;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

/** 显式 fire_sound_data 的客户端事件入口；原载具类型/口径默认接管规则已经关闭。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientWeaponFireSound {
    private RVP_ClientWeaponFireSound() {}
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 调用包配置播放，资源可用且已处理本次发射才抑制本体同一发；超过配置范围时也禁止本体声泄漏。
        if (!RVP_ClientVehicleSound.playLocalLaunch(event)) return;
        var weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        SoundEvent original = getOriginalFireSound(event.getWeapon());
        if (original == null) return;
        if (weapon instanceof VehicleMissile && weapon.getWeaponUnit().getColdLaunchTimeTick() > 0)
            RVP_ClientCameraRelativeFireSound.registerDelayedPending(original.getLocation(), weapon.getWeaponUnit());
        else RVP_ClientCameraRelativeFireSound.registerPending(original.getLocation(), weapon.getWeaponUnit().worldPivotPosition());
    }
    /** 供原相机/观瞄/衰减处理器让出显式配置的同一发，避免重复播放。 */
    static boolean willTakeOver(VehicleFireEvent.Post event) { return RVP_ClientVehicleSound.willTakeOver(event); }

    @Nullable private static SoundEvent getOriginalFireSound(@Nullable AbstractVehicleWeapon<?> weapon) {
        while (weapon instanceof VehicleMultiWeapons multi) weapon = multi.getSelectedWeapon();
        if (weapon == null || weapon instanceof VehicleWeaponAgent || weapon.getData().getWeaponId() == null) return null;
        return weapon.getFireSound();
    }
}
