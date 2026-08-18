package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
    /** 弹药克隆位置允许的最大外推 tick。 */
    private static final double MAX_EXTRAPOLATION_TICK = 5.0D;
    /** 尾迹相邻采样点允许连接的最大距离平方。 */
    private static final double MAX_TRAIL_LINK_DISTANCE_SQ = 64.0D * 64.0D;
    /** 按弹药实体 ID 保存的尾迹采样状态。 */
    private static final Map<Integer, TrailState> TRAIL_STATES = new HashMap<>();
    /** 当前尾迹状态所属维度。 */
    private static ResourceLocation trailDimension;

    private RVP_RemoteAmmoVisualRenderer() {
    }

    /** 在正常实体之后绘制服务端授权的远程弹药克隆。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
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
        boolean rendered = false;

        for (LocalVehiclePlayer.ServerEntity remote : LocalVehiclePlayer.instance.serverEntities.values()) {
            Entity entity = remote.entity;
            if (entity == null || !isSupported(entity) || minecraft.level.getEntity(entity.getId()) != null) {
                continue;
            }
            if (!RVP_ClientRemoteAmmoVisualState.contains(currentDimension, entity.getId())) {
                continue;
            }
            Vec3 renderPos = extrapolatedPosition(minecraft, remote, entity, event.getPartialTick());
            if (!isVisibleAtRange(entity, renderPos, cameraPos)) {
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
        if (rendered) {
            buffers.endBatch();
        }
        Iterator<Integer> trailIterator = TRAIL_STATES.keySet().iterator();
        while (trailIterator.hasNext()) {
            if (!RVP_ClientRemoteAmmoVisualState.contains(currentDimension, trailIterator.next())) {
                trailIterator.remove();
            }
        }
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

    /** 根据本体远程克隆更新时间进行最多五 tick 的短时外推。 */
    private static Vec3 extrapolatedPosition(Minecraft mc, LocalVehiclePlayer.ServerEntity remote,
                                             Entity entity, float partialTick) {
        int updateTick = remote.updateTick == null ? mc.player.tickCount : remote.updateTick;
        double age = Mth.clamp(mc.player.tickCount - updateTick + partialTick,
                0.0D, MAX_EXTRAPOLATION_TICK);
        return entity.position().add(entity.getDeltaMovement().scale(age));
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
        if (!RVP_ClientRemoteAmmoVisualState.isMotorBurning(dimension, entity.getId())) {
            TRAIL_STATES.remove(entity.getId());
            return;
        }

        long gameTick = minecraft.level.getGameTime();
        double horizontalDistance = Math.sqrt(horizontalDistanceSqr(renderPos, cameraPos));
        int interval = horizontalDistance < 768.0D ? 1 : horizontalDistance < 1152.0D ? 2 : 3;
        double spacing = horizontalDistance < 768.0D ? 1.0D : horizontalDistance < 1152.0D ? 2.0D : 4.0D;
        TrailState state = TRAIL_STATES.computeIfAbsent(entity.getId(), ignored -> new TrailState());
        Vec3 nozzlePos = remoteNozzlePosition(entity, renderPos);
        if (state.lastSpawnTick == gameTick || gameTick % interval != 0) {
            return;
        }

        Vec3 previous = state.lastPosition;
        state.lastPosition = nozzlePos;
        state.lastSpawnTick = gameTick;
        if (previous == null || previous.distanceToSqr(nozzlePos) > MAX_TRAIL_LINK_DISTANCE_SQ) {
            addSmokeParticle(minecraft, nozzlePos);
            return;
        }

        Vec3 delta = nozzlePos.subtract(previous);
        double distance = delta.length();
        int segments = Math.max(1, (int) Math.ceil(distance / spacing));
        for (int index = 1; index <= segments; index++) {
            addSmokeParticle(minecraft, previous.add(delta.scale((double) index / segments)));
        }
        if (horizontalDistance < 768.0D && gameTick % 2L == 0L) {
            Vec3 velocity = entity.getDeltaMovement();
            minecraft.level.addParticle(ParticleTypes.FLAME, true,
                    nozzlePos.x, nozzlePos.y, nozzlePos.z,
                    -velocity.x * 0.01D, -velocity.y * 0.01D, -velocity.z * 0.01D);
        }
    }

    /** 根据弹体速度估算远程尾喷口世界位置。 */
    private static Vec3 remoteNozzlePosition(Entity entity, Vec3 renderPos) {
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 rear = velocity.lengthSqr() > 1.0E-6D ? velocity.normalize().scale(-1.0D) : Vec3.ZERO;
        double offset = entity instanceof RocketEntity || entity instanceof RVP_RocketEntity ? 1.0D : 2.0D;
        return renderPos.add(rear.scale(offset));
    }

    /** 计算两个位置的水平距离平方。 */
    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    /** 在指定位置生成一枚远程烟迹粒子。 */
    private static void addSmokeParticle(Minecraft minecraft, Vec3 position) {
        minecraft.level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                position.x, position.y, position.z, 0.0D, 0.0D, 0.0D);
    }

    /** 单个弹药实体的尾迹采样状态。 */
    private static final class TrailState {
        /** 上一个尾喷口采样位置。 */
        private Vec3 lastPosition;
        /** 上一次生成尾迹的客户端世界 tick。 */
        private long lastSpawnTick = Long.MIN_VALUE;
    }
}
