package org.ywzj.rvp.projectile.simulation;

/** 仿真初态，不覆盖武器 JSON 中的物理参数；距离用格、速度用格/秒、角度用度。 */
public final class RVP_ProjectileSimulationScenario {
    /** 当前场景格式，只接受 1。 */ public int schemaVersion = 1;
    /** 稳定输出文件名。 */ public String id;
    /** 页面名称。 */ public String name;
    /** 测试目的。 */ public String description = "RVP 武器配置数值仿真";
    /** 相对于 weapons 根目录的资源 ID。 */ public String weapon;
    /** 最多运行刻数，20 刻为一秒。 */ public int ticks = 600;
    /** 发射点世界 X/Y/Z，格。 */ public double[] position = {0, 164, 0};
    /** Minecraft 航向；0 朝 +Z，90 朝 -X。 */ public float yaw = 0;
    /** 正数抬头；导出时同工作台角度约定。 */ public float pitch = 0;
    /** 发射平台速度；仅武器 inherit_vehicle_velocity=true 时叠加，格/秒。 */ public double[] vehicleVelocity = {0, 0, 0};
    /** 可选 SpawnContext 世界初速度，格/秒；用于复现子弹药等外部出膛初态，仍交给真实 initFromWeapon 处理。 */ public double[] initialVelocity;
    /** 可选母弹释放瞬间姿态：航向、正数抬头的俯仰、滚转，度；滚转不改变父弹视轴。 */ public double[] parentRotation;
    /** 母弹释放瞬间世界速度，格/秒；仅作为生产风向解析的退化方向输入。 */ public double[] parentVelocity = {0, 0, 0};
    /** 发射平台姿态：航向、俯仰、滚转，度；用于冷发射本地轴转换。 */ public double[] carrierRotation = {0, 0, 0};
    /** 挂架冷发射时长，刻；0 表示没有冷发射。 */ public int coldLaunchTicks;
    /** 挂架局部冷发射速度，格/秒；与本体局部 X/Y/Z 轴约定相同。 */ public double[] coldLaunchVelocity = {0, -20, 0};
    /** 可选布撒初始分量，格/秒；使用真实部署状态初始化，半衰期仍读武器 JSON。 */ public double[] deploymentVelocity;
    /** 可选智能引信接管点，世界坐标；只用于测试接管后的真实运动，触发探测不在场景中伪造。 */ public double[] smartFuseTarget;
    /** 固定随机种子，控制真实实体出膛状态和 GPS 随机误差，默认 721。 */ public long seed = 721;
    /** 可选固定目标世界坐标；无目标时填 null。 */ public double[] target;
    /** 目标脚本初速度，格/秒；给生产拦截制导提供真实 Entity 运动观测。 */ public double[] targetVelocity = {0, 0, 0};
    /** 目标脚本加速度，格/秒²；默认不加速。 */ public double[] targetAcceleration = {0, 0, 0};
    /** 可选到达点，默认空；非空时从第 0 刻的 target 沿各点匀速移动，替代 targetVelocity/targetAcceleration，末点后静止。 */ public TargetPoint[] targetPath = {};
    /** 可选连续水平机动，默认空；与 targetPath、非零 targetAcceleration 互斥，段内速度与航向连续变化。 */
    public TargetManeuver[] targetManeuvers = {};
    /** 测试停止球半径，格；不是武器引信半径。 */ public double targetRadius = 2;
    /** 平面地面世界 Y，格。 */ public double groundY = 64;
    /** 可选水平水面世界 Y，格；null 表示干燥，须高于 groundY。 */ public Double waterY;
    /** true 把场景目标观测交给实体制导执行器；false 只计算实体自由运动。 */ public boolean guidance = true;

    /** 目标中心的到达点；第 0 刻起点只由 target 表达，避免同刻位置冲突。 */
    public record TargetPoint(/** 到达时刻，刻；必须为 1..ticks 的整数并严格递增，20 刻等于 1 秒。 */ double tick,
                              /** 到达点世界 X/Y/Z，格；必须为有限三元组。 */ double[] position) {}
    /** 从上一段末尾继续运动；最后一段之后保持末速度和末航向，不突然停止。 */
    public record TargetManeuver(/** 本段时长，整数 Tick，至少 1。 */ double durationTicks,
                                 /** 本段结束时的水平速率，格/秒，非负。 */ double endSpeed,
                                 /** 本段累计水平转角，度；从 +X 朝 +Z 为正，范围 -360～360。 */ double turnDeg) {}
    /** 某个绝对时刻的目标观测，供真实实体读取；速度单位为格/秒。 */
    public record TargetState(/** 目标中心世界坐标，格。 */ double[] position,
                              /** 当前段世界速度，格/秒；末点后为零。 */ double[] velocity) {}

    /** 输入错误必须在积分前给出直接可读的提示。 */
    public void validate() {
        if (schemaVersion != 1) throw new IllegalArgumentException("场景仅接受 schemaVersion=1");
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]*") || name == null || name.isBlank()
                || description == null || weapon == null) throw new IllegalArgumentException("场景需要有效 id、name、description、weapon");
        if (ticks < 1 || ticks > 20000) throw new IllegalArgumentException("ticks 必须在 1..20000 内");
        vector(position, "position"); vector(vehicleVelocity, "vehicleVelocity");
        vector(carrierRotation, "carrierRotation"); vector(coldLaunchVelocity, "coldLaunchVelocity");
        vector(targetVelocity, "targetVelocity"); vector(targetAcceleration, "targetAcceleration");
        vector(parentVelocity, "parentVelocity");
        if (initialVelocity != null) vector(initialVelocity, "initialVelocity");
        if (parentRotation != null) vector(parentRotation, "parentRotation");
        if (target != null) vector(target, "target");
        if (deploymentVelocity != null) vector(deploymentVelocity, "deploymentVelocity");
        if (smartFuseTarget != null) vector(smartFuseTarget, "smartFuseTarget");
        if (coldLaunchTicks < 0 || coldLaunchTicks > 20000) throw new IllegalArgumentException("coldLaunchTicks 必须在 0..20000 内");
        if (waterY != null && (!Double.isFinite(waterY) || waterY <= groundY)) throw new IllegalArgumentException("waterY 必须是高于地面的有限高度");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90
                || !Double.isFinite(groundY) || !Double.isFinite(targetRadius) || targetRadius <= 0) {
            throw new IllegalArgumentException("角度、groundY、targetRadius 必须有效，俯仰限于 ±90 度");
        }
        if (position[1] <= groundY) throw new IllegalArgumentException("发射点必须高于地面");
        if (targetPath == null || targetPath.length > 256) throw new IllegalArgumentException("targetPath 必须为数组，最多 256 个到达点");
        if (targetPath.length > 0 && target == null) throw new IllegalArgumentException("使用 targetPath 前必须启用 target");
        if (targetManeuvers == null || targetManeuvers.length > 64) {
            throw new IllegalArgumentException("targetManeuvers 必须为数组，最多 64 段");
        }
        if (targetManeuvers.length > 0 && (target == null || targetPath.length > 0
                || java.util.Arrays.stream(targetAcceleration).anyMatch(value -> value != 0.0))) {
            throw new IllegalArgumentException("连续机动需要 target，且不能同时使用 targetPath 或非零 targetAcceleration");
        }
        double maneuverEnd = 0;
        for (TargetManeuver maneuver : targetManeuvers) {
            if (maneuver == null || !Double.isFinite(maneuver.durationTicks())
                    || maneuver.durationTicks() != Math.rint(maneuver.durationTicks()) || maneuver.durationTicks() < 1
                    || !Double.isFinite(maneuver.endSpeed()) || maneuver.endSpeed() < 0
                    || !Double.isFinite(maneuver.turnDeg()) || Math.abs(maneuver.turnDeg()) > 360) {
                throw new IllegalArgumentException("连续机动需要正整数时长、非负有限末速和 -360～360 度有限转角");
            }
            maneuverEnd += maneuver.durationTicks();
            if (maneuverEnd > ticks) throw new IllegalArgumentException("连续机动总时长不能超过场景 ticks");
        }
        double checkedManeuverEnd = 0;
        for (TargetManeuver maneuver : targetManeuvers) {
            checkedManeuverEnd += maneuver.durationTicks();
            // 全部段合法后才调用轨迹，避免尚未验证的后续坏段进入取样器。
            targetAt(checkedManeuverEnd);
        }
        double previousTick = 0;
        for (TargetPoint point : targetPath) {
            if (point == null || !Double.isFinite(point.tick()) || point.tick() != Math.rint(point.tick())
                    || point.tick() <= previousTick || point.tick() > ticks) {
                throw new IllegalArgumentException("轨迹点时刻须为严格递增的整数，且在 1..ticks 内");
            }
            // 调用统一三元组校验，明确拒绝缺失或非有限的到达位置。
            vector(point.position(), "targetPath.position"); previousTick = point.tick();
        }
        if (target != null) {
            // 调用统一脚本取样检查每段速度及终点，避免数值溢出进入生产制导。
            targetAt(0);
            for (TargetPoint point : targetPath) targetAt(point.tick());
            targetAt(ticks);
        }
    }

    /** 直接按绝对时刻取样，倒拖和重复运行不依赖历史；折点刻使用下一段速度，末点后停住。 */
    public TargetState targetAt(double tick) {
        if (target == null) return null;
        if (targetManeuvers.length > 0) {
            // 调用连续目标脚本；只生成外部目标观测，不计算导弹制导或运动。
            var state = RVP_TargetManeuverMath.sample(target, targetVelocity, targetManeuvers, tick);
            vector(state.position(), "连续机动位置"); vector(state.velocity(), "连续机动速度");
            return state;
        }
        double[] result = new double[3], velocity = new double[3];
        double[] from = target;
        double fromTick = 0;
        for (TargetPoint point : targetPath) {
            if (tick < point.tick()) {
                double duration = point.tick() - fromTick, fraction = (tick - fromTick) / duration;
                for (int axis = 0; axis < 3; axis++) {
                    result[axis] = from[axis] + (point.position()[axis] - from[axis]) * fraction;
                    velocity[axis] = (point.position()[axis] - from[axis]) * 20 / duration;
                }
                // 调用统一三元组校验，防止有效坐标之间的差值发生溢出。
                vector(result, "目标轨迹位置"); vector(velocity, "目标轨迹速度");
                return new TargetState(result, velocity);
            }
            from = point.position(); fromTick = point.tick();
        }
        if (targetPath.length > 0) return new TargetState(from.clone(), velocity);
        double seconds = tick / 20.0;
        for (int axis = 0; axis < 3; axis++) {
            result[axis] = target[axis] + targetVelocity[axis] * seconds + 0.5 * targetAcceleration[axis] * seconds * seconds;
            velocity[axis] = targetVelocity[axis] + targetAcceleration[axis] * seconds;
        }
        // 调用统一三元组校验，在原恒加速度模式中同样拒绝溢出。
        vector(result, "目标脚本位置"); vector(velocity, "目标脚本速度");
        return new TargetState(result, velocity);
    }

    /** 坐标和速度都要求有限三元组，不能靠数值容错掩盖坏输入。 */
    private static void vector(double[] value, String name) {
        if (value == null || value.length != 3 || java.util.Arrays.stream(value).anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException(name + " 必须是三个有限数值");
        }
    }
}
