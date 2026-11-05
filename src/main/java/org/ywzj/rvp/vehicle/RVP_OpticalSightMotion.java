package org.ywzj.rvp.vehicle;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/**
 * 吊舱的双端运动规划。先沿视线规划，再求机械角；渲染只读取本刻保存的轨迹。
 * <p>速度单位为度/刻，加速度单位为度/刻²；有限轴始终在展开的机械区间中运动。</p>
 */
public final class RVP_OpticalSightMotion {
    /** 每刻的固定细分数；与渲染帧数无关，并限制极点附近机械插值的弦误差。 */
    public static final int STEPS = 32;
    /** 解角及收敛容差，单位为度。 */
    private static final double EPSILON = 1.0E-7;
    /** 判断精确极点的水平分量容差，不用于吞掉鼠标输入。 */
    private static final double POLE_EPSILON = 1.0E-8;
    /** 当前刻与上一刻的实际俯仰、偏航轨迹，单位为度，均包含瞄准轴固定偏角。 */
    private final double[] pitchPath = new double[STEPS + 1], yawPath = new double[STEPS + 1];
    /** 稳定画面的横轴轨迹；机械绕竖直视线转动时不强制旋转整幅画面。 */
    private final Quaterniond[] viewPath = new Quaterniond[STEPS + 1];
    /** 当前实际俯仰和偏航，单位为度。 */
    private double pitch, yaw;
    /** 当前两轴速度，单位为度/刻。 */
    private double pitchVelocity, yawVelocity;
    /** 当前执行阶段。 */
    private Phase phase = Phase.IDLE;
    /** 是否已从真实机械角初始化。 */
    private boolean initialized;

    /** 当前运动阶段；只反映实际执行结果，不修改锁点业务。 */
    public enum Phase { IDLE, TRACKING, REORIENTING, LIMITED, FROZEN }

    /** 本次运动约束，所有角度均包含瞄准轴固定偏角。 */
    public record Limits(
            /** 俯仰下限，度。 */ double minPitch,
            /** 俯仰上限，度。 */ double maxPitch,
            /** 偏航下限，度；极大负值表示无有限止挡。 */ double minYaw,
            /** 偏航上限，度；极大正值表示无有限止挡。 */ double maxYaw,
            /** 俯仰最大速度，度/刻。 */ double pitchSpeed,
            /** 偏航最大速度，度/刻。 */ double yawSpeed,
            /** 从静止加速到额定转速的时间，秒。 */ double accelerationSeconds) {
        /** 有限轴不得把穿越止挡的圆周近路当成机械路径。 */
        public boolean continuousYaw() { return minYaw < -1.0E20 && maxYaw > 1.0E20; }
    }

    /** 一次可重放的逻辑刻输入；姿态和方向均为值副本，不包含客户端对象。 */
    public record Input(
            /** 刻首的完整基座姿态。 */ Quaterniond baseBefore,
            /** 刻末的完整基座姿态。 */ Quaterniond baseAfter,
            /** 已解析的请求世界单位方向。 */ Vec3 direction,
            /** 当前机械约束。 */ Limits limits,
            /** 是否进行世界视线和画面横轴稳定。 */ boolean stabilized,
            /** 当前动力和受损状态是否允许主动转动。 */ boolean powered) {}

    /** 可同步、可重放的实际状态；数组在构造时复制，禁止接收端改写原轨迹。 */
    public record Snapshot(
            /** 俯仰速度，度/刻。 */ double pitchVelocity,
            /** 偏航速度，度/刻。 */ double yawVelocity,
            /** 执行阶段。 */ Phase phase,
            /** 本刻俯仰轨迹。 */ double[] pitches,
            /** 本刻偏航轨迹。 */ double[] yaws,
            /** 本刻画面姿态轨迹，xyzw 顺序。 */ double[] views) {
        public Snapshot { pitches = pitches.clone(); yaws = yaws.clone(); views = views.clone(); }
    }

    /** 从模型真实初态或存档恢复，不能沿用另一实体的画面记忆。 */
    public void reset(double pitch, double yaw, Quaterniondc view) {
        this.pitch = pitch;
        this.yaw = yaw;
        pitchVelocity = yawVelocity = 0;
        phase = Phase.IDLE;
        initialized = true;
        for (int i = 0; i <= STEPS; i++) {
            pitchPath[i] = pitch;
            yawPath[i] = yaw;
            viewPath[i] = new Quaterniond(view);
        }
    }

    /** 推进一个游戏刻；相机事件无权调用这个方法。 */
    public void advance(Input input) {
        if (!initialized) throw new IllegalStateException("观瞄运动尚未初始化");
        Limits limits = input.limits;
        // 损坏或调参降低轴速时，新的硬速度上限立即优先于旧惯性。
        pitchVelocity = clamp(pitchVelocity, -Math.max(0, limits.pitchSpeed), Math.max(0, limits.pitchSpeed));
        yawVelocity = clamp(yawVelocity, -Math.max(0, limits.yawSpeed), Math.max(0, limits.yawSpeed));
        Quaterniond view = new Quaterniond(viewPath[STEPS]);
        pitchPath[0] = pitch;
        yawPath[0] = yaw;
        viewPath[0] = new Quaterniond(view);
        phase = Phase.IDLE;
        Vec3 localTarget = inverse(input.baseAfter, input.direction);
        Vec3 movingTarget = localTarget;
        for (int step = 1; step <= STEPS; step++) {
            double t = (double) step / STEPS;
            Quaterniond base = new Quaterniond(input.baseBefore).slerp(input.baseAfter, t).normalize();
            Vec3 requested = input.stabilized ? input.direction : transform(base, movingTarget);
            Vec3 local = inverse(base, requested);
            if (input.powered) {
                // 调用纯几何执行，补偿机体转动与玩家请求只共享这一份速度预算。
                move(local, limits);
            } else {
                pitchVelocity = yawVelocity = 0;
                phase = Phase.FROZEN;
            }
            Quaterniond actual = new Quaterniond(base).rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch));
            // 调用完整姿态随动，仅稳定画面横轴；视线始终与真实机械姿态一致。
            view = input.stabilized ? RVP_OpticalSightMath.followDirection(view,
                    RVP_OpticalSightMath.direction(actual)) : actual;
            pitchPath[step] = pitch;
            yawPath[step] = yaw;
            viewPath[step] = new Quaterniond(view);
        }
    }

    /** 沿可达视线弧推进；精确极点先原地调整偏航，有限区间的远端目标使用区间内路线。 */
    private void move(Vec3 target, Limits limits) {
        Vec3 current = direction(pitch, yaw);
        double[] goal = reachable(target, pitch, yaw, limits);
        if (goal == null) {
            // 调用连续解角后限位，只在当前分支停止，不为绕过止挡瞬间换到半圈外。
            var raw = RVP_OpticalSightMath.localAngles(target, (float) pitch, (float) yaw,
                    (float) limits.minPitch, (float) limits.maxPitch, (float) limits.minYaw, (float) limits.maxYaw);
            goal = new double[]{clamp(raw.x, limits.minPitch, limits.maxPitch), clamp(raw.y, limits.minYaw, limits.maxYaw)};
            phase = Phase.LIMITED;
        }
        if (Math.hypot(current.x, current.z) < POLE_EPSILON && current.distanceToSqr(target.normalize()) > 1.0E-16) {
            // 此处只有偏航是不会改变视线的自由轴；先转到离开极点所需方位，禁止先俯仰再拐弯。
            if (Math.abs(goal[1] - yaw) > EPSILON) {
                joint(pitch, goal[1], limits);
                phase = Phase.REORIENTING;
                return;
            }
        }
        if (Math.abs(goal[0] - pitch) + Math.abs(goal[1] - yaw) < EPSILON) {
            pitchVelocity = yawVelocity = 0;
            return;
        }
        if (phase != Phase.LIMITED) phase = Phase.TRACKING;
        // 检查视线近路能否留在机械区间。不能通过的远端目标沿有限区间转动，例如 -119→0→119。
        boolean arc = arcReachable(current, target, limits);
        double lo = 0, hi = 1;
        double capPitch = velocityCap(pitchVelocity, goal[0] - pitch, limits.pitchSpeed, limits.accelerationSeconds);
        double capYaw = velocityCap(yawVelocity, goal[1] - yaw, limits.yawSpeed, limits.accelerationSeconds);
        double[] next = {pitch, yaw};
        for (int i = 0; i < 32; i++) {
            double progress = (lo + hi) * 0.5;
            double[] candidate = arc ? angles(arc(current, target, progress), pitch, yaw, limits)
                    : new double[]{pitch + (goal[0] - pitch) * progress, yaw + (goal[1] - yaw) * progress};
            if (candidate != null && Math.abs(candidate[0] - pitch) * STEPS <= capPitch + EPSILON
                    && Math.abs(candidate[1] - yaw) * STEPS <= capYaw + EPSILON) {
                lo = progress;
                next = candidate;
            } else hi = progress;
        }
        double vp = (next[0] - pitch) * STEPS, vy = (next[1] - yaw) * STEPS;
        double ap = acceleration(limits.pitchSpeed, limits.accelerationSeconds) / STEPS;
        double ay = acceleration(limits.yawSpeed, limits.accelerationSeconds) / STEPS;
        if (Math.abs(vp - pitchVelocity) > ap + 1.0E-5 || Math.abs(vy - yawVelocity) > ay + 1.0E-5) {
            // 请求反向或路径曲率突然变化时先受控制动，禁止积压旧请求或瞬间反转速度。
            brake(limits);
        } else {
            pitch = limits.pitchSpeed <= 0 ? pitch : clamp(next[0], limits.minPitch, limits.maxPitch);
            yaw = limits.yawSpeed <= 0 ? yaw : clamp(next[1], limits.minYaw, limits.maxYaw);
            pitchVelocity = vp;
            yawVelocity = vy;
        }
    }

    /** 固定视线的极点转轴，以及边界路线的单轴协调执行。 */
    private void joint(double targetPitch, double targetYaw, Limits limits) {
        double vp = signedCap(targetPitch - pitch, pitchVelocity, limits.pitchSpeed, limits.accelerationSeconds);
        double vy = signedCap(targetYaw - yaw, yawVelocity, limits.yawSpeed, limits.accelerationSeconds);
        pitch = limits.pitchSpeed <= 0 ? pitch : clamp(pitch + vp / STEPS, limits.minPitch, limits.maxPitch);
        yaw = limits.yawSpeed <= 0 ? yaw : clamp(yaw + vy / STEPS, limits.minYaw, limits.maxYaw);
        pitchVelocity = vp;
        yawVelocity = vy;
    }

    /** 保持速度连续的制动；机械硬止挡优先于惯性。 */
    private void brake(Limits limits) {
        pitchVelocity = approach(pitchVelocity, 0, acceleration(limits.pitchSpeed, limits.accelerationSeconds) / STEPS);
        yawVelocity = approach(yawVelocity, 0, acceleration(limits.yawSpeed, limits.accelerationSeconds) / STEPS);
        double p = pitch + pitchVelocity / STEPS, y = yaw + yawVelocity / STEPS;
        pitch = clamp(p, limits.minPitch, limits.maxPitch);
        yaw = clamp(y, limits.minYaw, limits.maxYaw);
        if (pitch != p) pitchVelocity = 0;
        if (yaw != y) yawVelocity = 0;
    }

    /** 从两组等价角选择区间内且机械运动最短的一组，绝不先折回再夹断有限轴。 */
    private static double[] reachable(Vec3 target, double referencePitch, double referenceYaw, Limits limits) {
        double horizontal = Math.hypot(target.x, target.z);
        double p = Math.toDegrees(Math.atan2(-target.y, horizontal));
        double y = horizontal < POLE_EPSILON ? referenceYaw : Math.toDegrees(Math.atan2(-target.x, target.z));
        double[] best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int branch = 0; branch < 2; branch++) {
            double cp = periodInRange(branch == 0 ? p : 180 - p, referencePitch, limits.minPitch, limits.maxPitch);
            double cy = periodInRange(y + branch * 180, referenceYaw, limits.minYaw, limits.maxYaw);
            if (!Double.isFinite(cp) || !Double.isFinite(cy)) continue;
            double value = square(cp - referencePitch) + square(cy - referenceYaw);
            if (value < score) { score = value; best = new double[]{cp, cy}; }
        }
        return best;
    }

    /** 连续视线弧的局部解角；禁止在相邻采样间隐式跳到半圈外的机械分支。 */
    private static double[] angles(Vec3 direction, double p, double y, Limits limits) {
        double[] result = reachable(direction, p, y, limits);
        return result != null && Math.abs(result[0] - p) <= 90 && Math.abs(result[1] - y) <= 90 ? result : null;
    }

    /** 只检查范围与分支，不在此处使用每刻速度；精确极点由专门转轴阶段处理。 */
    private boolean arcReachable(Vec3 from, Vec3 to, Limits limits) {
        double p = pitch, y = yaw;
        for (int i = 1; i <= 8; i++) {
            double[] next = angles(arc(from, to, i / 8.0), p, y, limits);
            if (next == null) return false;
            p = next[0]; y = next[1];
        }
        return true;
    }

    /** 球面视线插值；反向目标使用当前机械方向确定一条连续弧。 */
    private static Vec3 arc(Vec3 from, Vec3 to, double amount) {
        Vec3 a = from.normalize(), b = to.normalize();
        double dot = clamp(a.dot(b), -1, 1);
        if (dot > 0.999999999) return a.scale(1 - amount).add(b.scale(amount)).normalize();
        if (dot < -0.999999999) {
            Vec3 axis = Math.abs(a.y) < 0.9 ? a.cross(new Vec3(0, 1, 0)).normalize() : a.cross(new Vec3(1, 0, 0)).normalize();
            return a.scale(Math.cos(Math.PI * amount)).add(axis.scale(Math.sin(Math.PI * amount)));
        }
        double theta = Math.acos(dot), sin = Math.sin(theta);
        return a.scale(Math.sin((1 - amount) * theta) / sin).add(b.scale(Math.sin(amount * theta) / sin));
    }

    /** 按机械区间挑选同一角度的展开值；无限轴自然得到最近的一圈。 */
    private static double periodInRange(double angle, double reference, double min, double max) {
        double result = angle + 360 * Math.rint((reference - angle) / 360);
        if (result < min) result += 360 * Math.ceil((min - result) / 360);
        if (result > max) result -= 360 * Math.ceil((result - max) / 360);
        return result >= min - EPSILON && result <= max + EPSILON ? clamp(result, min, max) : Double.NaN;
    }

    /** 在加速预算和到达目标所需制动距离之间取较小速度。 */
    private static double velocityCap(double velocity, double distance, double speed, double seconds) {
        double a = acceleration(speed, seconds);
        return Math.max(0, Math.min(Math.min(Math.max(0, speed), Math.abs(velocity) + a / STEPS),
                Math.sqrt(a * a / (STEPS * STEPS) + 2 * a * Math.abs(distance)) - a / STEPS));
    }

    /** 单轴速度先制动再反向，最后一个细分步准确停在目标。 */
    private static double signedCap(double distance, double velocity, double speed, double seconds) {
        double desired = Math.copySign(Math.min(Math.abs(distance) * STEPS,
                velocityCap(velocity, distance, speed, seconds)), distance);
        return approach(velocity, desired, acceleration(speed, seconds) / STEPS);
    }

    /** 约束参数统一为有限非负数，避免非法数据污染整条轨迹。 */
    private static double acceleration(double speed, double seconds) {
        return Math.max(0, speed) / Math.max(0.01, seconds * 20);
    }

    /** 按本体同一 Y-X 顺序由机械角构造双精度方向，不使用角度查表。 */
    public static Vec3 direction(double pitch, double yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** 变换世界方向，保留双精度输入。 */
    public static Vec3 transform(Quaterniondc q, Vec3 direction) {
        var v = new Quaterniond(q).transform(new org.joml.Vector3d(direction.x, direction.y, direction.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** 将世界请求转换回当前基座；不依赖世界水平假设。 */
    public static Vec3 inverse(Quaterniondc q, Vec3 direction) { return transform(new Quaterniond(q).conjugate(), direction); }

    /** 读取无副作用的机械轨迹。 */
    public double pitch(double partial) { return sample(pitchPath, partial); }
    /** 读取无副作用的偏航轨迹。 */
    public double yaw(double partial) { return sample(yawPath, partial); }
    /** 读取无副作用的画面横轴参考；调用方再对齐本帧实际视线。 */
    public Quaterniond view(double partial) {
        double time = clamp(partial, 0, 1) * STEPS;
        int i = Math.min(STEPS - 1, (int) time);
        return new Quaterniond(viewPath[i]).slerp(viewPath[i + 1], time - i).normalize();
    }
    /** 返回当前执行阶段。 */
    public Phase phase() { return phase; }
    /** 是否已有真实机械初态。 */
    public boolean initialized() { return initialized; }
    /** 保存完整实际轨迹，用于网络快照和预测回放。 */
    public Snapshot snapshot() {
        double[] views = new double[(STEPS + 1) * 4];
        for (int i = 0; i <= STEPS; i++) {
            Quaterniond q = viewPath[i];
            views[i * 4] = q.x; views[i * 4 + 1] = q.y; views[i * 4 + 2] = q.z; views[i * 4 + 3] = q.w;
        }
        return new Snapshot(pitchVelocity, yawVelocity, phase, pitchPath, yawPath, views);
    }
    /** 只接收已经完成长度与有限性检查的权威轨迹。 */
    public void restore(Snapshot snapshot) {
        System.arraycopy(snapshot.pitches, 0, pitchPath, 0, STEPS + 1);
        System.arraycopy(snapshot.yaws, 0, yawPath, 0, STEPS + 1);
        for (int i = 0; i <= STEPS; i++) viewPath[i] = new Quaterniond(snapshot.views[i * 4],
                snapshot.views[i * 4 + 1], snapshot.views[i * 4 + 2], snapshot.views[i * 4 + 3]).normalize();
        pitch = pitchPath[STEPS]; yaw = yawPath[STEPS];
        pitchVelocity = snapshot.pitchVelocity; yawVelocity = snapshot.yawVelocity;
        phase = snapshot.phase; initialized = true;
    }
    /** 同一刻轨迹按固定小段采样，模型和相机共用。 */
    private static double sample(double[] path, double partial) {
        double time = clamp(partial, 0, 1) * STEPS;
        int i = Math.min(STEPS - 1, (int) time);
        return path[i] + (path[i + 1] - path[i]) * (time - i);
    }
    private static double square(double value) { return value * value; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double approach(double value, double target, double step) { return value + clamp(target - value, -step, step); }
}
