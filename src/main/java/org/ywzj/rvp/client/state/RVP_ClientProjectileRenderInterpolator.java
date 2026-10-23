package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * RVP 客户端弹体显示位置解析器。
 *
 * <p>本体广播克隆收包时会把原点和当前位置同时写成新样本，弹体的实际超视距渲染
 * 因此需要按最近速度短时外推。本类把该位置同时提供给实体渲染器、雷达航迹点和 HUD
 * 框，避免不同显示通道各自计算后出现相互错位。</p>
 */
public final class RVP_ClientProjectileRenderInterpolator {

    /** 广播样本之间允许使用速度外推的最大时长，单位为 Tick。 */
    public static final double MAX_EXTRAPOLATION_TICK = 5.0D;

    private RVP_ClientProjectileRenderInterpolator() {
    }

    /**
     * 解析实体包围盒中心的显示位置。
     *
     * <p>远距弹体优先使用本体广播克隆的短时外推；其它实体继续交给既有远距载具插值器，
     * 普通实体保持 Minecraft 的单 Tick 插值。扫描框、硬锁框及雷达盘航迹统一使用本入口，
     * 不允许用离散的雷达探测快照替代显示位置。</p>
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick) {
        Vec3 remoteAmmoCenter = resolveRemoteAmmoCenter(entity, partialTick);
        if (remoteAmmoCenter != null) {
            return remoteAmmoCenter;
        }
        // 调用本项目广播载具插值器，保留最大 OBB 瞄准点和远距载具显示轨迹语义。
        return RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(entity, partialTick);
    }

    /**
     * 为火控保留调用方指定的近距瞄准位置；HUD 应使用双参数入口，避免误用雷达探测快照。
     *
     * @param entity 当前火控目标实体
     * @param partialTick 当前 Tick 或帧进度
     * @param nonRemoteCenter 非远距目标的火控瞄准点；为空时使用单 Tick 包围盒中心插值
     * @return 远距目标的平滑中心，或原火控瞄准点
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick,
                                           @Nullable Vec3 nonRemoteCenter) {
        // 调用本项目弹体外推解析，为远距弹体提供连续轨迹。
        Vec3 remoteAmmoCenter = resolveRemoteAmmoCenter(entity, partialTick);
        if (remoteAmmoCenter != null) {
            return remoteAmmoCenter;
        }
        // 调用本类普通实体插值，仅在火控未提供瞄准点时补齐回退坐标。
        Vec3 fallback = nonRemoteCenter != null
                ? nonRemoteCenter : resolveVanillaCenter(entity, partialTick);
        // 调用本项目广播载具插值，保留现有火控对普通实体的瞄准语义。
        return RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(entity, partialTick, fallback);
    }

    /**
     * 解析反辐射 HUD 的雷达源位置，保留辐射源相对载具中心的偏移。
     *
     * @param entityId 辐射源所属载具 ID
     * @param emitterPosition 当前雷达源位置；实体已不可用时保持最后探测点
     * @param partialTick 当前渲染帧的局部 Tick
     * @return 沿既有实体平滑轨迹移动的雷达源位置
     */
    public static Vec3 resolveRadarEmitterPosition(int entityId, Vec3 emitterPosition, float partialTick) {
        // 调用本项目实体查找入口，兼容近距实体和远距广播载具。
        Entity entity = findClientEntity(Minecraft.getInstance(), entityId);
        if (entity == null || !entity.isAlive()) {
            return emitterPosition;
        }
        // 调用本项目瞄准点解析，取得与平滑器一致的当前中心，再保留雷达部件偏移。
        Vec3 center = org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(entity);
        // 调用本项目统一位置解析，使反辐射单/双框和导引头双圈沿同一轨迹移动。
        return resolveRenderCenter(entity, partialTick).add(emitterPosition.subtract(center));
    }

    /**
     * 解析本体广播克隆的实体原点，供 3D 弹体渲染器复用。
     *
     * @param minecraft 当前客户端实例
     * @param remote 本体广播实体记录
     * @param entity 广播记录绑定的实体对象
     * @param partialTick 当前渲染帧的局部 Tick
     * @return 外推后的实体原点
     */
    public static Vec3 resolveRenderOrigin(Minecraft minecraft,
                                           LocalVehiclePlayer.ServerEntity remote,
                                           Entity entity,
                                           float partialTick) {
        if (minecraft.player == null) {
            return entity.position();
        }
        int updateTick = remote.updateTick == null
                ? minecraft.player.tickCount
                : remote.updateTick;
        double age = Mth.clamp(minecraft.player.tickCount - updateTick + partialTick,
                0.0D, MAX_EXTRAPOLATION_TICK);
        return entity.position().add(entity.getDeltaMovement().scale(age));
    }

    /** 判断目标是否是绑定在本体 serverEntities 中的远距弹体克隆。 */
    public static boolean isRemoteProjectile(Entity entity) {
        return entity instanceof AmmoEntity && findRemoteEntity(entity) != null;
    }

    /**
     * 解析外置雷达条目的显示位置。
     *
     * <p>客户端能找到同 ID 实体时优先使用实体渲染轨迹；找不到实体时，所有条目都按
     * 快照接收时间和快照速度做最多 5 Tick 的短时外推，避免外置雷达框在快照周期内阶梯跳变。</p>
     */
    public static Vec3 resolveExternalRadarEntryPosition(S2CExternalRadarSnapshot.Entry entry,
                                                          float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity resolved = findClientEntity(minecraft, entry.entityId());
        if (resolved != null && resolved.isAlive()) {
            // 调用本项目统一显示位置解析，复用实体广播克隆的弹体外推或载具插值轨迹。
            return resolveRenderCenter(resolved, partialTick);
        }
        Vec3 position = entry.position();
        if (minecraft.level == null) {
            return position;
        }
        long sampleGameTime = RVP_ClientExternalRadarState.getSnapshotClientGameTime();
        if (sampleGameTime == Long.MIN_VALUE) {
            return position;
        }
        double age = Mth.clamp(minecraft.level.getGameTime() - sampleGameTime + partialTick,
                0.0D, MAX_EXTRAPOLATION_TICK);
        return position.add(entry.velocity().scale(age));
    }

    /** 解析一个远距 RVP/本体弹药广播克隆的包围盒中心。 */
    @Nullable
    private static Vec3 resolveRemoteAmmoCenter(Entity entity, float partialTick) {
        if (entity instanceof RVP_RemoteHbmMissileEntity) {
            // 调用本项目 HBM 快照时钟，复用远距弹体最多 5 Tick 的速度外推；不改代理真实坐标。
            long sampleTick = RVP_ClientHbmMissileState.getSnapshotGameTime(entity);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && entity.level() == minecraft.level && sampleTick != Long.MIN_VALUE) {
                double age = Mth.clamp(minecraft.level.getGameTime() - sampleTick + partialTick,
                        0.0D, MAX_EXTRAPOLATION_TICK);
                return entity.getBoundingBox().getCenter().add(entity.getDeltaMovement().scale(age));
            }
        }
        if (!(entity instanceof AmmoEntity)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer.ServerEntity remote = findRemoteEntity(entity);
        if (minecraft.level == null || minecraft.player == null || remote == null) {
            return null;
        }
        Vec3 renderOrigin = resolveRenderOrigin(minecraft, remote, entity, partialTick);
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return renderOrigin.add(centerOffset);
    }

    /** 查找当前实体对象对应的本体广播记录，防止同 ID 实体复用串入外推。 */
    @Nullable
    private static LocalVehiclePlayer.ServerEntity findRemoteEntity(Entity entity) {
        LocalVehiclePlayer.ServerEntity remote = LocalVehiclePlayer.instance.serverEntities.get(entity.getId());
        return remote != null && remote.entity == entity ? remote : null;
    }

    /** 在客户端世界和本体广播表中按实体 ID 查找外置雷达可用的实体表示。 */
    @Nullable
    private static Entity findClientEntity(Minecraft minecraft, int entityId) {
        if (minecraft.level != null) {
            Entity localEntity = minecraft.level.getEntity(entityId);
            if (localEntity != null) {
                return localEntity;
            }
        }
        LocalVehiclePlayer.ServerEntity remote = LocalVehiclePlayer.instance.serverEntities.get(entityId);
        return remote == null ? null : remote.entity;
    }

    /** 计算火控未提供回退点时的普通实体包围盒中心，保持既有语义。 */
    private static Vec3 resolveVanillaCenter(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return new Vec3(x, y, z).add(centerOffset);
    }
}
