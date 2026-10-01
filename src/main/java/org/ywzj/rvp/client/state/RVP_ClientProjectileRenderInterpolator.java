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
     * 普通实体保持 Minecraft 的单 Tick 插值。</p>
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick) {
        return resolveRenderCenter(entity, partialTick, resolveVanillaCenter(entity, partialTick));
    }

    /**
     * 按调用方提供的普通实体位置语义解析显示中心。
     *
     * @param entity 需要显示的实体
     * @param partialTick 当前渲染帧的局部 Tick
     * @param nonRemoteCenter 非远距弹体时的调用方位置；可为空，空时使用实体本体插值中心
     * @return 当前渲染帧应使用的包围盒中心
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick,
                                           @Nullable Vec3 nonRemoteCenter) {
        Vec3 remoteAmmoCenter = resolveRemoteAmmoCenter(entity, partialTick);
        if (remoteAmmoCenter != null) {
            return remoteAmmoCenter;
        }
        Vec3 fallback = nonRemoteCenter != null
                ? nonRemoteCenter
                : resolveVanillaCenter(entity, partialTick);
        // 调用本项目广播载具插值器，保持弹体之外的远距载具 HUD 轨迹行为不变。
        return RVP_ClientBroadcastVehicleInterpolator.resolveRenderCenter(entity, partialTick, fallback);
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
     * <p>客户端能找到同 ID 实体时优先使用实体渲染轨迹；找不到实体时，仅对弹体快照按
     * 快照接收时间做最多 5 Tick 外推，载具和其它雷达条目保持快照位置。</p>
     */
    public static Vec3 resolveExternalRadarEntryPosition(S2CExternalRadarSnapshot.Entry entry,
                                                          float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity resolved = findClientEntity(minecraft, entry.entityId());
        if (resolved != null && resolved.isAlive()) {
            // 调用本项目统一显示位置解析，复用实体广播克隆的弹体外推或载具插值轨迹。
            return resolveRenderCenter(resolved, partialTick, entry.position());
        }
        Vec3 position = entry.position();
        if (!entry.ammo() || minecraft.level == null) {
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

    /** 计算普通实体的单 Tick 包围盒中心，保持原版 HUD 位置语义。 */
    private static Vec3 resolveVanillaCenter(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return new Vec3(x, y, z).add(centerOffset);
    }
}
