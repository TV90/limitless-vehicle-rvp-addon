package org.ywzj.rvp.client.firecontrol;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_AimAssistState;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_MachinegunLeadState;
import org.ywzj.rvp.client.state.RVP_SemiAutoLeadTrimState;
import org.ywzj.rvp.debug.RVP_LeadFcDebug;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用弹道提前量火控执行器。
 *
 * <p>该类承载原 {@code WeaponUnitSoftRfMixin} 的业务逻辑，使RF软火控与通用弹道提前量模式
 * 共用同一套目标点替换、离轴限制、平滑和稳定器状态处理；Mixin只负责转发本体瞄准调用。</p>
 */
public final class RVP_BallisticLeadFireControlExecutor {
    /** 每个武器站的非机炮软限位平滑状态，键由载具实体ID和武器站索引组合。 */
    private static final Map<Integer, RVP_AimAssistState> AIM_ASSIST_STATES = new HashMap<>();

    /** 非机炮软限位的最低插值系数。 */
    private static final double AIM_ASSIST_ALPHA_BASE = 0.14D;

    /** 非机炮软限位随方向误差增长的插值系数。 */
    private static final double AIM_ASSIST_ALPHA_SCALE = 0.026D;

    /** 非机炮软限位允许使用的最大插值系数。 */
    private static final double AIM_ASSIST_ALPHA_MAX = 0.34D;

    /** 超过该Tick间隔后丢弃旧的软限位平滑方向。 */
    private static final int AIM_ASSIST_STALE_TICKS = 4;

    /** 现有 {@code rvp_rf} 机炮离轴角的兼容缩放，保持原有防空车手感不变。 */
    private static final float RVP_RF_MACHINEGUN_OFF_AXIS_SCALE = 0.1F;

    /** 工具类不允许实例化。 */
    private RVP_BallisticLeadFireControlExecutor() {}

    /**
     * 尝试接管本体对锁定目标的瞄准调用。
     *
     * @param weaponUnit 当前执行 {@code tickFireControl} 的本体武器站
     * @param trackedTargetWorldPos 本体锁定目标中心点
     * @return {@code true} 表示本次瞄准已由RVP处理；{@code false} 表示应调用本体 {@code aim}
     */
    public static boolean tryApply(WeaponUnit weaponUnit, Vec3 trackedTargetWorldPos) {
        WeaponUnitData data = weaponUnit.getData();
        if (!(data instanceof WeaponUnitDataExt ext)) {
            clearAimAssist(weaponUnit);
            RVP_SemiAutoLeadTrimState.clear(weaponUnit);
            return false;
        }

        // 调用本项目机炮解析器，确认当前实际选中的武器是否能进行RVP弹道提前量解算。
        boolean machinegunLeadMode = RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(weaponUnit);
        // 调用本项目RF导弹跟踪辅助器，确认当前导弹是否应使用完整火控三态。
        boolean radarMissileTrackMode = RVP_RadarMissileTrackHelper.isEligible(weaponUnit);
        // 调用本项目传感器解析器，兼容武器级覆盖并与三态资格使用同一口径。
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(weaponUnit);
        RVP_BallisticLeadFireControlPolicy.Profile profile = RVP_BallisticLeadFireControlPolicy.resolve(
                ext.ywzj_rvp$getFireControlMode(),
                sensorType,
                machinegunLeadMode
        );
        // [RVP] 诊断（/rvpdebug flags lead_fc on）：输出执行器模式、传感器、武器分支与解析档位，
        // 定位"炮塔跟踪目标本体而非预瞄圈"时哪个输入与预期不符。
        RVP_LeadFcDebug.logExecutorInput(weaponUnit, ext.ywzj_rvp$getFireControlMode(), sensorType,
                machinegunLeadMode, radarMissileTrackMode, profile,
                RVP_FireControlStabilizerState.getMode(weaponUnit));
        if (profile == RVP_BallisticLeadFireControlPolicy.Profile.NONE) {
            RVP_LeadFcDebug.logDecision(weaponUnit, "NONE_回退本体瞄准目标中心", false, null, null, trackedTargetWorldPos);
            clearAimAssist(weaponUnit);
            RVP_SemiAutoLeadTrimState.clear(weaponUnit);
            return false;
        }

        try {
            float offAxisDeg = resolveOffAxisDeg(profile, ext);
            // 调用本项目硬锁解析器，只允许本车/外置雷达正式锁定驱动导弹视线。
            Entity radarLockedTarget = radarMissileTrackMode
                    ? RVP_RadarMissileTrackHelper.resolveHardLockedTarget(weaponUnit)
                    : null;
            if (radarMissileTrackMode && radarLockedTarget == null) {
                clearAimAssist(weaponUnit);
                RVP_SemiAutoLeadTrimState.clear(weaponUnit);
                RVP_LeadFcDebug.logDecision(weaponUnit, "RF导弹_无硬锁_不驱动", false, null, null, null);
                return true;
            }
            if (radarLockedTarget != null) {
                trackedTargetWorldPos = radarLockedTarget.getBoundingBox().getCenter();
            }

            if (offAxisDeg <= 0.0F && !radarMissileTrackMode) {
                clearAimAssist(weaponUnit);
                RVP_SemiAutoLeadTrimState.clear(weaponUnit);
                RVP_LeadFcDebug.logDecision(weaponUnit, "离轴角为零_跟目标中心", false, null, null, trackedTargetWorldPos);
                // 调用本体瞄准方法，保持旧配置将离轴角设为零时退回目标中心跟踪的行为。
                weaponUnit.aim(trackedTargetWorldPos);
                return true;
            }

            // 调用本项目机炮解算缓存，使用保留世界坐标 EMA 且带相位补偿的控制解以兼顾抗抖与高速跟随。
            RVP_LeadSolution leadSolution = machinegunLeadMode
                    ? RVP_MachinegunLeadState.resolveCurrent(weaponUnit, 1.0F)
                    : null;
            if (leadSolution != null) {
                trackedTargetWorldPos = leadSolution.leadWorldPos();
                if (profile == RVP_BallisticLeadFireControlPolicy.Profile.RVP_RF) {
                    offAxisDeg *= RVP_RF_MACHINEGUN_OFF_AXIS_SCALE;
                }
            }

            // 调用本项目稳定器状态，决定本Tick采用全自动、软限位或完全手动控制。
            RVP_FireControlStabilizerState.Mode stabilizerMode = RVP_FireControlStabilizerState.getMode(weaponUnit);
            Vec3 aimFrom = leadSolution != null ? aimOrigin(weaponUnit) : weaponUnit.worldPivotPosition();
            if (stabilizerMode == RVP_FireControlStabilizerState.Mode.OFF) {
                clearAimAssist(weaponUnit);
                RVP_SemiAutoLeadTrimState.clear(weaponUnit);
                RVP_LeadFcDebug.logDecision(weaponUnit, "OFF_不驱动", false, null, null, null);
                return true;
            }
            if (stabilizerMode == RVP_FireControlStabilizerState.Mode.STABLE) {
                clearAimAssist(weaponUnit);
                RVP_SemiAutoLeadTrimState.clear(weaponUnit);
                if (machinegunLeadMode) {
                    if (leadSolution == null) {
                        RVP_LeadFcDebug.logDecision(weaponUnit, "STABLE_无解_不驱动", false, null, null, null);
                        return true;
                    }
                    RVP_LeadFcDebug.logDecision(weaponUnit, "STABLE_跟预瞄圈", true, leadSolution, aimFrom, trackedTargetWorldPos);
                    aimAlongDirection(weaponUnit, trackedTargetWorldPos.subtract(aimFrom));
                } else {
                    RVP_LeadFcDebug.logDecision(weaponUnit, "STABLE_非机炮_跟目标中心", false, null, null, trackedTargetWorldPos);
                    // 调用本体瞄准方法，让现有RF非机炮在稳定模式下继续指向目标中心。
                    weaponUnit.aim(trackedTargetWorldPos);
                }
                return true;
            }
            if (machinegunLeadMode && leadSolution == null) {
                clearAimAssist(weaponUnit);
                RVP_LeadFcDebug.logDecision(weaponUnit, "SEMI_无解_不驱动", false, null, null, null);
                return true;
            }

            Vec3 targetDir = trackedTargetWorldPos.subtract(aimFrom);
            if (targetDir.lengthSqr() < 1.0E-6D) {
                clearAimAssist(weaponUnit);
                return true;
            }
            if (machinegunLeadMode) {
                clearAimAssist(weaponUnit);
                // 调用本项目半自动微调状态，按目标和当前武器身份初始化或延续锁存偏置。
                RVP_SemiAutoLeadTrimState.activate(
                        weaponUnit,
                        leadSolution.target(),
                        RVP_SemiAutoLeadTrimState.AnchorType.BALLISTIC_LEAD
                );
                // 调用本项目半自动微调解算，将玩家双轴偏置叠加到持续移动的理论预瞄方向。
                Vec3 trimmedDirection = RVP_SemiAutoLeadTrimState.apply(
                        weaponUnit,
                        targetDir,
                        offAxisDeg
                );
                Vec3 trimmedPoint = aimFrom.add(trimmedDirection.scale(targetDir.length()));
                RVP_LeadFcDebug.logDecision(
                        weaponUnit,
                        "SEMI_跟随预瞄并保持微调",
                        true,
                        leadSolution,
                        aimFrom,
                        trimmedPoint
                );
                aimAlongDirection(weaponUnit, trimmedDirection);
                return true;
            }

            if (radarMissileTrackMode) {
                clearAimAssist(weaponUnit);
                // 调用本项目半自动微调状态，按硬锁目标和当前导弹身份初始化或延续锁存偏置。
                RVP_SemiAutoLeadTrimState.activate(
                        weaponUnit,
                        radarLockedTarget,
                        RVP_SemiAutoLeadTrimState.AnchorType.RADAR_HARD_LOCK
                );
                // 调用本项目双轴微调解算，把玩家偏置叠加到持续移动的雷达目标方向。
                Vec3 trimmedDirection = RVP_SemiAutoLeadTrimState.apply(
                        weaponUnit,
                        targetDir,
                        offAxisDeg
                );
                Vec3 trimmedPoint = aimFrom.add(trimmedDirection.scale(targetDir.length()));
                RVP_LeadFcDebug.logDecision(
                        weaponUnit,
                        "SEMI_RF导弹_跟随硬锁并保持微调",
                        false,
                        null,
                        aimFrom,
                        trimmedPoint
                );
                aimAlongDirection(weaponUnit, aimFrom, trimmedDirection);
                return true;
            }

            // 调用本体方向换算，取得玩家本Tick请求的瞄准方向而不是炮塔尚未追上的当前姿态。
            Vec3 desiredDir = weaponUnit.worldVec(weaponUnit.getXAimRot(), weaponUnit.getYAimRot());
            double angleDeg = Math.toDegrees(VectorUtil.angleBetween(desiredDir, targetDir));
            if (angleDeg > offAxisDeg) {
                Vec3 clampedPoint = clampAimToOffAxisBoundary(
                        aimFrom,
                        trackedTargetWorldPos,
                        desiredDir,
                        offAxisDeg
                );
                Vec3 smoothedPoint = smoothAimAssist(
                        weaponUnit,
                        aimFrom,
                        desiredDir,
                        clampedPoint,
                        angleDeg - offAxisDeg
                );
                RVP_LeadFcDebug.logDecision(weaponUnit, "SEMI_超离轴_软修正目标中心", false, null, aimFrom, smoothedPoint);
                // 调用本体瞄准方法，把非机炮软修正后的世界点同步到武器站旋转。
                weaponUnit.aim(smoothedPoint);
                return true;
            }
            clearAimAssist(weaponUnit);
            RVP_LeadFcDebug.logDecision(weaponUnit, "SEMI_离轴内_跟鼠标", false, null, aimFrom, trackedTargetWorldPos);
            return true;
        } catch (Throwable t) {
            // [RVP] 诊断：策略通过但解算/决策中途抛异常时，上层若吞掉会导致"看起来回退本体跟机体"。
            // 记录堆栈后按原语义继续抛出，不改变行为。
            RVP_LeadFcDebug.logThrowable(weaponUnit, t);
            throw t;
        }
    }

    /** 按模式读取各自的离轴角字段，避免把现有RF字段当作旧键别名迁移。 */
    private static float resolveOffAxisDeg(RVP_BallisticLeadFireControlPolicy.Profile profile,
                                           WeaponUnitDataExt ext) {
        float configured = profile == RVP_BallisticLeadFireControlPolicy.Profile.RVP_RF
                ? ext.ywzj_rvp$getRfOffAxisDeg()
                : ext.ywzj_rvp$getFireControlOffAxisDeg();
        return Mth.clamp(configured, 0.0F, 89.0F);
    }

    /** 将玩家请求方向限制到目标轴线周围的离轴边界。 */
    private static Vec3 clampAimToOffAxisBoundary(Vec3 aimFrom, Vec3 targetWorldPos,
                                                   Vec3 desiredDir, float offAxisDeg) {
        Vec3 targetDirNorm = targetWorldPos.subtract(aimFrom).normalize();
        Vec3 desiredDirNorm = desiredDir.normalize();
        double dot = Mth.clamp(targetDirNorm.dot(desiredDirNorm), -1.0D, 1.0D);
        Vec3 tangent = desiredDirNorm.subtract(targetDirNorm.scale(dot));
        if (tangent.lengthSqr() < 1.0E-6D) {
            return targetWorldPos;
        }

        tangent = tangent.normalize();
        double offAxisRad = Math.toRadians(offAxisDeg);
        Vec3 boundaryDir = targetDirNorm.scale(Math.cos(offAxisRad))
                .add(tangent.scale(Math.sin(offAxisRad)))
                .normalize();
        return aimFrom.add(boundaryDir.scale(targetWorldPos.distanceTo(aimFrom)));
    }

    /** 平滑非机炮越过离轴边界后的辅助修正，避免准线突然跳到边界。 */
    private static Vec3 smoothAimAssist(WeaponUnit weaponUnit, Vec3 aimFrom, Vec3 desiredDir,
                                        Vec3 targetPoint, double overflowDeg) {
        int nowTick = weaponUnit.getVehicle().tickCount;
        int key = stateKey(weaponUnit);
        RVP_AimAssistState state = AIM_ASSIST_STATES.computeIfAbsent(key, unused -> new RVP_AimAssistState());
        Vec3 desiredDirNorm = desiredDir.normalize();
        Vec3 targetDir = targetPoint.subtract(aimFrom);
        if (desiredDirNorm.lengthSqr() < 1.0E-6D || targetDir.lengthSqr() < 1.0E-6D) {
            return targetPoint;
        }
        Vec3 targetDirNorm = targetDir.normalize();
        double targetDistance = targetDir.length();
        if (!state.initialized || nowTick - state.lastTick > AIM_ASSIST_STALE_TICKS) {
            state.currDir = desiredDirNorm;
            state.currDistance = targetDistance;
            state.initialized = true;
        } else if (state.lastTick != nowTick) {
            double dirErrorDeg = Math.toDegrees(VectorUtil.angleBetween(state.currDir, targetDirNorm));
            double alpha = Mth.clamp(
                    AIM_ASSIST_ALPHA_BASE + dirErrorDeg * AIM_ASSIST_ALPHA_SCALE + overflowDeg * 0.02D,
                    AIM_ASSIST_ALPHA_BASE,
                    AIM_ASSIST_ALPHA_MAX
            );
            state.currDir = state.currDir.lerp(targetDirNorm, alpha).normalize();
            state.currDistance += (targetDistance - state.currDistance) * alpha;
        }
        state.lastTick = nowTick;
        return aimFrom.add(state.currDir.scale(state.currDistance));
    }

    /** 优先取得实际发射武器瞄准上下文的炮口原点，缺失时回退其当前炮闩世界坐标。 */
    private static Vec3 aimOrigin(WeaponUnit weaponUnit) {
        // 调用本项目发射单元解析，处理根火控站把当前武器委托给子武器站的载具配置。
        WeaponUnit launchWeaponUnit = RVP_MachinegunLeadSolver.resolveCurrentLaunchWeaponUnit(weaponUnit);
        if (launchWeaponUnit == null) {
            launchWeaponUnit = weaponUnit;
        }
        // 调用本体实际发射单元瞄准上下文，确保火控方向与弹体生成共用真实炮口。
        AimContext aimContext = launchWeaponUnit.aimContext();
        if (aimContext != null && aimContext.from != null) {
            return aimContext.from;
        }
        // 调用本体实际发射单元炮闩坐标作为瞄准上下文缺失时的安全回退。
        return launchWeaponUnit.worldCurrentBoltPosition();
    }

    /** 沿指定世界方向调用本体瞄准，使用远点避免距离改变方向。 */
    private static void aimAlongDirection(WeaponUnit weaponUnit, Vec3 desiredDir) {
        aimAlongDirection(weaponUnit, aimOrigin(weaponUnit), desiredDir);
    }

    /** 从指定世界原点沿目标方向调用本体瞄准，供根视线和实际炮口分别使用。 */
    private static void aimAlongDirection(WeaponUnit weaponUnit, Vec3 aimFrom, Vec3 desiredDir) {
        if (desiredDir.lengthSqr() < 1.0E-6D) {
            return;
        }
        // 调用本体瞄准方法，将通用执行器求出的世界方向转换并同步为武器站旋转。
        weaponUnit.aim(aimFrom.add(desiredDir.normalize().scale(4096.0D)));
    }

    /** 清理指定武器站遗留的非机炮软限位平滑状态。 */
    private static void clearAimAssist(WeaponUnit weaponUnit) {
        AIM_ASSIST_STATES.remove(stateKey(weaponUnit));
    }

    /** 组合载具实体ID和武器站索引，隔离不同武器站的客户端平滑状态。 */
    private static int stateKey(WeaponUnit weaponUnit) {
        return weaponUnit.getVehicle().getId() * 257 + weaponUnit.getIndex();
    }
}
