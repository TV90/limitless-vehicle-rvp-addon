package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Snapshot;

import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter.*;

/** 殉燃的纯数值计算；控制器和粒子共用，不引用客户端世界、显卡或声音对象。 */
public final class RVP_WreckCookoffDynamics {
    private RVP_WreckCookoffDynamics() { }

    /** 火星轻重差异只影响尺寸、初速、寿命与空气阻力，各类仍承受同一重力。 */
    public enum SparkKind {
        /** 细小颗粒更快冷却、容易被气流带走。 */
        SMALL(0.45, 0.65, 0.35, 1.7),
        /** 中等颗粒采用面板的基准数值。 */
        MEDIUM(1, 1, 1, 1),
        /** 较大碎屑滞空和发光较久，受空气阻力较小。 */
        HEAVY(1.8, 0.8, 1.4, 0.5);

        /** 相对于火星基准尺寸的倍率。 */
        public final double size;
        /** 相对于基准初速度的倍率。 */
        public final double speed;
        /** 相对于基准寿命的倍率。 */
        public final double lifetime;
        /** 相对于基准空气阻力的倍率。 */
        public final double drag;

        SparkKind(double size, double speed, double lifetime, double drag) {
            this.size = size;
            this.speed = speed;
            this.lifetime = lifetime;
            this.drag = drag;
        }
    }

    /** 连续、不逐刻重掷的压力起伏；输出始终在1-强度至1之间，0强度严格返回1。 */
    public static double pressure(long tick, double phase, Snapshot settings) {
        double strength = settings.value(PULSE_STRENGTH);
        if (strength == 0) { return 1; }
        double time = tick * (Math.PI * 2 / settings.value(PULSE_PERIOD));
        double modulation = settings.value(PULSE_IRREGULARITY) * Math.sin(time * 0.37 + phase * 1.7);
        double wave = 0.5 + 0.5 * Math.sin(time + phase + modulation);
        return 1 - strength + strength * Math.pow(unit(wave), settings.value(PULSE_SHARPNESS));
    }

    /** 根部、中段、末端配色，位置和年龄共同推进；0混合严格保留原随机橙色。 */
    public static Vec3 flameColor(double axialProgress, double ageProgress, Vec3 original, Snapshot settings) {
        double progress = unit(Math.max(axialProgress, ageProgress * 0.65));
        Vec3 root = new Vec3(1, settings.value(ROOT_GREEN), settings.value(ROOT_BLUE));
        Vec3 mid = new Vec3(1, settings.value(MID_GREEN), settings.value(MID_BLUE));
        Vec3 tip = new Vec3(1, settings.value(TIP_GREEN), settings.value(TIP_BLUE));
        Vec3 layered = progress <= 0.5 ? root.lerp(mid, progress * 2) : mid.lerp(tip, progress * 2 - 1);
        return original.lerp(layered, settings.value(FLAME_GRADIENT)).scale(settings.value(FLAME_BRIGHTNESS));
    }

    /** 寿命淡出与空间末端淡出独立，循环模式不应用末端消散。 */
    public static double flameFade(double ageProgress, double axialProgress, Snapshot settings) {
        double result = fade(ageProgress, settings.value(FLAME_FADE_START));
        return settings.enabled(NATURAL_TAIL) ? result * fade(axialProgress, settings.value(TIP_FADE_START)) : result;
    }

    /** 连续共享气流：相邻火焰一起翻卷；近口沿喷口，远端可渐向世界上方抬升。 */
    public static Vec3 flameAcceleration(Vec3 position, Vec3 axis, double axialProgress,
                                         long worldTick, Snapshot settings) {
        Vec3 direction = axis.lerp(new Vec3(0, 1, 0), unit(axialProgress) * settings.value(WORLD_UP_MIX));
        direction = direction.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : direction.normalize();
        Vec3 acceleration = direction.scale(settings.value(FLAME_BUOYANCY))
                .add(settings.value(WIND_X) * 0.035, 0, settings.value(WIND_Z) * 0.035);
        double strength = settings.value(CURL_STRENGTH);
        if (strength == 0) { return acceleration; }
        double spatial = Math.PI * 2 / settings.value(CURL_SCALE);
        double time = worldTick * 0.05 * settings.value(CURL_RATE);
        double x = position.x * spatial, y = position.y * spatial, z = position.z * spatial;
        // 每个分量不依赖自身坐标，形成连续无散度扰动，避免每颗粒子各抖各的。
        return acceleration.add(new Vec3(Math.sin(z - time) - Math.cos(y + time),
                Math.sin(x + time) - Math.cos(z - time), Math.sin(y - time) - Math.cos(x + time)).scale(strength * 0.5));
    }

    /** 归一化权重抽样；全部为0时使用中等火星，避免空集合和除零。 */
    public static SparkKind sparkKind(double random, Snapshot settings) {
        double small = settings.value(SMALL_WEIGHT), medium = settings.value(MEDIUM_WEIGHT);
        double total = small + medium + settings.value(HEAVY_WEIGHT);
        if (total == 0) { return SparkKind.MEDIUM; }
        double pick = Math.min(Math.nextDown(1.0), unit(random)) * total;
        return pick < small ? SparkKind.SMALL : pick < small + medium ? SparkKind.MEDIUM : SparkKind.HEAVY;
    }

    /** 默认保持原有倒数分布，细小火星缩短发光、较大碎屑延长。 */
    public static int sparkLifetime(double random, SparkKind kind, Snapshot settings) {
        double maximum = settings.value(SPARK_LIFETIME_MAX);
        int base = (int) (maximum * 0.2 / (unit(random) * 0.8 + 0.2));
        double bounded = Math.max(settings.value(SPARK_LIFETIME_MIN), Math.min(maximum, base));
        return Math.max(4, (int) Math.round(bounded * kind.lifetime));
    }

    /** 从新生色向冷却色连续过渡，并按冷却量衰减能量；红色通道固定为1再统一乘亮度。 */
    public static Vec3 sparkColor(double ageProgress, Snapshot settings) {
        double cooling = settings.value(SPARK_COOLING) * Math.pow(unit(ageProgress), settings.value(SPARK_COOLING_POWER));
        Vec3 hot = new Vec3(1, settings.value(SPARK_HOT_GREEN), settings.value(SPARK_HOT_BLUE));
        Vec3 cold = new Vec3(1, settings.value(SPARK_COLD_GREEN), settings.value(SPARK_COLD_BLUE));
        return hot.lerp(cold, cooling).scale(settings.value(SPARK_BRIGHTNESS) * (1 - cooling * 0.85));
    }

    /** 只拉伸相机画面内的运动分量；朝相机飞行不制造横向长线，长度与帧率无关。 */
    public static Vec3 projectedSparkTrail(Vec3 velocity, Quaternionf cameraRotation, Snapshot settings) {
        Vector3f local = velocity.toVector3f().rotate(new Quaternionf(cameraRotation).conjugate());
        double speed = Math.hypot(local.x, local.y);
        if (speed < 1.0E-8) { return Vec3.ZERO; }
        double length = Math.min(settings.value(SPARK_TRAIL_MAX), speed * 20 * settings.value(SPARK_TRAIL_TIME));
        return new Vec3(local.x / speed * length, local.y / speed * length, 0);
    }

    /** 生命周期末段的线性衰减，start=1时不产生除零。 */
    public static double fade(double progress, double start) {
        if (progress >= 1) { return 0; }
        return progress <= start ? 1 : unit((1 - progress) / Math.max(1.0E-9, 1 - start));
    }

    private static double unit(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
}
