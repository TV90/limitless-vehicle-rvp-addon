package org.ywzj.rvp.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.MissileEntityArmExt;
import org.ywzj.rvp.ext.WeaponUnitArmExt;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.mixin.accessor.MissileEntityAccessor;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = MissileEntity.class, remap = false)
public abstract class MissileEntityMixin implements MissileEntityArmExt {

    @Unique
    private final Map<Long, Integer> ywzj_rvp$armPulseTickMap = new HashMap<>();

    @Unique
    private int ywzj_rvp$armMemoryLeftTick;

    @Unique
    private Vec3 ywzj_rvp$armLastSeenPos;

    @Unique
    private int ywzj_rvp$armTargetVehicleId = -1;

    @Unique
    private boolean ywzj_rvp$armParamsInitialized;

    @Unique
    private float ywzj_rvp$armSeekerFov = 60f;

    @Unique
    private float ywzj_rvp$armSeekRange = 900f;

    @Unique
    private int ywzj_rvp$armScanIntervalTick = 2;

    @Unique
    private int ywzj_rvp$armMemoryTick = 120;

    @Unique
    private int ywzj_rvp$armPulseMemoryTick = 25;

    @Unique
    private float ywzj_rvp$armLockedBonus = 0.5f;

    @Unique
    private int ywzj_rvp$armTickCounter;

    /** Preselected target copied from the weapon unit at first tick. */
    @Unique
    private int ywzj_rvp$armPreselectVehicleId = -1;

    @Unique
    private int ywzj_rvp$armPreselectRadarIndex = -1;

    @Override
    public void ywzj_rvp$initArmState() {
        this.ywzj_rvp$armPulseTickMap.clear();
        this.ywzj_rvp$armMemoryLeftTick = 0;
        this.ywzj_rvp$armLastSeenPos = null;
        this.ywzj_rvp$armTargetVehicleId = -1;
        this.ywzj_rvp$armTickCounter = 0;
        this.ywzj_rvp$armPreselectVehicleId = -1;
        this.ywzj_rvp$armPreselectRadarIndex = -1;
    }

    @Override
    public int ywzj_rvp$getArmMemoryLeftTick() {
        return ywzj_rvp$armMemoryLeftTick;
    }

    @Override
    public void ywzj_rvp$setArmMemoryLeftTick(int ticks) {
        this.ywzj_rvp$armMemoryLeftTick = ticks;
    }

    @Override
    public Vec3 ywzj_rvp$getArmLastSeenPos() {
        return ywzj_rvp$armLastSeenPos;
    }

    @Override
    public void ywzj_rvp$setArmLastSeenPos(Vec3 pos) {
        this.ywzj_rvp$armLastSeenPos = pos;
    }

    @Override
    public int ywzj_rvp$getArmTargetVehicleId() {
        return ywzj_rvp$armTargetVehicleId;
    }

    @Override
    public void ywzj_rvp$setArmTargetVehicleId(int id) {
        this.ywzj_rvp$armTargetVehicleId = id;
    }

    @Override
    public int ywzj_rvp$getArmPreselectVehicleId() {
        return ywzj_rvp$armPreselectVehicleId;
    }

    @Override
    public void ywzj_rvp$setArmPreselectVehicleId(int id) {
        this.ywzj_rvp$armPreselectVehicleId = id;
    }

    @Override
    public int ywzj_rvp$getArmPreselectRadarIndex() {
        return ywzj_rvp$armPreselectRadarIndex;
    }

    @Override
    public void ywzj_rvp$setArmPreselectRadarIndex(int index) {
        this.ywzj_rvp$armPreselectRadarIndex = index;
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = true)
    private void rvp$armTick(CallbackInfo ci) {
        MissileEntity self = (MissileEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide()) {
            return;
        }

        MissileEntityAccessor accessor = (MissileEntityAccessor) this;
        VehicleMissileWeaponData.Guidance guidance = accessor.getGuidance();
        WeaponUnit weaponUnit = accessor.getWeaponUnit();

        if (guidance != VehicleMissileWeaponData.Guidance.HOMING) {
            return;
        }

        if (!ywzj_rvp$armParamsInitialized) {
            ywzj_rvp$initArmParameters(weaponUnit, self);
        }

        if (!ywzj_rvp$armParamsInitialized) {
            return;
        }

        ywzj_rvp$armTickCounter++;

        if (ywzj_rvp$armTickCounter % ywzj_rvp$armScanIntervalTick != 0) {
            if (ywzj_rvp$armMemoryLeftTick > 0 && ywzj_rvp$armLastSeenPos != null) {
                self.targetPos = ywzj_rvp$armLastSeenPos;
                ywzj_rvp$armMemoryLeftTick--;
                self.targetEntity = null;
            }
            return;
        }

        // STEP 1: If we have a preselected vehicle, try to find it first
        AntiRadiationSeekerHelper.AntiRadiationTarget preselectedTarget = null;
        if (ywzj_rvp$armPreselectVehicleId >= 0) {
            // Collect all pulse descriptors and find the preselected vehicle among them
            var pulses = AntiRadiationSeekerHelper.collectPulseDescriptors(
                    level, self.position(), self.getLookAngle(),
                    ywzj_rvp$armSeekerFov, ywzj_rvp$armSeekRange, self.vehicle,
                    ywzj_rvp$armTickCounter, ywzj_rvp$armPulseTickMap, ywzj_rvp$armPulseMemoryTick
            );
            for (var pulse : pulses) {
                if (pulse.emitterVehicleId() == ywzj_rvp$armPreselectVehicleId
                        && (ywzj_rvp$armPreselectRadarIndex < 0
                        || pulse.emitterRadarIndex() == ywzj_rvp$armPreselectRadarIndex)) {
                    if (!(level.getEntity(pulse.emitterVehicleId()) instanceof AbstractVehicle targetVehicle)) {
                        continue;
                    }
                    preselectedTarget = new AntiRadiationSeekerHelper.AntiRadiationTarget(
                            pulse.emitterVehicleId(),
                            pulse.emitterRadarIndex(),
                            targetVehicle,
                            pulse.emitterPosition(),
                            40 // default memory tick
                    );
                    break;
                }
            }
        }

        if (preselectedTarget != null) {
            // Lock onto the preselected target
            ywzj_rvp$armMemoryLeftTick = ywzj_rvp$armMemoryTick;
            ywzj_rvp$armLastSeenPos = preselectedTarget.position();
            self.targetPos = preselectedTarget.position();
            self.targetEntity = null;
            ywzj_rvp$armTargetVehicleId = preselectedTarget.vehicleId();
        } else {
            // STEP 2: Fall back to general best-target scan
            AntiRadiationSeekerHelper.AntiRadiationTarget target = AntiRadiationSeekerHelper.findBestRadiationSource(
                    self, ywzj_rvp$armSeekerFov, ywzj_rvp$armSeekRange, ywzj_rvp$armTickCounter,
                    ywzj_rvp$armPulseTickMap, ywzj_rvp$armPulseMemoryTick, ywzj_rvp$armLockedBonus
            );

            if (target != null) {
                ywzj_rvp$armMemoryLeftTick = ywzj_rvp$armMemoryTick;
                ywzj_rvp$armLastSeenPos = target.position();
                self.targetPos = target.position();
                self.targetEntity = null;
                ywzj_rvp$armTargetVehicleId = target.vehicleId();
            } else if (ywzj_rvp$armMemoryLeftTick > 0 && ywzj_rvp$armLastSeenPos != null) {
                ywzj_rvp$armMemoryLeftTick--;
                self.targetPos = ywzj_rvp$armLastSeenPos;
                self.targetEntity = null;
            } else {
                ywzj_rvp$armLastSeenPos = null;
                ywzj_rvp$armPulseTickMap.clear();
            }
        }
    }

    @Unique
    private void ywzj_rvp$initArmParameters(WeaponUnit weaponUnit, MissileEntity self) {
        if (weaponUnit == null || self.vehicle == null) {
            return;
        }
        var weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            return;
        }
        var weapon = weaponOpt.get();
        if (!(weapon.getData() instanceof RVP_WeaponData rvpData)) {
            return;
        }
        if (!rvpData.isAntiRadiationMissile()) {
            return;
        }

        var stages = rvpData.getGuidanceData().getStages();
        if (stages == null || stages.isEmpty()) {
            return;
        }

        for (var stage : stages) {
            var sources = stage.getSources();
            if (sources == null) {
                continue;
            }
            for (var source : sources) {
                if (source.getType() != RVP_EnumGuidanceType.ARM) {
                    continue;
                }
                ywzj_rvp$armSeekerFov = stage.getSeeker().getFov();
                ywzj_rvp$armSeekRange = stage.getSeeker().getRange();

                var params = source.getParams();
                if (params != null) {
                    ywzj_rvp$armScanIntervalTick = params.scanIntervalTick(2);
                    ywzj_rvp$armMemoryTick = params.memoryTick(120);
                    ywzj_rvp$armPulseMemoryTick = params.radiationPulseMemoryTick(25);
                    ywzj_rvp$armLockedBonus = params.lockedBonus(0.5f);
                }

                // Copy preselected target from the ROOT weapon unit (preselect is stored on root)
                WeaponUnit rootWu = weaponUnit.getRootParentWeaponUnit();
                if (rootWu instanceof WeaponUnitArmExt armExt) {
                    ywzj_rvp$armPreselectVehicleId = armExt.ywzj_rvp$getArmPreselectedVehicleId();
                    ywzj_rvp$armPreselectRadarIndex = armExt.ywzj_rvp$getArmPreselectedRadarIndex();
                    // Also set the running target vehicle ID so the missile remembers
                    if (ywzj_rvp$armPreselectVehicleId >= 0) {
                        ywzj_rvp$armTargetVehicleId = ywzj_rvp$armPreselectVehicleId;
                    }
                }

                ywzj_rvp$armParamsInitialized = true;
                return;
            }
        }
    }
}
