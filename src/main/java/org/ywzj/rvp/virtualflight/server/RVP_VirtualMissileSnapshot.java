package org.ywzj.rvp.virtualflight.server;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryState;

import java.util.UUID;

/**
 * 实体与虚拟记录之间传递的完整、不可变运行时快照。
 *
 * <p>该类型只承载恢复真实实体必须保持连续的状态，不执行积分、世界查询或 NBT 编解码。
 * NBT 字段由 {@link RVP_VirtualMissileState} 集中维护。</p>
 *
 * @param trajectory 位置、速度、姿态、航程、寿命和双脉冲开始时间
 * @param targetPosition 当前权威目标点
 * @param lastGuidancePosition 最近有效制导点
 * @param targetEntityUuid 可选目标实体稳定 UUID
 * @param lastKnownTargetVelocity 进入虚拟态时目标最后已知速度
 * @param guidancePhase MAIN/TERMINAL 制导相位
 * @param motorBurnEndTick 主发动机燃烧结束 Tick
 * @param secondPulseBurnTimeTick 第二脉冲持续 Tick
 * @param activeRadarOn 弹载主动雷达是否开机
 * @param activeRadarCatch 弹载主动雷达是否已捕获
 * @param activeRadarLostTargetTick 主动雷达连续丢失目标 Tick
 * @param gpsCruiseVerticalResetApplied GPS 巡航垂直速度一次性重置是否已执行
 * @param gpsTargetOffset GPS 散布偏移快照
 * @param gpsTargetOffsetResolved GPS 散布偏移是否已生成
 * @param topAttackLaunchPosition Top Attack 轨迹发射参考点
 * @param topAttackInitialTargetPosition Top Attack 初始目标参考点
 * @param topAttackApexPosition Top Attack 顶点
 * @param topAttackApexReached 是否已经越过 Top Attack 顶点
 * @param topAttackTriggerTick 攻顶引信触发 Tick；不支持的引信会在资格阶段拒绝
 * @param irSeekerGraceUntilTick 红外导引头宽限结束 Tick
 * @param irSeekerLossGraceStarted 红外丢失宽限是否已经启动
 */
public record RVP_VirtualMissileSnapshot(
        RVP_VirtualTrajectoryState trajectory,
        Vec3 targetPosition,
        @Nullable Vec3 lastGuidancePosition,
        @Nullable UUID targetEntityUuid,
        Vec3 lastKnownTargetVelocity,
        RVP_GuidancePhase guidancePhase,
        int motorBurnEndTick,
        int secondPulseBurnTimeTick,
        boolean activeRadarOn,
        boolean activeRadarCatch,
        int activeRadarLostTargetTick,
        boolean gpsCruiseVerticalResetApplied,
        @Nullable Vec3 gpsTargetOffset,
        boolean gpsTargetOffsetResolved,
        @Nullable Vec3 topAttackLaunchPosition,
        @Nullable Vec3 topAttackInitialTargetPosition,
        @Nullable Vec3 topAttackApexPosition,
        boolean topAttackApexReached,
        int topAttackTriggerTick,
        int irSeekerGraceUntilTick,
        boolean irSeekerLossGraceStarted) {
}
