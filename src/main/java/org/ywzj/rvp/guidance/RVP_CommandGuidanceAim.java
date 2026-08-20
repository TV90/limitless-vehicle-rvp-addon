package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 解析操作手用于指令制导的世界瞄准方向。
 *
 * <p>正常路径必须使用武器站已经完成稳定器、伺服转速与机械限位结算的实际物理姿态；
 * 客户端异步同步的目标角只用于实际姿态异常时的防御性回退。</p>
 */
public final class RVP_CommandGuidanceAim {

    /** 可用方向向量的最小长度平方，避免归一化零向量或数值噪声。 */
    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0E-6;

    private RVP_CommandGuidanceAim() {}

    /**
     * 获取操作手武器站的服务端权威世界瞄准方向。
     *
     * @param shooterUnit 弹体记录的射手武器站，可以为空
     * @return 已归一化的实际炮轴；实际炮轴异常时回退到目标角炮轴，两者都异常则返回 {@code null}
     */
    @Nullable
    public static Vec3 operatorAimDirection(@Nullable WeaponUnit shooterUnit) {
        WeaponUnit aimUnit = resolveOperatorAimUnit(shooterUnit);
        if (aimUnit == null) {
            return null;
        }

        // 调用本体无参 worldVec()，读取稳定器、伺服转速与机械限位结算后的服务端实际物理炮轴。
        Vec3 actualDirection = aimUnit.worldVec();
        if (isUsableDirection(actualDirection)) {
            return actualDirection.normalize();
        }

        // 实际炮轴异常时才调用本体目标角版本；目标角来自客户端异步命令，不能参与正常制导路径。
        Vec3 requestedDirection = aimUnit.worldVec(
                aimUnit.getXAimRot(), aimUnit.getYAimRot());
        return selectDirection(actualDirection, requestedDirection);
    }

    /**
     * 优先使用客户端已完成稳定器结算后同步的世界瞄准点解析指令制导方向。
     *
     * <p>本体服务端稳定器在目标角与实际角相差超过 5 度时会放弃车体转动补偿，
     * 因而仅依赖 {@link WeaponUnit#worldVec()} 仍可能把车体转向带入 LBR/SACLOS。
     * 世界瞄准点不与服务端车体局部角混算，可以消除该瞬态拉弹；同步点不可用时再回退实际炮轴。</p>
     *
     * @param shooterUnit 弹体记录的射手武器站，可以为空
     * @param beamOrigin 指令光束在世界坐标中的起点，可以为空
     * @param synchronizedAimPoint 客户端或 AI 同步的世界瞄准点，可以为空
     * @return 已归一化的世界指令方向；所有数据源都异常时返回 {@code null}
     */
    @Nullable
    public static Vec3 operatorAimDirection(@Nullable WeaponUnit shooterUnit,
                                            @Nullable Vec3 beamOrigin,
                                            @Nullable Vec3 synchronizedAimPoint) {
        Vec3 synchronizedDirection = directionFromPoint(beamOrigin, synchronizedAimPoint);
        if (synchronizedDirection != null) {
            return synchronizedDirection;
        }
        // 调用本项目炮轴解析回退，保证无玩家同步点的旧载具与无主弹体仍可继续制导。
        return operatorAimDirection(shooterUnit);
    }

    /** 将两个有限世界坐标转换为已归一化方向，坐标非法或过近时返回 {@code null}。 */
    @Nullable
    static Vec3 directionFromPoint(@Nullable Vec3 origin, @Nullable Vec3 aimPoint) {
        if (!isFiniteVector(origin) || !isFiniteVector(aimPoint)) {
            return null;
        }
        Vec3 direction = aimPoint.subtract(origin);
        return isUsableDirection(direction) ? direction.normalize() : null;
    }

    /**
     * 从实际炮轴和目标角炮轴中选择可用方向，实际炮轴始终具有更高优先级。
     *
     * <p>该纯函数用于锁定制导数据源优先级，防止以后重新把异步目标角放回正常路径。</p>
     */
    @Nullable
    static Vec3 selectDirection(@Nullable Vec3 actualDirection,
                                @Nullable Vec3 requestedDirection) {
        if (isUsableDirection(actualDirection)) {
            return actualDirection.normalize();
        }
        return isUsableDirection(requestedDirection)
                ? requestedDirection.normalize()
                : null;
    }

    /** 判断方向向量的各分量和长度是否可安全参与归一化。 */
    private static boolean isUsableDirection(@Nullable Vec3 direction) {
        if (!isFiniteVector(direction)) {
            return false;
        }
        double lengthSqr = direction.lengthSqr();
        return Double.isFinite(lengthSqr) && lengthSqr > MIN_DIRECTION_LENGTH_SQR;
    }

    /** 判断世界坐标或方向的三个分量是否均为有限值。 */
    private static boolean isFiniteVector(@Nullable Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    /**
     * 解析真正承载操作手瞄准姿态的武器站。
     *
     * @param shooterUnit 弹体记录的射手武器站，可以为空
     * @return 子武器站要求继承父站瞄准时返回根武器站，否则返回原武器站
     */
    @Nullable
    public static WeaponUnit resolveOperatorAimUnit(@Nullable WeaponUnit shooterUnit) {
        if (shooterUnit == null) {
            return null;
        }
        // 调用本体武器站层级方法，将 parent_weapon_unit_aim 子站解析到实际负责瞄准的根武器站。
        return shooterUnit.isParentWeaponUnitAim()
                ? shooterUnit.getRootParentWeaponUnit()
                : shooterUnit;
    }
}
