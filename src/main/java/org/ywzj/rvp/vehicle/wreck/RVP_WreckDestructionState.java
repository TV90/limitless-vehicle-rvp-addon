package org.ywzj.rvp.vehicle.wreck;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 服务端抽签后固定的击毁时间表；客户端只消费快照，不重新抽签或从观察时刻重新计时。
 *
 * @param mode 三种互斥结果
 * @param startedAt 服务端击毁的世界游戏 tick（不使用可被 /time set 修改的日历时间）
 * @param durationTicks 殉燃总时长，含增长和收缩；单位 tick，零表示关闭
 * @param launchDelayTicks 从击毁到飞头的 tick 数；只殉燃时为 -1
 * @param smokeDelayTicks 殉燃结束后的长程烟等待，单位 tick，范围 30～60
 * @param partsLaunched 是否已经消费过本体飞头调用，持久化防止重复生成
 */
public record RVP_WreckDestructionState(Mode mode, long startedAt, long durationTicks,
                                       long launchDelayTicks, int smokeDelayTicks, boolean partsLaunched) {
    /** 殉燃总时长占 common 残骸寿命的百分比；双端共用唯一基准。 */
    public static final double WRECK_LIFETIME_PERCENT = 40.0D;
    /** 实体 ForgeData 下独立保存的键，不改本体的 Destroyed / Detached 数据。 */
    public static final String NBT_KEY = "ywzj_rvp:WreckDestruction";

    /** 每车只抽一次的互斥击毁结果；名称同时用作当前 NBT schema 的枚举值。 */
    public enum Mode {
        /** 30%：立即飞出所有本体可脱落部件，不播放殉燃。 */
        IMMEDIATE,
        /** 30%：只殉燃，始终不调用本体飞头生成器。 */
        BURN_ONLY,
        /** 40%：先殉燃，到总时长的 40%～80% 时飞头。 */
        DELAYED
    }

    /** 校验内部状态与网络/NBT 的当前 schema，拒绝矛盾的时间表。 */
    public RVP_WreckDestructionState {
        if (mode == null || durationTicks < 0 || smokeDelayTicks < 30 || smokeDelayTicks > 60
                || (mode == Mode.IMMEDIATE && (durationTicks != 0 || launchDelayTicks != 0))
                || (mode == Mode.BURN_ONLY && (launchDelayTicks != -1 || partsLaunched))
                || (mode == Mode.DELAYED && (durationTicks < 2
                    || launchDelayTicks < minimumLaunchDelay(durationTicks)
                    || launchDelayTicks > maximumLaunchDelay(durationTicks)))) {
            throw new IllegalArgumentException("Invalid wreck destruction timeline");
        }
    }

    /** 将残骸秒数与百分比转换为 tick；同样供客户端无伤害预览使用。 */
    public static long cookoffDurationTicks(int wreckLifetimeSeconds, double percent) {
        if (wreckLifetimeSeconds <= 0 || !Double.isFinite(percent) || percent <= 0) return 0;
        return Math.max(1L, Math.round(wreckLifetimeSeconds * 20.0D * percent / 100.0D));
    }

    /** 输入均匀整数 0～99，得到严格的 30 / 30 / 40 分段。 */
    public static Mode selectMode(int roll) {
        if (roll < 0 || roll >= 100) throw new IllegalArgumentException("roll must be in [0, 100)");
        return roll < 30 ? Mode.IMMEDIATE : roll < 60 ? Mode.BURN_ONLY : Mode.DELAYED;
    }

    /** 延迟下界向上取整，保证不会早于总时长的 40%。 */
    public static long minimumLaunchDelay(long duration) {
        return (duration * 2 + 4) / 5;
    }

    /** 延迟上界向下取整，保证不会晚于总时长的 80%。 */
    public static long maximumLaunchDelay(long duration) {
        return duration * 4 / 5;
    }

    /** 随机整数已由服务端限制在闭区间长度内，映射到 40%～80% 的可用 tick。 */
    public static long launchDelay(long duration, long sample) {
        // 调用本类整数边界规则，避免浮点舍入将短时长推出目标区间。
        long min = minimumLaunchDelay(duration);
        long max = maximumLaunchDelay(duration);
        if (duration < 2 || sample < 0 || sample > max - min) {
            throw new IllegalArgumentException("Invalid delayed launch sample");
        }
        return min + sample;
    }

    /** 服务端使用：只在尚未飞头且已经到达预定时刻时允许一次调用。 */
    public boolean isLaunchDue(long now) {
        return mode != Mode.BURN_ONLY && !partsLaunched && now - startedAt >= launchDelayTicks;
    }

    /** 客户端使用：已过期残骸及立即飞头分支都不能建立新的喷燃实例。 */
    public boolean isBurning(long now) {
        return mode != Mode.IMMEDIATE && durationTicks > 0 && now >= startedAt
                && now - startedAt < durationTicks;
    }

    /** 普通烟独立于殉燃；立即飞头不受等待门限制，燃烧分支在结束后等待 30～60 tick。 */
    public boolean canEmitLongSmoke(long now) {
        return mode == Mode.IMMEDIATE || durationTicks == 0 || now - startedAt >= durationTicks + smokeDelayTicks;
    }

    /** 在进入本体生成器前消费调用，防止事件重入、重复调用或重新读档再次飞头。 */
    public RVP_WreckDestructionState markPartsLaunched() {
        return new RVP_WreckDestructionState(mode, startedAt, durationTicks, launchDelayTicks, smokeDelayTicks, true);
    }

    /** 写入当前 schema；同时用于实体持久化和 S2C 编码，不包含任何资源配置。 */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", mode.name());
        tag.putLong("StartedAt", startedAt);
        tag.putLong("DurationTicks", durationTicks);
        tag.putLong("LaunchDelayTicks", launchDelayTicks);
        tag.putInt("SmokeDelayTicks", smokeDelayTicks);
        tag.putBoolean("PartsLaunched", partsLaunched);
        return tag;
    }

    /** 只读取完整的当前 schema；缺失/损坏时不猜测时长，也不恢复随机抽签。 */
    public static RVP_WreckDestructionState fromTag(CompoundTag tag) {
        if (tag == null || !tag.contains("Mode", Tag.TAG_STRING) || !tag.contains("StartedAt", Tag.TAG_LONG)
                || !tag.contains("DurationTicks", Tag.TAG_LONG) || !tag.contains("LaunchDelayTicks", Tag.TAG_LONG)
                || !tag.contains("SmokeDelayTicks", Tag.TAG_INT) || !tag.contains("PartsLaunched", Tag.TAG_BYTE)) {
            return null;
        }
        try {
            return new RVP_WreckDestructionState(Mode.valueOf(tag.getString("Mode")), tag.getLong("StartedAt"),
                    tag.getLong("DurationTicks"), tag.getLong("LaunchDelayTicks"), tag.getInt("SmokeDelayTicks"),
                    tag.getBoolean("PartsLaunched"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
