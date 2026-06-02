package org.ywzj.rvp.entity.weapon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.data.VehicleAntiRadiationMissileWeaponData;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AntiRadiationMissileEntity extends MissileEntity {

    private int antiRadiationScanIntervalTick = 2;
    private int antiRadiationMemoryTick = 0;
    private float antiRadiationSeekRange = 1024f;
    private boolean antiRadiationAllowReacquire = true;
    private int antiRadiationRadiationPulseMemoryTick = 25;
    private float antiRadiationLockedBonus = 0.5f;
    private boolean antiRadiationPreselectEnabled = true;

    private int antiRadiationMemoryLeftTick = 0;
    private int antiRadiationNextScanTick = 0;
    private int antiRadiationTargetVehicleId = -1;
    private int antiRadiationTargetRadarIndex = -1;
    private boolean antiRadiationLostPermanent = false;

    @Nullable
    private Vec3 antiRadiationLastSeenPos;

    private int antiRadiationPreselectedVehicleId = -1;
    private int antiRadiationPreselectedRadarIndex = -1;

    private final Map<Long, Integer> antiRadiationPulseTickMap = new HashMap<>();

    public AntiRadiationMissileEntity(EntityType<? extends Projectile> entityType, Level level) {
        super(entityType, level);
    }

    public AntiRadiationMissileEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(msg, level);
    }

    public AntiRadiationMissileEntity(
            EntityType<? extends Projectile> entityType,
            Level level,
            VehicleAntiRadiationMissileWeaponData data,
            WeaponUnit weaponUnit,
            int preselectedVehicleId,
            int preselectedRadarIndex,
            @Nullable Vec3 preselectedPos
    ) {
        super(entityType, level, data, weaponUnit);
        this.antiRadiationScanIntervalTick = Math.max(data.getAntiRadiationScanIntervalTick(), 1);
        this.antiRadiationMemoryTick = Math.max(data.getAntiRadiationMemoryTick(), 0);
        this.antiRadiationSeekRange = Math.max(data.getAntiRadiationSeekRange(), 1f);
        this.antiRadiationAllowReacquire = data.isAntiRadiationAllowReacquire();
        this.antiRadiationRadiationPulseMemoryTick = Math.max(data.getAntiRadiationRadiationPulseMemoryTick(), 0);
        this.antiRadiationLockedBonus = data.getAntiRadiationLockedBonus();
        this.antiRadiationPreselectEnabled = data.isAntiRadiationPreselectEnabled();

        if (this.antiRadiationPreselectEnabled && preselectedVehicleId >= 0 && preselectedRadarIndex >= 0) {
            this.antiRadiationPreselectedVehicleId = preselectedVehicleId;
            this.antiRadiationPreselectedRadarIndex = preselectedRadarIndex;
            if (preselectedPos != null) {
                this.antiRadiationLastSeenPos = preselectedPos;
                this.targetPos = preselectedPos;
                this.targetEntity = null;
                this.antiRadiationMemoryLeftTick = this.antiRadiationMemoryTick > 0 ? this.antiRadiationMemoryTick : 200;
            }
        }
    }

    @Override
    public void tick() {
        ywzj_rvp$tickAntiRadiationTrack();
        super.tick();
    }

    public void ywzj_rvp$tickAntiRadiationTrack() {
        if (this.level().isClientSide()) {
            return;
        }

        MissileEntity self = this;
        boolean canScan = !this.antiRadiationLostPermanent || this.antiRadiationAllowReacquire;
        AntiRadiationSeekerHelper.AntiRadiationTarget target = null;
        if (canScan && self.tickCount >= this.antiRadiationNextScanTick) {
            this.antiRadiationNextScanTick = self.tickCount + this.antiRadiationScanIntervalTick;
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters = AntiRadiationSeekerHelper.scanVisibleEmitters(
                    self.level(),
                    self.position(),
                    self.getLookAngle(),
                    self.seekerFov,
                    this.antiRadiationSeekRange,
                    self.vehicle,
                    self.tickCount,
                    this.antiRadiationPulseTickMap,
                    this.antiRadiationRadiationPulseMemoryTick
            );

            AntiRadiationSeekerHelper.AntiRadiationEmitter chosen = null;
            if (this.antiRadiationPreselectEnabled && this.antiRadiationPreselectedVehicleId >= 0 && this.antiRadiationPreselectedRadarIndex >= 0) {
                for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                    if (emitter.vehicleId() == this.antiRadiationPreselectedVehicleId && emitter.radarIndex() == this.antiRadiationPreselectedRadarIndex) {
                        chosen = emitter;
                        break;
                    }
                }
            }

            if (chosen == null) {
                double bestScore = Double.MAX_VALUE;
                for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
                    double score = AntiRadiationSeekerHelper.score(self.position(), self.getLookAngle(), self.seekerFov, this.antiRadiationSeekRange, emitter.position(), emitter.locked(), this.antiRadiationLockedBonus);
                    if (score < bestScore) {
                        bestScore = score;
                        chosen = emitter;
                    }
                }
            }

            if (chosen != null) {
                target = new AntiRadiationSeekerHelper.AntiRadiationTarget(chosen.vehicleId(), chosen.radarIndex(), chosen.vehicle(), chosen.position(), AntiRadiationSeekerHelper.getDefaultMemoryTick(chosen.radarUnit()));
            }
        }

        if (target != null) {
            this.antiRadiationLostPermanent = false;
            this.antiRadiationTargetVehicleId = target.vehicleId();
            this.antiRadiationTargetRadarIndex = target.radarIndex();
            this.antiRadiationLastSeenPos = target.position();
            this.antiRadiationMemoryLeftTick = this.antiRadiationMemoryTick > 0 ? this.antiRadiationMemoryTick : target.defaultMemoryTick();
            this.targetPos = target.position();
            this.targetEntity = null;

            if (self.tickCount % 2 == 0) {
                ServerVehicleWarn packet = new ServerVehicleWarn(self.getId(), target.vehicle().getId(), WarnType.MISSILE_LAUNCH, "MSL");
                Channel.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(target::vehicle), packet);
            }
        } else if (this.antiRadiationMemoryLeftTick > 0 && this.antiRadiationLastSeenPos != null) {
            this.antiRadiationMemoryLeftTick -= 1;
            this.targetPos = this.antiRadiationLastSeenPos;
            this.targetEntity = null;
        } else {
            this.targetPos = null;
            this.targetEntity = null;
            this.antiRadiationLastSeenPos = null;
            this.antiRadiationTargetVehicleId = -1;
            this.antiRadiationTargetRadarIndex = -1;
            if (!this.antiRadiationAllowReacquire) {
                this.antiRadiationLostPermanent = true;
            }
        }
    }

    @Override
    public void writeData(CompoundTag data) {
        super.writeData(data);
        data.putBoolean("rvpAntiRadiationEnabled", true);
        data.putInt("rvpAntiRadiationMemoryLeftTick", this.antiRadiationMemoryLeftTick);
        data.putInt("rvpAntiRadiationTargetVehicleId", this.antiRadiationTargetVehicleId);
        data.putInt("rvpAntiRadiationTargetRadarIndex", this.antiRadiationTargetRadarIndex);
        data.putBoolean("rvpAntiRadiationLostPermanent", this.antiRadiationLostPermanent);
        if (this.antiRadiationLastSeenPos != null) {
            data.putDouble("rvpAntiRadiationLastSeenX", this.antiRadiationLastSeenPos.x);
            data.putDouble("rvpAntiRadiationLastSeenY", this.antiRadiationLastSeenPos.y);
            data.putDouble("rvpAntiRadiationLastSeenZ", this.antiRadiationLastSeenPos.z);
        }
    }

    @Override
    public void readData(CompoundTag data) {
        super.readData(data);
        if (!data.getBoolean("rvpAntiRadiationEnabled")) {
            return;
        }
        this.antiRadiationMemoryLeftTick = data.getInt("rvpAntiRadiationMemoryLeftTick");
        this.antiRadiationTargetVehicleId = data.getInt("rvpAntiRadiationTargetVehicleId");
        this.antiRadiationTargetRadarIndex = data.getInt("rvpAntiRadiationTargetRadarIndex");
        this.antiRadiationLostPermanent = data.getBoolean("rvpAntiRadiationLostPermanent");
        if (data.contains("rvpAntiRadiationLastSeenX")) {
            this.antiRadiationLastSeenPos = new Vec3(
                    data.getDouble("rvpAntiRadiationLastSeenX"),
                    data.getDouble("rvpAntiRadiationLastSeenY"),
                    data.getDouble("rvpAntiRadiationLastSeenZ")
            );
        }
    }
}
