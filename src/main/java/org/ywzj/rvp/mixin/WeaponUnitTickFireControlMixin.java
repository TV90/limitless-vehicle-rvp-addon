package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.Optional;

/**
 * 让 RVP 导弹在本体 WeaponUnit.tickFireControl() 中也能触发自动锁定。
 * 本体只对 instanceof VehicleMissile 做自动锁定，RVP_WeaponBase 被排除在外。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitTickFireControlMixin {

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "tickFireControl", at = @At("TAIL"), remap = false)
    private void rvp$onTickFireControl(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;

        // 只处理 RVP 导弹的自动锁定
        if (!self.isSeekerOn()) {
            return;
        }
        if (self.getLockedEntity() != null) {
            return;
        }
        if (self.getLockCoolingTick() <= 20) {
            return;
        }

        Optional<?> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            return;
        }
        Object weapon = weaponOpt.get();
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        if (rvpWeapon.getData().getWeaponKind() != RVP_EnumWeaponKind.MISSILE) {
            return;
        }

        WeaponUnitData.FireControlSensorType sensorType = self.getFireControlSensorType();
        Entity entity = null;

        // 红外锁定
        if (sensorType == WeaponUnitData.FireControlSensorType.IR) {
            // 从 guidance 数据取 seeker FOV（兜底 30°）
            float fov = 30f;
            var stages = rvpWeapon.getData().getGuidanceData().getStages();
            if (stages != null && !stages.isEmpty()) {
                fov = stages.get(0).getSeeker().getFov();
            }
            entity = Infrared.findTarget(self, fov);
        }
        // 雷达锁定（包含主动雷达弹和半主动雷达弹）
        else if (sensorType == WeaponUnitData.FireControlSensorType.RF) {
            RadarUnit radar = self.getMainRadarUnit();
            if (radar != null) {
                entity = Radar.findTarget(radar, 90, self);
            }
        }

        if (entity != null) {
            self.setLockedEntity(entity);
        }
    }
}
