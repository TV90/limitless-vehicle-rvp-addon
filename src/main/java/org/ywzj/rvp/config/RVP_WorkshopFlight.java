package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.ywzj.rvp.wingsweep.RVP_WingSweepState;
import org.ywzj.vehicle.custom.vehicle.FixedWingVehicleDataPojo.FixedWingAttributes;
import org.ywzj.vehicle.util.PhysicsHelper;

import static org.ywzj.rvp.config.RVP_WorkshopData.number;
import static org.ywzj.rvp.config.RVP_WorkshopData.object;
import static org.ywzj.rvp.config.RVP_WorkshopData.text;

/** 档案用的完好、轻油、最大动力稳态估算。速度单位均为格/tick，只在载具数据同步时计算。 */
record RVP_WorkshopFlight(Double airspeed, Double climb) {
    /** 与本体空中每 tick 的重力扣减相同。 */
    private static final double G = PhysicsHelper.GRAVITY / PhysicsHelper.TICKS_PER_SECOND_SQUARED;
    /** 本体允许省略的迎角及加力系数沿用同一个反序列化类的初值。 */
    private static final FixedWingAttributes DEFAULTS = new FixedWingAttributes();

    static RVP_WorkshopFlight fixed(JsonObject data, JsonObject attr, Double mass) {
        Double thrust = number(attr, "thrust"), drag = number(attr, "air_drag_k_min");
        Double highDrag = number(attr, "air_drag_k_max"), lift = number(attr, "lift_to_drag_k");
        if (mass == null || mass <= 0 || thrust == null || thrust <= 0 || drag == null || drag <= 0
                || highDrag == null || highDrag < drag || lift == null || lift <= 0
                || !text(data, "thrust_part_id").isEmpty()) return new RVP_WorkshopFlight(null, null);
        double boost = fallback(number(attr, "thrust_k"), DEFAULTS.thrustK);
        double a = PhysicsHelper.accelerationPerTick(thrust * Math.max(1, boost), mass);
        double low = fallback(number(attr, "angle_of_attack_min"), DEFAULTS.angleOfAttackMin);
        double high = fallback(number(attr, "angle_of_attack_max"), DEFAULTS.angleOfAttackMax);
        if (high <= 0 || low > high) return new RVP_WorkshopFlight(null, null);
        double[] best = new double[2]; // 最大平飞速度、最大稳定垂直速度。
        scanWing(best, a, drag / mass, highDrag / mass, lift, low, high);
        JsonObject sweep = object(data, "wing_sweep");
        if (sweep != null && hasSweepControls(data)) {
            // 与本体上实际启用的后掠翼控制器共用配置解析；两种可保持的翼形分别取优。
            var cfg = RVP_WingSweepConfig.parse(sweep);
            scanWing(best, a, drag * cfg.dragKFactor() / mass, highDrag * cfg.dragKFactor() / mass,
                    lift * cfg.liftToDragKFactor(), low, high);
        }
        return new RVP_WorkshopFlight(best[0] > 0 ? best[0] : null, best[1] > 0 ? best[1] : null);
    }

    private static boolean hasSweepControls(JsonObject data) {
        JsonElement parts = data.get("parts");
        if (parts == null || !parts.isJsonArray()) return false;
        boolean manual = false, form = false;
        for (JsonElement entry : parts.getAsJsonArray()) {
            if (!entry.isJsonObject() || !text(entry.getAsJsonObject(), "type").endsWith(":switchable")) continue;
            String id = text(entry.getAsJsonObject(), "id");
            manual |= RVP_WingSweepState.PART_MANUAL_ID.equals(id);
            form |= RVP_WingSweepState.PART_FORM_ID.equals(id);
        }
        return manual && form;
    }

    private static double fallback(Double value, double defaultValue) { return value == null ? defaultValue : value; }

    /** 在纵向平面按本体的推力、阻力、升力转向、重力顺序反求稳态。高度效率可在本体允许范围内取值。 */
    private static void scanWing(double[] best, double thrust, double minDrag, double maxDrag,
                                 double liftRatio, double minAngle, double maxAngle) {
        for (int halfDegrees = -178; halfDegrees <= 178; halfDegrees++) {
            double angle = halfDegrees * 0.5, radians = Math.toRadians(angle);
            double sin = Math.sin(radians), cos = Math.cos(radians);
            double drag = minDrag + (maxDrag - minDrag) * Math.abs(sin);
            double ratio = angle >= minAngle && angle <= maxAngle ? liftRatio + 2 * angle / maxAngle
                    : liftRatio * Math.exp(-Math.max(minAngle - angle, angle - maxAngle));
            if (ratio <= 0) continue;
            double ax = thrust * cos, ay = thrust * sin;
            // w 为加推力之后、扣阻力之前的速度。超过此界，平飞或上升均不能维持。
            double upper = Math.sqrt(ax / drag);
            double previousRise = -1;
            for (int i = 1; i <= 240; i++) {
                double w = upper * i / 240;
                double rise = rise(w, drag, ax, thrust);
                if (rise > best[1] && canTrim(w, rise, ax, ay, sin, cos, drag, ratio)) best[1] = rise;
                if (rise <= 0 && previousRise > 0) {
                    double lo = upper * (i - 1) / 240, hi = w;
                    for (int n = 0; n < 28; n++) {
                        double mid = (lo + hi) / 2;
                        if (rise(mid, drag, ax, thrust) > 0) lo = mid; else hi = mid;
                    }
                    if (canTrim(hi, 0, ax, ay, sin, cos, drag, ratio))
                        best[0] = Math.max(best[0], Math.hypot(hi - ax, ay));
                }
                previousRise = rise;
            }
        }
    }

    /** 归一化升力只改变方向；由重力前后速度模长的平方差得到每 tick 可维持的垂直分量。 */
    private static double rise(double w, double drag, double ax, double thrust) {
        double loss = drag * w * w;
        return (2 * w * (ax - loss) + loss * loss - thrust * thrust - G * G) / (2 * G);
    }

    /** 检查达到上述速度方向所需的升力，是否落在本体从低空到升限的实际效率区间。 */
    private static boolean canTrim(double w, double rise, double ax, double ay, double sin, double cos,
                                   double drag, double liftRatio) {
        double previous = Math.hypot(w - ax, ay);
        if (previous == 0 || rise > previous) return false;
        double loss = drag * w * w, retained = w - loss;
        if (retained <= 0) return false;
        double sinPath = rise / previous, cosPath = Math.sqrt(Math.max(0, 1 - sinPath * sinPath));
        double horizontal = ((w - ax) * (previous + G * sinPath) + ay * G * cosPath) / previous;
        double vertical = ((w - ax) * G * cosPath - ay * (previous + G * sinPath)) / previous;
        double projection = cos * horizontal + sin * vertical;
        if (projection <= 0) return false;
        double airFactor = retained * vertical / (projection * loss * liftRatio);
        return airFactor >= 0.001 && airFactor <= 1;
    }

    /** 满总距下逐刻计算旋翼轴向和垂直于轴的速度，包含平移升力、上升折减、线性阻力及整机限速。 */
    static Double rotaryClimb(JsonObject attr, JsonObject physics, Double cap) {
        Double mass = number(physics, "mass"), rotor = number(attr, "main_rotor_force"), friction = number(physics, "friction");
        if (mass == null || mass <= 0 || rotor == null || rotor <= 0 || friction == null || friction < 0
                || cap == null || cap <= 0) return null;
        double thrust = PhysicsHelper.accelerationPerTick(rotor, mass);
        double damping = 1 - PhysicsHelper.accelerationPerTick(friction, mass);
        if (damping <= 0) return null;
        double best = 0;
        for (int halfDegrees = 0; halfDegrees < 180; halfDegrees++) {
            double tilt = Math.toRadians(halfDegrees * 0.5), cos = Math.cos(tilt), sin = Math.sin(tilt);
            double axial = 0, lateral = 0;
            for (int tick = 0; tick < 5000; tick++) {
                double efficiency = axial > 0 ? Math.min(1, 0.4 / axial) : Math.min(2, Math.max(1, -axial / 0.05));
                double beforeDrag = axial + thrust * efficiency + lateral * 0.005;
                double speed = Math.hypot(beforeDrag, lateral);
                double keep = speed > 0 ? Math.min(damping, cap / speed) : damping;
                double nextAxial = beforeDrag * keep - G * cos;
                double nextLateral = lateral * keep + G * sin;
                double change = Math.abs(nextAxial - axial) + Math.abs(nextLateral - lateral);
                axial = nextAxial;
                lateral = nextLateral;
                if (change < 1e-9) {
                    best = Math.max(best, axial * cos - lateral * sin);
                    break;
                }
            }
        }
        return best;
    }
}
