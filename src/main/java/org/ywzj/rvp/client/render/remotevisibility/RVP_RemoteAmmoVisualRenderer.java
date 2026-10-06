package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientProjectileRenderInterpolator;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.visual.RVP_RocketFlameRuntimeTuning;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.FarPlaneDemand;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 只负责远程弹药克隆的超视距绘制、运动朝向和发动机尾迹。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteAmmoVisualRenderer {
    /** 原生实体追踪接管边界，单位格。 */
    public static final double BASE_RANGE = 32.0D * 16.0D;
    /** 无条件弹药可视边界，单位格。 */
    public static final double UNCONDITIONAL_RANGE = 64.0D * 16.0D;
    /** 弹药视觉最远边界，单位格。 */
    public static final double EXTENDED_RANGE = 256.0D * 16.0D;
    /** 弹药视觉最低离地高度，供既有界面展示规则复用。 */
    public static final double MIN_AGL = 100.0D;
    /** 原生实体追踪接管边界平方。 */
    private static final double BASE_RANGE_SQ = BASE_RANGE * BASE_RANGE;
    /** 无条件弹药可视边界平方。 */
    private static final double UNCONDITIONAL_RANGE_SQ = UNCONDITIONAL_RANGE * UNCONDITIONAL_RANGE;
    /** 弹药视觉最远边界平方。 */
    private static final double EXTENDED_RANGE_SQ = EXTENDED_RANGE * EXTENDED_RANGE;
    /** 远程弹体及喷口尾迹参与远平面计算的包围半径，单位格。 */
    private static final double REMOTE_AMMO_CULL_RADIUS = 32.0D;
    /** 单次远程补线最多生成的尾迹粒子数，防止位置快照跳变造成瞬时粒子洪峰。 */
    private static final int MAX_TRAIL_PARTICLES_PER_SPAWN = 8;
    /** 单个远程弹药允许缓存的待补尾迹线段数，避免异常断包导致队列无限增长。 */
    private static final int MAX_PENDING_TRAIL_SEGMENTS = 32;
    /** 单个远程弹药允许缓存的待补路径长度，单位格；超过后以当前位置重新播种。 */
    private static final double MAX_PENDING_TRAIL_DISTANCE = 1024.0D;
    /** 路径线段的最小有效长度平方，过滤远程插值产生的浮点噪声。 */
    private static final double MIN_TRAIL_SEGMENT_DISTANCE_SQ = 1.0E-8D;
    /** 尾迹粒子之间的最小安全间距，单位格，防止异常配置造成零步长循环。 */
    private static final double MIN_TRAIL_SPACING = 0.25D;
    /** 远程授权快照缺失后的尾迹宽限时间，单位 Tick。 */
    private static final long TRAIL_AUTHORIZATION_GRACE_TICKS = 10L;
    /** 按弹药实体 ID 保存的尾迹采样状态。 */
    private static final Map<Integer, TrailState> TRAIL_STATES = new HashMap<>();
    /** 当前尾迹状态所属维度。 */
    private static ResourceLocation trailDimension;
    /** 粒子阶段临时使用的远平面作用域，跨 AFTER_BLOCK_ENTITIES 到 AFTER_PARTICLES 保持有效。 */
    @Nullable
    private static RVP_RemoteVehicleRenderScope particleProjectionScope;
    /** 本帧是否存在需要让粒子阶段使用远平面的远程弹药或尾迹状态。 */
    private static boolean remoteAmmoProjectionNeeded;

    private RVP_RemoteAmmoVisualRenderer() {
    }

    /** 在正常实体之后绘制服务端授权的远程弹药克隆。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            closeParticleProjectionScope();
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            openParticleProjectionScope(event);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        ResourceLocation currentDimension = minecraft.level.dimension().location();
        if (!currentDimension.equals(trailDimension)) {
            TRAIL_STATES.clear();
            trailDimension = currentDimension;
        }

        // 调用现有远距投影规划器，把弹药 4096 格授权边界纳入世界远裁剪面，避免客户端视距约 2048 格时整批被 GPU 裁掉。
        Optional<ProjectionPlan> projectionPlan = RVP_RemoteVehicleProjection.plan(
                event.getProjectionMatrix(),
                List.of(new FarPlaneDemand(EXTENDED_RANGE, REMOTE_AMMO_CULL_RADIUS)));
        boolean rendered;
        if (projectionPlan.isPresent()) {
            // 调用现有远距渲染作用域，临时扩展投影和雾距离，绘制结束后恢复原版状态。
            try (RVP_RemoteVehicleRenderScope ignored = RVP_RemoteVehicleRenderScope.open(
                    projectionPlan.get(), event.getCamera())) {
                rendered = renderRemoteAmmoEntities(minecraft, cameraPos, poseStack, buffers, dispatcher,
                        currentDimension, event);
                if (rendered) {
                    // 在远距投影作用域仍然有效时提交实体缓冲，确保 GPU 使用扩展后的远平面。
                    buffers.endBatch();
                }
            }
        } else {
            // 非标准投影无法安全扩展时保留原绘制路径，不因投影诊断失败阻断近距弹体显示。
            rendered = renderRemoteAmmoEntities(minecraft, cameraPos, poseStack, buffers, dispatcher,
                    currentDimension, event);
            if (rendered) {
                buffers.endBatch();
            }
        }
        remoteAmmoProjectionNeeded = rendered || !TRAIL_STATES.isEmpty();
        Iterator<Integer> trailIterator = TRAIL_STATES.keySet().iterator();
        while (trailIterator.hasNext()) {
            if (!RVP_ClientRemoteAmmoVisualState.containsForTrail(
                    currentDimension, trailIterator.next(), minecraft.level.getGameTime())) {
                trailIterator.remove();
            }
        }
    }

    /** 在原版粒子批次开始前打开远程弹药投影，并将作用域延续到粒子批次提交完成。 */
    private static void openParticleProjectionScope(RenderLevelStageEvent event) {
        boolean projectionNeeded = remoteAmmoProjectionNeeded;
        closeParticleProjectionScope();
        if (!projectionNeeded) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        // 调用现有远距投影规划器，为 AFTER_PARTICLES 阶段的火箭尾迹粒子准备与弹体相同的远平面。
        Optional<ProjectionPlan> projectionPlan = RVP_RemoteVehicleProjection.plan(
                event.getProjectionMatrix(),
                List.of(new FarPlaneDemand(EXTENDED_RANGE, REMOTE_AMMO_CULL_RADIUS)));
        if (projectionPlan.isEmpty()) {
            return;
        }
        // 调用现有远距渲染作用域，覆盖原版粒子渲染窗口，完成后由 AFTER_PARTICLES 恢复原状态。
        particleProjectionScope = RVP_RemoteVehicleRenderScope.open(
                projectionPlan.get(), event.getCamera());
    }

    /** 关闭粒子阶段远平面作用域，避免扩展投影和雾状态泄漏到天气及后续世界渲染。 */
    private static void closeParticleProjectionScope() {
        RVP_RemoteVehicleRenderScope scope = particleProjectionScope;
        particleProjectionScope = null;
        remoteAmmoProjectionNeeded = false;
        if (scope != null) {
            scope.close();
        }
    }

    /** 在当前远平面作用域中绘制远程弹药克隆，并返回是否有弹体写入实体缓冲。 */
    private static boolean renderRemoteAmmoEntities(Minecraft minecraft, Vec3 cameraPos, PoseStack poseStack,
                                                    MultiBufferSource.BufferSource buffers,
                                                    EntityRenderDispatcher dispatcher,
                                                    ResourceLocation currentDimension,
                                                    RenderLevelStageEvent event) {
        boolean rendered = false;
        for (LocalVehiclePlayer.ServerEntity remote : LocalVehiclePlayer.instance.serverEntities.values()) {
            Entity entity = remote.entity;
            if (entity == null || !isSupported(entity)) {
                continue;
            }
            if (minecraft.level.getEntity(entity.getId()) != null) {
                TRAIL_STATES.remove(entity.getId());
                continue;
            }
            long clientGameTime = minecraft.level.getGameTime();
            if (!RVP_ClientRemoteAmmoVisualState.containsForTrail(
                    currentDimension, entity.getId(), clientGameTime)) {
                continue;
            }
            // 调用本项目统一弹体位置解析，使 3D 弹体与雷达/HUD 框共用同一外推轨迹。
            Vec3 renderPos = RVP_ClientProjectileRenderInterpolator.resolveRenderOrigin(
                    minecraft, remote, entity, event.getPartialTick());
            if (!isVisibleAtRange(entity, renderPos, cameraPos)) {
                // 当前克隆已离开远程渲染边界：清除路径状态，避免重新进入时连接到过期位置。
                TRAIL_STATES.remove(entity.getId());
                continue;
            }

            spawnRemoteTrail(minecraft, entity, renderPos, cameraPos, currentDimension);

            Vec3 oldPos = entity.position();
            float oldXRot = entity.getXRot();
            float oldYRot = entity.getYRot();
            float oldXRotO = entity.xRotO;
            float oldYRotO = entity.yRotO;
            applyMotionFacing(entity);
            entity.setPos(renderPos);
            entity.xo = renderPos.x;
            entity.yo = renderPos.y;
            entity.zo = renderPos.z;
            try {
                // 调用本体实体渲染分派器，使用已注册的 RVP 类型化弹体渲染器绘制远程弹药克隆。
                dispatcher.render(entity,
                        renderPos.x - cameraPos.x,
                        renderPos.y - cameraPos.y,
                        renderPos.z - cameraPos.z,
                        entity.getYRot(), event.getPartialTick(), poseStack, buffers, LightTexture.FULL_BRIGHT);
                rendered = true;
            } finally {
                entity.setPos(oldPos);
                entity.setXRot(oldXRot);
                entity.setYRot(oldYRot);
                entity.xRotO = oldXRotO;
                entity.yRotO = oldYRotO;
            }
        }
        return rendered;
    }

    /** 判断实体是否是只由 RVP 战术地图额外处理的视觉弹药克隆。 */
    public static boolean isVisualOnlyRvpAmmo(Entity entity) {
        return entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    /** 清除世界退出或维度切换后遗留的尾迹状态。 */
    public static void clear() {
        TRAIL_STATES.clear();
        trailDimension = null;
    }

    /** 判断远程克隆是否属于支持的弹药实体类型。 */
    private static boolean isSupported(Entity entity) {
        return entity instanceof MissileEntity
                || entity instanceof RocketEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    private static boolean ensureVehicleDisplayInitialized(AbstractVehicle vehicle) {
        if (vehicle.getVehicleModelInstance() != null) {
            return true;
        }
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null || display.getModel() == null || display.getTexture() == null) {
            return false;
        }
        vehicle.initDisplayData(display);
        return vehicle.getVehicleModelInstance() != null;
    }

    /** 应用弹药超视距距离边界。 */
    private static boolean isVisibleAtRange(Entity entity, Vec3 position, Vec3 cameraPos) {
        double dx = position.x - cameraPos.x;
        double dz = position.z - cameraPos.z;
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= BASE_RANGE_SQ || distanceSq > EXTENDED_RANGE_SQ) {
            return false;
        }
        return !(entity instanceof RVP_BulletEntity) || distanceSq <= UNCONDITIONAL_RANGE_SQ;
    }

    /** 使用速度方向更新弹药克隆的俯仰和偏航。 */
    private static void applyMotionFacing(Entity entity) {
        // 调用本项目双端攻角门控，攻角导弹使用同步机头姿态，远距渲染不得抹平机头与速度夹角。
        if (entity instanceof org.ywzj.rvp.entity.projectile.RVP_BaseBullet projectile
                && org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion.usesAttackAngle(projectile)) {
            return;
        }
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() <= 1.0E-6D) {
            return;
        }
        // 调用本体向量旋转工具，把弹药速度转换为实体渲染朝向。
        Vec2 rotation = VectorUtil.vecToRot(velocity);
        entity.setXRot(rotation.x);
        entity.setYRot(rotation.y);
        entity.xRotO = rotation.x;
        entity.yRotO = rotation.y;
    }

    /** 按距离降采样并补点生成远程导弹或火箭尾迹。 */
    private static void spawnRemoteTrail(Minecraft minecraft, Entity entity, Vec3 renderPos, Vec3 cameraPos,
                                         ResourceLocation dimension) {
        if (!(entity instanceof MissileEntity || entity instanceof RocketEntity
                || entity instanceof RVP_MissileEntity || entity instanceof RVP_RocketEntity)) {
            return;
        }
        long gameTick = minecraft.level.getGameTime();
        if (!RVP_ClientRemoteAmmoVisualState.isMotorBurningForTrail(
                dimension, entity.getId(), gameTick)) {
            TRAIL_STATES.remove(entity.getId());
            return;
        }

        // 调用 RVP 远程克隆配置出口，以同步的 weaponId 读取客户端同款尾迹风格；本体弹保持 null 回退。
        RVP_EffectsData effects = resolveRemoteTrailEffects(entity);
        boolean rocketFlameStyle = effects != null && effects.isMissileNativeTrailRocketFlame();
        // 调用本项目运行时调参入口：远程火箭尾迹也遵守客户端会话级总开关。
        if (effects != null && (!effects.isMissileNativeTrailEnabled()
                || (rocketFlameStyle && !RVP_RocketFlameRuntimeTuning.resolveEnabled(true)))) {
            TRAIL_STATES.remove(entity.getId());
            return;
        }

        double horizontalDistance = Math.sqrt(horizontalDistanceSqr(renderPos, cameraPos));
        int lodInterval = horizontalDistance < 768.0D ? 1 : horizontalDistance < 1152.0D ? 2 : 3;
        double lodSpacing = horizontalDistance < 768.0D ? 2.0D : horizontalDistance < 1152.0D ? 4.0D : 8.0D;
        int configuredInterval = effects == null
                ? lodInterval : effects.getMissileNativeTrailSpawnIntervalTick();
        if (rocketFlameStyle) {
            // 调用本项目运行时调参入口：让超视距火箭尾迹使用与近距相同的生成频率覆盖。
            configuredInterval = RVP_RocketFlameRuntimeTuning.resolveSpawnInterval(configuredInterval);
        }
        int interval = effects == null ? lodInterval : Math.max(lodInterval, configuredInterval);
        double spacing = lodSpacing;
        if (effects != null) {
            // 调用 RVP 密度/步长解析出口：配置只能让远距尾迹更稀，不能突破远距性能下限。
            float densityScale = effects.getMissileNativeTrailDensityScale();
            if (rocketFlameStyle) {
                // 调用本项目运行时调参入口：让超视距火箭尾迹使用与近距相同的密度覆盖。
                densityScale = RVP_RocketFlameRuntimeTuning.resolveDensityScale(densityScale);
            }
            if (densityScale <= 0.0f) {
                TRAIL_STATES.remove(entity.getId());
                return;
            }
            double configuredStep = effects.getMissileNativeTrailStep();
            if (rocketFlameStyle) {
                // 调用本项目运行时调参入口：让超视距补线使用与近距相同的采样步长覆盖。
                configuredStep = RVP_RocketFlameRuntimeTuning.resolveStep((float) configuredStep);
            }
            spacing = Math.max(lodSpacing, configuredStep / densityScale);
        }

        TrailState state = TRAIL_STATES.computeIfAbsent(entity.getId(), ignored -> new TrailState());
        if (state.lastSpawnTick == gameTick || gameTick % interval != 0) {
            return;
        }
        Vec3 nozzlePos = remoteNozzlePosition(entity, renderPos, effects);

        Vec3 previous = state.lastPosition;
        state.lastPosition = nozzlePos;
        state.lastSpawnTick = gameTick;
        Vec3 exhaustVelocity = remoteExhaustVelocity(entity);
        int remainingBurnTicks = RVP_ClientRemoteAmmoVisualState.getMotorBurnRemainingTicks(
                dimension, entity.getId());
        if (previous == null) {
            // 调用 RVP 远程尾迹发射器，为新路径播种首个风格化尾迹点。
            RVP_RemoteMissileTrailEmitter.spawn(
                    minecraft, effects, nozzlePos, exhaustVelocity, remainingBurnTicks);
            return;
        }

        if (!appendPendingTrailSegment(state, previous, nozzlePos)) {
            // 异常长跳点超出有界路径缓存：丢弃旧路径并从当前位置重新播种，避免旧尾迹拖到错误位置。
            state.resetPendingPath();
            // 调用 RVP 远程尾迹发射器，为异常跳点后的新路径播种当前位置。
            RVP_RemoteMissileTrailEmitter.spawn(
                    minecraft, effects, nozzlePos, exhaustVelocity, remainingBurnTicks);
            return;
        }
        drainPendingTrail(minecraft, effects, state, spacing, exhaustVelocity, remainingBurnTicks);
        if (!RVP_RemoteMissileTrailEmitter.usesCustomStyle(effects)
                && horizontalDistance < 768.0D && gameTick % 2L == 0L) {
            Vec3 velocity = entity.getDeltaMovement();
            minecraft.level.addParticle(ParticleTypes.FLAME, true,
                    nozzlePos.x, nozzlePos.y, nozzlePos.z,
                    -velocity.x * 0.01D, -velocity.y * 0.01D, -velocity.z * 0.01D);
        }
    }

    /** 根据弹体速度估算远程尾喷口世界位置。 */
    private static Vec3 remoteNozzlePosition(Entity entity, Vec3 renderPos,
                                             @Nullable RVP_EffectsData effects) {
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 rear = velocity.lengthSqr() > 1.0E-6D ? velocity.normalize().scale(-1.0D) : Vec3.ZERO;
        double offset;
        if (effects != null && entity instanceof RVP_MissileEntity) {
            // 调用 RVP 效果数据出口，使远程尾喷口与近距 missile_native_trail_offset 完全同源。
            offset = effects.getMissileNativeTrailOffset();
            if (effects.isMissileNativeTrailRocketFlame()) {
                // 调用本项目运行时调参入口：让超视距尾喷口位置与近距尾迹同步调节。
                offset = RVP_RocketFlameRuntimeTuning.resolveOffset((float) offset);
            }
        } else {
            offset = entity instanceof RocketEntity || entity instanceof RVP_RocketEntity ? 1.0D : 2.0D;
        }
        return renderPos.add(rear.scale(offset));
    }

    /** 以克隆速度方向构造 HBM 尾迹需要的反向单位尾喷初速。 */
    private static Vec3 remoteExhaustVelocity(Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        return velocity.lengthSqr() > 1.0E-6D ? velocity.normalize().scale(-1.0D) : Vec3.ZERO;
    }

    /** 从 RVP 远程导弹克隆恢复效果配置；本体导弹、火箭或资源尚未就绪时返回 null。 */
    @Nullable
    private static RVP_EffectsData resolveRemoteTrailEffects(Entity entity) {
        if (!(entity instanceof RVP_MissileEntity missile)) {
            return null;
        }
        // 调用 RVP 弹体公共配置解析出口，按远程广播同步的 weaponId 查询武器数据。
        RVP_WeaponData config = missile.getResolvedWeaponConfig();
        return config == null ? null : config.getEffectsData();
    }

    /** 计算两个位置的水平距离平方。 */
    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    /**
     * 把两个连续远程采样点加入待补路径；超出有界缓存时返回 false，让调用方从当前位置重新播种。
     *
     * @param state 当前弹药尾迹状态
     * @param start 上一个路径采样位置
     * @param end 当前路径采样位置
     * @return 是否成功保留该路径线段
     */
    private static boolean appendPendingTrailSegment(TrailState state, Vec3 start, Vec3 end) {
        if (start.distanceToSqr(end) <= MIN_TRAIL_SEGMENT_DISTANCE_SQ) {
            return true;
        }
        state.pendingSegments.addLast(new TrailSegment(start, end));
        if (state.pendingSegments.size() > MAX_PENDING_TRAIL_SEGMENTS
                || pendingTrailDistance(state) > MAX_PENDING_TRAIL_DISTANCE) {
            return false;
        }
        return true;
    }

    /**
     * 按当前距离 LOD 间距消化待补路径，每次最多生成固定数量的粒子；未消化路径留给后续 Tick。
     *
     * @param minecraft 当前客户端实例
     * @param effects 当前导弹效果配置
     * @param state 当前弹药尾迹状态
     * @param spacing 当前 LOD 下尾迹粒子间距，单位格
     * @param exhaustVelocity 尾喷反向初速
     * @param remainingBurnTicks 服务端同步的距发动机燃尽剩余 Tick
     */
    private static void drainPendingTrail(Minecraft minecraft, @Nullable RVP_EffectsData effects,
                                          TrailState state, double spacing, Vec3 exhaustVelocity,
                                          int remainingBurnTicks) {
        double safeSpacing = Math.max(spacing, MIN_TRAIL_SPACING);
        int spawned = 0;
        while (!state.pendingSegments.isEmpty() && spawned < MAX_TRAIL_PARTICLES_PER_SPAWN) {
            TrailSegment segment = state.pendingSegments.peekFirst();
            double remainingDistance = segment.remainingDistance();
            if (remainingDistance <= MIN_TRAIL_SPACING * 0.01D) {
                state.pendingSegments.removeFirst();
                continue;
            }

            double distanceToNext = safeSpacing - state.distanceSinceLastParticle;
            double travel = Math.max(distanceToNext, 0.0D);
            if (travel > remainingDistance) {
                state.distanceSinceLastParticle += remainingDistance;
                state.pendingSegments.removeFirst();
                continue;
            }

            Vec3 particlePosition = segment.positionAfter(travel);
            segment.advance(travel);
            state.distanceSinceLastParticle = 0.0D;
            // 调用 RVP 远程尾迹发射器，对路径上的补采样点复用同一风格与燃尽保持时间。
            RVP_RemoteMissileTrailEmitter.spawn(minecraft, effects, particlePosition,
                    exhaustVelocity, remainingBurnTicks);
            spawned++;
            if (segment.remainingDistance() <= MIN_TRAIL_SPACING * 0.01D) {
                state.pendingSegments.removeFirst();
            }
        }
    }

    /** 计算当前弹药尚未生成粒子的待补路径总长度，单位格。 */
    private static double pendingTrailDistance(TrailState state) {
        double total = 0.0D;
        for (TrailSegment segment : state.pendingSegments) {
            total += segment.remainingDistance();
        }
        return total;
    }

    /** 单个弹药实体的尾迹采样状态。 */
    private static final class TrailState {
        /** 上一个尾喷口采样位置。 */
        private Vec3 lastPosition;
        /** 上一次生成尾迹的客户端世界 tick。 */
        private long lastSpawnTick = Long.MIN_VALUE;
        /** 尚未按 LOD 间距生成粒子的路径线段队列。 */
        private final ArrayDeque<TrailSegment> pendingSegments = new ArrayDeque<>();
        /** 上一个已生成尾迹点之后累计的未满间距路径长度，单位格。 */
        private double distanceSinceLastParticle;

        /** 清空待补路径并重置间距累计，供异常跳点和边界切换重新播种。 */
        private void resetPendingPath() {
            this.pendingSegments.clear();
            this.distanceSinceLastParticle = 0.0D;
        }
    }

    /** 一段可被限额逐步消化的远程尾迹路径。 */
    private static final class TrailSegment {
        /** 线段起点世界坐标。 */
        private final Vec3 start;
        /** 线段方向向量，终点减起点。 */
        private final Vec3 delta;
        /** 线段总长度，单位格。 */
        private final double length;
        /** 已从线段起点消化的距离，单位格。 */
        private double consumedDistance;

        /** 创建一段可按距离推进的直线路径。 */
        private TrailSegment(Vec3 start, Vec3 end) {
            this.start = start;
            this.delta = end.subtract(start);
            this.length = this.delta.length();
        }

        /** 返回线段尚未消化的长度，单位格。 */
        private double remainingDistance() {
            return Math.max(this.length - this.consumedDistance, 0.0D);
        }

        /** 返回从当前消化游标继续前进指定距离后的位置。 */
        private Vec3 positionAfter(double distance) {
            if (this.length <= MIN_TRAIL_SPACING * 0.01D) {
                return this.start;
            }
            double ratio = Math.min((this.consumedDistance + Math.max(distance, 0.0D)) / this.length, 1.0D);
            return this.start.add(this.delta.scale(ratio));
        }

        /** 推进线段消化游标。 */
        private void advance(double distance) {
            this.consumedDistance = Math.min(this.length,
                    this.consumedDistance + Math.max(distance, 0.0D));
        }
    }
}
