package org.ywzj.rvp.client.render;

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
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.state.RVP_ClientExtendedAirVisualState;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ExtendedAirEntityRenderer {

    public static final double BASE_RANGE = 32.0D * 16.0D;
    public static final double UNCONDITIONAL_RANGE = 64.0D * 16.0D;
    public static final double EXTENDED_RANGE = 256.0D * 16.0D;
    public static final double MIN_AGL = 100.0D;
    private static final double BASE_RANGE_SQ = BASE_RANGE * BASE_RANGE;
    private static final double UNCONDITIONAL_RANGE_SQ = UNCONDITIONAL_RANGE * UNCONDITIONAL_RANGE;
    private static final double EXTENDED_RANGE_SQ = EXTENDED_RANGE * EXTENDED_RANGE;
    private static final double MAX_EXTRAPOLATION_TICK = 5.0D;
    private static final double MAX_TRAIL_LINK_DISTANCE_SQ = 64.0D * 64.0D;
    /** 是否渲染载具（飞机）超视距；与广播服务端开关保持一致，当前需求为关闭车辆、保留弹药超视距。 */
    private static final boolean ENABLE_AIR_VEHICLE_RENDER = false;
    private static final Map<Integer, TrailState> TRAIL_STATES = new HashMap<>();
    private static ResourceLocation trailDimension;

    private RVP_ExtendedAirEntityRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        ResourceLocation currentDimension = mc.level.dimension().location();
        if (!currentDimension.equals(trailDimension)) {
            TRAIL_STATES.clear();
            trailDimension = currentDimension;
        }
        boolean rendered = false;

        for (LocalVehiclePlayer.ServerEntity remote : LocalVehiclePlayer.instance.serverEntities.values()) {
            Entity entity = remote.entity;
            if (entity == null || !isSupported(entity) || mc.level.getEntity(entity.getId()) != null) {
                continue;
            }
            if (!RVP_ClientExtendedAirVisualState.contains(mc.level.dimension().location(), entity.getId())) {
                continue;
            }
            Vec3 renderPos = extrapolatedPosition(mc, remote, entity, event.getPartialTick());
            if (!isVisibleAtRange(entity, renderPos, cameraPos)) {
                continue;
            }
            if (entity instanceof AbstractVehicle vehicle && !ensureVehicleDisplayInitialized(vehicle)) {
                continue;
            }

            spawnRemoteTrail(mc, entity, renderPos, cameraPos, currentDimension);

            Vec3 oldPos = entity.position();
            float oldXRot = entity.getXRot();
            float oldYRot = entity.getYRot();
            float oldXRotO = entity.xRotO;
            float oldYRotO = entity.yRotO;
            float oldZRot = entity instanceof AbstractVehicle vehicle ? vehicle.getZRot() : 0.0F;
            float oldZRotO = entity instanceof AbstractVehicle vehicle ? vehicle.zRotO : 0.0F;
            applyMotionFacing(entity);
            entity.setPos(renderPos);
            entity.xo = renderPos.x;
            entity.yo = renderPos.y;
            entity.zo = renderPos.z;
            try {
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
                if (entity instanceof AbstractVehicle vehicle) {
                    vehicle.setZRot(oldZRot);
                    vehicle.zRotO = oldZRotO;
                }
            }
        }
        if (rendered) {
            buffers.endBatch();
        }
        Iterator<Integer> trailIterator = TRAIL_STATES.keySet().iterator();
        while (trailIterator.hasNext()) {
            if (!RVP_ClientExtendedAirVisualState.contains(currentDimension, trailIterator.next())) {
                trailIterator.remove();
            }
        }
    }

    public static boolean isVisualOnlyRvpAmmo(Entity entity) {
        return entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    private static boolean isSupported(Entity entity) {
        return (ENABLE_AIR_VEHICLE_RENDER && entity instanceof AbstractVehicle)
                || entity instanceof MissileEntity
                || entity instanceof RocketEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_MissileEntity
                || entity instanceof RVP_RocketEntity
                || entity instanceof RVP_BombEntity;
    }

    private static boolean ensureVehicleDisplayInitialized(AbstractVehicle vehicle) {
        if (vehicle.getModelInstance() != null) {
            return true;
        }
        BaseDisplay display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null || display.getModel() == null || display.getTexture() == null) {
            return false;
        }
        vehicle.initDisplayData(display);
        return vehicle.getModelInstance() != null;
    }

    private static Vec3 extrapolatedPosition(Minecraft mc, LocalVehiclePlayer.ServerEntity remote,
                                             Entity entity, float partialTick) {
        int updateTick = remote.updateTick == null ? mc.player.tickCount : remote.updateTick;
        double age = Mth.clamp(mc.player.tickCount - updateTick + partialTick, 0.0D, MAX_EXTRAPOLATION_TICK);
        return entity.position().add(entity.getDeltaMovement().scale(age));
    }

    private static boolean isVisibleAtRange(Entity entity, Vec3 position, Vec3 cameraPos) {
        double dx = position.x - cameraPos.x;
        double dz = position.z - cameraPos.z;
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= BASE_RANGE_SQ || distanceSq > EXTENDED_RANGE_SQ) {
            return false;
        }
        if (entity instanceof RVP_BulletEntity && distanceSq > UNCONDITIONAL_RANGE_SQ) {
            return false;
        }
        return true;
    }

    private static void applyMotionFacing(Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() <= 1.0E-6D) {
            return;
        }
        Vec2 rot = VectorUtil.vecToRot(velocity);
        entity.setXRot(rot.x);
        entity.setYRot(rot.y);
        entity.xRotO = rot.x;
        entity.yRotO = rot.y;
        if (entity instanceof AbstractVehicle vehicle) {
            vehicle.setZRot(0.0F);
            vehicle.zRotO = 0.0F;
        }
    }

    private static void spawnRemoteTrail(Minecraft mc, Entity entity, Vec3 renderPos, Vec3 cameraPos,
                                         ResourceLocation dimension) {
        if (!(entity instanceof MissileEntity || entity instanceof RocketEntity
                || entity instanceof RVP_MissileEntity || entity instanceof RVP_RocketEntity)) {
            return;
        }
        if (!RVP_ClientExtendedAirVisualState.isMotorBurning(dimension, entity.getId())) {
            TRAIL_STATES.remove(entity.getId());
            return;
        }

        long gameTick = mc.level.getGameTime();
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
            addSmokeParticle(mc, nozzlePos);
            return;
        }

        Vec3 delta = nozzlePos.subtract(previous);
        double distance = delta.length();
        int segments = Math.max(1, (int) Math.ceil(distance / spacing));
        for (int i = 1; i <= segments; i++) {
            addSmokeParticle(mc, previous.add(delta.scale((double) i / segments)));
        }
        if (horizontalDistance < 768.0D && gameTick % 2L == 0L) {
            Vec3 velocity = entity.getDeltaMovement();
            mc.level.addParticle(ParticleTypes.FLAME, true,
                    nozzlePos.x, nozzlePos.y, nozzlePos.z,
                    -velocity.x * 0.01D, -velocity.y * 0.01D, -velocity.z * 0.01D);
        }
    }

    private static Vec3 remoteNozzlePosition(Entity entity, Vec3 renderPos) {
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 rear = velocity.lengthSqr() > 1.0E-6D ? velocity.normalize().scale(-1.0D) : Vec3.ZERO;
        double offset = entity instanceof RocketEntity || entity instanceof RVP_RocketEntity ? 1.0D : 2.0D;
        return renderPos.add(rear.scale(offset));
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private static void addSmokeParticle(Minecraft mc, Vec3 position) {
        mc.level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                position.x, position.y, position.z, 0.0D, 0.0D, 0.0D);
    }

    private static final class TrailState {
        private Vec3 lastPosition;
        private long lastSpawnTick = Long.MIN_VALUE;
    }
}
