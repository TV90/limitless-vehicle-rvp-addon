package org.ywzj.rvp.entity.gunner.behavior.action;

import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.server.RVP_CountermeasureRuntimeManager;
import org.ywzj.rvp.ecm.RVP_EcmActiveManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

/** 封装 Gunner 可触发的本体式反制武器、RVP 干扰物和主动 ECM。 */
public final class RVP_GunnerDefenseActions {

    /** 本体式反制武器的瞄准/发射适配器。 */
    private final RVP_GunnerWeaponActions weapons;

    RVP_GunnerDefenseActions(RVP_GunnerWeaponActions weapons) {
        this.weapons = weapons;
    }

    /** 尝试让本体式反制武器对危险弹药开火。 */
    public RVP_GunnerActionResult fireBaseCountermeasure(GunnerEntity gunner,
                                                         AbstractVehicle vehicle,
                                                         AmmoEntity threat) {
        // 调用武器动作适配器，确保本体式反制也不绕过统一 shoot 边界。
        return weapons.fireCountermeasure(gunner, vehicle, threat);
    }

    /** 查询载具是否配置了指定 RVP 干扰物系统。 */
    public boolean hasCountermeasure(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        return RVP_CountermeasureRuntimeManager.hasSystem(vehicle, type);
    }

    /**
     * 向 RVP 干扰物状态机提交一次发射请求。
     *
     * <p>底层入口不返回实际生成结果，因此成功通过基本能力检查后返回 DISPATCHED。</p>
     */
    public RVP_GunnerActionResult fireCountermeasure(AbstractVehicle vehicle,
                                                     RVP_EnumCountermeasureType type) {
        if (vehicle == null || type == null || vehicle.level().isClientSide()
                || !vehicle.isAlive() || vehicle.isDestroyed() || !vehicle.hasPower()) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (!hasCountermeasure(vehicle, type)) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        // 调用 RVP 服务端干扰物状态机；弹量、骨模块与装填冷却仍由状态机权威校验。
        RVP_CountermeasureRuntimeManager.fire(vehicle, type);
        return RVP_GunnerActionResult.DISPATCHED;
    }

    /** 尝试触发载具主动 ECM，并保留底层真实成功结果。 */
    public RVP_GunnerActionResult fireActiveEcm(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide() || !vehicle.isAlive()) {
            return RVP_GunnerActionResult.INVALID;
        }
        // 调用 RVP 主动 ECM 管理器，由其校验骨模块、持续时间和冷却。
        return RVP_EcmActiveManager.tryFireForVehicle(vehicle)
                ? RVP_GunnerActionResult.EXECUTED : RVP_GunnerActionResult.GATED;
    }
}
