package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.firecontrol.RVP_BallisticLeadFireControlExecutor;
import org.ywzj.rvp.client.state.RVP_ClientProjectileRenderInterpolator;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSoftRfMixin {
    @Redirect(
            method = "tickFireControl",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;aim(Lnet/minecraft/world/phys/Vec3;)V",
                    ordinal = 1
            ),
            remap = false
    )
    private void ywzj_rvp$applyConfiguredFireControl(WeaponUnit self, Vec3 worldPos) {
        // IR 通道占用期间跳过本体站级锁定瞄准（AABB 中心）：炮塔不强制转向目标，
        // 保持玩家鼠标控制（HUD 锁定框与弹体目标仍读站级锁/armed 表）。
        if (org.ywzj.rvp.client.state.RVP_IrSeekerTracker.isIrLockActive(self)) {
            return;
        }
        Entity lockedEntity = self.getLockedEntity();
        Vec3 resolvedWorldPos = worldPos;
        if (lockedEntity != null) {
            // 调用本项目统一远距目标位置解析器，让本体自动火控使用与雷达硬锁框相同的插值/外推轨迹。
            resolvedWorldPos = RVP_ClientProjectileRenderInterpolator.resolveRenderCenter(
                    lockedEntity, 1.0F, worldPos);
        }
        // 调用本项目通用执行器，让现有RF模式和新的弹道提前量模式共享同一套瞄准业务逻辑。
        if (!RVP_BallisticLeadFireControlExecutor.tryApply(self, resolvedWorldPos)) {
            // 通用执行器未接管时调用本体瞄准，完整保留未配置武器站的默认行为。
            self.aim(resolvedWorldPos);
        }
    }
}
