package org.ywzj.rvp.entity.gunner.ai;

/**
 * 地面 Gunner 的数值决策：游戏与无客户端仿真共用同一份巡逻、接敌和脱困算法。
 * 不查询世界、不写载具输入；障碍是否阻挡运动由调用方的物理环境决定。
 */
public final class RVP_GunnerGroundPolicy {
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
            /** 巡逻大转弯持续时间，刻。 */ int turnDuration) {}

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
        /** 是否正在换弹期战术倒车（滞回状态，避免进出倒车窗口时抖动）。 */ private boolean reversing;
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
    public static final class GroundEngagementState extends TacticalMoveState {}
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
        // stop 为 0，AI 会一路顶到目标身上。
        double stop = s.stopDistance();
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
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
        }
        if (in.reloading()) {
            // 换弹期战术倒车（2026-11-06 用户定版）：目标进入 1.2× 停车距离后倒车拉开，
            // 到 1.8× 退出（滞回）；倒车而非掉头，保持正面装甲对敌。未进入倒车窗口时原地保持。
            if (!state.reversing && distance < stop * 1.2) {
                state.reversing = true;
            } else if (state.reversing && distance >= stop * 1.8) {
                state.reversing = false;
            }
            if (state.reversing && frontFacing(yawDelta)) {
                // 倒车时本体履带/轮式转向符号会反转：交换左右，车头才会继续转向目标。
                return new Command(false, true, yawDelta > 8, yawDelta < -8, "RELOAD_REVERSE");
            }
            // 目标不在车头前方时先原地转向；禁止倒车，否则车尾朝目标反而会倒退贴近。
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "RELOAD_HOLD");
        }
        // 目标主动贴脸（<0.7×停车距离）：倒车恢复到停车距离，同样保持正面装甲对敌。
        if (state.reversing) {
            if (distance >= stop) {
                state.reversing = false;
            }
        } else if (distance < stop * 0.7) {
            state.reversing = true;
        }
        if (state.reversing && frontFacing(yawDelta)) {
            // 同上：倒车输入的左右方向与前进相反，交换后车头继续追踪目标。
            return new Command(false, true, yawDelta > 8, yawDelta < -8, "BACK_OFF");
        }
        if (state.reversing) {
            // 车头未对准目标：原地转向，等正面朝向目标后再倒车拉开。
            return new Command(false, false, yawDelta < -8, yawDelta > 8, "HOLD");
        }
        // 2026-11-06 用户定版：进入停车距离后纯停车（只做对敌转向），去掉原 55° 侧移——
        // 55° 侧向行驶会让坦克画弧绕向目标，看起来像"不停车、往脸上开"。
        return new Command(distance > stop * 1.2 && Math.abs(yawDelta) < 80, false,
                yawDelta < -8, yawDelta > 8, distance > stop * 1.2 ? "APPROACH" : "HOLD");
    }

    /** 原卡住判定；返回 null 表示不提交高优先级脱困候选。 */
    public static Command recover(Input in, Settings s, StuckRecoveryState state) {
        float yawDelta = 0;
        boolean desireMove = false;
        if (in.target() != null && (!in.launcher() || !in.combatAmmo())) {
            yawDelta = targetYawDelta(in);
            // 与接敌策略一致：停车距离对全部目标生效。
            double stop = s.stopDistance();
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
