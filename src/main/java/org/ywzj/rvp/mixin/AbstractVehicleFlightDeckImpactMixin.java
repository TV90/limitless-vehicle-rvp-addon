package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.flightdeck.RVP_FlightDeckManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 甲板停靠期间的载具间动能碰撞豁免（2026-10-31）。
 *
 * <p>本体 {@code AbstractVehicle.impact} 的伤害是 {@code 0.5 × 质量(吨) × 相对速度²}，
 * 航母这类大质量母舰只要和舰载机有一点相对速度，伤害就是几千点——降落即击毁。
 * 停靠期间由 {@link RVP_FlightDeckManager} 做速度耦合，相对速度已经被抹平，
 * 此时再结算动能伤害没有物理意义，直接跳过。</p>
 *
 * <p>只在"撞击方确实是本车甲板上停着的载具"时豁免，不改变其它任何碰撞。</p>
 */
@Mixin(value = AbstractVehicle.class, remap = false)
public class AbstractVehicleFlightDeckImpactMixin {

    @Inject(method = "impact", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$skipDeckImpact(Entity entity, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!(entity instanceof AbstractVehicle other)) {
            return;
        }
        if (RVP_FlightDeckManager.dockedShipId(other.getId()) == self.getId()) {
            ci.cancel();
        }
    }
}
