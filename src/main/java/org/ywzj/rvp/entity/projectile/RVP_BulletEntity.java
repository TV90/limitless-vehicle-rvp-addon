package org.ywzj.rvp.entity.projectile;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Explosion;

/**
 * Machinegun / cannon pellet. Motion and facing follow {@link org.ywzj.vehicle.entity.weapon.BulletEntity}
 * on both sides; server calls {@link RVP_BaseBullet#tickHit()} before motion in {@link #tickBullet()}.
 */
public class RVP_BulletEntity extends RVP_BaseBullet {

    private Vec3 startPos = Vec3.ZERO;
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

    @Override
    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                               org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle,
                               LivingEntity shooter, Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        super.initFromWeapon(data, kind, vehicle, shooter, spawnPos, aim, initialMotion);
        this.keepChunkLoaded = false;
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
        if (!level().isClientSide()) {
            if (!tickBulletServerPreMotion()) {
                return;
            }
        }
        tickBulletMotionAndFacing();
        if (!level().isClientSide()) {
            tickBulletServerPostMotion();
        }
    }

    private boolean tickBulletServerPreMotion() {
        if (rvpData == null) {
            discard();
            return false;
        }
        updateCount++;
        if (tickDelayFuse()) {
            return false;
        }
        if (!checkShooterValid()) {
            discard();
            return false;
        }
        tickSubmunition();
        tickGuidance();
        // Hit test before motion (same as {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()}).
        tickHit();
        return isAlive();
    }

    private void tickBulletServerPostMotion() {
        tickProgrammableAirburst();
        tickProximityFuse();
        if (tickBounceFuse()) {
            return;
        }
        broadcastTrailParticles();
        life--;
        if (life < 0) {
            if (rvpData.getFuseData().isDetonateOnLifeEnd()) {
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

        double nextPosX = getX() + x;
        double nextPosY = getY() + y;
        double nextPosZ = getZ() + z;
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
        setDeltaMovement(getDeltaMovement().scale(1 - friction));
        setDeltaMovement(getDeltaMovement().add(0, -gravity, 0));
        if (level().isClientSide() && tickCount >= life - 1) {
            discard();
        }
    }

    @Override
    protected void sprinkleSubmunition() {
        if (level().isClientSide() || rvpData == null || shooterVehicle == null) {
            return;
        }
        RVP_BulletEntity child = new RVP_BulletEntity(RVP_Entities.RVP_BULLET.get(), level(), rvpData.getWeaponId());
        Vec3 velocity = getDeltaMovement();
        LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
        child.initFromWeapon(rvpData, RVP_EnumWeaponKind.MACHINEGUN, shooterVehicle, shooter,
                position(), new AimRot(getXRot(), getYRot()), velocity);
        child.setShooterWeaponUnit(getShooterWeaponUnit());
        child.submunitionFlag = 1;
        child.submunitionsRemaining = 0;
        child.sprinkleTime = 0;
        child.damage = Math.max(1f, damage * 0.35f);
        if (child.explosion != null) {
            child.explosion = RVP_Explosion.disabled();
        }
        RandomSource rand = level().getRandom();
        float spread = rvpData.getBombletDiff();
        Vec3 spreadVel = velocity.add(
                (rand.nextDouble() - 0.5) * spread,
                (rand.nextDouble() - 0.5) * spread * 0.5,
                (rand.nextDouble() - 0.5) * spread
        );
        child.setDeltaMovement(spreadVel);
        child.finalizeSpawnOrientation(new AimRot(child.getXRot(), child.getYRot()));
        level().addFreshEntity(child);
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
        startPos = position();
        applyCannonFacingFromVelocity(getDeltaMovement(), false);
        yRotO = getYRot();
        xRotO = getXRot();
    }
}
