package org.ywzj.rvp.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.flightdeck.RVP_FlightDeckManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.List;

/**
 * 让甲板骨骼参与<b>实体</b>碰撞（2026-10-31）。
 *
 * <p>{@code AbstractVehicle.getOBBs()} 只返回 {@code vehicleCubeOBBs}（仅 {@code vehicle_body} 骨的 cube）
 * 加各部件自己的 OBB。而实体踩载具走的 {@code AbstractVehicle.support()} 正是遍历这一份列表，
 * 所以只要甲板骨的 OBB 没进这份列表，人和载具就会直接穿过甲板掉进船体里——这正是 004 首测的表现。</p>
 *
 * <p>{@link RVP_FlightDeckManager#deckOBBs} 与 {@code PhysicsEngineFlightDeckMixin} 用的是同一个来源
 * （结构模型骨骼现算），这里把同一批 OBB 补进 {@code getOBBs()}，两条碰撞链（实体 / 载具）才一致。</p>
 *
 * <p>去重是必须的：结构模型里若甲板骨已经靠部件条目拿到了 OBB，再追加一份会让 {@code support()}
 * 对同一块板执行两次 MTV，把实体弹出去。这里按中心/半长/朝向近似比较，已经在列表里的跳过。</p>
 */
@Mixin(value = AbstractVehicle.class, remap = false)
public class AbstractVehicleFlightDeckObbMixin {

    /** 判定"同一个 OBB"的容差（格）。同一批 cube 算出来的中心/半长应当完全一致，容差只为浮点误差兜底。 */
    private static final float SAME_OBB_EPSILON = 1.0E-3F;

    @Inject(method = "getOBBs", at = @At("RETURN"), cancellable = true)
    private void ywzj_rvp$appendDeckOBBs(CallbackInfoReturnable<List<OBB>> cir) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (self.level() == null || !RVP_FlightDeckManager.hasFlightDeck(self)) {
            return;
        }
        List<OBB> decks = RVP_FlightDeckManager.deckCollisionOBBs(self);
        if (decks.isEmpty()) {
            return;
        }
        List<OBB> base = cir.getReturnValue();
        List<OBB> merged = new ArrayList<>(base == null ? List.of() : base);
        boolean added = false;
        for (OBB deck : decks) {
            if (!containsSimilar(merged, deck)) {
                merged.add(deck);
                added = true;
            }
        }
        if (added) {
            cir.setReturnValue(merged);
        }
    }

    private static boolean containsSimilar(List<OBB> list, OBB candidate) {
        for (OBB obb : list) {
            if (sameOBB(obb, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOBB(OBB a, OBB b) {
        Vector3f ca = a.center();
        Vector3f cb = b.center();
        if (near(ca.x, cb.x) && near(ca.y, cb.y) && near(ca.z, cb.z)) {
            Vector3f ea = a.extents();
            Vector3f eb = b.extents();
            return near(ea.x, eb.x) && near(ea.y, eb.y) && near(ea.z, eb.z);
        }
        return false;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= SAME_OBB_EPSILON;
    }
}
