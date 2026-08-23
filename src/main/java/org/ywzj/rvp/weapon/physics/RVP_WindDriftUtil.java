package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_WindData;

/**
 * 服务器弹体风漂积分工具；只改变速度，不负责重力、阻力或位置积分。
 */
public final class RVP_WindDriftUtil {

    private RVP_WindDriftUtil() {}

    /**
     * 让当前速度按响应比例趋近目标风速，并叠加由实体种子和飞行 Tick 派生的确定性水平扰动。
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
        double phase = seed * 0.000_000_119_209_289_6D + Math.max(flightTick, 0) * 0.754_877_666D;
        double noiseX = Math.sin(phase * 1.618_033_989D);
        double noiseZ = Math.cos(phase * 2.414_213_562D);
        return next.add(noiseX * turbulence, 0.0D, noiseZ * turbulence);
    }
}
