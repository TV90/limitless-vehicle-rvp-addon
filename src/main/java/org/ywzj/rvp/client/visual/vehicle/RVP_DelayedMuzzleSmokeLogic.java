package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Snapshot;

import static org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter.*;

/** 延时白烟的生成、运动与消散计算；正式粒子和预览使用同一路径，不依赖新美术资产。 */
public final class RVP_DelayedMuzzleSmokeLogic {
    /** 最大生成距离，与原有延时白烟保持一致。 */
    public static final double MAX_DISTANCE = 256;
    /** 近处保持完整烟量，从此距离开始向远处保留比例过渡。 */
    private static final double DETAIL_START_DISTANCE = 64;
    /** 口径缩放的美术基准，单位毫米；不是武器 ID 或白名单。 */
    private static final double REFERENCE_CALIBER = 125;

    private RVP_DelayedMuzzleSmokeLogic() { }

    /** 单枚实际生成参数；管理器把这些数值交给既有自绘烟管线。 */
    public record Spawn(
            /** 世界坐标出生点。 */ Vec3 position,
            /** 初速度，单位格/刻。 */ Vec3 velocity,
            /** 烟片基准半尺寸，单位格。 */ float size,
            /** 出生浓度上限。 */ float alpha,
            /** 单枚寿命，单位刻。 */ int lifetime) { }

    /** 每次喷烟任务独立累积小数烟量，避免低密度或远景烟被整数截断。 */
    public static final class EmissionCounter {
        /** 未满一枚的剩余烟量；预算超限丢弃的整枚数量不在之后补发。 */
        private double remainder;

        public int next(Snapshot settings, double strength, double distance) {
            // 调用正式距离减量曲线，测试和真实队列使用完全相同的累积行为。
            remainder += settings.value(PARTICLES_PER_TICK) * strength * distanceDensity(settings, distance);
            int count = (int) Math.floor(remainder + 1.0E-9);
            remainder = Math.max(0, remainder - count);
            return count;
        }
    }

    /** 起烟平滑增强，再按幂次泄劲；默认返回原先的恒定强度。 */
    public static double emissionStrength(Snapshot settings, int age, int duration) {
        double progress = clamp(age / (double) Math.max(1, duration - 1), 0, 1);
        double attackTicks = settings.value(EMIT_ATTACK_TICKS);
        double attack = attackTicks == 0 ? 1 : smoothstep((age + 1) / attackTicks);
        double power = settings.value(EMIT_DECAY_POWER);
        double end = settings.value(EMIT_END_STRENGTH);
        return attack * (power == 0 ? 1 : end + (1 - end) * Math.pow(1 - progress, power));
    }

    /** 只按真实口径缩放；影响为 0 时完全保留统一大小。 */
    public static double caliberScale(Snapshot settings, float caliber) {
        return Math.pow(clamp(caliber / REFERENCE_CALIBER, 0.25, 4), settings.value(CALIBER_INFLUENCE));
    }

    /** 距离只减少新烟数量，不缩短已有烟团寿命。 */
    public static double distanceDensity(Snapshot settings, double distance) {
        double progress = clamp((distance - DETAIL_START_DISTANCE) / (MAX_DISTANCE - DETAIL_START_DISTANCE), 0, 1);
        return 1 + (settings.value(FAR_DENSITY) - 1) * progress;
    }

    /** 按实时炮口、当前强度及不可变快照生成一枚烟。 */
    public static Spawn sample(Snapshot settings, float caliber, Vec3 muzzle, Vec3 axis,
                               Vec3 muzzleVelocity, int index, int count, double strength, RandomSource random) {
        Vec3 side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-8) { side = axis.cross(new Vec3(1, 0, 0)); }
        side = side.normalize();
        Vec3 cross = axis.cross(side).normalize();
        // 调用口径比例计算，让出生范围与烟团尺寸使用同一尺度。
        double scale = caliberScale(settings, caliber);
        double along = settings.value(SOURCE_LENGTH) * scale * (count <= 1 ? 0 : index / (double) (count - 1));
        double radius = settings.value(SOURCE_RADIUS) * scale;
        Vec3 position = muzzle.add(axis.scale(along))
                .add(side.scale((random.nextDouble() * 2 - 1) * radius))
                .add(cross.scale((random.nextDouble() * 2 - 1) * radius));
        // 调用快照初速范围；泄劲时减速，炮口运动只在出生时继承一次。
        double speed = settings.randomDouble(AXIAL_SPEED_MIN, AXIAL_SPEED_MAX, random) * (0.2 + 0.8 * strength);
        Vec3 velocity = axis.scale(speed).add(0, settings.value(UPDRAFT), 0)
                .add(muzzleVelocity.scale(settings.value(VELOCITY_INHERITANCE)))
                .add(side.scale((random.nextDouble() - 0.5) * settings.value(SPREAD)))
                .add(cross.scale((random.nextDouble() - 0.5) * settings.value(SPREAD)));
        double variation = settings.value(VARIATION);
        float size = (float) (settings.value(PARTICLE_SIZE) * scale * (1 + (random.nextDouble() * 2 - 1) * variation));
        float alpha = (float) clamp(settings.value(ALPHA) * strength
                * (1 + (random.nextDouble() * 2 - 1) * variation), 0, 1);
        // 调用逐枚随机寿命，修复排队参数没有传入实际白烟的断链。
        int lifetime = settings.randomInt(PARTICLE_LIFETIME_MIN, PARTICLE_LIFETIME_MAX, random);
        return new Spawn(position, velocity, size, alpha, lifetime);
    }

    /** 膨胀与浓度稀释共用这一条尺寸曲线。 */
    public static double sizeMultiplier(Snapshot settings, double progress) {
        return settings.value(INITIAL_SIZE_FACTOR) + settings.value(GROWTH_AMOUNT) * clamp(progress, 0, 1);
    }

    /** 同时考虑出生淡入、寿命淡出和膨胀稀释，不修改贴图。 */
    public static double alphaMultiplier(Snapshot settings, int age, int lifetime) {
        double progress = clamp(age / (double) Math.max(1, lifetime), 0, 1);
        double fadeIn = settings.value(FADE_IN_TICKS);
        double start = settings.value(FADE_START);
        double alpha = fadeIn == 0 ? 1 : smoothstep(age / fadeIn);
        if (progress >= 1) { return 0; }
        if (start < 1 && progress > start) { alpha *= (1 - progress) / (1 - start); }
        // 调用同一尺寸函数，以相对出生面积变化近似烟雾散开变薄。
        return alpha * Math.pow(settings.value(INITIAL_SIZE_FACTOR) / sizeMultiplier(settings, progress),
                settings.value(EXPANSION_FADE));
    }

    /** 一刻的阻力、上浮和旋涡；向下喷出时也保留平滑转向过程。 */
    public static Vec3 velocityStep(Snapshot settings, Vec3 velocity, Vec3 position, double worldTime,
                                     int age, double swayPhase) {
        double drag = settings.value(HORIZONTAL_DRAG);
        double wave = age * 0.07 + swayPhase;
        // 调用连续旋涡场，相邻烟团采样同一空间和世界时间，不逐枚独立抽签抖动。
        Vec3 curl = settings.value(CURL_STRENGTH) == 0 ? Vec3.ZERO
                : curl(position, worldTime * 0.035 * settings.value(CURL_RATE), settings.value(CURL_SCALE))
                .scale(settings.value(CURL_STRENGTH));
        return new Vec3(
                velocity.x + (settings.value(WIND_X) - velocity.x) * drag + Math.sin(wave) * settings.value(SPREAD),
                velocity.y + (settings.value(UPDRAFT) - velocity.y) * settings.value(UPDRAFT_RESPONSE),
                velocity.z + (settings.value(WIND_Z) - velocity.z) * drag + Math.cos(wave) * settings.value(SPREAD))
                .add(curl);
    }

    /**
     * 三个平滑三角函数组成势场，再取旋度；可理解为连续小旋涡气流。
     * 解析求导并统一缩放，不逐点归一化，保持相邻气流连续。
     */
    public static Vec3 curl(Vec3 position, double time, double scale) {
        double frequency = Math.PI * 2 / scale;
        double x = position.x * frequency;
        double y = position.y * frequency;
        double z = position.z * frequency;
        return new Vec3(
                -0.5 * (Math.sin(x - 0.6 * time) * Math.sin(y - 0.8 * time)
                        + Math.cos(z + 0.8 * time) * Math.cos(x + time)),
                -0.5 * (Math.sin(y + time) * Math.sin(z - 0.7 * time)
                        + Math.cos(x - 0.6 * time) * Math.cos(y - 0.8 * time)),
                -0.5 * (Math.sin(z + 0.8 * time) * Math.sin(x + time)
                        + Math.cos(y + time) * Math.cos(z - 0.7 * time)));
    }

    /** 分别混合方块光和天空光位段，不能直接插值打包整数。 */
    public static int blendLight(int original, int environment, double mix) {
        int block = (int) Math.round(((original >> 4) & 15) * (1 - mix) + ((environment >> 4) & 15) * mix);
        int sky = (int) Math.round(((original >> 20) & 15) * (1 - mix) + ((environment >> 20) & 15) * mix);
        return block << 4 | sky << 20;
    }

    private static double smoothstep(double value) {
        double t = clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
