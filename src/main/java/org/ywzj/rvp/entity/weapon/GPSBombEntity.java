package org.ywzj.rvp.entity.weapon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.all.AllSounds;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.util.VehicleExplosion;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

public class GPSBombEntity extends AmmoEntity implements ItemSupplier {

    private static final double GUIDANCE_STRENGTH = 0.05;
    private static final double MIN_GUIDANCE_DISTANCE_SQR = 1.0E-6;

    public float gravityScale = 1.0f;
    public Vec3 targetPos;
    public int fuseDelayTick;

    public boolean terminalIrEnabled = false;
    public float terminalIrActivationDistance = 0f;
    public float terminalIrSeekerFov = 30f;
    public float terminalIrSeekRange = 64f;
    public int terminalIrScanIntervalTick = 2;
    public boolean terminalIrVehicleOnly = true;
    public boolean terminalIrSmokeBreakLock = true;
    public int terminalIrMemoryTick = 0;
    public boolean terminalIrAllowReacquire = true;

    private boolean terminalIrActivated = false;
    private Entity terminalIrTarget;
    private Vec3 terminalIrLastSeenPos;
    private int terminalIrMemoryLeftTick = 0;
    private int terminalIrNextScanTick = 0;
    private boolean terminalIrEverLocked = false;
    private boolean terminalIrLostPermanent = false;

    private VehicleSound soundWhistle;
    private VehicleSound soundIncoming;

    public GPSBombEntity(EntityType<? extends Projectile> entityType, Level level) {
        super(entityType, level, null);
        this.keepChunkLoaded = true;
    }

    public GPSBombEntity(EntityType<? extends Projectile> entityType, Level level, ResourceLocation weaponId) {
        super(entityType, level, weaponId);
        this.keepChunkLoaded = true;
    }

    public GPSBombEntity(PlayMessages.SpawnEntity spawnEntity, Level level) {
        this(org.ywzj.rvp.all.RvpEntities.GPS_BOMB.get(), level);
        this.keepChunkLoaded = true;
    }

    @Override
    protected void defineSynchedData() {}

    public void shoot(AbstractVehicle vehicle, Component name, Vec3 spawnPos, float ammoXRot, float ammoYRot, LivingEntity shooter) {
        this.vehicle = vehicle;
        this.name = name;
        this.setPos(spawnPos);
        this.setRot(ammoYRot, ammoXRot);
        this.setDeltaMovement(vehicle.getDeltaMovement());
        this.setOwner(shooter);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickSound();
        } else {
            tickMoveGPSBomb();
            tickHit();
        }
    }

    private void tickMoveGPSBomb() {
        Vec3 motion = this.getDeltaMovement();
        if (!this.isNoGravity()) {
            motion = motion.add(0, -PhysicsEngine.G * gravityScale, 0);
        }
        if (!this.onGround()) {
            tickTerminalIr();
        }
        Vec3 guidanceTarget = getGuidanceTargetPos();
        if (guidanceTarget != null && !this.onGround()) {
            Vec3 toTarget = guidanceTarget.subtract(this.position());
            if (toTarget.lengthSqr() > MIN_GUIDANCE_DISTANCE_SQR && motion.lengthSqr() > MIN_GUIDANCE_DISTANCE_SQR) {
                double speed = motion.length();
                Vec3 currentDir = motion.normalize();
                Vec3 desiredDir = toTarget.normalize();
                Vec3 blended = currentDir.scale(1.0 - GUIDANCE_STRENGTH).add(desiredDir.scale(GUIDANCE_STRENGTH));
                if (blended.lengthSqr() > MIN_GUIDANCE_DISTANCE_SQR) {
                    motion = blended.normalize().scale(speed);
                }
            }
        }
        this.setDeltaMovement(motion);
        this.move(MoverType.SELF, motion);
        if (this.onGround()) {
            this.setDeltaMovement(Vec3.ZERO);
        } else if (motion.lengthSqr() > MIN_GUIDANCE_DISTANCE_SQR) {
            Vec2 rot = VectorUtil.vecToRot(motion);
            this.setXRot(rot.x);
            this.setYRot(rot.y);
            this.xRotO = Mth.rotLerp(0.2f, this.xRotO, this.getXRot());
            this.yRotO = Mth.rotLerp(0.2f, this.yRotO, this.getYRot());
        }
    }

    private void tickTerminalIr() {
        if (!terminalIrEnabled) {
            return;
        }
        if (!terminalIrActivated) {
            if (terminalIrActivationDistance <= 0f) {
                terminalIrActivated = true;
            } else if (targetPos != null && this.position().distanceTo(targetPos) <= terminalIrActivationDistance) {
                terminalIrActivated = true;
            } else {
                return;
            }
        }

        if (terminalIrTarget != null) {
            if (!isValidIrTarget(terminalIrTarget) || (terminalIrSmokeBreakLock && isSightObstructed(terminalIrTarget))) {
                loseIrLock();
            } else {
                terminalIrLastSeenPos = getAimPoint(terminalIrTarget);
                terminalIrMemoryLeftTick = terminalIrMemoryTick;
            }
        }

        if (terminalIrTarget == null) {
            if (terminalIrMemoryLeftTick > 0 && terminalIrLastSeenPos != null) {
                terminalIrMemoryLeftTick -= 1;
                if (terminalIrMemoryLeftTick <= 0) {
                    terminalIrMemoryLeftTick = 0;
                    terminalIrLastSeenPos = null;
                    if (terminalIrEverLocked && !terminalIrAllowReacquire) {
                        terminalIrLostPermanent = true;
                    }
                }
            }
            if (terminalIrLostPermanent || (terminalIrEverLocked && !terminalIrAllowReacquire)) {
                return;
            }
            if (tickCount < terminalIrNextScanTick) {
                return;
            }
            terminalIrNextScanTick = tickCount + Math.max(terminalIrScanIntervalTick, 1);
            Entity bestTarget = findBestIrTarget();
            if (bestTarget != null) {
                terminalIrTarget = bestTarget;
                terminalIrLastSeenPos = getAimPoint(bestTarget);
                terminalIrMemoryLeftTick = terminalIrMemoryTick;
                terminalIrEverLocked = true;
            }
        }
    }

    private Vec3 getGuidanceTargetPos() {
        if (terminalIrTarget != null) {
            return getAimPoint(terminalIrTarget);
        }
        if (terminalIrActivated) {
            if (terminalIrMemoryLeftTick > 0 && terminalIrLastSeenPos != null) {
                return terminalIrLastSeenPos;
            }
            if (terminalIrEverLocked) {
                return null;
            }
        }
        return targetPos;
    }

    private Entity findBestIrTarget() {
        double seekRange = Math.max(terminalIrSeekRange, 1.0f);
        AABB searchBox = this.getBoundingBox().inflate(seekRange);
        Vec3 seekerDirection = getSeekerDirection();
        Team ownerTeam = getOwner() == null ? null : getOwner().getTeam();
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : level().getEntities(this, searchBox, this::isCandidateEntity)) {
            if (!isValidIrTarget(entity)) {
                continue;
            }
            if (ownerTeam != null) {
                Team targetTeam = entity.getTeam();
                if (targetTeam != null && targetTeam.isAlliedTo(ownerTeam)) {
                    continue;
                }
            }
            Vec3 aimPoint = getAimPoint(entity);
            Vec3 offset = aimPoint.subtract(this.position());
            double distance = offset.length();
            if (distance > seekRange || distance <= 0.001) {
                continue;
            }
            double angle = Math.toDegrees(VectorUtil.angleBetween(seekerDirection, offset.normalize()));
            if (angle > terminalIrSeekerFov) {
                continue;
            }
            if (isSightObstructed(entity)) {
                continue;
            }
            double score = angle / Math.max(terminalIrSeekerFov, 1.0f) + distance / seekRange;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }
        return bestTarget;
    }

    private boolean isCandidateEntity(Entity entity) {
        if (entity == null
                || entity == this
                || entity == vehicle
                || entity == getOwner()
                || !entity.isAlive()
                || entity.isSpectator()) {
            return false;
        }
        if (vehicle != null && vehicle.getPassengers().contains(entity)) {
            return false;
        }
        if (terminalIrVehicleOnly) {
            return entity instanceof AbstractVehicle;
        }
        return entity.getBoundingBox().getSize() >= 1.0;
    }

    private boolean isValidIrTarget(Entity entity) {
        if (!isCandidateEntity(entity)) {
            return false;
        }
        if (terminalIrVehicleOnly && !(entity instanceof AbstractVehicle)) {
            return false;
        }
        return true;
    }

    private boolean isSightObstructed(Entity entity) {
        Vec3 checkStart = this.position();
        Vec3 checkEnd = getAimPoint(entity);
        EntityHitResult entityHit = VectorUtil.hitEntity(this, checkStart, checkEnd);
        if (entityHit == null) {
            return false;
        }
        Entity hitEntity = entityHit.getEntity();
        if (hitEntity == entity) {
            return false;
        }
        return hitEntity instanceof SightObstruction || hitEntity != entity;
    }

    private Vec3 getAimPoint(Entity entity) {
        return entity.getBoundingBox().getCenter();
    }

    private Vec3 getSeekerDirection() {
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() > MIN_GUIDANCE_DISTANCE_SQR) {
            return motion.normalize();
        }
        return this.getLookAngle();
    }

    private void loseIrLock() {
        terminalIrTarget = null;
        if (terminalIrMemoryTick > 0 && terminalIrLastSeenPos != null) {
            terminalIrMemoryLeftTick = terminalIrMemoryTick;
        } else {
            terminalIrMemoryLeftTick = 0;
            terminalIrLastSeenPos = null;
            if (terminalIrEverLocked && !terminalIrAllowReacquire) {
                terminalIrLostPermanent = true;
            }
        }
    }

    protected void tickHit() {
        if (onGround()) {
            if (fuseDelayTick > 0) {
                fuseDelayTick -= 1;
            } else {
                if (explosion != null && explosion.explode) {
                    VehicleExplosion vehicleExplosion = new VehicleExplosion(level(), this.getOwner(), this.vehicle, position(), explosion.radius, explosion.damage, explosion.destroyBlock);
                    vehicleExplosion.explode();
                }
                this.kill();
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void tickSound() {
        if (onGround()) {
            if (soundWhistle != null) {
                soundWhistle.stop();
            }
            if (soundIncoming != null) {
                soundIncoming.stop();
            }
        } else {
            Player player = LocalVehiclePlayer.instance.getPlayer();
            if (soundWhistle == null
                    && player.distanceTo(this) < 8
                    && !(player.getVehicle() instanceof AbstractVehicle)) {
                soundWhistle = new VehicleSound(AllSounds.BOMBS_INCOMING.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
                soundWhistle.play();
            }
            if (player.distanceTo(this) < 32) {
                if (soundIncoming == null) {
                    soundIncoming = new VehicleSound(AllSounds.BOMB_WHISTLE.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
                    soundIncoming.play();
                }
            }
        }
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeFloat(gravityScale);
        buffer.writeBoolean(targetPos != null);
        if (targetPos != null) {
            buffer.writeDouble(targetPos.x);
            buffer.writeDouble(targetPos.y);
            buffer.writeDouble(targetPos.z);
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf additionalData) {
        super.readSpawnData(additionalData);
        gravityScale = additionalData.readFloat();
        if (additionalData.readBoolean()) {
            targetPos = new Vec3(additionalData.readDouble(), additionalData.readDouble(), additionalData.readDouble());
        } else {
            targetPos = null;
        }
    }

    @Override
    public ItemStack getItem() {
        return AllItems.AMMO_AERIAL_BOMB.get().getDefaultInstance();
    }
}
