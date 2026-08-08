package org.ywzj.rvp.virtualflight.server;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * 集中维护阶段 B 的静态配置和动态实体资格检查。
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
        /** 主制导不是固定 GPS。 */ INELIGIBLE_GUIDANCE,
        /** 配置了阶段 B 尚不模拟的末段制导。 */ INELIGIBLE_TERMINAL_GUIDANCE,
        /** 配置了活动 Top Attack 弹道；状态可快照，但纯积分器尚不推进该轨迹。 */ INELIGIBLE_TOP_ATTACK,
        /** 存在 HITL、线导等实时操控依赖。 */ INELIGIBLE_REALTIME_CONTROL,
        /** 存在沿途引信、布撒、子母弹或跳弹等世界依赖。 */ INELIGIBLE_WORLD_DEPENDENT_PAYLOAD,
        /** 实体没有稳定目标点。 */ INELIGIBLE_NO_TARGET_POS,
        /** 当前仍绑定动态目标实体。 */ INELIGIBLE_TARGET_ENTITY,
        /** 实体正处于区块等待门控。 */ INELIGIBLE_WAITING_FOR_CHUNK,
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
        if (target == null) return Result.INELIGIBLE_NO_TARGET_POS;
        if (missile.getTargetEntity() != null) return Result.INELIGIBLE_TARGET_ENTITY;
        if (missile.isWaitingForChunk()) return Result.INELIGIBLE_WAITING_FOR_CHUNK;
        if (missile.getFlightTickCount() < config.getEntryMinFlightTick()) return Result.INELIGIBLE_FLIGHT_TICK;
        if (missile.position().distanceTo(missile.getVirtualMidcourseLaunchPosition())
                < config.getEntryMinDistanceFromLaunch()) return Result.INELIGIBLE_LAUNCH_DISTANCE;
        if (missile.position().distanceTo(target) < config.getEntryMinTargetDistance()) {
            return Result.INELIGIBLE_TARGET_DISTANCE;
        }
        if (!finite(target) || !finite(missile.position()) || !finite(missile.getDeltaMovement())
                || missile.getDeltaMovement().lengthSqr() <= 1.0E-12) {
            return Result.INELIGIBLE_INVALID_KINEMATICS;
        }
        return Result.ELIGIBLE;
    }

    /** 从完整武器对象提取资格所需字段并调用可单测的纯参数重载。 */
    public static Result evaluateConfiguration(RVP_WeaponData data) {
        return evaluateConfiguration(data.getVirtualMidcourseData(), data.getGuidanceData(), data.getFuseData(),
                data.hasHumanInTheLoop(), data.getEffectsData().isWireLinkEnabled(),
                data.getSubmunitionData().isEnabled(), data.getDispenserData().hasItem(),
                data.getBounce(), data.getBounceFuseTick());
    }

    /**
     * 纯静态配置检查重载，不依赖实体或世界，供加载检查和单元测试复用。
     */
    static Result evaluateConfiguration(RVP_VirtualMidcourseData config, RVP_GuidanceData guidance,
                                        RVP_FuseData fuse, boolean humanInLoop, boolean wireLink,
                                        boolean submunition, boolean dispenser, int bounce, int bounceFuseTick) {
        if (!config.isEnabled()) return Result.DISABLED;
        if (!config.usesFixedSnapshotTarget()) return Result.INELIGIBLE_TARGET_MODE;
        if (!config.discardsOnRestoreTimeout()
                || config.getRestoreTargetDistance() >= config.getEntryMinTargetDistance()) {
            return Result.INELIGIBLE_CONFIGURATION;
        }
        if (guidance.getGuidanceType() != RVP_EnumGuidanceType.GPS) return Result.INELIGIBLE_GUIDANCE;
        if (guidance.getTerminalGuidance() != null) return Result.INELIGIBLE_TERMINAL_GUIDANCE;
        if (guidance.getTopAttackHeight() != null && Math.abs(guidance.getTopAttackHeight()) > 1.0E-6f) {
            return Result.INELIGIBLE_TOP_ATTACK;
        }
        if (humanInLoop || wireLink) {
            return Result.INELIGIBLE_REALTIME_CONTROL;
        }
        if (submunition || dispenser
                || fuse.getDelayTick() > 0 || fuse.getProximityRadius() > 0f
                || fuse.isProgrammableAirburst() || fuse.isAheadEnabled()
                || fuse.isTopAttackFuseEnabled() || bounce > 0 || bounceFuseTick > 0) {
            return Result.INELIGIBLE_WORLD_DEPENDENT_PAYLOAD;
        }
        return Result.ELIGIBLE;
    }

    /** @return 向量三个分量是否均为有限 double。 */
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
