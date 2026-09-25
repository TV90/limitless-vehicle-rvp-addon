package org.ywzj.rvp.guidance.saclos;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeMath;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * SACLOS在STABLE火控模式下复用现有PIP的服务端权威资格与目标解析器。
 *
 * <p>本类只返回当Tick临时使用的目标实体，不写入弹体{@code targetEntity}，因此不会把
 * 雷达目标持久升级为视线弹的近炸目标、弹上持锁或跨Tick实体追踪源；当Tick的普通制导位置
 * 记忆仍由共享运行时按既有规则刷新。</p>
 */
public final class RVP_SACLOSStablePIPAssist {

    /** 客户端每Tick同步一次；超过500毫秒未刷新即视为STABLE请求失效。 */
    private static final long STABLE_REQUEST_MAX_AGE_MS = 500L;

    /** 工具类不允许实例化。 */
    private RVP_SACLOSStablePIPAssist() {}

    /**
     * 解析本Tick可交给既有PIP转向的雷达硬锁目标。
     *
     * @param context 当前服务端制导上下文
     * @return 全部门控通过后的硬锁实体，否则返回null并继续原SACLOS视线制导
     */
    @Nullable
    public static Entity resolveTarget(@Nullable RVP_GuidanceRuntimeContext context) {
        if (context == null || context.projectile() == null || context.active() == null
                || context.data() == null || context.projectile().getOwner() == null) {
            return null;
        }
        // 调用本项目操作手会话，只接受仍由客户端逐Tick刷新的STABLE辅助请求。
        boolean stableRequestFresh = RVP_SaclosOperatorSession.isStablePIPAssistRequested(
                context.projectile().getOwner().getUUID(), STABLE_REQUEST_MAX_AGE_MS);

        // 调用本项目指令制导武器站解析，使parent_weapon_unit_aim子站落到实际根火控站。
        WeaponUnit aimUnit = RVP_CommandGuidanceAim.resolveOperatorAimUnit(
                context.projectile().getShooterWeaponUnit());
        if (aimUnit == null || !(aimUnit.getData() instanceof WeaponUnitDataExt ext)) {
            return null;
        }
        // 调用本项目双端安全传感器解析，服务端不引用LocalVehiclePlayer客户端类型。
        WeaponUnitData.FireControlSensorType sensorType =
                RVP_WeaponSensorHelper.effectiveOrStatic(aimUnit);
        // 调用本项目武器解析器，要求操作手当前仍选中SACLOS导弹；切到机炮或其它弹种后
        // 即使旧客户端请求尚在500毫秒新鲜窗口内，也不能继续借用STABLE PIP。
        RVP_WeaponBase selectedWeapon = RVP_WeaponResolveHelper.currentPrimaryRvp(aimUnit);
        boolean selectedSACLOSMissile = selectedWeapon != null
                && selectedWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                && selectedWeapon.getData().usesGuidanceType(RVP_EnumGuidanceType.SACLOS);
        boolean eligible = supportsStablePIP(
                stableRequestFresh,
                context.data().getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                        && selectedSACLOSMissile,
                ext.ywzj_rvp$getFireControlMode(),
                sensorType,
                context.active().guidanceType(),
                context.active().predictTargetPos(),
                context.projectile().getFlightTickCount(),
                context.active().predictTargetPosStartTick(),
                context.active().topAttackHeight()
        );
        if (!eligible) {
            return null;
        }
        // 调用共享PIP门控，保留诱饵干扰和二脉冲等待期的原SACLOS回退行为。
        if (!RVP_GuidanceRuntimeMath.canUsePredictiveIntercept(context)) {
            return null;
        }
        // 调用本项目雷达硬锁解析；不读取WeaponUnit软锁，也不信任客户端目标ID。
        return RVP_RadarRoleHelper.getConfirmedRFHardLockedEntity(aimUnit);
    }

    /** 纯状态资格判定，供单元测试冻结方案A的严格边界。 */
    static boolean supportsStablePIP(boolean stableRequestFresh,
                                     boolean missile,
                                     @Nullable String fireControlMode,
                                     @Nullable WeaponUnitData.FireControlSensorType sensorType,
                                     @Nullable RVP_EnumGuidanceType guidanceType,
                                     boolean predictTargetPos,
                                     int flightTick,
                                     int predictStartTick,
                                     @Nullable Float topAttackHeight) {
        boolean topAttack = topAttackHeight != null
                && Math.abs(topAttackHeight) > 1.0E-6F;
        return stableRequestFresh
                && missile
                && "rvp_rf".equalsIgnoreCase(fireControlMode == null ? "" : fireControlMode.trim())
                && sensorType == WeaponUnitData.FireControlSensorType.RF
                && guidanceType == RVP_EnumGuidanceType.SACLOS
                && predictTargetPos
                && flightTick >= Math.max(predictStartTick, 0)
                && !topAttack;
    }
}
