package org.ywzj.rvp.entity.gunner;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public class GunnerEntity extends Mob {
    private static final EntityDataAccessor<String> PROFILE_ID = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> PROFILE_FACTION = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> TRACKED_TARGET_ID = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CONTROLLED_WEAPON_INDEX = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.INT);

    @Nullable
    private UUID ownerUuid;
    @Nullable
    private String ownerTeamName;
    private int detachedTicks;
    private int trackedTargetId = -1;
    private int refilledVehicleId = -1;
    private String profileId = GunnerProfileManager.DEFAULT_PROFILE_ID.toString();
    private int burstFireTicks;
    private int burstRestTicks;
    private int pendingBurstRestTicks;
    private int countermeasureCooldown;
    private double lastDriveCheckX;
    private double lastDriveCheckZ;
    private int recoveryTicks;
    private int recoveryTotalTicks;
    private int recoveryCooldownTicks;
    private int tacticalHoldTicks;
    private int tacticalEvadeTicks;
    private float tacticalEvadeYawBias;
    private int controlledWeaponIndex = -1;
    private int groundBigTurnCooldown;
    private int groundBigTurnTicks;
    private float groundBigTurnTargetYaw;
    private int airPhase;
    private int airPhaseTicks;
    private boolean airPhaseInitialized;
    private boolean homePosSet;
    private double homePosX;
    private double homePosY;
    private double homePosZ;

    public GunnerEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 8.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(PROFILE_ID, GunnerProfileManager.DEFAULT_PROFILE_ID.toString());
        this.entityData.define(PROFILE_FACTION, "friendly");
        this.entityData.define(TRACKED_TARGET_ID, -1);
        this.entityData.define(CONTROLLED_WEAPON_INDEX, -1);
    }

    public void initOwner(Player owner) {
        this.ownerUuid = owner.getUUID();
        Team team = owner.getTeam();
        this.ownerTeamName = team == null ? null : team.getName();
        this.setCustomName(Component.translatable("entity.ywzj_rvp.gunner"));
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }

        if (getVehicle() instanceof AbstractVehicle vehicle) {
            detachedTicks = 0;
            if (tickCount % 20 == 0) {
                PartUnit<?> unit = vehicle.getOwnOperatorUnit(this);
                if (!(unit instanceof WeaponUnit)) {
                    repairSeat(vehicle);
                }
            }
            GunnerBrain.tick(this, vehicle);
        } else {
            clearDriverRideState();
            setTrackedTarget(null);
            detachedTicks++;
            if (detachedTicks > 40) {
                stopRiding();
                discard();
            }
        }
    }

    private void repairSeat(AbstractVehicle vehicle) {
        int currentSeat = -1;
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == this.getId()) {
                currentSeat = seat.seatIndex;
                break;
            }
        }
        int original = currentSeat;
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != -1 && seat.passengerId != this.getId()) {
                continue;
            }
            if (!vehicle.changeSeat(this, seat.seatIndex)) {
                continue;
            }
            PartUnit<?> unit = vehicle.getOwnOperatorUnit(this);
            if (unit instanceof WeaponUnit weaponUnit && !weaponUnit.getIndexedWeapons().isEmpty()) {
                return;
            }
        }
        if (original >= 0) {
            vehicle.changeSeat(this, original);
        }
    }

    @Override
    public boolean hurt(DamageSource damageSource, float amount) {
        return super.hurt(damageSource, amount);
    }

    @Override
    protected void dropAllDeathLoot(DamageSource source) {}

    @Override
    public Component getName() {
        return getCustomName() != null ? getCustomName() : Component.translatable("entity.ywzj_rvp.gunner");
    }

    @Override
    public EntityType<?> getType() {
        return RvpEntities.GUNNER.get();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        if (ownerUuid != null) {
            nbt.putUUID("Owner", ownerUuid);
        }
        if (ownerTeamName != null && !ownerTeamName.isEmpty()) {
            nbt.putString("OwnerTeam", ownerTeamName);
        }
        nbt.putString("ProfileId", profileId);
        if (homePosSet) {
            nbt.putDouble("HomePosX", homePosX);
            nbt.putDouble("HomePosY", homePosY);
            nbt.putDouble("HomePosZ", homePosZ);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        ownerUuid = nbt.hasUUID("Owner") ? nbt.getUUID("Owner") : null;
        ownerTeamName = nbt.contains("OwnerTeam") ? nbt.getString("OwnerTeam") : null;
        setProfileId(nbt.contains("ProfileId") ? nbt.getString("ProfileId") : GunnerProfileManager.DEFAULT_PROFILE_ID.toString());
        this.setCustomName(Component.translatable("entity.ywzj_rvp.gunner"));
        if (nbt.contains("HomePosX") && nbt.contains("HomePosY") && nbt.contains("HomePosZ")) {
            homePosSet = true;
            homePosX = nbt.getDouble("HomePosX");
            homePosY = nbt.getDouble("HomePosY");
            homePosZ = nbt.getDouble("HomePosZ");
        } else {
            homePosSet = false;
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public Team getTeam() {
        Player owner = getOwnerPlayer();
        if (owner != null) {
            return owner.getTeam();
        }
        if (ownerTeamName != null && !ownerTeamName.isEmpty()) {
            return level().getScoreboard().getPlayerTeam(ownerTeamName);
        }
        return super.getTeam();
    }

    @Nullable
    public Player getOwnerPlayer() {
        if (ownerUuid == null || !(level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
    }

    public boolean isOwnedBy(Entity entity) {
        if (!(entity instanceof Player player) || ownerUuid == null) {
            return false;
        }
        return ownerUuid.equals(player.getUUID());
    }

    public void setTrackedTarget(@Nullable Entity target) {
        trackedTargetId = target == null ? -1 : target.getId();
        this.entityData.set(TRACKED_TARGET_ID, trackedTargetId);
    }

    @Nullable
    public Entity getTrackedTarget() {
        int id = level().isClientSide() ? entityData.get(TRACKED_TARGET_ID) : trackedTargetId;
        return id == -1 ? null : level().getEntity(id);
    }

    public boolean markDriverRide(int vehicleId) {
        if (refilledVehicleId == vehicleId) {
            return false;
        }
        refilledVehicleId = vehicleId;
        return true;
    }

    public void clearDriverRideState() {
        refilledVehicleId = -1;
        recoveryTicks = 0;
        recoveryCooldownTicks = 0;
        tacticalHoldTicks = 0;
        tacticalEvadeTicks = 0;
        tacticalEvadeYawBias = 0.0F;
        homePosSet = false;
    }

    public String getProfileId() {
        return entityData.get(PROFILE_ID);
    }

    public GunnerFaction getProfileFaction() {
        return GunnerFaction.parse(entityData.get(PROFILE_FACTION));
    }

    public void setProfileId(String profileId) {
        String raw = (profileId == null || profileId.isBlank())
                ? GunnerProfileManager.DEFAULT_PROFILE_ID.toString()
                : profileId.trim();
        ResourceLocation normalized = GunnerProfileManager.INSTANCE.normalizeProfileId(raw);
        this.profileId = normalized.toString();
        this.entityData.set(PROFILE_ID, this.profileId);
        if (!level().isClientSide()) {
            GunnerProfile profile = GunnerProfileManager.INSTANCE.getProfile(normalized);
            this.entityData.set(PROFILE_FACTION, profile.getFaction().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public int getBurstFireTicks() {
        return burstFireTicks;
    }

    public void setBurstFireTicks(int burstFireTicks) {
        this.burstFireTicks = burstFireTicks;
    }

    public int getBurstRestTicks() {
        return burstRestTicks;
    }

    public void setBurstRestTicks(int burstRestTicks) {
        this.burstRestTicks = burstRestTicks;
    }

    public int getCountermeasureCooldown() {
        return countermeasureCooldown;
    }

    public void setCountermeasureCooldown(int countermeasureCooldown) {
        this.countermeasureCooldown = countermeasureCooldown;
    }

    public void tickCooldowns() {
        if (burstFireTicks > 0) {
            burstFireTicks--;
            if (burstFireTicks == 0 && burstRestTicks == 0 && pendingBurstRestTicks > 0) {
                burstRestTicks = pendingBurstRestTicks;
                pendingBurstRestTicks = 0;
            }
        } else if (burstRestTicks > 0) {
            burstRestTicks--;
        }
        if (countermeasureCooldown > 0) {
            countermeasureCooldown--;
        }
        if (recoveryTicks > 0) {
            recoveryTicks--;
        }
        if (recoveryCooldownTicks > 0) {
            recoveryCooldownTicks--;
        }
        if (tacticalHoldTicks > 0) {
            tacticalHoldTicks--;
        }
        if (tacticalEvadeTicks > 0) {
            tacticalEvadeTicks--;
        }
    }

    public boolean isInBurstRest() {
        return burstRestTicks > 0;
    }

    public boolean isBurstWindowOpen() {
        return burstRestTicks <= 0;
    }

    public boolean hasRecoveryTicks() {
        return recoveryTicks > 0;
    }

    public void startRecovery(int ticks) {
        recoveryTicks = ticks;
        recoveryTotalTicks = ticks;
    }

    public int getRecoveryCooldownTicks() {
        return recoveryCooldownTicks;
    }

    public void setRecoveryCooldownTicks(int recoveryCooldownTicks) {
        this.recoveryCooldownTicks = Math.max(0, recoveryCooldownTicks);
    }

    public boolean hasTacticalHoldTicks() {
        return tacticalHoldTicks > 0;
    }

    public int getTacticalHoldTicks() {
        return tacticalHoldTicks;
    }

    public boolean hasTacticalEvadeTicks() {
        return tacticalEvadeTicks > 0;
    }

    public int getTacticalEvadeTicks() {
        return tacticalEvadeTicks;
    }

    public float getTacticalEvadeYawBias() {
        return tacticalEvadeYawBias;
    }

    public void startTacticalHold(int ticks) {
        tacticalHoldTicks = Math.max(0, ticks);
    }

    public void startTacticalEvade(int ticks, float yawBias) {
        tacticalEvadeTicks = Math.max(0, ticks);
        tacticalEvadeYawBias = yawBias;
    }

    public void clearTacticalEvade() {
        tacticalHoldTicks = 0;
        tacticalEvadeTicks = 0;
        tacticalEvadeYawBias = 0.0F;
    }

    public boolean hasHomePos() {
        return homePosSet;
    }

    @Nullable
    public Vec3 getHomePos() {
        if (!homePosSet) {
            return null;
        }
        return new Vec3(homePosX, homePosY, homePosZ);
    }

    public void setHomePos(Vec3 pos) {
        homePosSet = true;
        homePosX = pos.x;
        homePosY = pos.y;
        homePosZ = pos.z;
    }

    public int getRecoveryTicks() {
        return recoveryTicks;
    }

    public int getRecoveryTotalTicks() {
        return recoveryTotalTicks;
    }

    public double getLastDriveCheckX() {
        return lastDriveCheckX;
    }

    public double getLastDriveCheckZ() {
        return lastDriveCheckZ;
    }

    public void setLastDriveCheck(double x, double z) {
        this.lastDriveCheckX = x;
        this.lastDriveCheckZ = z;
    }

    public int getControlledWeaponIndex() {
        return level().isClientSide() ? entityData.get(CONTROLLED_WEAPON_INDEX) : controlledWeaponIndex;
    }

    public void setControlledWeaponIndex(int controlledWeaponIndex) {
        this.controlledWeaponIndex = controlledWeaponIndex;
        this.entityData.set(CONTROLLED_WEAPON_INDEX, controlledWeaponIndex);
    }

    public int getGroundBigTurnCooldown() {
        return groundBigTurnCooldown;
    }

    public void setGroundBigTurnCooldown(int groundBigTurnCooldown) {
        this.groundBigTurnCooldown = groundBigTurnCooldown;
    }

    public int getGroundBigTurnTicks() {
        return groundBigTurnTicks;
    }

    public void setGroundBigTurnTicks(int groundBigTurnTicks) {
        this.groundBigTurnTicks = groundBigTurnTicks;
    }

    public float getGroundBigTurnTargetYaw() {
        return groundBigTurnTargetYaw;
    }

    public void setGroundBigTurnTargetYaw(float groundBigTurnTargetYaw) {
        this.groundBigTurnTargetYaw = groundBigTurnTargetYaw;
    }

    public int getAirPhase() {
        return airPhase;
    }

    public void setAirPhase(int airPhase) {
        this.airPhase = airPhase;
    }

    public int getAirPhaseTicks() {
        return airPhaseTicks;
    }

    public void setAirPhaseTicks(int airPhaseTicks) {
        this.airPhaseTicks = airPhaseTicks;
    }

    public boolean isAirPhaseInitialized() {
        return airPhaseInitialized;
    }

    public void setAirPhaseInitialized(boolean airPhaseInitialized) {
        this.airPhaseInitialized = airPhaseInitialized;
    }

    public void onBurstShot(int fireTicks, int restTicks) {
        if (fireTicks <= 1) {
            burstFireTicks = 0;
            burstRestTicks = restTicks;
            pendingBurstRestTicks = 0;
            return;
        }
        if (burstFireTicks <= 0) {
            burstFireTicks = fireTicks;
            pendingBurstRestTicks = restTicks;
        }
    }
}
