package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_WindData;

/**
 * 服务器弹体风漂积分工具；只改变速度，不负责重力、阻力或位置积分。
 */
public final class RVP_WindDriftUtil {

    /** 把 64 位种子映射为双精度单位区间时保留的高位数量。 */
    private static final int UNIT_INTERVAL_SHIFT = 11;
    /** 53 位尾数对应的单位区间缩放系数。 */
    private static final double UNIT_INTERVAL_SCALE = 0x1.0p-53;
    /** 每枚子体相对配置频率的最小倍率。 */
    private static final double MIN_FREQUENCY_SCALE = 0.85D;
    /** 每枚子体相对配置频率的倍率跨度。 */
    private static final double FREQUENCY_SCALE_RANGE = 0.30D;
    /** 派生扰动初始相位时使用的独立盐值。 */
    private static final long PHASE_SALT = 0x6A09E667F3BCC909L;
    /** 派生扰动频率倍率时使用的独立盐值。 */
    private static final long FREQUENCY_SALT = 0xBB67AE8584CAA73BL;

    private RVP_WindDriftUtil() {}

    /**
     * 让当前速度按响应比例趋近目标风速，并叠加由实体种子和飞行 Tick 派生的平滑确定性水平扰动。
     *
     * @param velocity 当前弹体速度，单位格/Tick
     * @param windDirection 子体释放时固化的单位风向
     * @param data 风漂配置
     * @param seed 实体稳定种子
     * @param flightTick 当前飞行 Tick
     * @return 应在重力和阻力之前使用的新速度
     */
    public static Vec3 apply(Vec3 velocity, Vec3 windDirection, RVP_WindData data,
                             long seed, int flightTick) {
        if (velocity == null || data == null || !data.isEnabled()
                || windDirection == null || windDirection.lengthSqr() < 1.0E-10) {
            return velocity == null ? Vec3.ZERO : velocity;
        }
        Vec3 direction = windDirection.normalize();
        Vec3 target = direction.scale(data.getSpeed());
        double response = data.getResponse();
        double nextY = Math.abs(target.y) > 1.0E-10
                ? velocity.y + (target.y - velocity.y) * response
                : velocity.y;
        Vec3 next = velocity.add(
                (target.x - velocity.x) * response,
                nextY - velocity.y,
                (target.z - velocity.z) * response);

        double turbulence = data.getTurbulence();
        if (turbulence <= 0.0) {
            return next;
        }
        // 调用本项目共享相位解析：保持旧总速度风漂与独立风偏贡献使用同一确定性游移轨迹。
        double phase = resolveTurbulencePhase(seed, flightTick, data);
        return next.add(Math.cos(phase) * turbulence, 0.0D, Math.sin(phase) * turbulence);
    }

    /**
     * 更新独立风偏速度分量。扰动属于有界目标速度的一部分，不作为每 Tick 直接追加的冲量；
     * 调用方可把返回值与基础弹道、部署水平速度相加，而不会反向收敛或吞噬这些其他分量。
     *
     * @param contribution 当前独立风偏速度，单位格/Tick
     * @param windDirection 子体释放时固化的单位风向
     * @param data 风漂配置
     * @param seed 实体稳定种子
     * @param flightTick 当前飞行 Tick
     * @return 下一 Tick 的独立风偏速度
     */
    public static Vec3 updateContribution(Vec3 contribution, Vec3 windDirection, RVP_WindData data,
                                          long seed, int flightTick) {
        Vec3 current = contribution == null ? Vec3.ZERO : contribution;
        if (data == null || !data.isEnabled()
                || windDirection == null || windDirection.lengthSqr() < 1.0E-10) {
            return current;
        }
        Vec3 target = windDirection.normalize().scale(data.getSpeed());
        double turbulence = data.getTurbulence();
        if (turbulence > 0.0D) {
            double phase = resolveTurbulencePhase(seed, flightTick, data);
            target = target.add(Math.cos(phase) * turbulence, 0.0D, Math.sin(phase) * turbulence);
        }
        double response = data.getResponse();
        return current.add(target.subtract(current).scale(response));
    }

    /** 解析当前 Tick 的确定性扰动相位，供两种风漂积分模式共享。 */
    private static double resolveTurbulencePhase(long seed, int flightTick, RVP_WindData data) {
        double seedPhase = unitInterval(mix64(seed ^ PHASE_SALT)) * Math.PI * 2.0D;
        double rateScale = MIN_FREQUENCY_SCALE
                + unitInterval(mix64(seed ^ FREQUENCY_SALT)) * FREQUENCY_SCALE_RANGE;
        // 调用本项目风漂数据归一化接口：取得限制后的基础周期频率，防止非法配置污染速度。
        return seedPhase + Math.PI * 2.0D * data.getTurbulenceFrequency()
                * rateScale * Math.max(flightTick, 0);
    }

    /** 使用 SplitMix64 末端混洗，把相近实体种子稳定打散。 */
    private static long mix64(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    /** 把混洗后的无符号高 53 位映射到 {@code [0, 1)}。 */
    private static double unitInterval(long value) {
        return (value >>> UNIT_INTERVAL_SHIFT) * UNIT_INTERVAL_SCALE;
    }
}
