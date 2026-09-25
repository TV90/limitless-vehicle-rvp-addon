package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 本体超远距广播载具的客户端目标运动插值器。
 *
 * <p>本体 {@code ServerBroadcastEntities} 收包时会把 {@code xo/yo/zo} 与当前坐标同时写成
 * 新样本，且载具的 {@code remoteTick()} 不推进位置，因此常规 {@link Mth#lerp} 无法消除
 * 广播间隔内的阶梯。这里缓存 RVP HUD 与客户端火控共用的位置、速度轨迹，
 * 不修改实体坐标、速度、雷达探测结果或服务端权威状态。</p>
 */
public final class RVP_ClientBroadcastVehicleInterpolator {

    /** 判定两个广播中心点相同所用的平方距离误差。 */
    private static final double POSITION_EPSILON_SQR = 1.0E-12;

    /** 按实体 ID 保存的广播目标轨迹；实体对象变化时会重建，避免 ID 复用串轨。 */
    private static final Map<Integer, PositionTrack> TRACKS = new HashMap<>();

    /** 当前轨迹缓存所属客户端世界；切换维度或重进世界时用于整体失效。 */
    @Nullable
    private static ClientLevel trackedLevel;

    private RVP_ClientBroadcastVehicleInterpolator() {
    }

    /**
     * 解析实体在本帧应使用的包围盒中心。
     *
     * <p>本体广播载具使用跨广播周期的样本插值；普通实体继续使用 Minecraft 的
     * {@code xo/yo/zo -> 当前坐标} 帧插值。</p>
     *
     * @param entity      需要投影到 HUD 的目标实体
     * @param partialTick 当前渲染帧的局部 Tick
     * @return 本帧平滑后的世界坐标包围盒中心
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick) {
        Vec3 vanillaCenter = resolveVanillaCenter(entity, partialTick);
        return resolveRenderCenter(entity, partialTick, vanillaCenter);
    }

    /**
     * 解析广播载具的平滑中心；目标不是广播载具时保留调用方已有的语义位置。
     *
     * @param entity             用于识别本体广播克隆的实体
     * @param partialTick        当前渲染帧的局部 Tick
     * @param nonBroadcastCenter 普通实体应原样返回的位置，例如雷达自身记录的探测点
     * @return 广播载具的平滑中心，或调用方提供的普通实体位置
     */
    public static Vec3 resolveRenderCenter(Entity entity, float partialTick, Vec3 nonBroadcastCenter) {
        // 调用本项目共享目标运动解析，使 HUD 与火控消费同一条广播插值轨迹。
        return resolveTrackingSample(entity, partialTick, nonBroadcastCenter).center();
    }

    /**
     * 解析客户端火控应使用的目标运动样本。
     *
     * <p>广播载具返回跨包插值的中心和速度，普通实体保持原版单 Tick 中心插值与
     * 实体当前运动量。返回的缓冲时长仅用于弹道解算补偿显示时间线的固定延迟。</p>
     *
     * @param entity      当前锁定目标
     * @param partialTick 当前客户端 Tick 或渲染帧的局部 Tick
     * @return 可供 HUD 和火控共用的目标运动样本
     */
    public static TargetTrackingSample resolveTrackingSample(Entity entity, float partialTick) {
        return resolveTrackingSample(entity, partialTick, resolveVanillaCenter(entity, partialTick));
    }

    /** 以调用方的普通实体位置语义解析共享目标运动样本。 */
    private static TargetTrackingSample resolveTrackingSample(Entity entity, float partialTick,
                                                               Vec3 nonBroadcastCenter) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || entity.level() != level) {
            return new TargetTrackingSample(nonBroadcastCenter, entity.getDeltaMovement(), 0.0D, false);
        }
        ensureLevel(level);

        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        LocalVehiclePlayer.ServerEntity serverEntity = localVehiclePlayer.serverEntities.get(entity.getId());
        if (!isBroadcastVehicle(entity) || serverEntity == null) {
            removeTrackForDifferentEntity(entity);
            return new TargetTrackingSample(nonBroadcastCenter, entity.getDeltaMovement(), 0.0D, false);
        }

        Vec3 sampleCenter = entity.getBoundingBox().getCenter();
        Vec3 sampleVelocity = entity.getDeltaMovement();
        int sampleTick = serverEntity.updateTick == null
                ? minecraft.player.tickCount
                : serverEntity.updateTick;
        PositionTrack track = TRACKS.get(entity.getId());
        if (track == null || track.entity != entity) {
            // 首次看见该广播克隆时没有上一份样本，直接以当前中心初始化，避免从世界原点飞入。
            track = new PositionTrack(entity, sampleCenter, sampleVelocity, sampleTick);
            TRACKS.put(entity.getId(), track);
        } else if (track.sampleTick != sampleTick
                || track.targetCenter.distanceToSqr(sampleCenter) > POSITION_EPSILON_SQR) {
            // 调用本项目轨迹插值，先取得旧过渡在新样本时刻的连续位置，再接续到新样本。
            Vec3 continuousStart = track.interpolate(sampleTick);
            Vec3 continuousVelocity = track.interpolateVelocity(sampleTick);
            int intervalTicks = resolveBroadcastIntervalTicks();
            track.acceptSample(sampleCenter, sampleVelocity, sampleTick,
                    continuousStart, continuousVelocity, intervalTicks);
        }

        double renderTick = minecraft.player.tickCount + Mth.clamp(partialTick, 0.0F, 1.0F);
        return new TargetTrackingSample(
                track.interpolate(renderTick),
                track.interpolateVelocity(renderTick),
                track.bufferDelayTicks(),
                true
        );
    }

    /**
     * 判断目标是否为本体当前维护的远程广播载具克隆。
     *
     * <p>同时核对类型、{@code remote} 标记、实体 ID 与对象身份，供 HUD 接管门控判断
     * 是否确实需要跨广播样本平滑。</p>
     */
    public static boolean isBroadcastVehicle(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle) || !vehicle.remote) {
            return false;
        }
        // 调用本体广播实体表核对对象身份，防止普通实体或复用相同 ID 的新对象误入平滑路径。
        LocalVehiclePlayer.ServerEntity serverEntity =
                LocalVehiclePlayer.instance.serverEntities.get(entity.getId());
        return serverEntity != null && serverEntity.entity == entity;
    }

    /**
     * 客户端 Tick 生命周期维护：离开载具、切换世界或广播克隆被本体淘汰后清理轨迹。
     */
    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !LocalVehiclePlayer.instance.onVehicle()) {
            clear();
            return;
        }
        ensureLevel(level);

        // 调用本体广播实体表核对生命周期，只保留仍由本体维护且对象身份一致的载具克隆。
        TRACKS.entrySet().removeIf(entry -> {
            LocalVehiclePlayer.ServerEntity serverEntity =
                    LocalVehiclePlayer.instance.serverEntities.get(entry.getKey());
            return serverEntity == null || serverEntity.entity != entry.getValue().entity;
        });
    }

    /** 清空全部目标轨迹；不修改本体的广播实体表。 */
    public static void clear() {
        TRACKS.clear();
        trackedLevel = null;
    }

    /** 普通实体沿用原有的单 Tick 插值，并在插值后补回包围盒中心相对实体原点的偏移。 */
    private static Vec3 resolveVanillaCenter(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return new Vec3(x, y, z).add(centerOffset);
    }

    /** 切换客户端世界时清空旧维度轨迹，防止相同实体 ID 在新世界复用旧样本。 */
    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel != level) {
            TRACKS.clear();
            trackedLevel = level;
        }
    }

    /** 普通实体占用了同一 ID 时，仅删除属于旧对象的广播轨迹。 */
    private static void removeTrackForDifferentEntity(Entity entity) {
        PositionTrack track = TRACKS.get(entity.getId());
        if (track != null && track.entity != entity) {
            TRACKS.remove(entity.getId());
        }
    }

    /** 读取本体服务端广播间隔；默认配置为 5 Tick，最小按 1 Tick 处理。 */
    private static int resolveBroadcastIntervalTicks() {
        return Math.max(1, AllConfigs.server.serverBroadcastEntitiesInterval.get());
    }

    /**
     * HUD 与客户端火控共用的目标运动样本。
     *
     * @param center           当前时间线上的目标包围盒中心，单位为格
     * @param velocity         当前时间线上的目标速度，单位为格/Tick
     * @param bufferDelayTicks 相对服务端最新状态的样本缓冲时长，单位为 Tick
     * @param broadcastVehicle 是否为本体 {@code serverEntities} 中的广播载具克隆
     */
    public record TargetTrackingSample(Vec3 center, Vec3 velocity, double bufferDelayTicks,
                                       boolean broadcastVehicle) {
    }

    /** 单个广播载具的目标运动样本过渡状态。 */
    private static final class PositionTrack {

        /** 本轨迹绑定的实体对象，用于识别实体 ID 复用。 */
        private final Entity entity;

        /** 当前过渡的起点中心。 */
        private Vec3 startCenter;

        /** 最近一份广播样本的目标中心。 */
        private Vec3 targetCenter;

        /** 当前过渡的起点速度，单位为格/Tick。 */
        private Vec3 startVelocity;

        /** 最近一份广播样本的目标速度，单位为格/Tick。 */
        private Vec3 targetVelocity;

        /** 最近一份广播样本到达客户端时的玩家 Tick。 */
        private int sampleTick;

        /** 当前过渡使用的时长，单位为 Tick。 */
        private int intervalTicks;

        /** 是否已收到第二份样本并建立固定延迟时间线。 */
        private boolean buffered;

        private PositionTrack(Entity entity, Vec3 initialCenter, Vec3 initialVelocity, int sampleTick) {
            this.entity = entity;
            this.startCenter = initialCenter;
            this.targetCenter = initialCenter;
            this.startVelocity = initialVelocity;
            this.targetVelocity = initialVelocity;
            this.sampleTick = sampleTick;
            this.intervalTicks = 1;
            this.buffered = false;
        }

        /** 接收新广播样本，并从旧过渡的连续位置开始下一段插值。 */
        private void acceptSample(Vec3 sampleCenter, Vec3 sampleVelocity, int sampleTick,
                                  Vec3 continuousStart, Vec3 continuousVelocity, int intervalTicks) {
            this.startCenter = continuousStart;
            this.targetCenter = sampleCenter;
            this.startVelocity = continuousVelocity;
            this.targetVelocity = sampleVelocity;
            this.sampleTick = sampleTick;
            this.intervalTicks = intervalTicks;
            this.buffered = true;
        }

        /** 计算指定客户端 Tick（可含帧小数）对应的线性插值中心。 */
        private Vec3 interpolate(double renderTick) {
            double progress = interpolationProgress(renderTick);
            return startCenter.lerp(targetCenter, progress);
        }

        /** 计算指定客户端 Tick 对应的连续目标速度。 */
        private Vec3 interpolateVelocity(double renderTick) {
            double progress = interpolationProgress(renderTick);
            return startVelocity.lerp(targetVelocity, progress);
        }

        /** 计算当前广播过渡的归一化进度。 */
        private double interpolationProgress(double renderTick) {
            return Mth.clamp((renderTick - sampleTick) / intervalTicks, 0.0, 1.0);
        }

        /** 返回当前相对服务端样本的固定缓冲时长。 */
        private double bufferDelayTicks() {
            return buffered ? intervalTicks : 0.0D;
        }
    }
}
