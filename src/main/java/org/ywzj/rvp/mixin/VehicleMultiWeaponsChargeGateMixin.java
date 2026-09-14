package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.mount.RVP_ShootBoltQueueApplier;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

@OnlyIn(Dist.CLIENT)
@Mixin(value = VehicleMultiWeapons.class, remap = false)
public abstract class VehicleMultiWeaponsChargeGateMixin {

    @Shadow(remap = false)
    public abstract AbstractVehicleWeapon<?> getSelectedWeapon();

    @Inject(method = "doClientShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$chargeGate(CallbackInfoReturnable<Boolean> cir) {
        AbstractVehicleWeapon<?> selected = getSelectedWeapon();
        if (!(selected instanceof RVP_WeaponBase rvp)) {
            return;
        }
        // [RVP] 目的（2026-09-14）：多弹种槽客户端开火兜底——本体 Multi.doClientShoot 自行
        // 计算 aimContexts（出生坐标客户端权威），绕过 RVP_WeaponBase.doClientShoot 的
        // ensureApplied；客户端 bolts 若仍停留在挂点模板（重进存档/配置就绪竞态），
        // 炸弹就会从挂点出生。开火前拉取一次（幂等：口径×表版本未变时零开销）。
        RVP_ShootBoltQueueApplier.ensureApplied(rvp.getVehicle(), rvp.getWeaponUnit());
        rvp.getFireController().syncClientInput();
        if (!rvp.getFireController().shouldAttemptClientShot()) {
            cir.setReturnValue(false);
        }
        // 二选一武器组的锁定门控：本体 Multi.doClientShoot 不调用选中武器的
        // doClientShoot（RVP_WeaponBase 的 require_lock 门控在其中），导致经 Multi
        // 包装的 require_lock 武器无需锁定即可发射。此处解包后复用同一门控。
        if (!rvp.passesShootLockGates()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doClientShoot", at = @At("RETURN"), remap = false)
    private void ywzj_rvp$afterShot(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        AbstractVehicleWeapon<?> selected = getSelectedWeapon();
        if (selected instanceof RVP_WeaponBase rvp) {
            // 仅重置蓄力/点射状态，不在此计热：热量由服务器回包（VehicleFireEvent.Post → onClientFire）统一记录，
            // 避免单发被计热两次导致过热过快。
            rvp.getFireController().resetStateAfterShot();
        }
    }
}
