package org.ywzj.rvp.weapon;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AntiRadiationSeekerHelper {

    private AntiRadiationSeekerHelper() {}

    @Nullable
    public static AntiRadiationTarget findBestRadiationSource(MissileEntity missile, float seekerFov, float seekRange, int tickCount, Map<Long, Integer> pulseTickMap, int pulseMemoryTick, float lockedBonus) {
        List<AntiRadiationEmitter> emitters = scanVisibleEmitters(missile.level(), missile.position(), missile.getLookAngle(), seekerFov, seekRange, missile.vehicle, tickCount, pulseTickMap, pulseMemoryTick);
        AntiRadiationEmitter best = null;
        double bestScore = Double.MAX_VALUE;
        for (AntiRadiationEmitter emitter : emitters) {
            double score = score(missile.position(), missile.getLookAngle(), seekerFov, seekRange, emitter.position(), emitter.locked(), lockedBonus);
            if (score < bestScore) {
                bestScore = score;
                best = emitter;
            }
        }
        if (best == null) {
            return null;
        }
        return new AntiRadiationTarget(best.vehicleId(), best.radarIndex(), best.vehicle(), best.position(), getDefaultMemoryTick(best.radarUnit()));
    }

    public static List<AntiRadiationEmitter> scanVisibleEmitters(net.minecraft.world.level.Level level, Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange, @Nullable AbstractVehicle excludeVehicle, int tickCount, Map<Long, Integer> pulseTickMap, int pulseMemoryTick) {
        List<RVP_RadarPulseDescriptor> pulses = collectPulseDescriptors(level, seekerPos, seekerLook, seekerFov,
                seekRange, excludeVehicle, tickCount, pulseTickMap, pulseMemoryTick);
        List<AntiRadiationEmitter> out = new ArrayList<>();
        for (RVP_RadarPulseDescriptor pulse : pulses) {
            if (!(level.getEntity(pulse.emitterVehicleId()) instanceof AbstractVehicle vehicle)) {
                continue;
            }
            RadarUnit radarUnit = null;
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (partUnit instanceof RadarUnit candidate && candidate.getIndex() == pulse.emitterRadarIndex()) {
                    radarUnit = candidate;
                    break;
                }
            }
            if (radarUnit != null) {
                out.add(new AntiRadiationEmitter(vehicle.getId(), radarUnit.getIndex(), vehicle, radarUnit,
                        pulse.emitterPosition(), pulse.lockedEmission(), pulse));
            }
        }
        return out;
    }

    public static List<RVP_RadarPulseDescriptor> collectPulseDescriptors(net.minecraft.world.level.Level level, Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange, @Nullable AbstractVehicle excludeVehicle, int tickCount, Map<Long, Integer> pulseTickMap, int pulseMemoryTick) {
        AABB searchBox = AABB.ofSize(seekerPos, seekRange * 2.0, seekRange * 2.0, seekRange * 2.0);
        List<RVP_RadarPulseDescriptor> out = new ArrayList<>();
        for (AbstractVehicle vehicle : level.getEntitiesOfClass(AbstractVehicle.class, searchBox, entity -> excludeVehicle == null || entity != excludeVehicle)) {
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radarUnit)) {
                    continue;
                }
                if (!radarUnit.isOn()) {
                    continue;
                }
                Vec3 radarPos = radarUnit.worldRadarPosition();
                double distance = radarPos.distanceTo(seekerPos);
                if (distance > seekRange) {
                    continue;
                }
                Vec3 toRadar = radarPos.subtract(seekerPos);
                double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, toRadar));
                if (angle > seekerFov) {
                    continue;
                }

                boolean locked = radarUnit.getLockedEntity() != null;
                boolean visible;
                if (locked) {
                    visible = true;
                } else {
                    long key = emitterKey(vehicle.getId(), radarUnit.getIndex());
                    if (isScanRadiatingToSeeker(radarUnit, seekerPos)) {
                        pulseTickMap.put(key, tickCount);
                        visible = true;
                    } else if (pulseMemoryTick > 0) {
                        Integer last = pulseTickMap.get(key);
                        visible = last != null && tickCount - last <= pulseMemoryTick;
                    } else {
                        visible = false;
                    }
                }

                if (!visible) {
                    continue;
                }

                out.add(createPdw(seekerPos, seekerLook, tickCount, vehicle, radarUnit, radarPos, locked));
            }
        }
        return out;
    }

    private static RVP_RadarPulseDescriptor createPdw(Vec3 seekerPos, Vec3 seekerLook, int tickCount,
                                                      AbstractVehicle vehicle, RadarUnit radarUnit,
                                                      Vec3 radarPos, boolean locked) {
        double distance = Math.max(radarPos.distanceTo(seekerPos), 1.0);
        double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, radarPos.subtract(seekerPos)));
        long key = emitterKey(vehicle.getId(), radarUnit.getIndex());
        double carrierFrequencyMhz = 8000.0 + Math.floorMod(key, 4000);
        double pulseWidthMicroseconds = locked ? 4.0 : 1.2;
        double rcs = Math.max(vehicle.physicsEngine.radarCrossSection, 0.1f);
        double amplitude = (locked ? 2.0 : 1.0) * rcs / (distance * distance);
        return new RVP_RadarPulseDescriptor(tickCount, pulseWidthMicroseconds, angle,
                carrierFrequencyMhz, amplitude, vehicle.getId(), radarUnit.getIndex(), radarPos, locked);
    }

    public static boolean isScanRadiatingToSeeker(RadarUnit radarUnit, Vec3 seekerPos) {
        int periodTick = 20;
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            int scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (scanPeriodTick > 0) {
                periodTick = scanPeriodTick;
            }
        }

        if (periodTick <= 0) {
            periodTick = 20;
        }

        int phase = Math.abs((radarUnit.getVehicle().getId() * 31) ^ (radarUnit.getIndex() * 131)) % periodTick;
        int t = radarUnit.getVehicle().tickCount;
        if ((t + phase) % periodTick != 0) {
            return false;
        }

        Vec2 aimRot = radarUnit.aimRot(seekerPos);
        if (aimRot.y < radarUnit.getYRotMin() || aimRot.y > radarUnit.getYRotMax()) {
            return false;
        }
        if (Math.abs(aimRot.x - radarUnit.getXRot()) > radarUnit.getScanSectorAngle() / 2.0f) {
            return false;
        }
        return true;
    }

    public static double score(Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange, Vec3 emitterPos, boolean locked, float lockedBonus) {
        double distance = emitterPos.distanceTo(seekerPos);
        Vec3 toEmitter = emitterPos.subtract(seekerPos);
        double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, toEmitter));
        double base = angle / Math.max(seekerFov, 1.0f) + distance / Math.max(seekRange, 1.0f);
        if (locked) {
            base -= lockedBonus;
        }
        return base;
    }

    public static double score(Vec3 seekerPos, Vec3 seekerLook, float seekerFov, float seekRange,
                               RVP_RadarPulseDescriptor pdw, float lockedBonus) {
        double distance = pdw.emitterPosition().distanceTo(seekerPos);
        double base = pdw.angleOfArrivalDegrees() / Math.max(seekerFov, 1.0f)
                + distance / Math.max(seekRange, 1.0f)
                - Math.min(pdw.amplitude() * 128.0, 1.0);
        if (pdw.lockedEmission()) {
            base -= lockedBonus;
        }
        return base;
    }

    public static long emitterKey(int vehicleId, int radarIndex) {
        return (((long) vehicleId) << 32) | (radarIndex & 0xffffffffL);
    }

    public static int getDefaultMemoryTick(RadarUnit radarUnit) {
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            int contactHoldTick = ext.ywzj_rvp$getContactHoldTick();
            if (contactHoldTick > 0) {
                return Math.max(contactHoldTick / 2, 1);
            }
        }

        float yRotSpeed = radarUnit.getYRotSpeed();
        if (yRotSpeed <= 0) {
            return 10;
        }

        float range = Math.min(360f, radarUnit.getYRotMax() - radarUnit.getYRotMin());
        int scanCycleTick = Math.max((int) ((range / yRotSpeed) * 2f), 1);
        return Math.max(scanCycleTick, 1);
    }

    public record AntiRadiationEmitter(int vehicleId, int radarIndex, AbstractVehicle vehicle, RadarUnit radarUnit, Vec3 position, boolean locked, RVP_RadarPulseDescriptor pdw) {
    }

    public record AntiRadiationTarget(int vehicleId, int radarIndex, AbstractVehicle vehicle, Vec3 position, int defaultMemoryTick) {
    }
}
