package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.particle.RVP_WreckSparkParticle;
import org.ywzj.rvp.network.S2CVehicleImpactSparks;
import org.ywzj.rvp.weapon.impact.RVP_VehicleImpactSparks;

/** 复用带重力火星，按弹丸入射方向与载具/方块表面朝向生成飞溅。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_VehicleImpactSparkEffects {
    /** 单客户端每个游戏 tick 最多新增的火星总数，限制密集机炮命中的开销。 */
    private static final int MAX_PARTICLES_PER_TICK = 160;
    /** 当前预算所属世界；弱引用避免切服后静态字段保留旧世界。 */
    private static java.lang.ref.WeakReference<ClientLevel> budgetLevel = new java.lang.ref.WeakReference<>(null);
    /** 最近计数的游戏时刻，单位 tick。 */
    private static long budgetTick = Long.MIN_VALUE;
    /** 该 tick 已创建的火星数。 */
    private static int usedParticles;

    private RVP_VehicleImpactSparkEffects() {}

    /** 接收服务端命中快照；只在同维度、可视距离内生成，且遵守总粒子预算。 */
    public static void accept(S2CVehicleImpactSparks message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(message.dimension())) {
            return;
        }
        double distance = minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(message.position());
        if (distance > RVP_VehicleImpactSparks.VIEW_RANGE) {
            return;
        }
        if (budgetLevel.get() != level || budgetTick != level.getGameTime()) {
            budgetLevel = new java.lang.ref.WeakReference<>(level);
            budgetTick = level.getGameTime();
            usedParticles = 0;
        }
        // 调用统一口径规则；远处减数量而不放大粒子，保持口径间的尺寸关系。
        int desired = RVP_VehicleImpactSparks.sparkCount(message.caliber());
        int count = Math.min(distance > 96 ? Math.max(3, desired / 2) : desired,
                MAX_PARTICLES_PER_TICK - usedParticles);
        if (count <= 0) {
            return;
        }
        usedParticles += count;
        // 调用共用法线与尺寸计算，出生点略移到装甲外侧以避免被表面遮住。
        Vec3 normal = RVP_VehicleImpactSparks.outwardNormal(message.normal());
        float size = RVP_VehicleImpactSparks.sparkSize(message.caliber());
        Vec3 origin = message.position().add(normal.scale(size + 0.03));
        for (int index = 0; index < count; index++) {
            // 调用入射角与装甲面联合采样：正面命中向外散开，擦板命中沿前进方向贴板飞溅。
            Vec3 direction = RVP_VehicleImpactSparks.sparkDirection(normal, message.incomingDirection(),
                    level.random.nextDouble(), level.random.nextDouble());
            double speed = RVP_VehicleImpactSparks.sparkSpeed(message.caliber())
                    * (0.65 + level.random.nextDouble() * 0.7);
            float particleSize = size * (0.75f + level.random.nextFloat() * 0.5f);
            // 复用 RVP 自重火星的重力、方块碰撞、落地弹跳与淡出，纯视觉不造成额外伤害。
            RVP_WreckSparkParticle.spawn(minecraft.particleEngine, level, origin.x, origin.y, origin.z,
                    direction.x, direction.y, direction.z, speed, particleSize);
        }
    }
}
