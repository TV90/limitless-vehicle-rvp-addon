package org.ywzj.rvp.virtualflight.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightReason;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryParameters;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryResult;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 虚拟飞行生命周期日志、累计计数和性能统计出口。
 *
 * <p>显式开关与现有 projectilelife 调试开关任一启用即可输出；关闭时逐弹方法只做一次
 * 快速布尔判断。所有调用均来自服务器主线程，因此计数器不承担跨线程同步职责。</p>
 */
public final class RVP_VirtualMissileDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 独立 virtualflight 调试开关。 */
    private static boolean enabled;
    /** 成功从真实态进入虚拟态的累计次数。 */
    private static long entered;
    /** 进入恢复请求阶段的累计次数。 */
    private static long restoreRequested;
    /** 成功恢复真实实体的累计次数。 */
    private static long restored;
    /** 恢复尝试失败累计次数。 */
    private static long restoreFailed;
    /** 恢复等待超时累计次数。 */
    private static long timedOut;
    /** 非法或非有限状态累计次数。 */
    private static long invalid;
    /** 公平预算实际批准的新 Ticket 数。 */
    private static long ticketsGranted;
    /** 纯积分器累计耗时。 */
    private static long integrationNanos;
    /** 管理器 ServerTick 累计耗时。 */
    private static long managerNanos;

    private RVP_VirtualMissileDebug() {}

    /** 设置独立调试开关。 */
    public static void setEnabled(boolean value) { enabled = value; }
    /** @return 独立开关或 projectilelife 开关是否开启。 */
    public static boolean isEnabled() { return enabled || RVP_ProjectileLifecycleDebug.isEnabled(); }
    /** 记录一次成功进入虚拟态。 */ public static void noteEntered() { entered++; }
    /** 记录一次恢复请求。 */ public static void noteRestoreRequested() { restoreRequested++; }
    /** 记录一次成功实体恢复。 */ public static void noteRestored() { restored++; }
    /** 记录一次恢复尝试失败。 */ public static void noteRestoreFailed() { restoreFailed++; }
    /** 记录一次恢复等待超时。 */ public static void noteTimedOut() { timedOut++; }
    /** 记录一次非法状态。 */ public static void noteInvalid() { invalid++; }
    /** 累加本 Tick 新批准 Ticket 数。 */ public static void noteTicketsGranted(int count) { ticketsGranted += count; }
    /** 累加纯积分器耗时，负数按零处理。 */ public static void noteIntegrationNanos(long nanos) { integrationNanos += Math.max(nanos, 0); }
    /** 累加管理器调度耗时，负数按零处理。 */ public static void noteManagerNanos(long nanos) { managerNanos += Math.max(nanos, 0); }

    /** 输出单实体一次性的虚拟化资格拒绝详情。 */
    public static void eligibilityRejected(RVP_MissileEntity missile,
                                           RVP_VirtualMissileEligibility.Result result) {
        if (!isEnabled() || !missile.markVirtualEligibilityRejectionLogged()) return;
        LOGGER.info("[RVP][VirtualFlight] event=VIRTUAL_ENTRY_REJECTED uuid={} entityId={} weapon={} "
                        + "dimension={} flightTick={} pos={} velocity={} result={}",
                missile.getUUID(), missile.getId(), missile.getWeaponId(), missile.level().dimension().location(),
                missile.getFlightTickCount(), missile.position(), missile.getDeltaMovement(), result);
    }

    /**
     * 输出一次权威状态转换或终止事件，包含恢复诊断所需的完整关联字段。
     */
    public static void lifecycle(RVP_VirtualMissileState state, RVP_VirtualFlightReason reason, String detail) {
        if (!isEnabled()) return;
        LOGGER.info("[RVP][VirtualFlight] event={} uuid={} oldEntityId={} weapon={} dimension={} phase={} "
                        + "gameTime={} flightTick={} life={} pos={} velocity={} target={} distance={} travelled={} "
                        + "restoreWait={} restoreAttempts={} integrator={}/{} detail={}",
                reason, state.flightUuid, state.originalEntityId, state.weaponId, state.dimension, state.phase,
                state.lastUpdatedGameTime, state.trajectory().flightTick(), state.trajectory().remainingLife(),
                state.trajectory().position(), state.trajectory().velocity(), state.snapshot.targetPosition(),
                state.trajectory().position().distanceTo(state.snapshot.targetPosition()),
                state.trajectory().flightDistance(), state.restoreWaitTicks, state.restoreAttemptCount,
                state.trajectoryImplementationId, state.trajectoryStateVersion, detail);
    }

    /**
     * 输出单 Tick 转换连续性数据：前后位置/速度、最大 G、实际 G、实际 θ 和航程。
     */
    public static void trajectoryTick(RVP_VirtualMissileState state, Vec3Before before,
                                      RVP_VirtualTrajectoryParameters parameters,
                                      RVP_VirtualTrajectoryResult result) {
        if (!isEnabled()) return;
        double speedBefore = before.velocity().length();
        double actualGs = PhysicsEngine.G <= 0.0 || speedBefore <= 1.0E-8 ? 0.0
                : 2.0 * speedBefore * Math.sin(result.turnAngleRadians() * 0.5) / PhysicsEngine.G;
        LOGGER.info("[RVP][VirtualFlight][Trajectory] uuid={} flightTick={} beforePos={} afterPos={} "
                        + "beforeVelocity={} afterVelocity={} speed={} maxGs={} actualGs={} thetaRad={} travelled={}",
                state.flightUuid, result.state().flightTick(), before.position(), result.state().position(),
                before.velocity(), result.state().velocity(), result.state().velocity().length(),
                parameters.maxGs(), actualGs, result.turnAngleRadians(), result.state().flightDistance());
    }

    /** 积分前位置与速度的不可变日志快照，避免更新 state 后丢失前值。 */
    public record Vec3Before(net.minecraft.world.phys.Vec3 position,
                             net.minecraft.world.phys.Vec3 velocity) {}

    /** 每 200 个服务器 Tick 输出一次汇总；关闭调试时不生成字符串。 */
    public static void summarizeIfDue(MinecraftServer server) {
        if (!isEnabled() || server.getTickCount() % 200 != 0) return;
        // 调用 status 统一生成命令与周期日志共用的统计格式。
        LOGGER.info("[RVP][VirtualFlight] summary {}", status(server));
    }

    /** @return 面向日志和调试命令的单行累计状态文本。 */
    public static String status(MinecraftServer server) {
        // activeCount 通过各维度 SavedData 汇总当前权威记录数。
        return "active=" + RVP_VirtualMissileManager.activeCount(server)
                + " entered=" + entered + " restoreRequested=" + restoreRequested
                + " restored=" + restored + " restoreFailed=" + restoreFailed
                + " timeout=" + timedOut + " invalid=" + invalid
                + " ticketsGranted=" + ticketsGranted
                + " integrationMs=" + String.format(java.util.Locale.ROOT, "%.3f", integrationNanos / 1_000_000.0)
                + " managerMs=" + String.format(java.util.Locale.ROOT, "%.3f", managerNanos / 1_000_000.0);
    }
}
