package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Optional;

/**
 * 按 R 手锁时自动开启导引头（seekerOn），方便 HUD 反馈。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitFireControlLockMixin {

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "fireControlLock", at = @At("TAIL"), remap = false)
    private void rvp$onFireControlLock(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;

        // 检查是否锁上了目标
        boolean locked = false;
        RadarUnit radar = self.getMainRadarUnit();
        if (radar != null && radar.getLockedEntity() != null) {
            locked = true;
        }
        if (!locked && self.getLockedEntity() != null) {
            locked = true;
        }

        // 只对 RVP 武器生效
        Optional<?> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) return;
        if (!(weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon)) return;

        // ARM 反辐射导弹：即使无锁也开启导引头，用于预选扫描
        if (rvpWeapon.getData().isAntiRadiationMissile()) {
            if (!self.isSeekerOn()) {
                self.toggleSeeker(true);
            }
            return;
        }

        // 其他 RVP 导弹：需要锁上目标后才开启导引头
        if (!locked) return;
        if (!self.isSeekerOn()) {
            self.toggleSeeker(true);
        }
    }
}
