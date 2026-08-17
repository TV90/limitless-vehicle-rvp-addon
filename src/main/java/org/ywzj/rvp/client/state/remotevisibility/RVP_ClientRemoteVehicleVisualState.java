package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.render.RVP_LodModelManager;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端远距载具非世界代理状态。
 * <p>
 * 代理只保存在本侧表中，不加入 {@link ClientLevel}，因此不会参与碰撞、交互、声音或实体 Tick。
 * 网络完整快照在客户端主线程写入，渲染线程读取同一份状态。
 */
public final class RVP_ClientRemoteVehicleVisualState {
    /** 服务端授权消失后，旧代理允许保留的最大客户端 Tick 数。 */
    static final long EXPIRE_TICKS = 25L;
    /** 当前状态所属的客户端维度。 */
    private static ResourceLocation dimension;
    /** 当前维度最后接受的完整快照序号。 */
    private static long lastSequence = -1L;
    /** 按服务端实体 ID 保存的非世界代理与时间线。 */
    private static final Map<Integer, ProxyState> PROXIES = new HashMap<>();

    private RVP_ClientRemoteVehicleVisualState() {
    }

    /** 接受阶段 A 公共端口分发的载具视觉完整快照。 */
    public static void accept(S2CRemoteVehicleVisualSnapshot message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !message.dimension().equals(level.dimension().location())) {
            return;
        }
        ResourceLocation currentDimension = level.dimension().location();
        if (!currentDimension.equals(dimension)) {
            clear();
            dimension = currentDimension;
        }
        if (!isNewerSequence(message.sequence(), lastSequence)) {
            return;
        }
        lastSequence = message.sequence();

        long clientTick = level.getGameTime();
        Set<Integer> retainedIds = new HashSet<>();
        for (S2CRemoteVehicleVisualSnapshot.Entry entry : message.entries()) {
            if (!retainedIds.add(entry.entityId())) {
                continue;
            }
            ProxyMetadata metadata = ProxyMetadata.from(entry);
            Sample sample = Sample.from(message.serverGameTime(), entry);
            ProxyState existing = PROXIES.get(entry.entityId());
            if (requiresRebuild(existing == null ? null : existing.metadata,
                    existing == null ? null : existing.latest, metadata, sample)) {
                AbstractVehicle proxy = createProxy(level, metadata, entry.entityId(), sample);
                if (proxy == null) {
                    removeProxy(entry.entityId());
                    continue;
                }
                removeProxy(entry.entityId());
                PROXIES.put(entry.entityId(), ProxyState.initial(metadata, proxy, sample, clientTick));
                continue;
            }

            existing.previous = existing.latest;
            existing.latest = sample;
            existing.lastUpdateClientTick = clientTick;
            applyLatestDynamicState(existing.proxy, sample);
        }

        // 调用完整集合语义立即撤销缺失实体，避免客户端继续显示已失去服务端授权的目标。
        List<Integer> removedIds = PROXIES.keySet().stream()
                .filter(entityId -> !retainedIds.contains(entityId))
                .toList();
        removedIds.forEach(RVP_ClientRemoteVehicleVisualState::removeProxy);
    }

    /** 在客户端 Tick 末尾清理换维度或超过 25 Tick 未更新的代理。 */
    public static void tick(ClientLevel level) {
        ResourceLocation currentDimension = level.dimension().location();
        if (!currentDimension.equals(dimension)) {
            clear();
            dimension = currentDimension;
            return;
        }
        long clientTick = level.getGameTime();
        List<Integer> expiredIds = PROXIES.entrySet().stream()
                .filter(entry -> isExpired(entry.getValue().lastUpdateClientTick, clientTick))
                .map(Map.Entry::getKey)
                .toList();
        expiredIds.forEach(RVP_ClientRemoteVehicleVisualState::removeProxy);
    }

    /**
     * 生成当前帧的只读渲染条目。
     * <p>
     * 此方法只计算插值结果，不把代理加入世界，也不永久改写代理的位置和姿态。
     */
    public static List<RenderEntry> renderEntries(ClientLevel level, float partialTick) {
        if (!level.dimension().location().equals(dimension) || PROXIES.isEmpty()) {
            return List.of();
        }
        double clientTime = level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F);
        double maxExtrapolation = Math.min(5.0D, RVP_ClientConfig.getRemoteVehicleMaxExtrapolationTicks());
        List<RenderEntry> result = new ArrayList<>(PROXIES.size());
        for (ProxyState state : PROXIES.values()) {
            InterpolatedSample interpolated = state.interpolate(clientTime, maxExtrapolation);
            result.add(new RenderEntry(state.proxy, state.latest.entityId, interpolated.position,
                    interpolated.xRot, interpolated.yRot, interpolated.zRot,
                    interpolated.heightAboveGround, state.latest.destroyed));
        }
        return List.copyOf(result);
    }

    /** 清除世界退出、维度切换或资源重载后遗留的全部代理与序号。 */
    public static void clear() {
        PROXIES.values().forEach(state -> RVP_LodModelManager.forgetVehicle(state.proxy));
        PROXIES.clear();
        dimension = null;
        lastSequence = -1L;
    }

    /** 返回当前保存的代理数量，供测试和调试统计使用。 */
    static int proxyCount() {
        return PROXIES.size();
    }

    /** 移除单个代理，并同步丢弃其 LOD 选级与节流状态。 */
    private static void removeProxy(int entityId) {
        ProxyState removed = PROXIES.remove(entityId);
        if (removed != null) {
            // 调用 RVP LOD 管理器清理该非世界代理的弱引用选级缓存。
            RVP_LodModelManager.forgetVehicle(removed.proxy);
        }
    }

    /** 判断元数据或不可逆损毁状态是否要求重建代理。 */
    static boolean requiresRebuild(ProxyMetadata oldMetadata, Sample oldSample,
                                   ProxyMetadata newMetadata, Sample newSample) {
        return oldMetadata == null
                || oldSample == null
                || !oldMetadata.equals(newMetadata)
                || oldSample.destroyed && !newSample.destroyed;
    }

    /** 判断代理是否已经达到无更新超时边界。 */
    static boolean isExpired(long lastUpdateTick, long currentTick) {
        return currentTick - lastUpdateTick >= EXPIRE_TICKS;
    }

    /** 判断完整快照序号是否严格晚于客户端已接受序号。 */
    static boolean isNewerSequence(long sequence, long acceptedSequence) {
        return sequence > acceptedSequence;
    }

    /** 使用最短环绕角差插值角度，正确处理 {@code 179 -> -179}。 */
    static float interpolateAngleDegrees(float start, float end, double alpha) {
        return start + Mth.wrapDegrees(end - start) * (float) Mth.clamp(alpha, 0.0D, 1.0D);
    }

    /** 推进单调渲染游标，并把外推严格限制在最新样本后的配置上限内。 */
    static double advanceRenderTime(double currentRenderTime, double delayedTarget,
                                    long latestServerTime, double maxExtrapolation) {
        double monotonicTarget = Math.max(currentRenderTime, delayedTarget);
        return Math.min(monotonicTarget, latestServerTime + Math.max(0.0D, maxExtrapolation));
    }

    /** 在两个服务端样本间插值，越过最新样本后按速度进行受限外推。 */
    static InterpolatedSample interpolateSamples(Sample previous, Sample latest,
                                                 double renderTime, double maxExtrapolation) {
        if (renderTime <= latest.serverGameTime) {
            double denominator = Math.max(1.0D, latest.serverGameTime - previous.serverGameTime);
            double alpha = Mth.clamp((renderTime - previous.serverGameTime) / denominator,
                    0.0D, 1.0D);
            return new InterpolatedSample(previous.position.lerp(latest.position, alpha),
                    interpolateAngleDegrees(previous.xRot, latest.xRot, alpha),
                    interpolateAngleDegrees(previous.yRot, latest.yRot, alpha),
                    interpolateAngleDegrees(previous.zRot, latest.zRot, alpha),
                    Mth.lerp(alpha, previous.heightAboveGround, latest.heightAboveGround));
        }

        double extrapolationTicks = Math.min(Math.max(0.0D, maxExtrapolation),
                Math.max(0.0D, renderTime - latest.serverGameTime));
        return new InterpolatedSample(latest.position.add(latest.velocity.scale(extrapolationTicks)),
                latest.xRot, latest.yRot, latest.zRot, latest.heightAboveGround);
    }

    /** 创建并初始化正确实体类型的非世界载具代理。 */
    private static AbstractVehicle createProxy(ClientLevel level, ProxyMetadata metadata,
                                               int entityId, Sample sample) {
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(metadata.entityType).orElse(null);
        if (entityType == null) {
            return null;
        }
        Entity created = entityType.create(level);
        if (!(created instanceof AbstractVehicle proxy)) {
            return null;
        }
        proxy.setId(entityId);
        proxy.setVehicleId(metadata.vehicleId);
        proxy.setDisplayId(metadata.displayId);
        // 调用本体公开初始化方法，建立车型数据、部件数据、旋转枢轴与原模型实例。
        proxy.initData();
        if (proxy.isRemoved()) {
            return null;
        }
        // 调用本体公开显示初始化方法，按快照 displayId 创建正确的载具模型实例。
        proxy.initDisplayData();
        proxy.remote = true;
        applyLatestDynamicState(proxy, sample);
        applyPose(proxy, sample.position, sample.xRot, sample.yRot, sample.zRot);
        return proxy;
    }

    /** 把最新快照中无需插值的速度、动力和损毁状态应用到代理。 */
    private static void applyLatestDynamicState(AbstractVehicle proxy, Sample sample) {
        proxy.setDeltaMovement(sample.velocity);
        proxy.toggleEngine(sample.engineOn);
        proxy.setPower(sample.power);
        proxy.setEngineSpeed(sample.engineSpeed);
        if (sample.destroyed && !proxy.isDestroyed()) {
            proxy.setDestroyed();
        }
    }

    /** 同步设置代理当前/上一帧位置与三轴姿态，避免本体辅助方法再次插值。 */
    private static void applyPose(AbstractVehicle proxy, Vec3 position,
                                  float xRot, float yRot, float zRot) {
        proxy.setPos(position);
        proxy.xo = position.x;
        proxy.yo = position.y;
        proxy.zo = position.z;
        proxy.setXRot(xRot);
        proxy.setYRot(yRot);
        proxy.setZRot(zRot);
        proxy.xRotO = xRot;
        proxy.yRotO = yRot;
        proxy.zRotO = zRot;
    }

    /**
     * 决定代理具体类型与资源初始化身份的元数据。
     *
     * @param entityType 实体类型注册表 ID
     * @param vehicleId 本体车型数据 ID
     * @param displayId 本体显示变体 ID
     */
    record ProxyMetadata(ResourceLocation entityType, ResourceLocation vehicleId, ResourceLocation displayId) {
        /** 从网络条目提取代理重建键。 */
        static ProxyMetadata from(S2CRemoteVehicleVisualSnapshot.Entry entry) {
            return new ProxyMetadata(entry.entityType(), entry.vehicleId(), entry.displayId());
        }
    }

    /**
     * 单个服务端载具视觉样本。
     *
     * @param serverGameTime 样本服务端世界时间，单位 Tick
     * @param entityId 服务端实体 ID
     * @param position 世界位置，单位格
     * @param velocity 速度，单位格每 Tick
     * @param xRot 俯仰角，单位度
     * @param yRot 偏航角，单位度
     * @param zRot 滚转角，单位度
     * @param heightAboveGround 服务端权威离地高度，单位格
     * @param destroyed 是否损毁
     * @param engineOn 发动机是否开启
     * @param power 动力表现值
     * @param engineSpeed 发动机转速表现值
     */
    record Sample(long serverGameTime, int entityId, Vec3 position, Vec3 velocity,
                  float xRot, float yRot, float zRot, double heightAboveGround,
                  boolean destroyed, boolean engineOn, float power, float engineSpeed) {
        /** 从网络条目构造带服务端时间的样本。 */
        static Sample from(long serverGameTime, S2CRemoteVehicleVisualSnapshot.Entry entry) {
            return new Sample(serverGameTime, entry.entityId(), entry.position(), entry.velocity(),
                    entry.xRot(), entry.yRot(), entry.zRot(), entry.heightAboveGround(),
                    entry.destroyed(), entry.engineOn(), entry.power(), entry.engineSpeed());
        }
    }

    /**
     * 当前帧完成插值或受限外推后的纯姿态。
     *
     * @param position 当前帧世界位置
     * @param xRot 当前帧俯仰角
     * @param yRot 当前帧偏航角
     * @param zRot 当前帧滚转角
     * @param heightAboveGround 当前帧服务端离地高度
     */
    record InterpolatedSample(Vec3 position, float xRot, float yRot, float zRot,
                              double heightAboveGround) {
    }

    /**
     * 远距渲染器消费的只读条目。
     *
     * @param proxy 不加入世界的载具代理
     * @param entityId 服务端实体 ID
     * @param position 当前帧世界位置
     * @param xRot 当前帧俯仰角
     * @param yRot 当前帧偏航角
     * @param zRot 当前帧滚转角
     * @param heightAboveGround 当前帧服务端离地高度
     * @param destroyed 是否损毁
     */
    public record RenderEntry(AbstractVehicle proxy, int entityId, Vec3 position,
                              float xRot, float yRot, float zRot,
                              double heightAboveGround, boolean destroyed) {
    }

    /** 单个代理的元数据、两个样本与单调渲染时间游标。 */
    private static final class ProxyState {
        /** 代理重建键。 */
        private final ProxyMetadata metadata;
        /** 不加入世界的本体载具实例。 */
        private final AbstractVehicle proxy;
        /** 上一个服务端样本；首包时为空。 */
        private Sample previous;
        /** 最新服务端样本。 */
        private Sample latest;
        /** 最新样本到达的客户端世界 Tick。 */
        private long lastUpdateClientTick;
        /** 单调不回退的服务端渲染时间游标。 */
        private double renderServerTime;

        private ProxyState(ProxyMetadata metadata, AbstractVehicle proxy, Sample previous,
                           Sample latest, long lastUpdateClientTick, double renderServerTime) {
            this.metadata = metadata;
            this.proxy = proxy;
            this.previous = previous;
            this.latest = latest;
            this.lastUpdateClientTick = lastUpdateClientTick;
            this.renderServerTime = renderServerTime;
        }

        /** 创建首样本代理状态；首包保持在权威位置，等待第二个样本建立时间线。 */
        private static ProxyState initial(ProxyMetadata metadata, AbstractVehicle proxy,
                                          Sample sample, long clientTick) {
            return new ProxyState(metadata, proxy, null, sample, clientTick, sample.serverGameTime);
        }

        /** 按一份快照间隔延迟插值，缺包时只允许进行受配置约束的短时外推。 */
        private InterpolatedSample interpolate(double clientTime, double maxExtrapolation) {
            if (previous == null) {
                return new InterpolatedSample(latest.position, latest.xRot, latest.yRot, latest.zRot,
                        latest.heightAboveGround);
            }
            double sampleSpan = Math.max(1.0D, latest.serverGameTime - previous.serverGameTime);
            double estimatedServerNow = latest.serverGameTime + Math.max(0.0D, clientTime - lastUpdateClientTick);
            double delayedTarget = estimatedServerNow - sampleSpan;
            renderServerTime = advanceRenderTime(renderServerTime, delayedTarget,
                    latest.serverGameTime, maxExtrapolation);
            return interpolateSamples(previous, latest, renderServerTime, maxExtrapolation);
        }
    }
}
