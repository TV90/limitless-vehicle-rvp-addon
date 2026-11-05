package org.ywzj.rvp.projectile.simulation;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileEntitySimulation.Snapshot;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 无世界逐刻仿真；Java 运行生产数学代码，浏览器只回放结果。 */
public final class RVP_ProjectileSimulation {
    /** Minecraft 每秒 20 刻；界面速度统一换算为格/秒。 */
    public static final int TICK_RATE = 20;

    /** 输出对象定义；模型尺寸仅供观察，不代替真实碰撞箱。 */
    public record ObjectDefinition(
            /** 稳定对象 ID。 */ String id, /** 显示名称。 */ String name,
            /** 工作台绘制器。 */ String renderer, /** 六位颜色。 */ String color,
            /** 观察图形 X/Y/Z 尺寸，格。 */ double[] size,
            /** 静态配置说明。 */ Map<String, Object> attributes) {}
    /** 一个对象在采样刻完成后的状态。 */
    public record Sample(
            /** 对象 ID。 */ String id, /** 是否显示。 */ boolean active,
            /** 世界位置，格。 */ double[] position, /** 航向，度。 */ float yaw,
            /** 正数抬头，度。 */ float pitch, /** 滚转，度。 */ float roll,
            /** 可选目标对象 ID。 */ String targetId, /** 原始观察值。 */ Map<String, Object> values) {}
    /** 同一刻的全部对象快照。 */
    public record Frame(/** 已完成的飞行刻。 */ int tick, /** 本刻对象。 */ List<Sample> objects) {}
    /** 由测试进程记录的离散事件。 */
    public record Event(/** 事件刻。 */ int tick, /** 事件类型。 */ String type,
                        /** 中文说明。 */ String message, /** 关联对象。 */ String objectId) {}
    /** 全场统计项。 */
    public record Value(/** 名称。 */ String label, /** 有限数值或文字。 */ Object value) {}
    /** 自动数值检查，与游戏实机命中结论分开。 */
    public record Check(/** 检查名称。 */ String name, /** passed/failed/info。 */ String status,
                        /** 证据与边界。 */ String message) {}
    /** 附带来源与初态的回放；核心帧字段遵守工作台当前统一格式。 */
    public record Replay(
            /** 本插件格式标识。 */ String format, /** 当前格式版本。 */ int schemaVersion,
            /** 场景 ID。 */ String id, /** 名称。 */ String name, /** 说明。 */ String description,
            /** 每秒刻数。 */ int tickRate, /** 平地高度。 */ double groundY,
            /** 原始场景初态。 */ RVP_ProjectileSimulationScenario scene,
            /** 输入武器来源与原始 JSON。 */ Map<String, Object> weapon,
            /** 本次实际执行的实体类和运动入口。 */ Map<String, Object> physics,
            /** 对象定义。 */ List<ObjectDefinition> objects,
            /** 静态障碍；当前只用 groundY 平面。 */ List<Object> obstacles,
            /** 从第 0 刻开始的连续帧。 */ List<Frame> frames,
            /** 已发生事件。 */ List<Event> events, /** 全场统计。 */ List<Value> summary,
            /** 自动检查。 */ List<Check> checks, /** 真正执行的生产代码。 */ List<String> coverage,
            /** 简化环境和未执行功能。 */ List<String> limitations) {}

    /** 只调度真实实体，不在回放导出器里重写气动、重力、推力或速度积分。 */
    public Replay run(RVP_ProjectileSimulationScenario scene, RVP_ProjectileSimulationWeapon weapon) {
        scene.validate();
        // 调用真实实体运行器；所有弹种从生产 initFromWeapon 和 finalizeSpawnOrientation 开始。
        var flight = new RVP_ProjectileEntitySimulation(weapon.data(), weapon.kind(), scene);
        Snapshot state = flight.snapshot();
        Vec3 target = flight.targetPosition();
        String entityClass = flight.entity().getClass().getSimpleName();
        String entry = weapon.kind() == RVP_EnumWeaponKind.MACHINEGUN ? "tickBulletMotionAndFacing" : "tickMotion";
        String model = "实体段 · " + entityClass;
        String guidanceModel = scene.guidance && target != null
                ? "实体制导执行器；目标观测由场景提供" : "实体自由运动（未输入制导观测）";
        var frames = new ArrayList<Frame>();
        var events = new ArrayList<Event>();
        var limits = List.of(
                "直接执行真实 RVP 实体的构造、初始化、制导执行器和运动方法，不调用虚拟段积分器。",
                "环境是固定平地、可选水平水面、已就绪区块；不运行区块存档、真实流场、载具运动或网络。",
                "GPS 使用真实坐标制导源；其他制导由场景提供理想目标意图，不验证搜索、锁定、干扰、遥控链路或制导源内部的观测生成。",
                "未运行引信触发、爆炸、伤害、穿透、跳弹、子母弹释放；可显式提供部署初态或已触发的智能引信接管初态。",
                "地面/目标球只决定何时结束试验，末帧位置、速度和姿态保留实体完整一步结果，不裁剪或改写物理状态。");
        events.add(new Event(0, "LAUNCH", "发射：" + weapon.name() + "；" + model, "projectile"));
        String status = "飞行中";
        double peakY = state.position().y, closest = target == null ? 0 : state.position().distanceTo(target);
        boolean reached = false;
        for (int tick = 0; tick <= scene.ticks; tick++) {
            frames.add(frame(scene, weapon, state, target, status));
            if (!status.equals("飞行中")) break;
            if (tick == scene.ticks) {
                status = "达到场景时长";
                frames.set(frames.size() - 1, frame(scene, weapon, state, target, status));
                events.add(new Event(tick, "TIME_LIMIT", status, "projectile"));
                break;
            }
            Snapshot before = state;
            Vec3 oldTarget = target;
            // 调用该实体真正执行的制导/运动方法；导出器不得计算或补偿下一刻速度。
            state = flight.step(); target = flight.targetPosition();
            checkFinite(state, weapon.id());
            double groundFraction = state.position().y <= scene.groundY
                    ? (before.position().y - scene.groundY) / (before.position().y - state.position().y) : Double.POSITIVE_INFINITY;
            double targetFraction = target == null ? Double.POSITIVE_INFINITY
                    : sphereEntry(before.position().subtract(oldTarget), state.position().subtract(target), Vec3.ZERO, scene.targetRadius);
            if (Math.min(groundFraction, targetFraction) <= 1) {
                reached = targetFraction <= groundFraction;
                status = reached ? "本刻运动段进入测试目标球" : "本刻运动段接触平面地面";
                events.add(new Event(tick + 1, reached ? "TARGET_REACHED" : "GROUND_CONTACT", status + "；保留完整实体步进状态", "projectile"));
            } else if (state.remainingLife() < 0) {
                status = "配置寿命结束";
                events.add(new Event(tick + 1, "LIFE_END", status, "projectile"));
            } else if (!flight.entity().isAlive()) {
                status = "实体已结束";
                events.add(new Event(tick + 1, "ENTITY_END", status, "projectile"));
            }
            if (target != null) closest = Math.min(closest,
                    segmentDistance(before.position().subtract(oldTarget), state.position().subtract(target), Vec3.ZERO));
            peakY = Math.max(peakY, state.position().y);
            if (!state.motorStage().equals(before.motorStage())) events.add(new Event(tick + 1, "MOTOR_STAGE", state.motorStage(), "projectile"));
            if (state.inWater() != before.inWater()) events.add(new Event(tick + 1, "MEDIUM", state.inWater() ? "实体进入水中" : "实体离开水面", "projectile"));
            if (!state.phase().equals(before.phase())) events.add(new Event(tick + 1, "GUIDANCE_PHASE", "实体制导阶段：" + state.phase(), "projectile"));
        }
        var objects = new ArrayList<ObjectDefinition>();
        objects.add(new ObjectDefinition("projectile", weapon.name(), "projectile:body", "#f2c56a",
                new double[]{1, 1, 4}, ordered("weaponId", weapon.id(), "kind", weapon.kind().name(), "model", model, "guidanceModel", guidanceModel)));
        if (target != null) objects.add(new ObjectDefinition("target", "场景目标观测", "sphere", "#f07b88",
                new double[]{scene.targetRadius * 2, scene.targetRadius * 2, scene.targetRadius * 2}, Map.of()));
        var summary = new ArrayList<Value>(List.of(new Value("武器", weapon.id()), new Value("计算入口", model + "#" + entry),
                new Value("结束原因", status), new Value("飞行时间（秒）", state.flightTick() / 20.0),
                new Value("实体累计路程（格）", state.flightDistance()), new Value("最高世界 Y（格）", peakY),
                new Value("峰值速度（格/秒）", state.peakFlightSpeed() * TICK_RATE), new Value("武器文件摘要 SHA-256", weapon.sha256())));
        if (target != null) summary.add(new Value("最近目标距离（格）", closest));
        return new Replay("rvp-projectile-simulation", 2, scene.id, scene.name, scene.description, TICK_RATE, scene.groundY,
                scene, ordered("id", weapon.id(), "sha256", weapon.sha256(), "json", weapon.source()),
                ordered("implementation", "rvp-entity-flight", "entityClass", entityClass, "motionEntry", entry,
                        "guidanceRuntime", RVP_GuidanceSimulationIdentity.current()),
                objects, List.of(), frames, events, summary,
                List.of(new Check("真实实体逐刻记录", "passed", "直接读取实体位置、速度、姿态及航程；共 " + frames.size() + " 帧。"),
                        new Check("测试停止球", "info", target == null ? "未设置目标。" : reached ? "完整运动段进入目标球；不代表真实命中。" : "本次未进入目标球。")),
                List.of("真实 " + entityClass + " 构造器、initFromWeapon、冷发射与 finalizeSpawnOrientation",
                        "RVP_GuidanceRuntimeController → 实体制导执行器 → 实体攻角兜底 → " + entry,
                        "生产实体的二脉冲、推力曲线、变质量、风漂、恒速、姿态、部署与水中分支，按配置生效"), limits);
    }

    /** 导出实体原值；炮弹的历史旋转符号与导弹不同，按生产绘制约定转换成工作台方向。 */
    private static Frame frame(RVP_ProjectileSimulationScenario scene, RVP_ProjectileSimulationWeapon weapon,
                               Snapshot state, Vec3 target, String status) {
        var values = new LinkedHashMap<String, Object>();
        values.put("weaponId", weapon.id()); values.put("speed", state.velocity().length() * TICK_RATE);
        values.put("velocity", array(state.velocity().scale(TICK_RATE))); values.put("height", state.position().y - scene.groundY);
        values.put("distance", state.flightDistance()); values.put("life", state.remainingLife());
        values.put("status", status); values.put("motorStage", state.motorStage());
        values.put("entityYaw", state.yRot()); values.put("entityPitch", state.xRot());
        values.put("pitchRate", state.pitchRateDeg()); values.put("yawRate", state.yawRateDeg());
        values.put("aeroLoadFactor", state.aeroLoadFactor()); values.put("inWater", state.inWater());
        values.put("phase", state.phase()); values.put("directMotion", state.directMotion());
        if (target != null) values.put("targetDistance", state.position().distanceTo(target));
        values.put("mass", state.mass()); values.put("thrust", state.thrust());
        values.put("densityFactor", weapon.data().getProjectileData().resolveAltitudeDragFactor(state.position().y));
        var samples = new ArrayList<Sample>();
        boolean cannon = weapon.kind() == RVP_EnumWeaponKind.MACHINEGUN;
        samples.add(new Sample("projectile", true, array(state.position()), cannon ? -state.yRot() : state.yRot(),
                cannon ? state.xRot() : -state.xRot(), 0, target == null ? null : "target", values));
        if (target != null) samples.add(new Sample("target", true,
                new double[]{target.x, target.y - scene.targetRadius, target.z}, 0, 0, 0, null,
                ordered("说明", "目标意图观测；脚本位置/速度交给生产拦截与气动执行器", "半径（格）", scene.targetRadius)));
        return new Frame(state.flightTick(), samples);
    }

    /** 坏状态直接失败，禁止换成简化弹道继续生成“成功”记录。 */
    private static void checkFinite(Snapshot state, String id) {
        if (!Double.isFinite(state.position().lengthSqr()) || !Double.isFinite(state.velocity().lengthSqr())
                || !Float.isFinite(state.xRot()) || !Float.isFinite(state.yRot())
                || !Double.isFinite(state.pitchRateDeg()) || !Double.isFinite(state.yawRateDeg())) {
            throw new IllegalStateException("实体第 " + state.flightTick() + " 刻产生非法状态：" + id);
        }
    }

    /** 输入三元组转游戏数学向量。 */
    private static Vec3 vector(double[] value) { return new Vec3(value[0], value[1], value[2]); }
    /** 输出只存普通数组，避免浏览器依赖 Minecraft 类型。 */
    private static double[] array(Vec3 value) { return new double[]{value.x, value.y, value.z}; }

    /** 保持导出键顺序；Map.of 的遍历顺序可能随 JVM 进程变化，不能用于确定性记录。 */
    private static Map<String, Object> ordered(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    /** 移动段第一次进入测试球的比例；未相交为正无穷。 */
    static double sphereEntry(Vec3 from, Vec3 to, Vec3 center, double radius) {
        Vec3 offset = from.subtract(center), delta = to.subtract(from);
        double c = offset.lengthSqr() - radius * radius;
        if (c <= 0) return 0;
        double a = delta.lengthSqr(), b = 2 * offset.dot(delta), discriminant = b * b - 4 * a * c;
        if (a <= 1.0E-16 || discriminant < 0) return Double.POSITIVE_INFINITY;
        double entry = (-b - Math.sqrt(discriminant)) / (2 * a);
        return entry >= 0 && entry <= 1 ? entry : Double.POSITIVE_INFINITY;
    }

    /** 连续运动段的最近距离，避免高速穿越时只比较端点导致误报脱靶。 */
    private static double segmentDistance(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 delta = to.subtract(from);
        double fraction = delta.lengthSqr() <= 1.0E-16 ? 0
                : Math.max(0, Math.min(1, point.subtract(from).dot(delta) / delta.lengthSqr()));
        return from.add(delta.scale(fraction)).distanceTo(point);
    }
}
