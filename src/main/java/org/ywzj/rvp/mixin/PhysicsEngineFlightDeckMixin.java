package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.flightdeck.RVP_FlightDeckManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.PhysicsEngine;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.List;

/**
 * 让"甲板骨骼"参与载具间物理接触（2026-10-31）。
 *
 * <p>本体 {@code PhysicsEngine.getVehicleCollisionBoxes} 只取对方的 {@code getMainCubeOBB()}
 * ——也就是结构模型里体积最大的那个 cube。航母甲板通常是一块扁平的大板，很难成为"最大 cube"，
 * 于是本体根本不会把它当作支撑面，落上去直接穿模掉下去。</p>
 *
 * <p>这里在返回值上追加甲板骨骼的全部 OBB，使 {@code findVehicleSupport} 等下游逻辑能把甲板当接触面；
 * 母舰自身不受影响（查询条件已经排除 {@code other != vehicle}）。</p>
 */
@Mixin(value = PhysicsEngine.class, remap = false)
public class PhysicsEngineFlightDeckMixin {

    @Inject(method = "getVehicleCollisionBoxes", at = @At("RETURN"), cancellable = true)
    private void ywzj_rvp$appendDeckBoxes(AABB bounds, CallbackInfoReturnable<List<OBB>> cir) {
        PhysicsEngine engine = (PhysicsEngine) (Object) this;
        AbstractVehicle self = engine.vehicle;
        if (self == null || self.level() == null || self.level().isClientSide()) {
            return;
        }
        List<AbstractVehicle> ships = self.level().getEntitiesOfClass(AbstractVehicle.class, bounds,
                other -> other != self && !other.noPhysics && other.collision
                        && RVP_FlightDeckManager.hasFlightDeck(other));
        if (ships.isEmpty()) {
            return;
        }
        List<OBB> extra = new ArrayList<>();
        for (AbstractVehicle ship : ships) {
            extra.addAll(RVP_FlightDeckManager.deckCollisionOBBs(ship));
        }
        if (extra.isEmpty()) {
            return;
        }
        // 返回值可能是 List.of() 之类的不可变列表，必须新建再合并
        List<OBB> merged = new ArrayList<>(cir.getReturnValue());
        merged.addAll(extra);
        cir.setReturnValue(merged);
    }
}
