package org.ywzj.rvp.virtualflight.server;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.virtualflight.trajectory.RVP_RvpTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualGuidanceInput;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryParameters;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryResult;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 阶段 A：固定 GPS 目标、无持久化、无客户端镜像的服务器虚拟中段管理器。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VirtualMissileManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESTORE_TICKET_LEVEL = 2;
    private static final Map<UUID, VirtualMissile> ACTIVE = new HashMap<>();
    private static RVP_VirtualTrajectoryIntegrator integrator = new RVP_RvpTrajectoryIntegrator();

    private RVP_VirtualMissileManager() {}

    /** 注入替代纯积分器；只允许在当前没有虚拟弹体时切换。 */
    public static void installIntegrator(RVP_VirtualTrajectoryIntegrator replacement) {
        if (!ACTIVE.isEmpty()) throw new IllegalStateException("Cannot replace integrator while virtual missiles are active");
        integrator = Objects.requireNonNull(replacement, "replacement");
    }

    /** 在实体完成当前 Tick 后尝试转入虚拟态。 */
    public static boolean tryVirtualize(RVP_MissileEntity missile) {
        if (!(missile.level() instanceof ServerLevel level) || !missile.isAlive() || ACTIVE.containsKey(missile.getUUID())) {
            return false;
        }
        RVP_WeaponData data = missile.getRvpData();
        Vec3 target = missile.getTargetPos();
        if (data == null || target == null || missile.getTargetEntity() != null) return false;
        RVP_VirtualMidcourseData config = data.getVirtualMidcourseData();
        if (!config.isEnabled() || !config.usesFixedSnapshotTarget()
                || data.getGuidanceData().getGuidanceType() != RVP_EnumGuidanceType.GPS
                || data.getGuidanceData().getTerminalGuidance() != null
                || (data.getGuidanceData().getTopAttackHeight() != null
                    && Math.abs(data.getGuidanceData().getTopAttackHeight()) > 1.0E-6f)
                || data.getSubmunitionData().isEnabled() || data.getEffectsData().isWireLinkEnabled()) return false;
        if (missile.getFlightTickCount() < config.getEntryMinFlightTick()
                || missile.position().distanceTo(missile.getVirtualMidcourseLaunchPosition()) < config.getEntryMinDistanceFromLaunch()
                || missile.position().distanceTo(target) < config.getEntryMinTargetDistance()) return false;

        Entity owner = missile.getOwner();
        VirtualMissile virtual = new VirtualMissile(
                missile.getUUID(), level.dimension(), missile.getWeaponId(), data,
                missile.createVirtualTrajectoryState(), missile.getVirtualMidcourseLaunchPosition(), target,
                owner == null ? null : owner.getUUID(), missile.getColdLaunchTimeTick(),
                integrator.implementationId(), integrator.implementationVersion(),
                level.getGameTime(), level.getGameTime(), 0, false);
        ACTIVE.put(virtual.uuid, virtual);
        LOGGER.debug("[RVP][VirtualFlight] enter uuid={} weapon={} integrator={} version={} pos={} target={}",
                virtual.uuid, virtual.weaponId, virtual.integratorId, virtual.integratorVersion,
                virtual.trajectory.position(), target);
        missile.discard();
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<VirtualMissile> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            VirtualMissile virtual = iterator.next();
            ServerLevel level = server.getLevel(virtual.dimension);
            if (level == null) {
                iterator.remove();
                continue;
            }
            if (virtual.waitingForRestore) {
                refreshRestoreTickets(level, virtual);
                virtual.restoreWaitTicks++;
                if (restoreAreaReady(level, virtual)) {
                    if (restoreEntity(level, virtual)) iterator.remove();
                } else if (virtual.restoreWaitTicks >= virtual.config().getRestoreWaitTimeoutTick()) {
                    LOGGER.warn("[RVP][VirtualFlight] restore timeout; discard uuid={} pos={}",
                            virtual.uuid, virtual.trajectory.position());
                    iterator.remove();
                }
                continue;
            }
            int interval = virtual.config().getVirtualUpdateIntervalTick();
            if (level.getGameTime() - virtual.lastIntegrationGameTime < interval) continue;
            for (int i = 0; i < interval; i++) {
                int nextTick = virtual.trajectory.flightTick() + 1;
                RVP_VirtualTrajectoryParameters parameters = RVP_VirtualTrajectoryParameters.from(
                        virtual.data, nextTick, virtual.coldLaunchTimeTick, virtual.trajectory.position().y);
                if (!virtual.integratorId.equals(integrator.implementationId())
                        || virtual.integratorVersion != integrator.implementationVersion()) break;
                RVP_VirtualTrajectoryResult result = integrator.step(virtual.trajectory,
                        new RVP_VirtualGuidanceInput(virtual.fixedTarget), parameters);
                virtual.trajectory = result.state();
                virtual.virtualFlightTicks++;
                if (result.invalid() || virtual.trajectory.remainingLife() < 0
                        || virtual.virtualFlightTicks >= virtual.config().getMaxVirtualFlightTick()) break;
            }
            virtual.lastIntegrationGameTime = level.getGameTime();
            if (!isViable(virtual)) {
                iterator.remove();
                continue;
            }
            double restoreDistance = Math.max(virtual.config().getRestoreTargetDistance(),
                    virtual.trajectory.velocity().length() * virtual.config().getRestoreLeadTick());
            if (virtual.trajectory.position().distanceTo(virtual.fixedTarget) <= restoreDistance) {
                virtual.waitingForRestore = true;
                refreshRestoreTickets(level, virtual);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    private static boolean isViable(VirtualMissile v) {
        return v.integratorId.equals(integrator.implementationId())
                && v.integratorVersion == integrator.implementationVersion()
                && v.trajectory.remainingLife() >= 0 && v.virtualFlightTicks < v.config().getMaxVirtualFlightTick()
                && finite(v.trajectory.position()) && finite(v.trajectory.velocity());
    }

    private static void refreshRestoreTickets(ServerLevel level, VirtualMissile v) {
        ChunkPos center = new ChunkPos(BlockPos.containing(v.trajectory.position()));
        int radius = v.config().getRestoreTicketRadius();
        int key = v.uuid.hashCode();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, new ChunkPos(x, z),
                        RESTORE_TICKET_LEVEL, key);
            }
        }
    }

    private static boolean restoreAreaReady(ServerLevel level, VirtualMissile v) {
        ChunkPos center = new ChunkPos(BlockPos.containing(v.trajectory.position()));
        int radius = v.config().getRestoreTicketRadius();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                BlockPos sample = new ChunkPos(x, z).getMiddleBlockPosition((int) v.trajectory.position().y);
                if (!level.hasChunkAt(sample) || !level.isPositionEntityTicking(sample)) return false;
            }
        }
        return true;
    }

    private static boolean restoreEntity(ServerLevel level, VirtualMissile v) {
        RVP_MissileEntity missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, v.weaponId);
        LivingEntity owner = resolveLivingOwner(level, v.ownerUuid);
        RVP_BaseBullet.AimRot aim = new RVP_BaseBullet.AimRot(v.trajectory.xRot(), v.trajectory.yRot());
        missile.initFromWeapon(v.data, RVP_EnumWeaponKind.MISSILE, null, owner,
                v.trajectory.position(), aim, v.trajectory.velocity());
        missile.setUUID(v.uuid);
        missile.restoreVirtualTrajectoryState(v.trajectory, v.fixedTarget, v.launchPosition);
        if (!level.addFreshEntity(missile)) {
            LOGGER.warn("[RVP][VirtualFlight] entity restore rejected uuid={} pos={}", v.uuid, v.trajectory.position());
            return false;
        }
        missile.primeDynamicChunkPath();
        LOGGER.debug("[RVP][VirtualFlight] restore uuid={} pos={} tick={}",
                v.uuid, v.trajectory.position(), v.trajectory.flightTick());
        return true;
    }

    @Nullable
    private static LivingEntity resolveLivingOwner(ServerLevel level, @Nullable UUID uuid) {
        if (uuid == null) return null;
        Entity entity = level.getEntity(uuid);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static final class VirtualMissile {
        final UUID uuid;
        final ResourceKey<Level> dimension;
        final ResourceLocation weaponId;
        final RVP_WeaponData data;
        RVP_VirtualTrajectoryState trajectory;
        final Vec3 launchPosition;
        final Vec3 fixedTarget;
        @Nullable final UUID ownerUuid;
        final int coldLaunchTimeTick;
        final String integratorId;
        final int integratorVersion;
        final long enteredGameTime;
        long lastIntegrationGameTime;
        int virtualFlightTicks;
        int restoreWaitTicks;
        boolean waitingForRestore;

        VirtualMissile(UUID uuid, ResourceKey<Level> dimension, ResourceLocation weaponId,
                       RVP_WeaponData data, RVP_VirtualTrajectoryState trajectory,
                       Vec3 launchPosition, Vec3 fixedTarget, @Nullable UUID ownerUuid,
                       int coldLaunchTimeTick, String integratorId, int integratorVersion,
                       long enteredGameTime, long lastIntegrationGameTime,
                       int virtualFlightTicks, boolean waitingForRestore) {
            this.uuid = uuid;
            this.dimension = dimension;
            this.weaponId = weaponId;
            this.data = data;
            this.trajectory = trajectory;
            this.launchPosition = launchPosition;
            this.fixedTarget = fixedTarget;
            this.ownerUuid = ownerUuid;
            this.coldLaunchTimeTick = coldLaunchTimeTick;
            this.integratorId = integratorId;
            this.integratorVersion = integratorVersion;
            this.enteredGameTime = enteredGameTime;
            this.lastIntegrationGameTime = lastIntegrationGameTime;
            this.virtualFlightTicks = virtualFlightTicks;
            this.waitingForRestore = waitingForRestore;
        }

        RVP_VirtualMidcourseData config() { return data.getVirtualMidcourseData(); }
    }
}
