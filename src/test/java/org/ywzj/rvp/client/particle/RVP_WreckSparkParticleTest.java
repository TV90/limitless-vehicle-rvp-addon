package org.ywzj.rvp.client.particle;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 殉燃火星物理的纯数学验证：只覆盖不需要客户端世界的静态部分
 * （重力积分、落地弹跳响应、速度/寿命取值）。
 *
 * <p>覆盖边界：粒子的碰撞位移走基类 {@code Particle.move}（需要真实世界与包围盒），
 * 本测试只验证"基类判定触地之后"的速度响应，以及发射参数的取值范围；
 * 出生盒底部对齐、落地位置贴合两项依赖真实客户端世界，属实机验收项。</p>
 */
class RVP_WreckSparkParticleTest {

    /** 判定浮点相等时使用的误差，数量级取火星单 tick 位移的千分之一。 */
    private static final double EPSILON = 1.0E-9;

    @Test
    void gravityMatchesIndependentFreeFallFormula() {
        double velocity = 0.6;
        double position = 0.0;
        int ticks = 20;
        for (int tick = 0; tick < ticks; tick++) {
            // 与 RVP_WreckSparkParticle.tick 相同的顺序：先加竖直速度，再用末速度位移
            velocity -= RVP_WreckSparkParticle.GRAVITY_PER_TICK;
            position += velocity;
        }
        // 独立基准：与循环里的逐 tick 累加不同，这里用解析式直接算
        // v(t) = v0 - g·t；因循环是"先加速再位移"的欧拉积分，位移解析式为 v0·t - g·t(t+1)/2
        double t = ticks;
        double analyticVelocity = 0.6 - RVP_WreckSparkParticle.GRAVITY_PER_TICK * t;
        double discretePosition = 0.6 * t - RVP_WreckSparkParticle.GRAVITY_PER_TICK * t * (t + 1) / 2.0;
        assertEquals(analyticVelocity, velocity, EPSILON);
        assertEquals(discretePosition, position, EPSILON);
        // 反向校验离散化修正确实存在且非零：解析位移与离散位移相差 g·t/2
        double analyticPosition = 0.6 * t - RVP_WreckSparkParticle.GRAVITY_PER_TICK * t * t / 2.0;
        assertEquals(RVP_WreckSparkParticle.GRAVITY_PER_TICK * t / 2.0,
                analyticPosition - position, EPSILON);
        // 初速 0.6、重力 0.04 时 20 tick 内已明显转为下落，构成"喷出后下坠"的弧线
        assertTrue(velocity < 0.0);
    }

    @Test
    void freeFlightLeavesIntegratedVelocityUntouched() {
        // 未触地时响应函数必须原样交还本 tick 的重力积分结果，否则抛物线会被破坏
        RVP_WreckSparkParticle.Contact free = RVP_WreckSparkParticle.respondToContact(
                0.5, -0.4001, -0.3, 1, false);
        assertEquals(0.5, free.xd(), EPSILON);
        assertEquals(-0.4001, free.yd(), EPSILON);
        assertEquals(-0.3, free.zd(), EPSILON);
        assertEquals(1, free.bounces());
    }

    @Test
    void groundContactReflectsFallingSpeedByRestitutionAndDampsHorizontally() {
        RVP_WreckSparkParticle.Contact contact = RVP_WreckSparkParticle.respondToContact(
                0.5, -0.4, -0.3, 0, true);
        assertEquals(0.5 * RVP_WreckSparkParticle.GROUND_FRICTION, contact.xd(), EPSILON);
        // Z 方向输入为负，触地只做水平衰减、不改变符号
        assertEquals(-0.3 * RVP_WreckSparkParticle.GROUND_FRICTION, contact.zd(), EPSILON);
        // 竖向按恢复系数反向：0.4 × 0.35 向上
        assertEquals(0.4 * RVP_WreckSparkParticle.GROUND_RESTITUTION, contact.yd(), EPSILON);
        assertTrue(contact.yd() > 0.0);
        assertEquals(1, contact.bounces());
    }

    @Test
    void bounceCountStopsAtConfiguredMaximum() {
        RVP_WreckSparkParticle.Contact contact = RVP_WreckSparkParticle.respondToContact(
                0.1, -0.4, 0.2, RVP_WreckSparkParticle.MAX_BOUNCES, true);
        // 已达上限：竖直停住不再起跳，水平保留滑行，弹跳计数不再增长
        assertEquals(0.0, contact.yd(), EPSILON);
        assertEquals(0.1, contact.xd(), EPSILON);
        assertEquals(0.2, contact.zd(), EPSILON);
        assertEquals(RVP_WreckSparkParticle.MAX_BOUNCES, contact.bounces());
    }

    @Test
    void slowDescentAboveGroundStopsVerticalMotionWithoutCountingBounce() {
        // 下落速度低于起跳阈值：贴地滑行而不是抖动式反复起跳；水平不再被额外摩擦
        double belowThreshold = RVP_WreckSparkParticle.MIN_BOUNCE_SPEED * 0.5;
        RVP_WreckSparkParticle.Contact slow = RVP_WreckSparkParticle.respondToContact(
                0.12, -belowThreshold, -0.34, 2, true);
        assertEquals(0.0, slow.yd(), EPSILON);
        assertEquals(0.12, slow.xd(), EPSILON);
        assertEquals(-0.34, slow.zd(), EPSILON);
        assertEquals(2, slow.bounces());
    }

    @Test
    void upwardContactNeverCountsAsGroundBounce() {
        // 上抛撞到方块顶/车底：竖直速度被基类清零，响应函数不得把它当成落地弹跳
        RVP_WreckSparkParticle.Contact ceiling = RVP_WreckSparkParticle.respondToContact(
                0.0, 0.5, 0.0, 0, true);
        assertEquals(0.0, ceiling.yd(), EPSILON);
        assertEquals(0, ceiling.bounces());
    }

    @Test
    void launchSpeedStaysWithinConfiguredRangeAndRejectsNonFiniteInput() {
        assertEquals(RVP_WreckSparkParticle.LAUNCH_SPEED_MIN, RVP_WreckSparkParticle.launchSpeed(0.0), EPSILON);
        assertEquals(RVP_WreckSparkParticle.LAUNCH_SPEED_MIN + RVP_WreckSparkParticle.LAUNCH_SPEED_RANGE,
                RVP_WreckSparkParticle.launchSpeed(1.0), EPSILON);
        // NaN / 无穷 / 负随机数都不得产生 NaN 速度或反向抛射
        assertEquals(RVP_WreckSparkParticle.LAUNCH_SPEED_MIN,
                RVP_WreckSparkParticle.launchSpeed(Double.NaN), EPSILON);
        assertEquals(RVP_WreckSparkParticle.LAUNCH_SPEED_MIN,
                RVP_WreckSparkParticle.launchSpeed(Double.POSITIVE_INFINITY), EPSILON);
        assertEquals(RVP_WreckSparkParticle.LAUNCH_SPEED_MIN,
                RVP_WreckSparkParticle.launchSpeed(-5.0), EPSILON);
        for (int i = 0; i <= 10; i++) {
            double speed = RVP_WreckSparkParticle.launchSpeed(i / 10.0);
            assertTrue(speed >= RVP_WreckSparkParticle.LAUNCH_SPEED_MIN
                            && speed <= RVP_WreckSparkParticle.LAUNCH_SPEED_MIN
                            + RVP_WreckSparkParticle.LAUNCH_SPEED_RANGE,
                    "抛射速度越界: " + speed);
        }
    }

    @Test
    void lifetimeKeepsEnoughTicksToShowFallAndBounce() {
        // 寿命下限必须长于"抛射速度上限 0.95 / 重力 0.04 ≈ 24 tick"才开始下落的时间
        assertEquals(RVP_WreckSparkParticle.MIN_LIFETIME, RVP_WreckSparkParticle.clampLifetime(0));
        assertTrue(RVP_WreckSparkParticle.clampLifetime(-100) >= RVP_WreckSparkParticle.MIN_LIFETIME);
        // 承受足够多的样本后必须同时探到分布两端：下限 MIN_LIFETIME 与上限 LIFETIME_BASE/0.2
        int upperBound = (int) (RVP_WreckSparkParticle.LIFETIME_BASE / 0.2);
        RandomSource random = RandomSource.create(20261006L);
        int minSeen = Integer.MAX_VALUE;
        int maxSeen = Integer.MIN_VALUE;
        for (int i = 0; i < 400; i++) {
            int lifetime = RVP_WreckSparkParticle.rollLifetime(random);
            assertTrue(lifetime >= RVP_WreckSparkParticle.MIN_LIFETIME, "寿命越界: " + lifetime);
            assertTrue(lifetime <= upperBound, "寿命越界: " + lifetime);
            minSeen = Math.min(minSeen, lifetime);
            maxSeen = Math.max(maxSeen, lifetime);
        }
        assertTrue(minSeen < upperBound / 2, "寿命分布没有探到短寿命端: " + minSeen);
        assertTrue(maxSeen > upperBound / 2, "寿命分布没有探到长寿命端: " + maxSeen);
    }
}
