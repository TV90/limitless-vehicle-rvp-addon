package org.ywzj.rvp.entity.gunner.flight;

import java.lang.ref.WeakReference;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionGateway;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorRuntime;
import org.ywzj.rvp.flight.*;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** Gunner业务适配：只构造公共请求并结合已执行反馈判断攻击，不写本体运动字段。 */
public final class RVP_GunnerFlightAdapter {
    /** Gunner运行时的独立业务状态键，不进入存档。 */
    private static final String STATE_KEY = "rvp:shared_fixed_wing";
    private RVP_GunnerFlightAdapter() {}

    /** 移动候选载荷；只有仲裁胜者才执行策略，避免落选候选推进业务阶段。 */
    public record Directive(
            /** 业务行为实例。 */ String behaviorId,
            /** 当前有效候选目标。 */ Entity target,
            /** 此行为配置视图。 */ GunnerProfile profile,
            /** 0普通、1SEAD飞离、2SEAD回转。 */ int seadMode) {}

    /** 按Gunner隔离的任务/会话容器。 */
    private static final class Runtime {
        /** 当前公共会话。 */ private RVP_FlightTickService.Session session;
        /** 已提交的业务任务状态。 */ private RVP_GunnerFlightPolicy.State policy = RVP_GunnerFlightPolicy.State.initial();
        /** 同刻固定翼配置，供SEAD借用正常高度范围。 */ private GunnerProfile profile;
        /** 本刻配置优先级。 */ private int priority = Integer.MIN_VALUE;
        /** 配置快照对应Gunner刻。 */ private int parameterTick = -1;
        /** 当前实际武器目标。 */ private int weaponTarget = -1;
        /** 当前业务几何许可。 */ private boolean attack;
        /** 当前制导武器标志。 */ private boolean guided;
        /** 本次接管配置代次。 */ private long generation;
    }

    /** 新模式仅作用于固定翼AI驾驶位。 */
    public static boolean enabled(GunnerEntity gunner, org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle) {
        return RVP_GunnerFlightOptions.USE_IMPROVED_FIXED_WING_AI && vehicle instanceof FixedWingVehicle
                && GunnerBrain.isDriverSeat(vehicle, gunner)
                && GunnerProfileManager.INSTANCE.getProfile(
                        GunnerProfileManager.INSTANCE.normalizeProfileId(gunner.getProfileId())).isAllowDrive();
    }

    /** 只记录配置视图，不计算操纵、不推进任务；同优先级保留配置先声明者。 */
    public static void offerParameters(GunnerEntity gunner, GunnerProfile profile, int priority) {
        Runtime state = runtime(gunner);
        if (state.parameterTick != gunner.tickCount) { state.parameterTick = gunner.tickCount; state.priority = Integer.MIN_VALUE; }
        if (priority > state.priority) { state.priority = priority; state.profile = profile; }
    }

    /** 仲裁后参数化提交实际业务目标，公共服务下一物理刻执行。 */
    public static RVP_GunnerActionResult apply(GunnerEntity gunner, FixedWingVehicle vehicle, Directive directive) {
        try {
            // 调用业务解析入口；坏世界数值/失效配置不能从实体tick直接抛出并打断服务端。
            return applyChecked(gunner, vehicle, directive);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            release(gunner);
            return RVP_GunnerActionResult.INVALID;
        }
    }

    /** 在公共值对象校验保护下构造本业务请求。 */
    private static RVP_GunnerActionResult applyChecked(GunnerEntity gunner, FixedWingVehicle vehicle, Directive directive) {
        Runtime state = runtime(gunner);
        if (!enabled(gunner, vehicle) || RVP_UavLoiterManager.isControlLocked(vehicle.getUUID())) return RVP_GunnerActionResult.OCCUPIED;
        GunnerProfile profile = directive.seadMode() != 0 && state.profile != null ? state.profile : directive.profile();
        if (state.session == null || !state.session.valid()) {
            WeakReference<GunnerEntity> reference = new WeakReference<>(gunner);
            state.generation = GunnerProfileManager.INSTANCE.getGeneration();
            long generation = state.generation;
            state.session = RVP_FlightTickService.acquire(vehicle, gunner.getUUID(), current -> {
                GunnerEntity driver = reference.get();
                return driver != null && driver.isAlive() && driver.getVehicle() == current
                        && GunnerBrain.isDriverSeat(current, driver)
                        && GunnerProfileManager.INSTANCE.getGeneration() == generation
                        && !RVP_UavLoiterManager.isControlLocked(current.getUUID());
            });
            if (state.session == null) return RVP_GunnerActionResult.OCCUPIED;
        }
        Entity target = directive.target();
        WeaponUnit weapons = GunnerBrain.resolveControlledWeaponUnit(vehicle, vehicle.getOwnOperatorUnit(gunner), true);
        double minRange = 0, maxRange = 600;
        boolean guided = false;
        if (target != null && weapons != null) {
            // 调用现有只读选弹入口，不落锁、不扣弹；真正发射仍由原武器链重新检查。
            int index = RVP_GunnerActionGateway.INSTANCE.weapons().findWeaponIndex(weapons, target, null);
            if (index >= 0) {
                var selected = RVP_WeaponResolveHelper.unwrap(weapons.proxyWeapon(weapons.getIndexedWeapons().get(index)));
                if (selected instanceof RVP_WeaponBase weapon) {
                    RVP_WeaponData data = weapon.getData();
                    guided = data.isHomingProjectile() || data.isGpsMissile();
                    var range = data.resolveLaunchGuidanceConfig().targetDistanceRange();
                    if (range != null) {
                        var intervals = range.intervals();
                        var first = intervals.get(0); var last = intervals.get(intervals.size() - 1);
                        if (first.lower() != null) minRange = first.lower();
                        maxRange = last.upper() == null ? 4096 : last.upper();
                    } else if (guided) maxRange = 2000;
                }
            }
        }
        Entity oldTarget = state.policy.missionId() < 0 ? null : vehicle.level().getEntity(state.policy.missionId());
        boolean destroyed = state.policy.missionId() >= 0 && oldTarget != null && !oldTarget.isAlive();
        var home = gunner.getHomePos() == null ? vehicle.position() : gunner.getHomePos();
        RVP_GunnerFlightPolicy.Input input = new RVP_GunnerFlightPolicy.Input(gunner.getUUID().toString(), vehicle.getServer().getTickCount(),
                vector(vehicle.position()), vehicle.getYRot(), vehicle.getDeltaMovement().length() * 20,
                vehicle.getY() - org.ywzj.vehicle.util.EntityUtil.getGroundY(vehicle.level(), vehicle.position()),
                vehicle.level().getBlockState(vehicle.blockPosition().below()).isSolid(), vector(home),
                target == null ? -1 : target.getId(), target == null ? null : vector(target.position()),
                target == null ? null : vector(target.getDeltaMovement().scale(20)), guided,
                target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle, target instanceof AmmoEntity,
                destroyed, minRange, maxRange, profile.getFixedwingCruiseAltitudeMin(), profile.getFixedwingCruiseAltitudeMax(),
                profile.getFixedwingCombatRadiusMax(), profile.getAirAttackPhaseTick(), profile.getAirDisengagePhaseTick(), directive.seadMode());
        // 调用纯业务策略，再将参数化请求交给公共会话；没有任何ControlUnit写入。
        var decision = RVP_GunnerFlightPolicy.decide(input, state.policy);
        if (!state.session.submit(decision.request(), RVP_FlightTuning.responsiveFlight(), 3)) return RVP_GunnerActionResult.GATED;
        state.policy = decision.state(); state.attack = decision.attackGeometry(); state.guided = guided;
        state.weaponTarget = target == null ? -1 : target.getId();
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 发射前业务安全门；公共层只返回飞行反馈，不包含此武器许可。 */
    public static boolean allowFire(GunnerEntity gunner, Entity target) {
        Runtime state = gunner.getBehaviorRuntime().getState(STATE_KEY, Runtime.class);
        if (state == null || state.session == null || !state.session.valid() || state.weaponTarget != target.getId()
                || state.session.executionResult() == null || !state.session.executionResult().applied()) return false;
        Feedback feedback = state.session.feedback();
        if (feedback == null || feedback.tick() != gunner.getServer().getTickCount()
                || feedback.revision() != state.policy.revision() || !feedback.goalId().equals(gunner.getUUID().toString())
                || feedback.status() == Status.RECOVERING || feedback.status() == Status.LIMITED) return false;
        // 近防武器目标不改变导航；它仍须通过本刻安全状态及原武器射界检查。
        if (target instanceof AmmoEntity) return true;
        return state.attack && Math.abs(feedback.headingError()) <= (state.guided ? 40 : 8)
                && Math.abs(feedback.altitudeError()) <= (state.guided ? 40 : 15);
    }

    /** 离座、换配置或退出旧计划时交还公共会话。 */
    public static void release(GunnerEntity gunner) {
        Runtime state = gunner.getBehaviorRuntime().getState(STATE_KEY, Runtime.class);
        if (state != null && state.session != null) state.session.close();
        gunner.getBehaviorRuntime().removeState(STATE_KEY);
    }

    /** 惰性创建业务容器，旧开关关闭时不调用此入口。 */
    private static Runtime runtime(GunnerEntity gunner) {
        RVP_GunnerBehaviorRuntime runtime = gunner.getBehaviorRuntime();
        Runtime state = runtime.getState(STATE_KEY, Runtime.class);
        if (state == null) { state = new Runtime(); runtime.putState(STATE_KEY, state); }
        return state;
    }
    /** 在业务适配边界转换为公共值对象。 */
    private static Vector vector(net.minecraft.world.phys.Vec3 value) { return new Vector(value.x, value.y, value.z); }
}
