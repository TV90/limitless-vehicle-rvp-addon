package org.ywzj.rvp.entity.gunner.ai;

/**
 * 地面 Gunner 的数值决策：游戏与无客户端仿真共用同一份巡逻、接敌和脱困算法。
 * 不查询世界、不写载具输入；障碍是否阻挡运动由调用方的物理环境决定。
 */
public final class RVP_GunnerGroundPolicy {
    /** 驻车带半宽，格：距随机驻车点超过该值才前进/倒车；小停车距离按比例缩小。 */
    private static final double STANDOFF_DEADBAND = 8.0D;
    /** 目标接近速度触发阈值，格/刻；0.12 约等于 2.4 格/秒。 */
    private static final double CLOSING_SPEED_TRIGGER = 0.12D;
    /** 单次换弹倒车最短持续时间，刻。 */
    private static final int RELOAD_REVERSE_TICK_MIN = 80;
    /** 单次换弹倒车最长持续时间，刻。 */
    private static final int RELOAD_REVERSE_TICK_MAX = 140;
    /** 对载具连续倒车的疲劳上限基准，刻；超过后强制一段前进变向。 */
    private static final int VEHICLE_BACKOFF_LIMIT_BASE = 45;
    /** 对载具连续倒车疲劳上限的随机幅度，刻。 */
    private static final int VEHICLE_BACKOFF_LIMIT_JITTER = 26;
    /** 对生物连续倒车的疲劳上限基准，刻；生物多为贴身逼近，更早打断倒车。 */
    private static final int CREATURE_BACKOFF_LIMIT_BASE = 28;
    /** 对生物连续倒车疲劳上限的随机幅度，刻。 */
    private static final int CREATURE_BACKOFF_LIMIT_JITTER = 18;
    /** 倒车疲劳后强制前进的最短时间，刻。 */
    private static final int REVERSE_BREAK_TICK_MIN = 25;
    /** 倒车疲劳后强制前进的最长时间，刻。 */
    private static final int REVERSE_BREAK_TICK_MAX = 45;

    private RVP_GunnerGroundPolicy() {}

    /** 随机源由调用方提供；仿真固定种子，游戏继续使用该 Gunner 原来的随机源。 */
    public interface RandomInput {
        /** 返回 [0,bound) 的整数。 */ int nextInt(int bound);
        /** 返回 [0,1) 的小数。 */ float nextFloat();
        /** 返回随机方向。 */ boolean nextBoolean();
    }

    /** 水平位置，单位格；null 目标表示当前无敌。 */
    public record Point(/** 世界 X。 */ double x, /** 世界 Z。 */ double z) {}

    /** 本刻只读观察，保留本体 lookAngle 的水平分量以维持重合目标时的原行为。 */
    public record Input(
            /** 运行刻号。 */ long tick,
            /** 载具位置。 */ Point position,
            /** Minecraft 航向，0 朝 +Z，正角向 -X。 */ float yaw,
            /** 本体朝向的 X 分量。 */ double lookX,
            /** 本体朝向的 Z 分量。 */ double lookZ,
            /** 目标位置，无目标时为 null。 */ Point target,
            /** 目标是否为玩家/生物等非载具活体；生物使用更近的停车距离且不做换弹倒车。 */
            boolean creatureTarget,
            /** 主战武器是否处于换弹/再装填窗口，决定换弹期战术倒车。 */ boolean reloading,
            /** 本车是否发射架。 */ boolean launcher,
            /** 本车是否仍有作战弹药。 */ boolean combatAmmo) {}

    /** 参数来自行为自己的 Profile 视图；仿真场景显式保存同名数值。 */
    public record Settings(
            /** 接敌停车距离，格。 */ double stopDistance,
            /** 停车观察时间，刻。 */ int holdTick,
            /** 侧移最短时间，刻。 */ int evadeMin,
            /** 侧移最长时间，刻。 */ int evadeMax,
            /** 侧移偏航角，度。 */ float evadeYaw,
            /** 卡住检查间隔，刻。 */ int stuckCheckTick,
            /** 检查期内移动小于此距离视为卡住，格。 */ double stuckDistance,
            /** 倒车脱困时间，刻。 */ int recoveryTick,
            /** 巡逻大转弯最短间隔，刻。 */ int turnIntervalMin,
            /** 巡逻大转弯最长间隔，刻。 */ int turnIntervalMax,
            /** 巡逻大转弯最小角，度。 */ float turnAngleMin,
            /** 巡逻大转弯最大角，度。 */ float turnAngleMax,
            /** 巡逻大转弯持续时间，刻。 */ int turnDuration,
            /** 驻车距离随机抖动比例，0 表示固定停车距离。 */ double standoffJitter,
            /** 换弹倒车距离相对停车距离的倍率。 */ double reloadReverseRatio,
            /** 停车期短机动触发概率。 */ double repositionChance,
            /** 短机动最短间隔，刻。 */ int repositionCooldownTick,
            /** 面对玩家/生物等非载具活体时的接敌停车距离，格。 */ double creatureStopDistance) {}

    /** 数值命令；保留同时出现左右输入的可能性，与现有动作层一致。 */
    public record Command(
            /** 前进。 */ boolean forward,
            /** 后退。 */ boolean backward,
            /** 左转。 */ boolean left,
            /** 右转。 */ boolean right,
            /** 便于诊断的决策阶段。 */ String phase) {}

    /** 地面接敌和发射架共用的战术计时，每个行为实例各持有一份。 */
    public static class TacticalMoveState {
        /** 停车剩余刻。 */ private int holdTicks;
        /** 侧移剩余刻；与停车计时同时递减，保留原有时序。 */ private int evadeTicks;
        /** 本轮侧移偏角。 */ private float evadeYawBias;
        /** 是否正在换弹期战术倒车（滞回状态，避免进出倒车窗口时抖动）。 */ boolean reversing;
        /** 行为每刻规划前调用一次，即使本刻没有目标也推进。 */
        public final void tick() {
            if (holdTicks > 0) holdTicks--;
            if (evadeTicks > 0) evadeTicks--;
        }
        /** 设置本轮战术计时。 */
        public final void start(int hold, int evade, float bias) {
            holdTicks = Math.max(0, hold); evadeTicks = Math.max(0, evade); evadeYawBias = bias;
        }
        /** 失去目标或离开交战距离后清空。 */
        public final void clearTacticalEvade() { holdTicks = 0; evadeTicks = 0; evadeYawBias = 0; reversing = false; }
    }

    /** 地面接敌行为独立状态。 */
    public static final class GroundEngagementState extends TacticalMoveState {
        /** 本轮交战的随机驻车距离，格；小于 0 表示尚未抽取。 */
        private double standoffDistance = -1.0;
        /** 本轮换弹倒车目标距离，格。 */
        private double reloadRetreatDistance = -1.0;
        /** 短机动剩余刻。 */
        private int maneuverTicks;
        /** 短机动类型：0 前进、1 倒车、2 原地调整。 */
        private int maneuverKind;
        /** 短机动附加航向偏角，度。 */
        private float maneuverYawBias;
        /** 下次短机动最早可触发刻。 */
        private int maneuverCooldownTicks;
        /** 本轮换弹倒车剩余刻，防止目标持续贴近时无限倒车。 */
        private int reloadReverseTicks;
        /** 本轮换弹倒车额度是否已用完；用完则不再重新触发，避免无限倒车。 */
        private boolean reloadReverseSpent;
        /** 换弹完成后的原地稳定刻，先稳住车体再决定下一步。 */
        private int settleHoldTicks;
        /** 连续 BACK_OFF 已持续的刻数。 */
        private int backOffStreak;
        /** 本次连续倒车的疲劳上限，刻；开始时随机一次。 */
        private int backOffLimit = VEHICLE_BACKOFF_LIMIT_BASE;
        /** 上一次发出 BACK_OFF 的刻号；用于判定连续倒车是否中断。 */
        private long lastBackOffTick = Long.MIN_VALUE;
        /** 倒车疲劳后强制前进的剩余刻。 */
        private int reverseBreakTicks;
        /** 强制前进期间的附加航向偏角，度。 */
        private float reverseBreakYawBias;
        /** 上一 tick 到目标的水平距离，用于估算目标接近速度。 */
        private double lastDistance = -1.0;
        /** 上一 tick 的运行刻号。 */
        private long lastTick = Long.MIN_VALUE;
        /** 上一 tick 是否处于换弹。 */
        private boolean lastReloading;
        /** 上一目标类型：0 载具/弹药，1 玩家/生物；切换时重抽驻车点。 */
        private int lastTargetKind = -1;

        /** 失去目标时清空交战机动状态，避免把上一目标的速度样本带过来。 */
        public final void clearGroundTactics() {
            standoffDistance = -1.0;
            reloadRetreatDistance = -1.0;
            maneuverTicks = 0;
            maneuverKind = 0;
            maneuverYawBias = 0;
            maneuverCooldownTicks = 0;
            reloadReverseTicks = 0;
            reloadReverseSpent = false;
            settleHoldTicks = 0;
            backOffStreak = 0;
            backOffLimit = VEHICLE_BACKOFF_LIMIT_BASE;
            lastBackOffTick = Long.MIN_VALUE;
            reverseBreakTicks = 0;
            reverseBreakYawBias = 0;
            lastDistance = -1.0;
            lastTick = Long.MIN_VALUE;
            lastReloading = false;
            lastTargetKind = -1;
        }
    }
    /** 发射架行为独立状态。 */
    public static final class LauncherPositioningState extends TacticalMoveState {
        /** 无目标且正在装填时使用的巡逻状态。 */ private final GroundPatrolState patrol = new GroundPatrolState();
    }
    /** 地面巡逻状态。 */
    public static final class GroundPatrolState {
        /** 大转弯冷却剩余刻。 */ private int turnCooldownTicks;
        /** 大转弯剩余刻。 */ private int turningTicks;
        /** 本轮转弯目标航向。 */ private float targetYaw;
    }
    /** 卡住检测与倒车恢复状态。 */
    public static final class StuckRecoveryState {
        /** 上次检测 X；初值 0 与原行为一致。 */ private double lastCheckX;
        /** 上次检测 Z。 */ private double lastCheckZ;
        /** 倒车剩余刻。 */ private int recoveryTicks;
        /** 检测冷却剩余刻。 */ private int cooldownTicks;
        /** 行为每刻规划前调用一次。 */
        public void tick() {
            if (recoveryTicks > 0) recoveryTicks--;
            if (cooldownTicks > 0) cooldownTicks--;
        }
    }

    /** 原地面接敌算法，随机调用顺序与原实现一致。 */
    public static Command engage(Input in, Settings settings, GroundEngagementState state, RandomInput random) {
        if (in.target() == null) {
            // 调用状态清理，保持无敌时退出战术走位的原语义。
            state.clearTacticalEvade();
            state.clearGroundTactics();
            return new Command(false, false, false, false, "NO_TARGET");
        }
        return tactical(in, settings, state, random, false);
    }

    /** 原发射架策略：有弹停车，装填时巡逻或战术走位。 */
    public static Command launcher(Input in, Settings settings, LauncherPositioningState state, RandomInput random) {
        if (in.combatAmmo()) {
            // 调用状态清理，装弹后立即停止走位。
            state.clearTacticalEvade();
            return new Command(false, false, false, false, "LAUNCHER_PARK");
        }
        if (in.target() == null) return patrol(in, settings, state.patrol, random);
        return tactical(in, settings, state, random, true);
    }

    /** 保留停车距离的 1.8 倍滞回、原侧移时间和各转向门槛。 */
    private static Command tactical(Input in, Settings s, TacticalMoveState state, RandomInput random, boolean launcher) {
        double distance = distance(in);
        float yawDelta = targetYawDelta(in);
        // 2026-11-06 用户定版：停车距离对全部目标生效——原先只对载具生效，追踪步兵/来袭弹时
        // stop 为 0，AI 会一路顶到目标身上。2026-11-08：玩家/生物等活体改用更近的独立阈值，
        // 避免面对人海时在远处反复倒车；来袭弹等非活体维持载具距离。
        double stop = !launcher && in.creatureTarget() ? s.creatureStopDistance() : s.stopDistance();
        boolean tacticalTarget = stop > 0;
        if (launcher) {
            // 发射架保留原策略：有弹停车由 launcher() 处理，无弹/装填时走原停车与侧移节奏。
            if (tacticalTarget && distance <= stop) {
                if (state.holdTicks <= 0 && state.evadeTicks <= 0) {
                    state.start(s.holdTick(),
                            s.evadeMin() + random.nextInt(Math.max(1, s.evadeMax() - s.evadeMin() + 1)),
                            s.evadeYaw());
                }
            } else if (!tacticalTarget || distance > stop * 1.8) {
                state.clearTacticalEvade();
            }
            if (state.holdTicks > 0) return new Command(false, false, false, false, "HOLD");
            if (state.evadeTicks > 0) return evade(in, yawDelta, state.evadeYawBias);
            return new Command(distance > stop * 1.2 && Math.abs(yawDelta) < 80, false,
                    yawDelta < -8, yawDelta > 8, "APPROACH");
        }
        if (!tacticalTarget) {
            // 未配置停车距离：不接受接敌移动，避免退回"一路顶到目标"的旧行为。
            state.reversing = false;
            if (state instanceof GroundEngagementState ground) {
                ground.reverseBreakTicks = 0;
                ground.backOffStreak = 0;
                ground.lastBackOffTick = Long.MIN_VALUE;
            }
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
        }
        GroundEngagementState ground = (GroundEngagementState) state;
        double deadband = standoffDeadband(stop);
        // 目标接近速度：用上一 tick 的水平距离差估算，作为换弹期提前倒车的触发量。
        double dt = ground.lastTick == Long.MIN_VALUE ? 0.0D
                : Math.max(1L, in.tick() - ground.lastTick);
        double closingSpeed = ground.lastDistance >= 0.0D && dt > 0.0D
                ? (ground.lastDistance - distance) / dt : 0.0D;
        ground.lastDistance = distance;
        ground.lastTick = in.tick();
        int targetKind = in.creatureTarget() ? 1 : 0;
        if (ground.lastTargetKind != targetKind) {
            // 载具/生物切换时停车距离基准变化，必须重抽驻车点与倒车距离，
            // 否则会继续拿 60 格的旧驻车点面对贴身生物，表现为一直倒车。
            ground.lastTargetKind = targetKind;
            ground.standoffDistance = -1.0;
            ground.reloadRetreatDistance = -1.0;
            ground.maneuverTicks = 0;
            ground.settleHoldTicks = 0;
            ground.backOffStreak = 0;
            ground.lastBackOffTick = Long.MIN_VALUE;
            ground.reverseBreakTicks = 0;
            state.reversing = false;
        }
        if (ground.standoffDistance <= 0.0D) {
            ground.standoffDistance = rollStandoff(stop, s, random);
            ground.reloadRetreatDistance = rollReloadRetreat(s, ground.standoffDistance, stop, random);
            // 首次接敌先稳定打一轮，短机动至少等一个冷却周期再触发。
            ground.maneuverCooldownTicks = Math.max(40, s.repositionCooldownTick());
        }
        if (ground.maneuverCooldownTicks > 0) ground.maneuverCooldownTicks--;
        if (ground.reloadReverseTicks > 0) ground.reloadReverseTicks--;
        if (ground.settleHoldTicks > 0) ground.settleHoldTicks--;

        if (ground.lastReloading && !in.reloading()) {
            // 换弹完成：重新抽驻车点，先稳车再决定前进/倒车/小角度调整；
            // 起射延迟由武器动作层负责，本状态只负责让车体不继续卡在倒车极限。
            ground.standoffDistance = rollStandoff(stop, s, random);
            ground.reloadRetreatDistance = rollReloadRetreat(s, ground.standoffDistance, stop, random);
            ground.maneuverTicks = 0;
            ground.reloadReverseTicks = 0;
            ground.reloadReverseSpent = false;
            ground.settleHoldTicks = Math.abs(distance - ground.standoffDistance) <= deadband * 1.5D
                    ? 12 + random.nextInt(19) : 0;
            ground.maneuverCooldownTicks = Math.max(ground.maneuverCooldownTicks, 40 + random.nextInt(61));
            state.reversing = false;
        } else if (!ground.lastReloading && in.reloading()) {
            // 新一次装填开始：重新给本轮一次倒车额度。
            ground.reloadReverseTicks = 0;
            ground.reloadReverseSpent = false;
        }
        ground.lastReloading = in.reloading();

        if (in.reloading()) {
            // 装填期强制结束倒车疲劳机动，避免装填结束后恢复一段过期的前进指令。
            ground.reverseBreakTicks = 0;
            ground.backOffStreak = 0;
            ground.lastBackOffTick = Long.MIN_VALUE;
            if (in.creatureTarget()) {
                // 生物目标不做装填期倒车：边装填边原地对敌转向，避免被人群顶着一直退。
                return new Command(false, false, yawDelta < -8, yawDelta > 8, "RELOAD_HOLD");
            }
            // 换弹期：目标进入随机倒车距离后倒车拉开；目标快速接近时提前倒车。
            boolean needReverse = distance < ground.reloadRetreatDistance - deadband
                    || (closingSpeed > CLOSING_SPEED_TRIGGER
                    && distance < ground.reloadRetreatDistance + deadband);
            if (!state.reversing && needReverse && frontFacing(yawDelta)
                    && !ground.reloadReverseSpent && ground.reloadReverseTicks <= 0) {
                state.reversing = true;
                ground.reloadReverseTicks = RELOAD_REVERSE_TICK_MIN
                        + random.nextInt(RELOAD_REVERSE_TICK_MAX - RELOAD_REVERSE_TICK_MIN + 1);
            }
            if (state.reversing && (distance >= ground.reloadRetreatDistance + deadband
                    || !frontFacing(yawDelta) || ground.reloadReverseTicks <= 0)) {
                state.reversing = false;
                if (ground.reloadReverseTicks <= 0) {
                    ground.reloadReverseSpent = true;
                }
            }
            if (state.reversing) {
                // 倒车时本体履带/轮式转向符号会反转：交换左右，车头才会继续转向目标。
                return new Command(false, true, yawDelta > 8, yawDelta < -8, "RELOAD_REVERSE");
            }
            // 目标不在车头前方时先原地转向；禁止倒车，否则车尾朝目标反而会倒退贴近。
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "RELOAD_HOLD");
        }

        if (ground.reverseBreakTicks > 0) {
            ground.reverseBreakTicks--;
            return reverseBreakCommand(ground, yawDelta);
        }
        if (ground.maneuverTicks > 0) {
            ground.maneuverTicks--;
            return maneuverCommand(ground, yawDelta, distance, "REPOSITION");
        }
        if (ground.settleHoldTicks > 0) {
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "SETTLE_HOLD");
        }
        // 停车期按间隔概率触发一次短机动，避免长时间钉在同一个停车点。
        if (distance < ground.standoffDistance * 1.8D && distance > ground.standoffDistance * 0.35D
                && ground.maneuverCooldownTicks <= 0 && frontFacing(yawDelta)) {
            if (random.nextFloat() < (float) s.repositionChance()) {
                startManeuver(ground, distance, yawDelta, random);
                ground.maneuverCooldownTicks = Math.max(0, s.repositionCooldownTick())
                        + random.nextInt(Math.max(1, s.repositionCooldownTick() / 2 + 1));
                return maneuverCommand(ground, yawDelta, distance, "REPOSITION");
            }
            ground.maneuverCooldownTicks = 20 + random.nextInt(41);
        }
        // 驻车带调节：带内不动，超出上沿前进、低于下沿倒车；保留正面装甲约束。
        if (state.reversing) {
            if (distance >= ground.standoffDistance) state.reversing = false;
        } else if (distance < ground.standoffDistance - deadband) {
            state.reversing = true;
        }
        if (state.reversing && frontFacing(yawDelta)) {
            // 连续倒车超过本轮疲劳上限后，强制一段带偏航的前进机动，避免一直倒车导致动作僵硬。
            if (ground.lastBackOffTick == in.tick() - 1L) {
                ground.backOffStreak++;
            } else {
                ground.backOffStreak = 0;
                ground.backOffLimit = (in.creatureTarget() ? CREATURE_BACKOFF_LIMIT_BASE : VEHICLE_BACKOFF_LIMIT_BASE)
                        + random.nextInt(in.creatureTarget()
                                ? CREATURE_BACKOFF_LIMIT_JITTER : VEHICLE_BACKOFF_LIMIT_JITTER);
            }
            ground.lastBackOffTick = in.tick();
            if (ground.backOffStreak >= ground.backOffLimit) {
                startReverseBreak(ground, random);
                return reverseBreakCommand(ground, yawDelta);
            }
            return new Command(false, true, yawDelta > 8, yawDelta < -8, "BACK_OFF");
        }
        if (state.reversing) {
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
        }
        boolean approach = distance > ground.standoffDistance + deadband
                && Math.abs(yawDelta) < 80.0F;
        return new Command(approach, false, yawDelta < -8, yawDelta > 8,
                approach ? "APPROACH" : "HOLD");
    }

    /** 原卡住判定；返回 null 表示不提交高优先级脱困候选。 */
    public static Command recover(Input in, Settings s, StuckRecoveryState state) {
        float yawDelta = 0;
        boolean desireMove = false;
        if (in.target() != null && (!in.launcher() || !in.combatAmmo())) {
            yawDelta = targetYawDelta(in);
            // 与接敌策略一致：停车距离对全部目标生效；活体目标使用更近的生物阈值。
            double stop = (!in.launcher() && in.creatureTarget()) ? s.creatureStopDistance() : s.stopDistance();
            desireMove = distance(in) > stop * 1.6 && Math.abs(yawDelta) < 25;
        }
        if (in.target() != null && in.tick() % s.stuckCheckTick() == 0 && state.cooldownTicks <= 0) {
            double dx = in.position().x() - state.lastCheckX, dz = in.position().z() - state.lastCheckZ;
            if (desireMove && dx * dx + dz * dz < s.stuckDistance() * s.stuckDistance()) {
                state.recoveryTicks = s.recoveryTick();
                state.cooldownTicks = s.recoveryTick() * 2 + s.stuckCheckTick();
            }
            state.lastCheckX = in.position().x(); state.lastCheckZ = in.position().z();
        }
        if (state.recoveryTicks <= 0) return null;
        return new Command(false, true, yawDelta <= 0, yawDelta > 0, "RECOVERY");
    }

    /** 驻车带半宽：大车用固定 8 格，小车按停车距离 12% 缩放，避免小距离车永远不动。 */
    private static double standoffDeadband(double stopDistance) {
        return Math.max(2.0D, Math.min(STANDOFF_DEADBAND, stopDistance * 0.12D));
    }

    /** 抽取本轮驻车距离：基准停车距离乘以 [1-jitter, 1+jitter]，避免每轮固定在同一点。 */
    private static double rollStandoff(double stopDistance, Settings s, RandomInput random) {
        double jitter = Math.max(0.0D, Math.min(0.6D, s.standoffJitter()));
        double factor = 1.0D + (random.nextFloat() * 2.0F - 1.0F) * (float) jitter;
        return stopDistance * Math.max(0.65D, Math.min(1.35D, factor));
    }

    /** 抽取本轮换弹倒车距离：必须比驻车点更远，并带少量随机抖动。 */
    private static double rollReloadRetreat(Settings s, double standoff, double stopDistance, RandomInput random) {
        double ratio = Math.max(1.0D, Math.min(2.0D, s.reloadReverseRatio()));
        double factor = ratio + (random.nextFloat() * 2.0F - 1.0F) * 0.08D;
        return Math.max(standoff + standoffDeadband(stopDistance), stopDistance * factor);
    }

    /** 开始一次倒车疲劳后的前进机动：带随机偏航，先离开倒车节奏再重新判断距离。 */
    private static void startReverseBreak(GroundEngagementState state, RandomInput random) {
        state.reverseBreakTicks = REVERSE_BREAK_TICK_MIN
                + random.nextInt(REVERSE_BREAK_TICK_MAX - REVERSE_BREAK_TICK_MIN + 1);
        float magnitude = 12.0F + random.nextFloat() * 12.0F;
        state.reverseBreakYawBias = random.nextBoolean() ? magnitude : -magnitude;
        state.backOffStreak = 0;
        state.lastBackOffTick = Long.MIN_VALUE;
        state.reversing = false;
        state.maneuverTicks = 0;
        state.maneuverCooldownTicks = Math.max(state.maneuverCooldownTicks, 30 + random.nextInt(31));
    }

    /** 倒车疲劳机动的命令：前进并朝偏航方向转向。 */
    private static Command reverseBreakCommand(GroundEngagementState state, float yawDelta) {
        float desiredDelta = wrap(yawDelta + state.reverseBreakYawBias);
        return new Command(true, false, desiredDelta < -8, desiredDelta > 8, "REVERSE_BREAK");
    }

    /** 开始一次短机动：按距离决定前进/倒车/原地调整，并写入随机偏航角。 */
    private static void startManeuver(GroundEngagementState state, double distance, float yawDelta,
                                      RandomInput random) {
        if (!frontFacing(yawDelta)) {
            state.maneuverKind = 2;
            state.maneuverTicks = 18 + random.nextInt(13);
        } else if (distance > state.standoffDistance + 12.0D) {
            state.maneuverKind = 0;
            state.maneuverTicks = 20 + random.nextInt(21);
        } else if (distance < state.standoffDistance - 12.0D) {
            state.maneuverKind = 1;
            state.maneuverTicks = 20 + random.nextInt(21);
        } else {
            state.maneuverKind = random.nextInt(3);
            state.maneuverTicks = state.maneuverKind == 2 ? 18 + random.nextInt(13) : 20 + random.nextInt(21);
        }
        float magnitude = 10.0F + random.nextFloat() * 10.0F;
        state.maneuverYawBias = random.nextBoolean() ? magnitude : -magnitude;
        state.reversing = false;
    }

    /** 短机动命令：前进/倒车都带一个偏离目标的航向偏角，原地调整只转向。 */
    private static Command maneuverCommand(GroundEngagementState state, float yawDelta,
                                           double distance, String phase) {
        float desiredDelta = wrap(yawDelta + state.maneuverYawBias);
        if (state.maneuverKind == 0) {
            if (distance < state.standoffDistance * 0.55D) {
                state.maneuverTicks = 0;
                return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
            }
            return new Command(true, false, desiredDelta < -8, desiredDelta > 8, phase);
        }
        if (state.maneuverKind == 1) {
            if (!frontFacing(yawDelta)) {
                state.maneuverTicks = 0;
                return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
            }
            return new Command(false, true, desiredDelta > 8, desiredDelta < -8, phase + "_BACK");
        }
        return new Command(false, false, desiredDelta < -8, desiredDelta > 8, phase + "_PIVOT");
    }

    /** 原侧移算法；同时保留对敌朝向修正，左右信号可能同时为 true。 */
    private static Command evade(Input in, float targetYawDelta, float bias) {
        double dx = in.position().x() - in.target().x(), dz = in.position().z() - in.target().z();
        if (dx * dx + dz * dz < 1.0E-4) { dx = in.lookX(); dz = in.lookZ(); }
        float delta = wrap(yawTo(dx, dz) + bias - in.yaw());
        boolean right = delta > 8, left = delta < -8;
        if (Math.abs(targetYawDelta) > 60) {
            if (targetYawDelta > 0) right = true;
            else left = true;
        }
        return new Command(Math.abs(delta) < 100, Math.abs(delta) >= 100, left, right, "EVADE");
    }

    /** 原无敌巡逻算法；大转弯期间不前进。 */
    public static Command patrol(Input in, Settings s, GroundPatrolState state, RandomInput random) {
        int cooldown = state.turnCooldownTicks, turning = state.turningTicks;
        if (turning > 0) {
            state.turningTicks = turning - 1;
            float delta = wrap(state.targetYaw - in.yaw());
            if (Math.abs(delta) <= 6) state.turningTicks = 0;
            return new Command(false, false, delta < -6, delta > 6, "PATROL_TURN");
        }
        if (cooldown > 0) state.turnCooldownTicks = cooldown - 1;
        else {
            state.turnCooldownTicks = s.turnIntervalMin() + random.nextInt(Math.max(1, s.turnIntervalMax() - s.turnIntervalMin() + 1));
            float angle = s.turnAngleMin() + random.nextFloat() * Math.max(0, s.turnAngleMax() - s.turnAngleMin());
            if (random.nextBoolean()) angle = -angle;
            state.targetYaw = in.yaw() + angle; state.turningTicks = s.turnDuration();
            return new Command(false, false, false, false, "PATROL_TURN_START");
        }
        return new Command(true, false, (in.tick() / 40) % 2 == 0, false, "PATROL");
    }

    /** 与本体 VectorUtil.vecToRot 的水平航向公式一致，保留 float 舍入。 */
    private static float yawTo(double x, double z) { return (float) Math.toDegrees(-Math.atan2(x, z)); }
    /** 与 Mth.wrapDegrees(float) 一致。 */
    private static float wrap(float angle) {
        float wrapped = angle % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
    /** 目标相对车头的水平偏角。 */
    private static float targetYawDelta(Input in) {
        return wrap(yawTo(in.target().x() - in.position().x(), in.target().z() - in.position().z()) - in.yaw());
    }
    /** 目标是否位于车头前方 80° 内；与前进接敌门槛一致，倒车只在此范围内执行。 */
    private static boolean frontFacing(float yawDelta) {
        return Math.abs(yawDelta) < 80.0f;
    }
    /** 水平距离，不混入高度差。 */
    private static double distance(Input in) {
        double dx = in.target().x() - in.position().x(), dz = in.target().z() - in.position().z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
