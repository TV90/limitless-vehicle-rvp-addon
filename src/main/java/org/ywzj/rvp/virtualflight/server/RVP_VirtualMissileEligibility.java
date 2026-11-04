package org.ywzj.rvp.virtualflight.server;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * 所有 RVP 导弹共用的静态配置和动态实体资格检查，不按制导类型设置白名单。
 *
 * <p>检查只读取已有配置/实体字段，不查询区块、目标世界状态或碰撞。返回稳定枚举，
 * 由管理器决定是否记录一次拒绝日志。</p>
 */
public final class RVP_VirtualMissileEligibility {
    private RVP_VirtualMissileEligibility() {}

    /** 按检查顺序返回的稳定资格结果码。 */
    public enum Result {
        /** 所有静态和动态条件均满足。 */ ELIGIBLE,
        /** 武器未显式启用虚拟中段。 */ DISABLED,
        /** 阶段 B 不支持当前目标更新模式。 */ INELIGIBLE_TARGET_MODE,
        /** 恢复距离、超时策略等配置不满足安全约束。 */ INELIGIBLE_CONFIGURATION,
        /** 实体正处于区块等待门控。 */ INELIGIBLE_WAITING_FOR_CHUNK,
        /** 冷发射、水中、部署分量或恢复冷却仍需要真实世界推进。 */ INELIGIBLE_WORLD_DEPENDENT_MOTION,
        /** 尚未达到最小真实飞行 Tick。 */ INELIGIBLE_FLIGHT_TICK,
        /** 尚未离开发射点足够远。 */ INELIGIBLE_LAUNCH_DISTANCE,
        /** 已过于接近目标，不值得进入虚拟态。 */ INELIGIBLE_TARGET_DISTANCE,
        /** 位置、目标或速度非有限/速度为零。 */ INELIGIBLE_INVALID_KINEMATICS
    }

    /**
     * 依次执行武器静态检查和导弹当前运行状态检查。
     *
     * @param missile 待转换的真实 RVP 导弹
     * @param data 当前 schema 武器数据
     */
    public static Result evaluate(RVP_MissileEntity missile, RVP_WeaponData data) {
        // 先调用纯配置检查，避免动态分支重复解析世界无关限制。
        Result configuration = evaluateConfiguration(data);
        if (configuration != Result.ELIGIBLE) return configuration;
        RVP_VirtualMidcourseData config = data.getVirtualMidcourseData();
        Vec3 target = missile.getTargetPos();
        if (missile.isWaitingForChunk()) return Result.INELIGIBLE_WAITING_FOR_CHUNK;
        // 调用实体运动资格出口，冷发射结束后才切换，避免脱离载具后改变弹射方向或漏掉水中运动。
        if (missile.hasWorldDependentMidcourseMotion()) return Result.INELIGIBLE_WORLD_DEPENDENT_MOTION;
        if (missile.getFlightTickCount() < config.getEntryMinFlightTick()) return Result.INELIGIBLE_FLIGHT_TICK;
        if (missile.position().distanceTo(missile.getVirtualMidcourseLaunchPosition())
                < config.getEntryMinDistanceFromLaunch()) return Result.INELIGIBLE_LAUNCH_DISTANCE;
        if (target != null && missile.position().distanceTo(target) < config.getEntryMinTargetDistance()) {
            return Result.INELIGIBLE_TARGET_DISTANCE;
        }
        if (target != null && !finite(target) || !finite(missile.position()) || !finite(missile.getDeltaMovement())
                || missile.getDeltaMovement().lengthSqr() <= 1.0E-12) {
            return Result.INELIGIBLE_INVALID_KINEMATICS;
        }
        return Result.ELIGIBLE;
    }

    /** 从完整武器对象提取资格所需字段并调用可单测的纯参数重载。 */
    public static Result evaluateConfiguration(RVP_WeaponData data) {
        // 所有制导、引信与载荷均可申请；世界结算由运行时提前恢复门负责，不能在此按弹种拒绝。
        return evaluateConfiguration(data.getVirtualMidcourseData());
    }

    /**
     * 纯静态配置检查重载，不依赖实体或世界，供加载检查和单元测试复用。
     */
    static Result evaluateConfiguration(RVP_VirtualMidcourseData config) {
        if (!config.isEnabled()) return Result.DISABLED;
        if (!config.usesFixedSnapshotTarget()) return Result.INELIGIBLE_TARGET_MODE;
        if (!config.discardsOnRestoreTimeout()
                || config.getRestoreTargetDistance() >= config.getEntryMinTargetDistance()) {
            return Result.INELIGIBLE_CONFIGURATION;
        }
        // 仅保留当前 schema 的显式开关和恢复配置校验；制导状态由原对象完整保留。
        return Result.ELIGIBLE;
    }

    /** @return 向量三个分量是否均为有限 double。 */
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
