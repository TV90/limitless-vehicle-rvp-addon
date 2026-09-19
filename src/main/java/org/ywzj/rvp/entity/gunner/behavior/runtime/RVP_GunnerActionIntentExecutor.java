package org.ywzj.rvp.entity.gunner.behavior.runtime;

import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionGateway;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

/** 将已仲裁意图映射到阶段 B 动作网关的生产执行器。 */
public final class RVP_GunnerActionIntentExecutor implements RVP_IGunnerIntentExecutor {

    /** 阶段 B 建立的唯一动作网关。 */
    private final RVP_GunnerActionGateway actions;

    public RVP_GunnerActionIntentExecutor(RVP_GunnerActionGateway actions) {
        this.actions = actions;
    }

    @Override
    public RVP_GunnerActionResult execute(RVP_GunnerBehaviorContext context, RVP_GunnerBehaviorIntent intent) {
        return switch (intent.kind()) {
            case TARGET -> {
                // 调用固定计划目标提交入口，统一推进组网记账和同步目标字段。
                GunnerBrain.commitTarget(context, intent.target());
                yield RVP_GunnerActionResult.EXECUTED;
            }
            case SUPPLY_MAINTAIN -> {
                // 调用补给适配器完成首次接管，再维持司机武器弹药。
                actions.supply().refillOnDriverEnter(context.gunner(), context.vehicle());
                yield actions.supply().sustainDriverAmmo(context.gunner(), context.vehicle());
            }
            case SUPPLY_CLEAR -> actions.supply().clearDriverAmmoTimers(context.vehicle());
            case BASE_COUNTERMEASURE -> intent.target() instanceof AmmoEntity threat
                    ? actions.defense().fireBaseCountermeasure(context.gunner(), context.vehicle(), threat)
                    : RVP_GunnerActionResult.INVALID;
            case RVP_COUNTERMEASURE -> intent.countermeasureType() == null
                    ? RVP_GunnerActionResult.INVALID
                    : actions.defense().fireCountermeasure(context.vehicle(), intent.countermeasureType());
            case ACTIVE_ECM -> actions.defense().fireActiveEcm(context.vehicle());
            case LOCAL_RADAR -> actions.radar().maintainLocalLock(
                    context.vehicle(), context.weaponUnit(), intent.target());
            case EXTERNAL_RADAR -> actions.radar().maintainExternalLock(
                    context.gunner(), context.vehicle(), context.weaponUnit(), intent.target(), intent.driverAi());
            case GUIDANCE_MAINTAIN -> actions.guidance().maintain(
                    context.gunner(), context.vehicle(), context.weaponUnit(), intent.target());
            case MOVEMENT -> intent.movement() == null
                    ? RVP_GunnerActionResult.INVALID
                    : actions.movement().apply(context.gunner(), context.vehicle(), intent.movement());
            case FIRE_ENGAGEMENT -> context.weaponUnit() == null || intent.target() == null
                    ? RVP_GunnerActionResult.INVALID
                    : actions.weapons().engage(context.gunner(), context.weaponUnit(), intent.target(),
                            context.profile(), context.has(RVP_GunnerBehaviorContext.Capability.LAUNCHER));
            case FIRE_ANTI_RADIATION -> context.weaponUnit() == null || intent.target() == null
                    ? RVP_GunnerActionResult.INVALID
                    : actions.weapons().fireAntiRadiation(context.gunner(), context.weaponUnit(), intent.target());
            case CLEAR_CONTROLLED_WEAPON -> {
                context.gunner().setControlledWeaponIndex(-1);
                yield RVP_GunnerActionResult.EXECUTED;
            }
        };
    }
}
