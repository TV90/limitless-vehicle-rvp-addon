package org.ywzj.rvp.firesupport.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;

/** 把数据驱动阶段解释成确定性的不可变任务时间表，不识别任何模式名称。 */
public final class RVP_FireSupportSchedulePlanner {
    private RVP_FireSupportSchedulePlanner() {}

    /**
     * 根据弹种基数、模式阶段和权威种子生成相对 Tick 时间表。
     *
     * @param baseCallDurationTicks profile 呼叫基准时长，单位 Tick
     * @param roundsPerUnit 弹种一基数逻辑弹数
     * @param registrationPhaseEnabled 是否包含标记为试射的阶段
     * @param mode 已校验的数据驱动模式
     * @param authoritativeSeed 服务端权威随机种子
     * @param limits profile 任务上限
     */
    public static Plan plan(int baseCallDurationTicks, int roundsPerUnit, boolean registrationPhaseEnabled,
                            RVP_FireSupportProfile.FireMode mode, long authoritativeSeed,
                            RVP_FireSupportProfile.Limits limits) {
        int callTicks = checkedCeil(baseCallDurationTicks * mode.callDurationMultiplier(), "呼叫时长溢出");
        List<PlannedRound> rounds = new ArrayList<>();
        Random random = new Random(authoritativeSeed);
        int previousLastTick = 0;
        int globalIndex = 0;
        int includedPhaseIndex = 0;
        for (int phaseIndex = 0; phaseIndex < mode.phases().size(); phaseIndex++) {
            RVP_FireSupportProfile.Phase phase = mode.phases().get(phaseIndex);
            if (phase.registrationPhase() && !registrationPhaseEnabled) continue;
            int count = resolveRoundCount(phase.rounds(), roundsPerUnit, random);
            int startTick = Math.addExact(includedPhaseIndex == 0 ? 0 : previousLastTick, phase.startDelayTicks());
            for (int i = 0; i < count; i++) {
                int offset;
                if (count == 1) offset = 0;
                else if (phase.intervalTicks() != null) offset = Math.multiplyExact(i, phase.intervalTicks());
                else offset = (int) Math.round((double) phase.durationTicks() * i / (count - 1));
                int tick = Math.addExact(startTick, offset);
                rounds.add(new PlannedRound(globalIndex++, phase.id(), phaseIndex, i, tick));
            }
            previousLastTick = rounds.get(rounds.size() - 1).strikeOffsetTicks();
            includedPhaseIndex++;
        }
        if (rounds.isEmpty()) throw new IllegalArgumentException("射击模式没有可执行阶段");
        if (rounds.size() > limits.maxRoundsPerMission()) {
            throw new IllegalArgumentException("计划弹数 " + rounds.size() + " 超过 profile 上限 " + limits.maxRoundsPerMission());
        }
        int lastAcceptedOffset = Math.addExact(callTicks, rounds.get(rounds.size() - 1).strikeOffsetTicks());
        if (lastAcceptedOffset > limits.maxMissionDurationTicks()) {
            throw new IllegalArgumentException("计划末弹 Tick " + lastAcceptedOffset + " 超过任务时长上限 " + limits.maxMissionDurationTicks());
        }
        return new Plan(callTicks, rounds, lastAcceptedOffset);
    }

    private static int resolveRoundCount(RVP_FireSupportProfile.RoundRule rule, int roundsPerUnit, Random random) {
        if (rule.baseMultiplier() != null) return checkedCeil(roundsPerUnit * rule.baseMultiplier(), "阶段弹数溢出");
        if (rule.fixed() != null) return rule.fixed();
        return rule.randomMin() + random.nextInt(rule.randomMax() - rule.randomMin() + 1);
    }

    private static int checkedCeil(double value, String message) {
        if (!Double.isFinite(value) || value <= 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(message);
        return Math.max(1, (int) Math.ceil(value));
    }

    /** 一个完整模式的冻结计划。 */
    public record Plan(
            /** 从接受任务到进入打击阶段的时间，单位 Tick。 */ int callDurationTicks,
            /** 按生成顺序排列的不可变炮弹计划。 */ List<PlannedRound> rounds,
            /** 从任务接受到最后一发计划时间，单位 Tick。 */ int lastRoundFromAcceptanceTicks) {
        public Plan { rounds = List.copyOf(rounds); }
    }

    /** 单发炮弹在打击阶段内的计划位置。 */
    public record PlannedRound(
            /** 任务内从 0 开始的全局轮次。 */ int roundIndex,
            /** 所属阶段 ID。 */ String phaseId,
            /** 所属阶段顺序。 */ int phaseIndex,
            /** 阶段内从 0 开始的轮次。 */ int roundInPhase,
            /** 相对进入打击阶段的生成 Tick。 */ int strikeOffsetTicks) {}
}
