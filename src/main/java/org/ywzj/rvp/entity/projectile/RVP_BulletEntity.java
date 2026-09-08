package org.ywzj.rvp.entity.projectile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.vehicle.util.BulletHitResult;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;
import org.ywzj.rvp.util.RVP_ChunkPathLoadManager;
import org.ywzj.rvp.util.RVP_ChunkPathLoader;
/**
 * Machinegun / cannon pellet. Both sides integrate motion; server runs {@link RVP_BaseBullet#tickHit()} before
 * movement. Clients with bounce predict block ricochet locally (no extra network packet).
 */
public class RVP_BulletEntity extends RVP_BaseBullet {

    private static final int LERP_SUPPRESS_TICKS_AFTER_BOUNCE = 3;

    private Vec3 startPos = Vec3.ZERO;
    /** Suppress {@link org.ywzj.vehicle.entity.weapon.AmmoEntity} position lerp briefly after a bounce. */
    private int lerpSuppressTicks;
    /** Position at tick start (after {@link #tick()} housekeeping), before hit-test / motion integration. */
    private Vec3 tickSegmentStart = Vec3.ZERO;
    private float caliber = 7.62f;
    private float tracerR = 1f;
    private float tracerG = 0.85f;
    private float tracerB = 0.2f;

    public RVP_BulletEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_BulletEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_BulletEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_BULLET.get(), level);
    }

    /** 普通机枪 Bullet 保持既有行为；仅明确标记的远程炮火弹申请动态路径。 */
    @Override
    protected boolean shouldKeepDynamicChunkPathLoaded() {
        return isRemoteChunkPathEnabled();
    }

    @Override
    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                               org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle,
                               LivingEntity shooter, Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        super.initFromWeapon(data, kind, vehicle, shooter, spawnPos, aim, initialMotion);
        // keepChunkLoaded 保持基类 RVP_BaseBullet 构造器的 false。
        this.startPos = spawnPos;
        RVP_EffectsData effects = data.getEffectsData();
        this.caliber = effects.getCaliber();
        this.tracerR = effects.getTracerR();
        this.tracerG = effects.getTracerG();
        this.tracerB = effects.getTracerB();
    }

    @Override
    protected Vec3 collisionSegmentStart() {
        return tickSegmentStart;
    }

    @Override
    protected Vec3 collisionSegmentEnd() {
        return tickSegmentStart.add(getDeltaMovement());
    }

    /** Entry from {@link RVP_BaseBullet#tick()}. */
    public void tickBullet() {
        tickSegmentStart = position();
        if (level().isClientSide()) {
            predictBounceBeforeMotion();
            // 调用本项目客户端桥：机枪类若启用纯粒子模式，同样按实体 Tick 生成主体与尾迹。
            RVP_ClientActionsAccess.tickParticleProjectile(this);
            // 客户端本地补渲轨迹粒子（force=true 绕过原版 32 格粒子裁剪，见基类注释）
            spawnClientLocalTrailParticles();
        } else if (!tickBulletServerPreMotion()) {
            return;
        }
        tickBulletMotionAndFacing();
        if (!level().isClientSide() && isAlive()) {
            tickBulletServerPostMotion();
        }
    }

    /**
     * Client-side bounce before motion (entity then block, same order as {@link #tickHit()}).
     */
    private void predictBounceBeforeMotion() {
        if (bounceLeft <= 0) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        if (motion.lengthSqr() <= 1.0E-6) {
            return;
        }
        Vec3 end = tickSegmentStart.add(motion);
        BulletHitResult entityResult = findEntityOnPathForSegment(tickSegmentStart, end, motion);
        if (entityResult != null
                && entityResult.getEntity() != vehicle
                && (vehicle == null || !vehicle.getPassengers().contains(entityResult.getEntity()))
                && tryBounceFromEntityHit(entityResult)) {
            applyBounceVisualState(position(), getDeltaMovement(), getYRot(), getXRot(), bounceLeft);
            return;
        }
        BlockHitResult hit = level().clip(new ClipContext(
                tickSegmentStart, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hit.getType() != HitResult.Type.MISS && tryBounceFromBlockHit(hit)) {
            applyBounceVisualState(position(), getDeltaMovement(), getYRot(), getXRot(), bounceLeft);
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (level().isClientSide() && lerpSuppressTicks > 0) {
            lerpSuppressTicks--;
            setPos(x, y, z);
            setRot(yRot, xRot);
            yRotO = yRot;
            xRotO = xRot;
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
    }

    /** Snap client state after a locally predicted bounce. */
    public void applyBounceVisualState(Vec3 pos, Vec3 velocity, float yRot, float xRot, int bouncesLeft) {
        setPos(pos);
        setDeltaMovement(velocity);
        setYRot(yRot);
        setXRot(xRot);
        yRotO = yRot;
        xRotO = xRot;
        bounceLeft = bouncesLeft;
        lerpSuppressTicks = LERP_SUPPRESS_TICKS_AFTER_BOUNCE;
    }

    private boolean tickBulletServerPreMotion() {
        if (resolveWeaponConfig() == null) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.CONFIG_MISSING,
                    () -> "action=discard path=machinegun");
            discard();
            return false;
        }
        if (shouldKeepDynamicChunkPathLoaded()) {
            if (tickChunkWaitGate()) return false;
            // 调用本项目动态路径加载器，远程炮火 Bullet 在推进引信和飞行时钟前等待路径就绪。
            RVP_ChunkPathLoader.PathLoadResult path = requestDynamicChunkPath(
                    RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
            if (!path.currentTickPathReady()) {
                enterChunkWait(path, false);
                return false;
            }
        }
        updateCount++;
        if (tickDelayFuse()) {
            return false;
        }
        if (!checkShooterValid()) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.SHOOTER_INVALID,
                    () -> "owner=" + RVP_ProjectileLifecycleDebug.formatEntity(getOwner())
                            + " shooterVehicle=" + RVP_ProjectileLifecycleDebug.formatEntity(shooterVehicle)
                            + " action=discard path=machinegun");
            discard();
            return false;
        }
        tickSubmunition();
        tickGuidance();
        // 调用本项目近地引信扫掠：机炮弹同样必须在本 Tick 碰撞检测前于配置高度起爆。
        if (tickGroundProximityFuse()) {
            return false;
        }
        // Hit test before motion (same as {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()}).
        tickHit();
        return isAlive();
    }

    private void tickBulletServerPostMotion() {
        if (shouldKeepDynamicChunkPathLoaded()) {
            // 调用本项目动态路径加载器，为远程炮弹下一 Tick 的连续路径提前申请租约。
            requestDynamicChunkPath(RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
            RVP_ChunkPathLoadManager.recordPostMoveObservation(this);
        }
        tickProgrammableAirburst();
        tickProximityFuse();
        if (tickBounceFuse()) {
            return;
        }
        broadcastTrailParticles();
        life--;
        if (life < 0) {
            boolean detonate = rvpData.getFuseData().isDetonateOnLifeEnd();
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.LIFE_END,
                    () -> "detonate=" + detonate + " path=machinegun position="
                            + RVP_ProjectileLifecycleDebug.formatVec(position()));
            if (detonate) {
                explodeAndDiscard(position());
            } else {
                discard();
            }
        }
    }

    /**
     * Copied from {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()} (motion / facing / life).
     */
    private void tickBulletMotionAndFacing() {
        Vec3 movement = getDeltaMovement();
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;
        double distance = movement.horizontalDistance();
        setYRot((float) Math.toDegrees(Mth.atan2(x, z)));
        setXRot((float) Math.toDegrees(Mth.atan2(y, distance)));
        if (xRotO == 0.0F && yRotO == 0.0F) {
            yRotO = getYRot();
            xRotO = getXRot();
        }
        setXRot(lerpRotation(xRotO, getXRot()));
        setYRot(lerpRotation(yRotO, getYRot()));

        // 调用本项目共享无制导炮弹步进；位置与速度更新顺序保持本体 Bullet 语义。
        RVP_UnguidedBallisticMath.Step step = RVP_UnguidedBallisticMath.stepCannon(
                position(), movement, cannonFriction, cannonGravity);
        double nextPosX = step.position().x;
        double nextPosY = step.position().y;
        double nextPosZ = step.position().z;
        // 调用服务端实体 Tick 就绪查询，在移动前确认目标区块不仅已加载且允许实体 Tick；
        // 普通机枪 Bullet 不强加载区块；远程炮火路径已在前置门确认就绪，仍用同一检查兜底。
        if (level() instanceof ServerLevel serverLevel
                && !serverLevel.isPositionEntityTicking(BlockPos.containing(nextPosX, nextPosY, nextPosZ))) {
            discard();
            return;
        }
        setPos(nextPosX, nextPosY, nextPosZ);
        flightDistance += movement.length();

        float friction = cannonFriction;
        float gravity = cannonGravity;
        if (isInWater()) {
            for (int i = 0; i < 4; i++) {
                level().addParticle(ParticleTypes.BUBBLE,
                        nextPosX - x * 0.25F, nextPosY - y * 0.25F, nextPosZ - z * 0.25F, x, y, z);
            }
            friction = 0.4F;
            gravity *= 0.6F;
        }
        if (friction == cannonFriction && gravity == cannonGravity) {
            setDeltaMovement(step.velocity());
        } else {
            setDeltaMovement(getDeltaMovement().scale(1 - friction).add(0, -gravity, 0));
        }
        if (level().isClientSide() && tickCount >= life - 1) {
            discard();
        }
    }

    public Vec3 getStartPos() {
        return startPos;
    }

    @Override
    public float getCaliber() {
        return caliber;
    }

    public float getTracerR() {
        return tracerR;
    }

    public float getTracerG() {
        return tracerG;
    }

    public float getTracerB() {
        return tracerB;
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeFloat(cannonFriction);
        buffer.writeFloat(cannonGravity);
        buffer.writeInt(life);
        buffer.writeFloat(caliber);
        buffer.writeFloat(tracerR);
        buffer.writeFloat(tracerG);
        buffer.writeFloat(tracerB);
        buffer.writeVarInt(bounceLeft);
        buffer.writeFloat(bounceStrength);
        buffer.writeFloat(bounceIncidenceAngleMin);
        buffer.writeBoolean(bounceOnVehicle);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        cannonFriction = buffer.readFloat();
        cannonGravity = buffer.readFloat();
        life = buffer.readInt();
        caliber = buffer.readFloat();
        tracerR = buffer.readFloat();
        tracerG = buffer.readFloat();
        tracerB = buffer.readFloat();
        bounceLeft = buffer.readVarInt();
        bounceStrength = buffer.readFloat();
        bounceIncidenceAngleMin = buffer.readFloat();
        bounceOnVehicle = buffer.readBoolean();
        startPos = position();
        applyCannonFacingFromVelocity(getDeltaMovement(), false);
        yRotO = getYRot();
        xRotO = getXRot();
    }
}
