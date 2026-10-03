package org.ywzj.rvp.weapon.impact;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CMissileAirTargetImpactFragments;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.slf4j.Logger;

/** 服务端判定并广播导弹命中离地空中目标后的纯视觉碎片。 */
public final class RVP_MissileAirTargetImpactFragmentService {
    /** 无效速度平方阈值，避免零速方向归一化。 */
    private static final double MIN_IMPACT_SPEED_SQR = 1.0E-8D;
    /** 纯视觉消息参数异常时使用的日志器；异常不得中断服务端实体 Tick。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_MissileAirTargetImpactFragmentService() {
    }

    /**
     * 尝试发布一次命中视觉事件。
     *
     * <p>调用方只在实体直击已经进入爆炸流程时调用；本方法再次执行类型、离地和速度校验，
     * 使非空爆、近炸、方块命中和地面目标都安全跳过。</p>
     *
     * @param level 当前服务端世界
     * @param missile 命中的 RVP 弹体
     * @param target 实体命中的碰撞根目标
     * @param impactPosition 爆炸位置
     * @param impactVelocity 爆炸时刻导弹速度
     */
    public static void tryPublish(ServerLevel level, RVP_BaseBullet missile,
                                  @Nullable Entity target, Vec3 impactPosition,
                                  @Nullable Vec3 impactVelocity) {
        RVP_MissileAirTargetImpactFragmentSettings.Snapshot settings =
                RVP_MissileAirTargetImpactFragmentSettings.snapshot();
        if (!settings.enabled() || missile == null || target == null
                || missile.getWeaponKind() != RVP_EnumWeaponKind.MISSILE
                || !isAirVehicle(target) || target.onGround()
                || impactPosition == null || !RVP_MissileAirTargetImpactFragmentMath.isFinite(impactPosition)
                || impactVelocity == null
                || !RVP_MissileAirTargetImpactFragmentMath.isFinite(impactVelocity)
                || impactVelocity.lengthSqr() <= MIN_IMPACT_SPEED_SQR) {
            return;
        }
        Vec3 initialVelocity = impactVelocity.scale(settings.speedScale());
        if (RVP_MissileAirTargetImpactFragmentMath.isStopped(initialVelocity, settings.stopSpeed())) {
            return;
        }
        Vec3 direction = initialVelocity.normalize();
        Vec3 farSide = RVP_MissileAirTargetImpactFragmentMath.resolveFarSide(
                target.getBoundingBox(), direction, settings.farSideOffset());
        ResourceLocation dimension = level.dimension().location();
        // 调用本项目数学辅助类，在 1～配置上限的闭区间内随机决定本次事件的碎片数量。
        int fragmentCount = RVP_MissileAirTargetImpactFragmentMath.randomFragmentCount(
                level.random, settings.count());
        S2CMissileAirTargetImpactFragments message;
        try {
            // 调用本项目网络消息构造器，把本次视觉事件的服务端参数快照封装为 S2C 数据。
            message = new S2CMissileAirTargetImpactFragments(
                    dimension,
                    farSide.x,
                    farSide.y,
                    farSide.z,
                    initialVelocity.x,
                    initialVelocity.y,
                    initialVelocity.z,
                    fragmentCount,
                    level.random.nextLong(),
                    level.getGameTime(),
                    settings.fragmentConeHalfAngleDegrees(),
                    settings.damping(),
                    settings.stopSpeed(),
                    settings.spawnSpread(),
                    settings.brownianStrength(),
                    settings.smokeInterval(),
                    settings.smokeSize(),
                    settings.smokeLifetime(),
                    settings.smokeStartAlpha(),
                    settings.smokeEndAlpha(),
                    settings.smokeLayers(),
                    settings.smokePointSpacing(),
                    settings.smokeRotationDegrees(),
                    settings.smokeLayerScaleVariance(),
                    settings.smokeMaxPointsPerTick(),
                    settings.maxLifetime(),
                    settings.broadcastRange());
        } catch (IllegalArgumentException exception) {
            // 视觉消息即使参数失配也只能跳过，不能让实体 Tick 抛出异常导致服务端崩溃。
            LOGGER.warn("[RVP] 跳过非法导弹空中目标碎片视觉消息: {}", exception.getMessage());
            return;
        }
        PacketDistributor.TargetPoint targetPoint = new PacketDistributor.TargetPoint(
                farSide.x, farSide.y, farSide.z, settings.broadcastRange(), level.dimension());
        // 调用 RVP 网络通道，把服务端快照发送给同维度附近客户端；碎片本身不进入服务端实体世界。
        RVP_Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> targetPoint), message);
    }

    /** 按本体空中载具类型判断目标是否为固定翼或旋翼载具。 */
    private static boolean isAirVehicle(Entity target) {
        return target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle;
    }
}
