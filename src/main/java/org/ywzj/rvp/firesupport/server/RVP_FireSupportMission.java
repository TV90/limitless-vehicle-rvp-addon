package org.ywzj.rvp.firesupport.server;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.firesupport.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.RVP_FireSupportSchedulePlanner;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.Map;
import java.util.UUID;

/** 单个服务端权威炮火任务；不缓存 Player、ItemStack 或世界等可变对象。 */
public final class RVP_FireSupportMission {
    /** 任务唯一 ID。 */ public final UUID missionId;
    /** 发起玩家 UUID。 */ public final UUID ownerId;
    /** 用于离线归属代理的已过滤玩家名。 */ public final String ownerName;
    /** 新呼叫绑定的具体终端实例 UUID。 */ public final UUID terminalInstanceId;
    /** 接受请求时玩家所在维度。 */ public final ResourceKey<Level> dimension;
    /** 创建任务所用 profile ID。 */ public final ResourceLocation profileId;
    /** 创建任务所用 profile revision。 */ public final long profileRevision;
    /** 冻结 profile 引用；重载会替换快照而不会修改此对象。 */ public final RVP_FireSupportProfile profile;
    /** 冻结弹种配置。 */ public final RVP_FireSupportProfile.Munition munition;
    /** 冻结射击模式配置。 */ public final RVP_FireSupportProfile.FireMode fireMode;
    /** 冻结打击预设配置。 */ public final RVP_FireSupportProfile.PatternPreset patternPreset;
    /** 冻结的类型化投送实现。 */ public final RVP_FireSupportDelivery delivery;
    /** 接受时由本体权威索引解析的 RVP 武器数据。 */ public final RVP_WeaponData weaponData;
    /** 服务端规范化后的动态参数。 */ public final Map<String, Double> parameters;
    /** 目标锚点世界 X。 */ public final double targetX;
    /** 目标锚点世界 Z。 */ public final double targetZ;
    /** 长轴或徐进方向，单位度。 */ public final double headingDegrees;
    /** 服务端权威随机种子。 */ public final long authoritativeSeed;
    /** 服务端接受任务的世界 Tick。 */ public final long acceptedTick;
    /** 呼叫阶段不可逆结束的世界 Tick。 */ public final long callDeadlineTick;
    /** 完整不可变弹序计划。 */ public final RVP_FireSupportSchedulePlanner.Plan plan;
    /** 当前任务状态。 */ RVP_FireSupportMissionState state = RVP_FireSupportMissionState.CALLING;
    /** 当前尚未成功生成的全局弹序号。 */ int nextRoundIndex;
    /** 当前单发连续生成失败次数。 */ int consecutiveFailures;
    /** 停火生效 Tick；Long.MAX_VALUE 表示尚未请求。 */ long ceaseFireEffectiveTick = Long.MAX_VALUE;
    /** 终态或异常原因。 */ RVP_FireSupportEndReason endReason = RVP_FireSupportEndReason.NONE;

    public RVP_FireSupportMission(UUID missionId, UUID ownerId, String ownerName, UUID terminalInstanceId,
                                  ResourceKey<Level> dimension, ResourceLocation profileId, long profileRevision,
                                  RVP_FireSupportProfile profile, RVP_FireSupportProfile.Munition munition,
                                  RVP_FireSupportProfile.FireMode fireMode,
                                  RVP_FireSupportProfile.PatternPreset patternPreset,
                                  RVP_FireSupportDelivery delivery, RVP_WeaponData weaponData,
                                  Map<String, Double> parameters, double targetX, double targetZ,
                                  double headingDegrees, long authoritativeSeed, long acceptedTick,
                                  RVP_FireSupportSchedulePlanner.Plan plan) {
        this.missionId = missionId;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.terminalInstanceId = terminalInstanceId;
        this.dimension = dimension;
        this.profileId = profileId;
        this.profileRevision = profileRevision;
        this.profile = profile;
        this.munition = munition;
        this.fireMode = fireMode;
        this.patternPreset = patternPreset;
        this.delivery = delivery;
        this.weaponData = weaponData;
        this.parameters = Map.copyOf(parameters);
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.headingDegrees = headingDegrees;
        this.authoritativeSeed = authoritativeSeed;
        this.acceptedTick = acceptedTick;
        this.callDeadlineTick = Math.addExact(acceptedTick, plan.callDurationTicks());
        this.plan = plan;
    }

    /** @return 下一发绝对计划 Tick；任务已无剩余弹时返回 Long.MAX_VALUE。 */
    public long nextSpawnTick() {
        if (nextRoundIndex >= plan.rounds().size()) return Long.MAX_VALUE;
        return Math.addExact(callDeadlineTick, plan.rounds().get(nextRoundIndex).strikeOffsetTicks());
    }

    /** @return 是否已经进入终态。 */
    public boolean terminal() {
        return state == RVP_FireSupportMissionState.COMPLETED || state == RVP_FireSupportMissionState.CANCELLED
                || state == RVP_FireSupportMissionState.CEASED || state == RVP_FireSupportMissionState.FAILED;
    }
}
