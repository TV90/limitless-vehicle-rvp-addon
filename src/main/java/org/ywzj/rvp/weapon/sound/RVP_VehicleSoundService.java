package org.ywzj.rvp.weapon.sound;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVehicleSound;
import org.ywzj.rvp.network.S2CVehicleSound.Kind;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.VesselVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;

/**
 * 默认导弹发射声的服务端兜底发布。
 *
 * <p>发射声主路径在客户端：本体 {@code ServerVehicleFire} 包只发给追踪载具实体的玩家，
 * 客户端收到开火完成事件后就地播放默认声，不产生额外数据包。此服务只向<b>实体追踪范围外</b>、
 * 根本收不到该开火广播的听者补发独立声音消息，因此不再需要每秒心跳或全范围广播。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleSoundService {

    /** 实体追踪范围下限，单位格；与服务端实体追踪包的最小覆盖距离一致。 */
    private static final double MIN_TRACKING_RANGE_BLOCKS = 16.0D;

    private RVP_VehicleSoundService() {
    }

    /** 服务端确认发射成功后，只向收不到本体开火广播的听者补发独立声音。 */
    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        if (!(event.getVehicle().level() instanceof ServerLevel level)) {
            return;
        }
        // 调用本项目代理解包器，按实际发射弹种判断，不按武器 ID 或名称判断。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        // 调用双端共用分类入口，确保客户端本地播放采用同样的判断。
        Kind kind = resolveMissileKind(event.getVehicle(), weapon);
        if (kind == null || weapon.getData().getWeaponId() == null) {
            return;
        }
        // 调用本体武器站获取发射位置，与本体开火声使用相同坐标。
        Vec3 position = weapon.getWeaponUnit().worldPivotPosition();
        S2CVehicleSound message = new S2CVehicleSound(event.getVehicle().level().dimension().location(),
                kind, event.getVehicle().getUUID(), event.getVehicle().getId(),
                weapon.getData().getWeaponId(), position);
        // 调用本服务兜底范围，客户端再执行自定义声音优先和资源缺失回退规则。
        broadcastToUntracked(level, event.getVehicle(), message);
    }

    /** 按本体实体类型、武器分类和实际发射站冷发射时长选择声音。 */
    @Nullable
    public static Kind resolveMissileKind(AbstractVehicle vehicle, @Nullable AbstractVehicleWeapon<?> weapon) {
        if (weapon == null || weapon.getWeaponUnit() == null) {
            return null;
        }
        // 调用 RVP 武器数据模型，也支持本体导弹武器；不把火箭、鱼雷等当成导弹。
        boolean missile = weapon instanceof VehicleMissile || weapon instanceof RVP_WeaponBase rvpWeapon
                && rvpWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        // 调用本体发射站配置，冷发射以 cold_launch_time_tick > 0 为准，不以点火延迟猜测。
        return resolveMissileKind(vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle
                        || vehicle instanceof VesselVehicle,
                vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle,
                missile, weapon.getWeaponUnit().getColdLaunchTimeTick());
    }

    /** 分类纯规则；空中载具即使落地或配置冷发射，也始终使用空中导弹声音。 */
    @Nullable
    static Kind resolveMissileKind(boolean surfaceVehicle, boolean aircraft, boolean missile, int coldLaunchTicks) {
        if (!missile) {
            return null;
        }
        if (aircraft) {
            return Kind.AIR2AIR;
        }
        return surfaceVehicle ? (coldLaunchTicks > 0 ? Kind.COLD_LAUNCH : Kind.SURFACE_TO_AIR) : null;
    }

    /** 实体追踪范围，单位格；本体实体类型的 clientTrackingRange 以区块为单位。 */
    static double trackingRangeBlocks(int trackingRangeChunks) {
        return Math.max(MIN_TRACKING_RANGE_BLOCKS, trackingRangeChunks * 16.0D);
    }

    /**
     * 实际追踪范围，单位格。本体在 {@code ChunkMap.TrackedEntity} 中取
     * “实体追踪距离”与“服务端视距”的较小值，两者均以区块 × 16 换算。
     */
    static double effectiveTrackingBlocks(int trackingRangeChunks, int viewDistanceChunks) {
        return Math.min(trackingRangeBlocks(trackingRangeChunks), Math.max(1, viewDistanceChunks) * 16.0D);
    }

    /** 纯规则：听者在实体追踪范围外，即本地播放无法覆盖、只能由独立声音消息兜底。 */
    static boolean needsFallback(double distanceSqr, double trackingRangeBlocks) {
        return distanceSqr > trackingRangeBlocks * trackingRangeBlocks;
    }

    /** 向可听范围内、但不在载具实体追踪范围内的玩家逐人发送声音消息。 */
    private static void broadcastToUntracked(ServerLevel level, AbstractVehicle vehicle, S2CVehicleSound message) {
        Vec3 position = message.position();
        double maximumDistanceSqr = (double) message.kind().maximumDistance() * message.kind().maximumDistance();
        // 调用本体实体类型追踪距离与服务端视距；范围内玩家已由客户端本地播放，不重复发包可避免叠音与多余流量。
        double trackingBlocks = effectiveTrackingBlocks(vehicle.getType().clientTrackingRange(),
                level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : level.players()) {
            double distanceSqr = player.distanceToSqr(position);
            if (distanceSqr > maximumDistanceSqr || !needsFallback(distanceSqr, trackingBlocks)) {
                continue;
            }
            // 调用本项目网络通道逐人发送，不扩大实体追踪或强制加载区块。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }
}
