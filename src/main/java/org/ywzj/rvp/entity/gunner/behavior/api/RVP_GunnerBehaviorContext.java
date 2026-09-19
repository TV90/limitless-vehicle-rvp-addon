package org.ywzj.rvp.entity.gunner.behavior.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gunner 单个服务端 tick 的只读行为上下文。
 *
 * <p>上下文只保存本 tick 解析出的实体引用，不得放入跨 tick 运行时状态。固定计划和后续内建行为
 * 只能读取本对象并提交意图，不能借此直接写载具控制、武器或雷达状态。</p>
 */
public final class RVP_GunnerBehaviorContext {

    /** 当前载具能力标签。 */
    public enum Capability {
        /** Gunner 当前是载具司机。 */
        DRIVER,
        /** Profile 允许 AI 驾驶。 */
        DRIVER_AI,
        /** 当前可解析到武器站。 */
        WEAPON_UNIT,
        /** 当前载具为地面车辆。 */
        GROUND_VEHICLE,
        /** 当前载具为固定翼。 */
        FIXED_WING,
        /** 当前载具为旋翼机。 */
        ROTARY_WING,
        /** 当前载具具备发射架部署配置。 */
        LAUNCHER,
        /** 当前武器站带本车雷达。 */
        RADAR,
        /** 当前武器站使用 RF 火控传感器。 */
        RF_FIRE_CONTROL
    }

    /** 当前 Gunner 实体。 */
    private final GunnerEntity gunner;
    /** 当前乘坐载具。 */
    private final AbstractVehicle vehicle;
    /** 当前平铺 schema Profile。 */
    private final GunnerProfile profile;
    /** 当前 Profile 标识。 */
    private final String profileId;
    /** Profile 资源加载代次。 */
    private final long profileGeneration;
    /** Gunner 实体 UUID。 */
    private final UUID gunnerUuid;
    /** Gunner 所有者 UUID；无在线/持久化所有者时为 null。 */
    @Nullable
    private final UUID ownerUuid;
    /** Gunner 当前阵营。 */
    private final RVP_EnumGunnerFaction faction;
    /** Gunner 当前记分板队伍；无队伍时为 null。 */
    @Nullable
    private final Team team;
    /** 当前座位实际控制部件。 */
    @Nullable
    private final PartUnit<?> seatUnit;
    /** 当前解析出的根/座位武器站。 */
    @Nullable
    private final WeaponUnit weaponUnit;
    /** 本 tick 已提交的权威目标。 */
    @Nullable
    private final Entity target;
    /** 本 tick 开始时的载具位置快照。 */
    private final Vec3 vehiclePosition;
    /** 本 tick 开始时的载具速度快照。 */
    private final Vec3 vehicleVelocity;
    /** 本 tick 开始时的载具偏航角，单位度。 */
    private final float vehicleYaw;
    /** 本 tick 开始时的载具俯仰角，单位度。 */
    private final float vehiclePitch;
    /** 本 tick 开始时的离地高度，单位格。 */
    private final double vehicleAgl;
    /** 本 tick 开始时载具是否已损毁。 */
    private final boolean vehicleDestroyed;
    /** 本 tick 开始时是否收到雷达锁定告警。 */
    private final boolean radarLockWarning;
    /** 本 tick 开始时是否收到导弹发射告警。 */
    private final boolean missileLaunchWarning;
    /** 本 tick 游戏时间。 */
    private final long gameTime;
    /** 不可变能力集合。 */
    private final Set<Capability> capabilities;

    private RVP_GunnerBehaviorContext(GunnerEntity gunner,
                                      AbstractVehicle vehicle,
                                      GunnerProfile profile,
                                      String profileId,
                                      long profileGeneration,
                                      UUID gunnerUuid,
                                      @Nullable UUID ownerUuid,
                                      RVP_EnumGunnerFaction faction,
                                      @Nullable Team team,
                                      @Nullable PartUnit<?> seatUnit,
                                      @Nullable WeaponUnit weaponUnit,
                                      @Nullable Entity target,
                                      Vec3 vehiclePosition,
                                      Vec3 vehicleVelocity,
                                      float vehicleYaw,
                                      float vehiclePitch,
                                      double vehicleAgl,
                                      boolean vehicleDestroyed,
                                      boolean radarLockWarning,
                                      boolean missileLaunchWarning,
                                      long gameTime,
                                      Set<Capability> capabilities) {
        this.gunner = gunner;
        this.vehicle = vehicle;
        this.profile = profile;
        this.profileId = profileId;
        this.profileGeneration = profileGeneration;
        this.gunnerUuid = gunnerUuid;
        this.ownerUuid = ownerUuid;
        this.faction = faction;
        this.team = team;
        this.seatUnit = seatUnit;
        this.weaponUnit = weaponUnit;
        this.target = target;
        this.vehiclePosition = vehiclePosition;
        this.vehicleVelocity = vehicleVelocity;
        this.vehicleYaw = vehicleYaw;
        this.vehiclePitch = vehiclePitch;
        this.vehicleAgl = vehicleAgl;
        this.vehicleDestroyed = vehicleDestroyed;
        this.radarLockWarning = radarLockWarning;
        this.missileLaunchWarning = missileLaunchWarning;
        this.gameTime = gameTime;
        this.capabilities = capabilities;
    }

    /** 解析座位、武器站与载具类型，创建本 tick 的基础上下文。 */
    public static RVP_GunnerBehaviorContext create(GunnerEntity gunner,
                                                   AbstractVehicle vehicle,
                                                   GunnerProfile profile,
                                                   String profileId,
                                                   long profileGeneration) {
        PartUnit<?> seatUnit = vehicle.getOwnOperatorUnit(gunner);
        boolean driver = GunnerBrain.isDriverSeat(vehicle, gunner);
        WeaponUnit weaponUnit = GunnerBrain.resolveControlledWeaponUnit(vehicle, seatUnit, driver);
        EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
        if (driver) capabilities.add(Capability.DRIVER);
        if (driver && profile.isAllowDrive()) capabilities.add(Capability.DRIVER_AI);
        if (weaponUnit != null) capabilities.add(Capability.WEAPON_UNIT);
        if (vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle) {
            capabilities.add(Capability.GROUND_VEHICLE);
        }
        if (vehicle instanceof FixedWingVehicle) capabilities.add(Capability.FIXED_WING);
        if (vehicle instanceof RotaryWingVehicle) capabilities.add(Capability.ROTARY_WING);
        if (GunnerBrain.hasLauncherDeployConfig(vehicle)) capabilities.add(Capability.LAUNCHER);
        if (weaponUnit != null && !weaponUnit.getRadarUnits().isEmpty()) capabilities.add(Capability.RADAR);
        if (weaponUnit != null
                && weaponUnit.getRootParentWeaponUnit().getFireControlSensorType()
                == WeaponUnitData.FireControlSensorType.RF) {
            capabilities.add(Capability.RF_FIRE_CONTROL);
        }
        boolean radarWarning = false;
        boolean missileWarning = false;
        if (vehicle.warningReceiver != null) {
            for (var warning : vehicle.warningReceiver.targets.values()) {
                radarWarning |= warning.warnType() == WarnType.RADAR_LOCK;
                missileWarning |= warning.warnType() == WarnType.MISSILE_LAUNCH;
            }
        }
        Player owner = gunner.getOwnerPlayer();
        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        return new RVP_GunnerBehaviorContext(gunner, vehicle, profile, profileId, profileGeneration,
                gunner.getUUID(), owner == null ? gunner.getOwnerUuid() : owner.getUUID(),
                gunner.getProfileFaction(), gunner.getTeam(), seatUnit, weaponUnit, gunner.getTrackedTarget(),
                vehicle.position(), vehicle.getDeltaMovement(), vehicle.getYRot(), vehicle.getXRot(),
                vehicle.getY() - groundY, vehicle.isDestroyed(), radarWarning, missileWarning,
                vehicle.level().getGameTime(), Collections.unmodifiableSet(capabilities));
    }

    /** 返回带有 TARGET 阶段胜者的新上下文，其余快照保持不变。 */
    public RVP_GunnerBehaviorContext withTarget(@Nullable Entity target) {
        return new RVP_GunnerBehaviorContext(gunner, vehicle, profile, profileId, profileGeneration,
                gunnerUuid, ownerUuid, faction, team, seatUnit, weaponUnit, target,
                vehiclePosition, vehicleVelocity, vehicleYaw, vehiclePitch, vehicleAgl, vehicleDestroyed,
                radarLockWarning, missileLaunchWarning, gameTime, capabilities);
    }

    /** 返回当前 Gunner。 */
    public GunnerEntity gunner() { return gunner; }
    /** 返回当前载具。 */
    public AbstractVehicle vehicle() { return vehicle; }
    /** 返回当前 Profile。 */
    public GunnerProfile profile() { return profile; }
    /** 返回当前 Profile ID。 */
    public String profileId() { return profileId; }
    /** 返回 Profile 加载代次。 */
    public long profileGeneration() { return profileGeneration; }
    /** 返回 Gunner UUID。 */ public UUID gunnerUuid() { return gunnerUuid; }
    /** 返回所有者 UUID。 */ @Nullable public UUID ownerUuid() { return ownerUuid; }
    /** 返回当前阵营。 */ public RVP_EnumGunnerFaction faction() { return faction; }
    /** 返回当前队伍。 */ @Nullable public Team team() { return team; }
    /** 返回当前座位部件。 */
    @Nullable public PartUnit<?> seatUnit() { return seatUnit; }
    /** 返回当前武器站。 */
    @Nullable public WeaponUnit weaponUnit() { return weaponUnit; }
    /** 返回本 tick 权威目标。 */
    @Nullable public Entity target() { return target; }
    /** 返回载具位置快照。 */
    public Vec3 vehiclePosition() { return vehiclePosition; }
    /** 返回载具速度快照。 */
    public Vec3 vehicleVelocity() { return vehicleVelocity; }
    /** 返回载具偏航角。 */ public float vehicleYaw() { return vehicleYaw; }
    /** 返回载具俯仰角。 */ public float vehiclePitch() { return vehiclePitch; }
    /** 返回载具离地高度。 */ public double vehicleAgl() { return vehicleAgl; }
    /** 返回载具是否已损毁。 */ public boolean vehicleDestroyed() { return vehicleDestroyed; }
    /** 返回是否存在雷达锁定告警。 */ public boolean radarLockWarning() { return radarLockWarning; }
    /** 返回是否存在导弹发射告警。 */ public boolean missileLaunchWarning() { return missileLaunchWarning; }
    /** 返回本 tick 游戏时间。 */
    public long gameTime() { return gameTime; }
    /** 返回不可变能力集合。 */
    public Set<Capability> capabilities() { return capabilities; }
    /** 判断是否具备指定能力。 */
    public boolean has(Capability capability) { return capabilities.contains(capability); }
}
