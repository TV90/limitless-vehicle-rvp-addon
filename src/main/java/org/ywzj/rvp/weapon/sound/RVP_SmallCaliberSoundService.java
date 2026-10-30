package org.ywzj.rvp.weapon.sound;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CSmallCaliberSound;
import org.ywzj.rvp.weapon.data.RVP_Explosion;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** 小口径弹体声音的服务端出口：按真实飞行段和实际方块命中向附近客户端发送。 */
public final class RVP_SmallCaliberSoundService {

    /** 每发弹体已听到划空声的玩家；弱键允许弹体移除后由垃圾回收器清理。仅服务端主线程访问。 */
    private static final Map<RVP_BaseBullet, Set<UUID>> INCOMING_LISTENERS = new WeakHashMap<>();

    private RVP_SmallCaliberSoundService() {
    }

    /** 对飞行段附近的玩家各发送一次划空声；弹体停止前的实际碰撞点由调用方裁剪。 */
    public static void publishIncoming(RVP_BaseBullet projectile, Vec3 start, Vec3 end) {
        if (!(projectile.level() instanceof ServerLevel level)) {
            return;
        }
        // 调用统一物理口径规则，仅小口径弹体产生划空声。
        if (!RVP_SmallCaliberSoundPolicy.isSmallProjectile(projectile)) {
            return;
        }
        // 调用方向保护：穿透推进超过本 Tick 末端时，不把反向裁剪段当成真实飞行。
        if (!RVP_SmallCaliberSoundPolicy.isForwardSegment(start, end, projectile.getDeltaMovement())) {
            return;
        }
        Set<UUID> listeners = INCOMING_LISTENERS.get(projectile);
        Entity shooter = projectile.getOwner();
        // 调用本项目弹体访问器，排除发射者及同一发射载具上的乘员。
        AbstractVehicle shooterVehicle = projectile.getShooterVehicle();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player == shooter
                    || shooterVehicle != null && player.getRootVehicle() == shooterVehicle
                    || listeners != null && listeners.contains(player.getUUID())) {
                continue;
            }
            // 调用路径投影规则，用整段计算玩家耳部附近的最近点，不漏过高速弹。
            Vec3 soundPosition = RVP_SmallCaliberSoundPolicy.incomingPosition(start, end,
                    player.getEyePosition(), RVP_SmallCaliberSoundPolicy.INCOMING_DISTANCE);
            if (soundPosition == null) {
                continue;
            }
            int variant = level.random.nextInt(S2CSmallCaliberSound.Kind.INCOMING.variantCount());
            S2CSmallCaliberSound message = new S2CSmallCaliberSound(level.dimension().location(),
                    S2CSmallCaliberSound.Kind.INCOMING, soundPosition, variant);
            // 调用本项目网络通道，只向这名玩家发送近身划空声。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
            if (listeners == null) {
                listeners = new HashSet<>();
                INCOMING_LISTENERS.put(projectile, listeners);
            }
            listeners.add(player.getUUID());
        }
    }

    /** 服务端确认小口径弹体命中任意方块表面后，向命中点附近广播一次声音。 */
    public static void publishBlockImpact(RVP_BaseBullet projectile, Vec3 position) {
        if (!(projectile.level() instanceof ServerLevel level)
                || !RVP_SmallCaliberSoundPolicy.isSmallProjectile(projectile)) {
            return;
        }
        // 调用本项目武器配置解析器；爆炸已启用且半径 >= 1 格时避免落点声与爆音叠加。
        RVP_WeaponData data = projectile.getResolvedWeaponConfig();
        RVP_Explosion explosion = data == null ? null : data.getExplosionData();
        float caliber = projectile instanceof org.ywzj.rvp.entity.projectile.RVP_BulletEntity bullet
                ? bullet.getCaliber() : data.getEffectsData().getCaliber();
        // 调用统一落点规则，保留 explode=false 或半径小于 1 的声音。
        if (!RVP_SmallCaliberSoundPolicy.allowsBlockImpact(caliber,
                explosion != null && explosion.explode, explosion == null ? 0.0F : explosion.radius)) {
            return;
        }
        int variant = level.random.nextInt(S2CSmallCaliberSound.Kind.BLOCK_IMPACT.variantCount());
        S2CSmallCaliberSound message = new S2CSmallCaliberSound(level.dimension().location(),
                S2CSmallCaliberSound.Kind.BLOCK_IMPACT, position, variant);
        PacketDistributor.TargetPoint point = new PacketDistributor.TargetPoint(
                position.x, position.y, position.z, RVP_SmallCaliberSoundPolicy.IMPACT_DISTANCE, level.dimension());
        // 调用本项目网络通道，向同维度命中点附近的玩家广播同一音频样本。
        RVP_Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> point), message);
    }
}
