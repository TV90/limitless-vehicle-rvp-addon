package org.ywzj.rvp.firesupport.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;

/** 把数据驱动阶段解释成确定性的不可变任务时间表，不识别任何模式名称。 */
public final class RVP_FireSupportSchedulePlanner {
    private RVP_FireSupportSchedulePlanner() {}

    /**
     * 根据弹药方案基数、武器权重、模式阶段和权威种子生成相对 Tick 时间表。
     *
     * @param baseCallDurationTicks profile 呼叫基准时长，单位 Tick
     * @param munition 已校验的弹药方案，提供总弹数基数和权重轮转
     * @param mode 已校验的数据驱动模式
     * @param authoritativeSeed 服务端权威随机种子
     * @param limits profile 任务上限
     */
    public static Plan plan(int baseCallDurationTicks, RVP_FireSupportProfile.Munition munition,
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
            if (phase.registrationPhase() && !munition.registrationPhaseEnabled()) continue;
            int count = resolveRoundCount(phase.rounds(), munition.roundsPerUnit(), random);
            int startTick = Math.addExact(includedPhaseIndex == 0 ? 0 : previousLastTick, phase.startDelayTicks());
            for (int i = 0; i < count; i++) {
                int offset;
                if (count == 1) offset = 0;
                else if (phase.intervalTicks() != null) offset = Math.multiplyExact(i, phase.intervalTicks());
                else offset = (int) Math.round((double) phase.durationTicks() * i / (count - 1));
                int tick = Math.addExact(startTick, offset);
                // 每个阶段按阶段内轮次重新开始权重轮转，避免随机试射弹数改变主射首发武器。
                int munitionWeaponIndex = resolveMunitionWeaponIndex(munition.weapons(), i);
                rounds.add(new PlannedRound(globalIndex++, phase.id(), phaseIndex, i,
                        munitionWeaponIndex, tick));
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

    /** 按声明顺序把阶段内轮次映射到累计整数权重区间。 */
    private static int resolveMunitionWeaponIndex(List<RVP_FireSupportProfile.MunitionWeapon> weapons,
                                                   int roundInPhase) {
        int totalWeight = 0;
        for (RVP_FireSupportProfile.MunitionWeapon weapon : weapons) {
            if (weapon.weight() <= 0) throw new IllegalArgumentException("弹药方案武器权重必须为正");
            totalWeight = Math.addExact(totalWeight, weapon.weight());
        }
        if (totalWeight <= 0) throw new IllegalArgumentException("弹药方案必须包含武器");
        int slot = Math.floorMod(roundInPhase, totalWeight);
        int cumulative = 0;
        for (int index = 0; index < weapons.size(); index++) {
            cumulative = Math.addExact(cumulative, weapons.get(index).weight());
            if (slot < cumulative) return index;
        }
        throw new IllegalStateException("无法解析弹药方案权重轮转");
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
            /** 本发使用的弹药方案武器成员下标。 */ int munitionWeaponIndex,
            /** 相对进入打击阶段的生成 Tick。 */ int strikeOffsetTicks) {}
}
