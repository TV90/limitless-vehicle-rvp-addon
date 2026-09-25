package org.ywzj.rvp.entity.gunner.behavior.api;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerMovementActions;

/** 单 tick 行为意图；仅携带动作数据和仲裁元数据，不直接执行游戏写操作。 */
public final class RVP_GunnerBehaviorIntent {

    /** 意图通道。 */
    public enum Channel {
        /** 权威目标通道。 */ TARGET,
        /** 司机补给通道。 */ SUPPLY,
        /** 干扰物/本体式反制通道。 */ COUNTERMEASURE,
        /** 主动 ECM 通道。 */ ECM,
        /** 本车或外置雷达通道。 */ RADAR_LOCK,
        /** 在途制导维持通道。 */ GUIDANCE_MAINTAIN,
        /** 载具移动通道。 */ MOVEMENT,
        /** 普通、CIWS 与 SEAD 共用的开火通道。 */ FIRE,
        /** 表现与调试同步通道。 */ SYNC
    }

    /** 管理器可执行的固定计划动作类型。 */
    public enum Kind {
        /** 提交目标。 */ TARGET,
        /** 首次补满并持续补给。 */ SUPPLY_MAINTAIN,
        /** 清理司机补给计时。 */ SUPPLY_CLEAR,
        /** 使用本体式反制武器。 */ BASE_COUNTERMEASURE,
        /** 释放 RVP 干扰物。 */ RVP_COUNTERMEASURE,
        /** 触发主动 ECM。 */ ACTIVE_ECM,
        /** 维持本车雷达锁。 */ LOCAL_RADAR,
        /** 维持外置雷达锁。 */ EXTERNAL_RADAR,
        /** 维持 GPS、照射与 HITL。 */ GUIDANCE_MAINTAIN,
        /** 应用唯一移动命令。 */ MOVEMENT,
        /** 执行普通/CIWS 武器交战事务。 */ FIRE_ENGAGEMENT,
        /** 执行 AntiRadiation 发射事务。 */ FIRE_ANTI_RADIATION,
        /** 占用本 tick 武器通道但不发射，用于脱离/起飞等安全门控。 */ FIRE_HOLD,
        /** 清除当前受控武器索引。 */ CLEAR_CONTROLLED_WEAPON
    }

    /** 动作结果回调，只能推进提交该意图的运行时状态。 */
    @FunctionalInterface
    public interface ResultHandler {
        /** 接收动作层真实返回结果。 */
        void accept(RVP_GunnerActionResult result);
    }

    /** 行为实例 ID。 */
    private final String behaviorId;
    /** 通道内资源键。 */
    private final String resourceKey;
    /** 固定计划内顺序。 */
    private final int planOrder;
    /** 意图优先级。 */
    private final int priority;
    /** 意图通道。 */
    private final Channel channel;
    /** 动作类型。 */
    private final Kind kind;
    /** 目标或威胁实体。 */
    @Nullable private final Entity target;
    /** 移动命令，仅 MOVEMENT 使用。 */
    @Nullable private final RVP_GunnerMovementActions.Command movement;
    /** 干扰物类型，仅 RVP_COUNTERMEASURE 使用。 */
    @Nullable private final RVP_EnumCountermeasureType countermeasureType;
    /** 当前是否为允许驾驶的司机 AI。 */
    private final boolean driverAi;
    /** 开火事务 ID，用于把瞄准与发射绑定为同一事务。 */
    private final String transactionId;
    /** 动作执行后的状态推进回调。 */
    @Nullable private final ResultHandler resultHandler;

    private RVP_GunnerBehaviorIntent(String behaviorId, String resourceKey, int planOrder, int priority,
                                     Channel channel, Kind kind, @Nullable Entity target,
                                     @Nullable RVP_GunnerMovementActions.Command movement,
                                     @Nullable RVP_EnumCountermeasureType countermeasureType,
                                     boolean driverAi, String transactionId,
                                     @Nullable ResultHandler resultHandler) {
        this.behaviorId = behaviorId;
        this.resourceKey = resourceKey;
        this.planOrder = planOrder;
        this.priority = priority;
        this.channel = channel;
        this.kind = kind;
        this.target = target;
        this.movement = movement;
        this.countermeasureType = countermeasureType;
        this.driverAi = driverAi;
        this.transactionId = transactionId;
        this.resultHandler = resultHandler;
    }

    /** 创建通用固定计划意图。 */
    public static RVP_GunnerBehaviorIntent of(String behaviorId, String resourceKey, int planOrder, int priority,
                                              Channel channel, Kind kind, @Nullable Entity target,
                                              @Nullable RVP_GunnerMovementActions.Command movement,
                                              @Nullable RVP_EnumCountermeasureType countermeasureType,
                                              boolean driverAi, String transactionId,
                                              @Nullable ResultHandler resultHandler) {
        return new RVP_GunnerBehaviorIntent(behaviorId, resourceKey, planOrder, priority, channel, kind,
                target, movement, countermeasureType, driverAi, transactionId, resultHandler);
    }

    /** 返回行为实例 ID。 */ public String behaviorId() { return behaviorId; }
    /** 返回通道资源键。 */ public String resourceKey() { return resourceKey; }
    /** 返回固定计划顺序。 */ public int planOrder() { return planOrder; }
    /** 返回优先级。 */ public int priority() { return priority; }
    /** 返回通道。 */ public Channel channel() { return channel; }
    /** 返回动作类型。 */ public Kind kind() { return kind; }
    /** 返回目标或威胁。 */ @Nullable public Entity target() { return target; }
    /** 返回移动命令。 */ @Nullable public RVP_GunnerMovementActions.Command movement() { return movement; }
    /** 返回干扰物类型。 */ @Nullable public RVP_EnumCountermeasureType countermeasureType() { return countermeasureType; }
    /** 返回是否允许司机 AI。 */ public boolean driverAi() { return driverAi; }
    /** 返回开火事务 ID。 */ public String transactionId() { return transactionId; }

    /** 将动作结果送回唯一提交者。 */
    public void notifyResult(RVP_GunnerActionResult result) {
        if (resultHandler != null) resultHandler.accept(result);
    }

    /** 用于调试快照的稳定描述。 */
    public String debugName() {
        return behaviorId + "/" + kind + "@" + priority;
    }
}
