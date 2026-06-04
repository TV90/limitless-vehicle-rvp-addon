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
import org.ywzj.vehicle.vehicle.pojo.Explosion;

/**
 * Machinegun / cannon pellet. Motion and facing follow {@link org.ywzj.vehicle.entity.weapon.BulletEntity}
 * on both sides; server calls {@link RVP_BaseBullet#tickHitSegment} before motion in {@link #tickBullet()}.
 */
public class RVP_BulletEntity extends RVP_BaseBullet {

    private Vec3 startPos = Vec3.ZERO;
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
        this.startPos = spawnPos;
        RVP_EffectsData effects = data.getEffectsData();
        this.caliber = effects.getCaliber();
        this.tracerR = effects.getTracerR();
        this.tracerG = effects.getTracerG();
        this.tracerB = effects.getTracerB();
    }

    /**
     * Ignore server tracking rotation/lerp — client integrates facing from velocity like official bullets.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (teleport) {
            setPos(x, y, z);
        }
    }

    /** Entry from {@link RVP_BaseBullet#tick()}. */
    public void tickBullet() {
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
        // Hit test before motion (same segment as official BulletEntity / AmmoEntity#tickHit).
        tickHitSegment(position(), position().add(getDeltaMovement()));
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
     * Identical integration order to {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()}.
     */
    private void tickBulletMotionAndFacing() {
        Vec3 movement = getDeltaMovement();
        if (isInWater() && level().isClientSide()) {
            double mx = movement.x;
            double my = movement.y;
            double mz = movement.z;
            double nextPosX = getX() + mx;
            double nextPosY = getY() + my;
            double nextPosZ = getZ() + mz;
            for (int i = 0; i < 4; i++) {
                level().addParticle(ParticleTypes.BUBBLE,
                        nextPosX - mx * 0.25F, nextPosY - my * 0.25F, nextPosZ - mz * 0.25F, mx, my, mz);
            }
        }
        RVP_ProjectileMotion.tickCannonBullet(this);
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
            Explosion noBlast = new Explosion();
            noBlast.explode = false;
            child.explosion = noBlast;
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

    private static final String ID_SHOTGUN = "test_30mm_shotgun";
    private static final String ID_HE_FRAG = "test_30mm_he_frag";
    private static final String ID_CLUSTER = "test_30mm_cluster";

    @Nullable
    private String weaponPath() {
        ResourceLocation id = rvpData != null ? rvpData.getWeaponId() : getWeaponId();
        return id != null ? id.getPath() : null;
    }

    /** Cube tracer: 30mm 霰弹 / 榴霰弹；子母弹撒出的子弹粒也为正方体。 */
    public boolean usesShotgunCubeVisual() {
        String path = weaponPath();
        if (ID_CLUSTER.equals(path) && submunitionFlag != 0) {
            return true;
        }
        return ID_SHOTGUN.equals(path) || ID_HE_FRAG.equals(path);
    }

    /** Elongated dart tracer: 30mm 子母弹母弹。 */
    public boolean usesSabotDartVisual() {
        return ID_CLUSTER.equals(weaponPath()) && submunitionFlag == 0;
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
