package org.ywzj.rvp.entity.gunner.simulation;

import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerGroundPolicy;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerGroundPolicy.*;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerTargetScore;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent.Channel;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent.Kind;
import org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerIntentArbiter;
import org.ywzj.rvp.entity.gunner.flight.RVP_GunnerFlightPolicy;
import org.ywzj.rvp.flight.RVP_FixedWingFlightController;
import org.ywzj.rvp.flight.RVP_FlightTuning;
import org.ywzj.rvp.flight.RVP_FlightTypes;
import org.ywzj.rvp.flight.RVP_FlightTypes.Vector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 测试侧的逐刻驱动器，无须启动 Minecraft 客户端、专用服务端或 Forge 世界。
 * 复用生产决策、同层目标评分和意图仲裁；感知、碰撞与武器尝试由测试夹具提供。
 */
public final class RVP_GunnerSimulation {
    /** Minecraft 标准每秒刻数。 */ public static final int TICK_RATE = 20;
    /** 显式列出接入范围，随记录一起交给回放者。 */
    private static final List<String> COVERAGE = List.of(
            "地面：RVP_GunnerGroundPolicy（与游戏共用的巡逻、接敌、停车侧移、发射架与倒车脱困）",
            "意图：RVP_GunnerIntentArbiter；目标：RVP_GunnerTargetScore（同层评分）",
            "固定翼：RVP_GunnerFlightPolicy + RVP_FixedWingFlightController + RVP_FlightExecutor（新公共飞控分支，游戏默认关闭）");
    /** 限制也写进数据，避免将测试夹具等同于完整游戏。 */
    private static final List<String> LIMITATIONS = List.of(
            "地面为平地、保守方盒碰撞和简化加减速；固定翼为测试气动模型。未运行本体实体物理。",
            "模拟感知只使用阵营、半径、可选方盒视线和同层评分；未运行完整雷达、隐身、目标分层或组网。",
            "开火尝试为场景的距离/朝向/间隔门控，无真实瞄准、制导、弹体、伤害和补给。",
            "当前地面算法没有 A* 绕障搜索；受阻与脱困是被观察的结果，不保证能够绕出障碍。",
            "未覆盖旧固定翼驾驶、旋翼、烟幕、ECM 或完整行为管理器生命周期。");

    /** 回放顶层合同，schemaVersion 仅接受当前版本。 */
    public record Replay(
            /** 格式版本。 */ int schemaVersion,
            /** 每秒刻数。 */ int tickRate,
            /** 确切初态和参数。 */ RVP_GunnerSimulationScenario scene,
            /** 接入的生产算法。 */ List<String> coverage,
            /** 测试环境限制。 */ List<String> limitations,
            /** 连续 0..ticks 的逐刻记录。 */ List<Frame> frames,
            /** 整场统计，只作结果汇总。 */ Summary summary) {}
    /** t 时刻位置与接下来 [t,t+1) 区间将使用的命令；blocked 来自上一积分区间。 */
    public record Frame(
            /** 刻号。 */ int tick,
            /** 按场景 actor 顺序记录，离场对象保留 active=false。 */ List<Sample> actors,
            /** 本刻发生的离散事件。 */ List<Event> events) {}
    /** 单载具完整观察。 */
    public record Sample(
            /** 对象标识。 */ String id,
            /** 当前是否在场。 */ boolean active,
            /** 世界 X/Y/Z，格。 */ double[] position,
            /** Minecraft 航向，度。 */ double yaw,
            /** 抬头为正的俯仰，度。 */ double pitch,
            /** 右倾为正的坡度，度。 */ double bank,
            /** 地面为带符号前向速度，固定翼为空速，格/秒。 */ double speed,
            /** 感知目标，无目标为 null。 */ String targetId,
            /** 胜出的移动行为。 */ String behavior,
            /** 决策阶段。 */ String phase,
            /** 运动命令；脚本目标为中立。 */ Control command,
            /** 上一运动区间是否被障碍阻挡。 */ boolean blocked,
            /** 当前是否有作战弹药。 */ boolean combatAmmo,
            /** 本刻是否通过模拟开火门控。 */ boolean fireAttempt,
            /** 意图落选原因或公共飞控反馈。 */ List<String> diagnostics) {}
    /** 统一展示的地面按键与固定翼杆量。 */
    public record Control(
            /** 前进。 */ boolean forward,
            /** 后退。 */ boolean backward,
            /** 左转。 */ boolean left,
            /** 右转。 */ boolean right,
            /** 抬头杆量。 */ double pitch,
            /** 右转杆量。 */ double yaw,
            /** 右倾杆量。 */ double roll,
            /** 油门百分比。 */ double throttle) {
        /** 地面数值决策转换。 */
        static Control ground(Command c) { return new Control(c.forward(), c.backward(), c.left(), c.right(), 0, 0, 0, 0); }
        /** 无运动输入。 */
        static Control neutral() { return new Control(false, false, false, false, 0, 0, 0, 0); }
    }
    /** 可跳转时间轴的事件。 */
    public record Event(
            /** 所属刻。 */ int tick,
            /** 对象标识。 */ String actorId,
            /** 稳定事件类型。 */ String type,
            /** 中文说明。 */ String message) {}
    /** 统计不含机器耗时或当前时间，以保持记录可逐字节重复。 */
    public record Summary(
            /** 各对象累计实际路程，格。 */ Map<String, Double> distance,
            /** 被阻挡的对象刻数。 */ int blockedTicks,
            /** 模拟开火尝试次数。 */ int fireAttempts,
            /** 进入脱困的次数。 */ int recoveries) {}

    /** 从同一个初态建立全新运行态，不在不同运行之间共享状态。 */
    public Replay run(RVP_GunnerSimulationScenario scene) {
        scene.validate();
        RVP_GunnerSimulationWorld world = new RVP_GunnerSimulationWorld(scene);
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < scene.actors.size(); i++) agents.add(new Agent(scene.actors.get(i), i, scene.seed));
        List<Frame> frames = new ArrayList<>();
        int blockedTicks = 0, attempts = 0, recoveries = 0;
        for (int tick = 0; tick <= scene.ticks; tick++) {
            List<Event> events = new ArrayList<>();
            for (Agent a : agents) {
                a.active = tick >= a.config.spawnTick && tick < a.config.despawnTick;
                if (a.active && a.config.mode.equals("scripted")) {
                    double[] previous = a.position.clone();
                    for (int k = 0; k < 3; k++) a.position[k] = a.config.position[k]
                            + a.config.velocity[k] * (tick - a.config.spawnTick) / TICK_RATE;
                    if (tick > a.config.spawnTick) a.travelled += distance(previous, a.position);
                    a.speed = Math.sqrt(Math.pow(a.config.velocity[0], 2) + Math.pow(a.config.velocity[1], 2)
                            + Math.pow(a.config.velocity[2], 2));
                }
                if (tick == a.config.spawnTick) events.add(new Event(tick, a.config.id, "SPAWN", a.config.name + " 出场"));
                if (tick == a.config.despawnTick) events.add(new Event(tick, a.config.id, "DESPAWN", a.config.name + " 离场"));
            }
            List<Sample> samples = new ArrayList<>();
            // 所有对象先观察和决策，再统一积分；对象列表顺序不会让后一个读取到未来位置。
            for (Agent a : agents) {
                a.control = Control.neutral(); a.fireAttempt = false; a.diagnostics = List.of();
                if (a.active && !a.config.mode.equals("scripted")) {
                    Agent oldTarget = a.target;
                    if (a.target != null && !a.target.active) a.target = null;
                    if ((tick - a.config.spawnTick) % a.config.scanInterval == 0) a.target = sense(a, agents, world);
                    if (oldTarget != a.target) events.add(new Event(tick, a.config.id, "TARGET",
                            a.target == null ? "失去目标" : "发现目标：" + a.target.config.name));
                    String oldPhase = a.phase;
                    if (a.aircraft == null) planGround(a, tick);
                    else planFlight(a, tick, scene.groundY);
                    if (!a.phase.equals(oldPhase)) {
                        events.add(new Event(tick, a.config.id, "PHASE", a.phase));
                        if (a.phase.equals("RECOVERY")) recoveries++;
                    }
                    a.fireAttempt = a.target != null && ammo(a, tick) && tick % a.config.fireInterval == 0
                            && distance(a.position, a.target.position) <= a.config.fireRange
                            && angle(a, a.target) <= Math.toRadians(a.config.fireWindowDeg)
                            && world.visible(a.position, a.config.height, a.target.position, a.target.config.height)
                            && (a.aircraft == null || a.attackGeometry);
                    if (a.fireAttempt) { attempts++; events.add(new Event(tick, a.config.id, "FIRE_ATTEMPT", "模拟开火尝试")); }
                    if (tick == a.config.reloadFrom) events.add(new Event(tick, a.config.id, "RELOAD", "开始模拟装填"));
                    if (tick == a.config.reloadUntil) events.add(new Event(tick, a.config.id, "RELOAD", "模拟装填完成"));
                    if (a.blocked) blockedTicks++;
                    if (a.blocked && !a.previouslyBlocked) events.add(new Event(tick, a.config.id, "BLOCKED", "运动受到障碍阻挡"));
                }
                samples.add(new Sample(a.config.id, a.active, a.position.clone(), a.yaw, a.pitch, a.bank, a.speed,
                        a.target == null ? null : a.target.config.id, a.behavior, a.phase, a.control,
                        a.blocked, ammo(a, tick), a.fireAttempt, a.diagnostics));
                a.previouslyBlocked = a.blocked;
            }
            frames.add(new Frame(tick, List.copyOf(samples), List.copyOf(events)));
            if (tick < scene.ticks) for (Agent a : agents) if (a.active && !a.config.mode.equals("scripted")) step(a, world);
        }
        Map<String, Double> distances = new LinkedHashMap<>();
        for (Agent a : agents) distances.put(a.config.id, a.travelled);
        return new Replay(1, TICK_RATE, scene, COVERAGE, LIMITATIONS, List.copyOf(frames),
                new Summary(distances, blockedTicks, attempts, recoveries));
    }

    /** 测试感知：同阵营过滤和可选视线；只在单一普通目标层内调用生产评分。 */
    private static Agent sense(Agent a, List<Agent> all, RVP_GunnerSimulationWorld world) {
        return all.stream().filter(b -> b != a && b.active && !a.config.team.equals(b.config.team))
                .filter(b -> distance(a.position, b.position) <= a.config.searchRadius)
                .filter(b -> !a.config.requireLineOfSight || world.visible(a.position, a.config.height, b.position, b.config.height))
                .min(Comparator.comparingDouble((Agent b) -> RVP_GunnerTargetScore.score(
                        distance(a.position, b.position), angle(a, b), a.config.mode.equals("launcher")))
                        .thenComparing(b -> b.config.id)).orElse(null);
    }

    /** 同生产行为相同的规划先后与默认优先级；使用真实仲裁器选出唯一移动意图。 */
    private static void planGround(Agent a, int tick) {
        // 原实体第一次 tick 的 tickCount 为 1；回放第 0 刻对应年龄 1，避免凭空在年龄 0 触发卡住检查。
        var in = new Input((long) tick - a.config.spawnTick + 1, new Point(a.position[0], a.position[2]), (float) a.yaw,
                -Math.sin(Math.toRadians(a.yaw)), Math.cos(Math.toRadians(a.yaw)),
                a.target == null ? null : new Point(a.target.position[0], a.target.position[2]),
                true, a.config.mode.equals("launcher"), ammo(a, tick));
        List<RVP_GunnerBehaviorIntent> intents = new ArrayList<>();
        Map<String, Command> commands = new LinkedHashMap<>();
        // 调用各独立行为状态推进；战术计时不会因为本刻无敌而暂停。
        a.engagement.tick(); a.launcher.tick(); a.recovery.tick();
        if (in.launcher()) offer(intents, commands, "launcher_positioning", 500, 0,
                RVP_GunnerGroundPolicy.launcher(in, a.config.ground, a.launcher, a.random));
        offer(intents, commands, "stuck_recovery", 800, 1, RVP_GunnerGroundPolicy.recover(in, a.config.ground, a.recovery));
        if (!in.launcher()) {
            Command engage = RVP_GunnerGroundPolicy.engage(in, a.config.ground, a.engagement, a.random);
            if (a.target != null) offer(intents, commands, "ground_engagement_move", 500, 2, engage);
            else offer(intents, commands, "ground_patrol", 200, 3,
                    RVP_GunnerGroundPolicy.patrol(in, a.config.ground, a.patrol, a.random));
        }
        // 调用生产意图仲裁器，保存胜出行为与所有落选原因。
        var resolution = a.arbiter.resolve(intents);
        var winner = resolution.winners().get("MOVEMENT/vehicle");
        a.behavior = winner.behaviorId();
        Command command = commands.get(a.behavior);
        a.phase = command.phase(); a.control = Control.ground(command); a.diagnostics = resolution.rejections();
    }

    /** 构造测试移动候选；实际命令保存在数值映射中，不创建游戏实体。 */
    private static void offer(List<RVP_GunnerBehaviorIntent> intents, Map<String, Command> commands,
                              String id, int priority, int order, Command command) {
        if (command == null) return;
        commands.put(id, command);
        // 调用现有意图数据工厂，不触发动作网关或世界写操作。
        intents.add(RVP_GunnerBehaviorIntent.of(id, "vehicle", order, priority, Channel.MOVEMENT, Kind.MOVEMENT,
                null, null, null, true, "", null));
    }

    /** 覆盖当前新增的公共固定翼分支，显式保存请求阶段与控制器反馈。 */
    private static void planFlight(Agent a, int tick, double groundY) {
        Vector position = vector(a.position), target = a.target == null ? null : vector(a.target.position);
        Vector velocity = a.target == null ? null : vector(a.target.config.velocity);
        // 调用生产 Gunner 任务策略，不将临时武器门控写入公共控制器。
        var decision = RVP_GunnerFlightPolicy.decide(new RVP_GunnerFlightPolicy.Input(a.config.id, tick, position,
                a.yaw, a.speed, a.position[1] - groundY, false, vector(a.config.position), a.target == null ? -1 : a.target.index,
                target, velocity, false, a.target != null && a.target.config.position[1] > groundY + 20,
                false, false, 0, a.config.fireRange, a.config.altitudeMin, a.config.altitudeMax,
                a.config.combatRadius, 200, 200, 0), a.flightState);
        a.flightState = decision.state(); a.attackGeometry = decision.attackGeometry();
        // 调用测试飞机只读观察，随后运行生产公共控制器和执行器。
        var solution = a.controller.solve(a.aircraft.observation(a.uuid, tick, groundY), a.aircraft.airframe(),
                decision.request(), RVP_FlightTuning.smoothCruise(), a.controllerState);
        a.aircraft.apply(a.uuid, tick, solution.command());
        a.controllerState = solution.nextState(); a.behavior = "fixed_wing_combat_flight"; a.phase = a.flightState.phase().name();
        var c = solution.command();
        a.control = new Control(false, false, false, false, c.pitch(), c.yaw(), c.roll(), c.throttlePercent());
        a.diagnostics = List.of(solution.feedback().status().name(), solution.feedback().reason());
    }

    /** 简化物理只消费胜出的操作；被阻挡时保留原位置，绝不为算法传送越墙。 */
    private static void step(Agent a, RVP_GunnerSimulationWorld world) {
        double[] old = a.position.clone(), next;
        if (a.aircraft != null) {
            a.aircraft.step(); next = a.aircraft.position();
            a.yaw = a.aircraft.yaw(); a.pitch = a.aircraft.pitchUp(); a.bank = a.aircraft.bank(); a.speed = a.aircraft.speed();
        } else {
            double desired = a.control.forward() ? a.config.maxSpeed : a.control.backward() ? -a.config.maxSpeed * 0.5 : 0;
            a.speed += Math.max(-a.config.acceleration / TICK_RATE, Math.min(a.config.acceleration / TICK_RATE, desired - a.speed));
            int steering = (a.control.right() ? 1 : 0) - (a.control.left() ? 1 : 0);
            a.yaw += steering * a.config.turnDegPerTick * (a.speed < 0 ? -1 : 1);
            double yaw = Math.toRadians(a.yaw), step = a.speed / TICK_RATE;
            next = new double[]{a.position[0] - Math.sin(yaw) * step, a.position[1], a.position[2] + Math.cos(yaw) * step};
        }
        a.blocked = world.blocked(old, next, a.config.radius, a.config.height);
        if (a.blocked) {
            a.speed = 0;
            if (a.aircraft != null) a.aircraft.stopAt(old);
        } else { a.position = next; a.travelled += distance(old, next); }
    }

    /** 场景装填状态只控制夹具弹药，不执行本体补给。 */
    private static boolean ammo(Agent a, int tick) { return !(tick >= a.config.reloadFrom && tick < a.config.reloadUntil); }
    /** 数值向量转换。 */
    private static Vector vector(double[] p) { return new Vector(p[0], p[1], p[2]); }
    /** 空间距离。 */
    private static double distance(double[] a, double[] b) { return Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2)); }
    /** 车头与目标的空间夹角，单位弧度；模拟武器默认沿车头。 */
    private static double angle(Agent a, Agent b) {
        double dx=b.position[0]-a.position[0], dy=b.position[1]-a.position[1], dz=b.position[2]-a.position[2];
        double yaw=Math.toRadians(a.yaw), pitch=Math.toRadians(a.pitch), length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        return length < 1.0E-9 ? 0 : Math.acos(Math.max(-1, Math.min(1,
                (-Math.sin(yaw)*Math.cos(pitch)*dx+Math.sin(pitch)*dy+Math.cos(yaw)*Math.cos(pitch)*dz)/length)));
    }

    /** 每场新建的运行态；随机序列按对象标识派生，不受其他对象消耗影响。 */
    private static final class Agent {
        /** 场景参数。 */ final RVP_GunnerSimulationScenario.Actor config;
        /** 稳定数值目标编号。 */ final int index;
        /** 稳定载具 UUID。 */ final UUID uuid;
        /** 当前坐标。 */ double[] position;
        /** 当前航向。 */ double yaw;
        /** 当前抬头角。 */ double pitch;
        /** 当前右倾角。 */ double bank;
        /** 当前速度。 */ double speed;
        /** 累计路程。 */ double travelled;
        /** 是否在场。 */ boolean active;
        /** 最近一次物理阻挡。 */ boolean blocked;
        /** 前一记录是否已报告阻挡。 */ boolean previouslyBlocked;
        /** 当前模拟目标。 */ Agent target;
        /** 胜出行为。 */ String behavior = "scripted";
        /** 当前决策阶段。 */ String phase = "SCRIPTED";
        /** 完整输入。 */ Control control = Control.neutral();
        /** 是否允许固定翼攻击几何。 */ boolean attackGeometry;
        /** 是否发生模拟开火。 */ boolean fireAttempt;
        /** 本刻反馈。 */ List<String> diagnostics = List.of();
        /** 独立接敌状态。 */ final GroundEngagementState engagement = new GroundEngagementState();
        /** 独立巡逻状态。 */ final GroundPatrolState patrol = new GroundPatrolState();
        /** 独立发射架状态。 */ final LauncherPositioningState launcher = new LauncherPositioningState();
        /** 独立脱困状态。 */ final StuckRecoveryState recovery = new StuckRecoveryState();
        /** 生产仲裁器。 */ final RVP_GunnerIntentArbiter arbiter = new RVP_GunnerIntentArbiter();
        /** 可重复的随机接口。 */ final RandomInput random;
        /** 可选测试飞机。 */ final RVP_GunnerSimulationAircraft aircraft;
        /** 当前固定翼业务状态。 */ RVP_GunnerFlightPolicy.State flightState = RVP_GunnerFlightPolicy.State.initial();
        /** 当前公共控制状态。 */ RVP_FlightTypes.State controllerState = RVP_FlightTypes.State.empty();
        /** 无副作用公共控制器。 */ final RVP_FixedWingFlightController controller = new RVP_FixedWingFlightController();
        Agent(RVP_GunnerSimulationScenario.Actor config, int index, long seed) {
            this.config=config; this.index=index; position=config.position.clone(); yaw=config.yaw; speed=config.speed;
            uuid=UUID.nameUUIDFromBytes(config.id.getBytes(StandardCharsets.UTF_8));
            Random source = new Random(seed ^ config.id.hashCode());
            random = new RandomInput() {
                @Override public int nextInt(int bound) { return source.nextInt(bound); }
                @Override public float nextFloat() { return source.nextFloat(); }
                @Override public boolean nextBoolean() { return source.nextBoolean(); }
            };
            aircraft=config.mode.equals("fixed_wing") ? new RVP_GunnerSimulationAircraft(config.position, config.yaw, config.speed) : null;
        }
    }
}
