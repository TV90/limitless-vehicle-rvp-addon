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
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorRuntime;
import org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerBehaviorManager;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GunnerEntity extends Mob {
    /** 阶段 D 行为实例状态、活动实例、计划身份与最近调试快照。 */
    private final RVP_GunnerBehaviorRuntime behaviorRuntime = new RVP_GunnerBehaviorRuntime();
    /** 同步到客户端的 Profile 资源 ID。 */
    private static final EntityDataAccessor<String> PROFILE_ID = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.STRING);
    /** 同步到客户端的 Gunner 阵营。 */
    private static final EntityDataAccessor<String> PROFILE_FACTION = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.STRING);
    /** 同步到客户端的权威目标实体 ID。 */
    private static final EntityDataAccessor<Integer> TRACKED_TARGET_ID = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.INT);
    /** 同步到客户端的当前受控武器索引。 */
    private static final EntityDataAccessor<Integer> CONTROLLED_WEAPON_INDEX = SynchedEntityData.defineId(GunnerEntity.class, EntityDataSerializers.INT);

    /** 创建该 Gunner 的玩家 UUID。 */
    @Nullable
    private UUID ownerUuid;
    /** 创建者离线时保留的记分板队伍名。 */
    @Nullable
    private String ownerTeamName;
    /** 离开有效载具后的累计 tick。 */
    private int detachedTicks;
    /** 服务端权威目标实体 ID。 */
    private int trackedTargetId = -1;
    /** 已执行首次司机补满的载具实体 ID。 */
    private int refilledVehicleId = -1;
    /** 当前 Gunner Profile 资源 ID。 */
    private String profileId = GunnerProfileManager.DEFAULT_PROFILE_ID.toString();
    /** 当前 burst 连射窗口剩余 tick。 */
    private int burstFireTicks;
    /** burst 休止窗口剩余 tick。 */
    private int burstRestTicks;
    /** 当前 burst 结束后待启用的休止 tick。 */
    private int pendingBurstRestTicks;
    /** 普通导弹再次发射前的剩余冷却 tick。 */
    private int missileCooldown;
    /** 对空导弹：开始锁定当前目标时的 tick（目标切换时重置；0=未开始）。 */
    private int airLockStartTick;
    /** 对空导弹：最后一次对当前目标发射导弹的 tick（目标切换时重置；0=未发射）。 */
    private int lastAirMissileFireTick;
    /** 对空导弹：最近一个有效目标 id（用于判定是否切换到不同目标）。 */
    private int lastAirEngagedTargetId = -1;
    /** CIWS 目标实体 ID 到剩余重复交战冷却 tick 的映射。 */
    private final Map<Integer, Integer> ciwsTargetCooldowns = new HashMap<>();
    /** 服务端权威当前受控武器索引。 */
    private int controlledWeaponIndex = -1;
    /** 是否已记录首次驾驶位置作为空战回航锚点。 */
    private boolean homePosSet;
    /** 回航锚点 X 坐标。 */
    private double homePosX;
    /** 回航锚点 Y 坐标。 */
    private double homePosY;
    /** 回航锚点 Z 坐标。 */
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
            // 调用阶段 C 固定计划管理器，统一构建 Context、仲裁 Intent 并进入动作层。
            RVP_GunnerBehaviorManager.INSTANCE.tick(this, vehicle);
        } else {
            // 调用行为管理器退出入口，释放上一载具的锁、制导、补给与行为运行时状态。
            RVP_GunnerBehaviorManager.INSTANCE.exit(this);
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
        return RVP_Entities.GUNNER.get();
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
        int newId = target == null ? -1 : target.getId();
        if (newId != -1 && newId != lastAirEngagedTargetId) {
            // 切换到不同的有效目标才重置对空导弹计时；目标短暂消失再回来同一目标不重置，
            // 否则扫描间隔内 findBestTarget 偶尔返回 null 会让 5 秒持锁计时反复清零
            this.airLockStartTick = 0;
            this.lastAirMissileFireTick = 0;
            this.lastAirEngagedTargetId = newId;
        }
        trackedTargetId = newId;
        this.entityData.set(TRACKED_TARGET_ID, trackedTargetId);
    }

    /** 当前跟踪目标实体 id（-1 = 无）；供组网交战侧表判断"开始跟踪新目标"。 */
    public int getTrackedTargetId() {
        return trackedTargetId;
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
        homePosSet = false;
    }

    /** 返回持久化所有者 UUID；未绑定所有者时为 null。 */
    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** 返回本实体拥有的服务端临时行为运行时。 */
    public RVP_GunnerBehaviorRuntime getBehaviorRuntime() {
        return behaviorRuntime;
    }

    public String getProfileId() {
        return entityData.get(PROFILE_ID);
    }

    public RVP_EnumGunnerFaction getProfileFaction() {
        return RVP_EnumGunnerFaction.parse(entityData.get(PROFILE_FACTION));
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

    public int getMissileCooldown() {
        return missileCooldown;
    }

    public void setMissileCooldown(int missileCooldown) {
        this.missileCooldown = missileCooldown;
    }

    /** 对空导弹：开始锁定当前目标时的 tick（目标切换时重置）。 */
    public int getAirLockStartTick() {
        return airLockStartTick;
    }

    public void setAirLockStartTick(int airLockStartTick) {
        this.airLockStartTick = airLockStartTick;
    }

    /** 对空导弹：最后一次对当前目标发射导弹的 tick（目标切换时重置）。 */
    public int getLastAirMissileFireTick() {
        return lastAirMissileFireTick;
    }

    public void setLastAirMissileFireTick(int lastAirMissileFireTick) {
        this.lastAirMissileFireTick = lastAirMissileFireTick;
    }

    public boolean isCiwsTargetOnCooldown(Entity target) {
        return ciwsTargetCooldowns.containsKey(target.getId());
    }

    public void setCiwsTargetCooldown(Entity target, int ticks) {
        ciwsTargetCooldowns.put(target.getId(), ticks);
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
        if (missileCooldown > 0) {
            missileCooldown--;
        }
        ciwsTargetCooldowns.values().removeIf(v -> v <= 1);
        ciwsTargetCooldowns.replaceAll((k, v) -> v - 1);
    }

    public boolean isInBurstRest() {
        return burstRestTicks > 0;
    }

    public boolean isBurstWindowOpen() {
        return burstRestTicks <= 0;
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

    public int getControlledWeaponIndex() {
        return level().isClientSide() ? entityData.get(CONTROLLED_WEAPON_INDEX) : controlledWeaponIndex;
    }

    public void setControlledWeaponIndex(int controlledWeaponIndex) {
        this.controlledWeaponIndex = controlledWeaponIndex;
        this.entityData.set(CONTROLLED_WEAPON_INDEX, controlledWeaponIndex);
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
