package org.ywzj.rvp.entity.gunner.ai.profile;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GunnerProfile {
    @SerializedName("name")
    private String name = "default";

    @SerializedName("faction")
    private String faction = "friendly";

    @SerializedName("target_types")
    private List<String> targetTypes = new ArrayList<>(List.of("vehicle", "monster", "player"));

    @SerializedName("search_radius")
    private double searchRadius = 96.0;

    @SerializedName("scan_interval_tick")
    private int scanIntervalTick = 10;

    @SerializedName("fire_window_deg")
    private float fireWindowDeg = 6.0F;

    @SerializedName("lead_scale")
    private double leadScale = 1.0;

    @SerializedName("burst_fire_tick")
    private int burstFireTick = 6;

    @SerializedName("burst_rest_tick")
    private int burstRestTick = 10;

    @SerializedName("countermeasure_range")
    private double countermeasureRange = 36.0;

    @SerializedName("countermeasure_cooldown_tick")
    private int countermeasureCooldownTick = 80;

    @SerializedName("allow_drive")
    private boolean allowDrive = true;

    @SerializedName("drive_pursuit_distance")
    private double drivePursuitDistance = 64.0;

    @SerializedName("drive_stop_distance")
    private double driveStopDistance = 12.0;

    @SerializedName("drive_stuck_check_tick")
    private int driveStuckCheckTick = 20;

    @SerializedName("drive_stuck_distance")
    private double driveStuckDistance = 1.0;

    @SerializedName("drive_recovery_tick")
    private int driveRecoveryTick = 20;

    @SerializedName("rotary_cruise_altitude_min")
    private double rotaryCruiseAltitudeMin = 28.0;

    @SerializedName("rotary_cruise_altitude_max")
    private double rotaryCruiseAltitudeMax = 60.0;

    @SerializedName("fixedwing_cruise_altitude_min")
    private double fixedwingCruiseAltitudeMin = 150.0;

    @SerializedName("fixedwing_cruise_altitude_max")
    private double fixedwingCruiseAltitudeMax = 500.0;

    @SerializedName("fixedwing_combat_radius_min")
    private double fixedwingCombatRadiusMin = 50.0;

    @SerializedName("fixedwing_combat_radius_max")
    private double fixedwingCombatRadiusMax = 500.0;

    @SerializedName("ground_wander_enabled")
    private boolean groundWanderEnabled = true;

    @SerializedName("ground_big_turn_interval_tick_min")
    private int groundBigTurnIntervalTickMin = 300;

    @SerializedName("ground_big_turn_interval_tick_max")
    private int groundBigTurnIntervalTickMax = 600;

    @SerializedName("ground_big_turn_angle_deg_min")
    private float groundBigTurnAngleDegMin = 120.0F;

    @SerializedName("ground_big_turn_angle_deg_max")
    private float groundBigTurnAngleDegMax = 180.0F;

    @SerializedName("ground_big_turn_duration_tick")
    private int groundBigTurnDurationTick = 40;

    @SerializedName("air_attack_phase_tick")
    private int airAttackPhaseTick = 200;

    @SerializedName("air_disengage_phase_tick")
    private int airDisengagePhaseTick = 200;

    @SerializedName("air_initial_disengage_tick_min")
    private int airInitialDisengageTickMin = 300;

    @SerializedName("air_initial_disengage_tick_max")
    private int airInitialDisengageTickMax = 400;

    public void normalize(String fallbackName) {
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        if (faction == null || faction.isBlank()) {
            faction = "friendly";
        }
        if (targetTypes == null || targetTypes.isEmpty()) {
            targetTypes = new ArrayList<>(List.of("vehicle", "monster", "player"));
        }
        scanIntervalTick = Math.max(1, scanIntervalTick);
        fireWindowDeg = Math.max(1.0F, fireWindowDeg);
        leadScale = Math.max(0.0, leadScale);
        burstFireTick = Math.max(0, burstFireTick);
        burstRestTick = Math.max(0, burstRestTick);
        countermeasureRange = Math.max(0.0, countermeasureRange);
        countermeasureCooldownTick = Math.max(0, countermeasureCooldownTick);
        drivePursuitDistance = Math.max(4.0, drivePursuitDistance);
        driveStopDistance = Math.max(0.0, driveStopDistance);
        driveStuckCheckTick = Math.max(5, driveStuckCheckTick);
        driveStuckDistance = Math.max(0.05, driveStuckDistance);
        driveRecoveryTick = Math.max(5, driveRecoveryTick);
        rotaryCruiseAltitudeMin = Math.max(0.0, rotaryCruiseAltitudeMin);
        rotaryCruiseAltitudeMax = Math.max(rotaryCruiseAltitudeMin, rotaryCruiseAltitudeMax);
        fixedwingCruiseAltitudeMin = Math.max(0.0, fixedwingCruiseAltitudeMin);
        fixedwingCruiseAltitudeMax = Math.max(fixedwingCruiseAltitudeMin, fixedwingCruiseAltitudeMax);
        fixedwingCombatRadiusMin = Math.max(0.0, fixedwingCombatRadiusMin);
        fixedwingCombatRadiusMax = Math.max(fixedwingCombatRadiusMin, fixedwingCombatRadiusMax);
        groundBigTurnIntervalTickMin = Math.max(1, groundBigTurnIntervalTickMin);
        groundBigTurnIntervalTickMax = Math.max(groundBigTurnIntervalTickMin, groundBigTurnIntervalTickMax);
        groundBigTurnAngleDegMin = Math.max(0.0F, groundBigTurnAngleDegMin);
        groundBigTurnAngleDegMax = Math.max(groundBigTurnAngleDegMin, groundBigTurnAngleDegMax);
        groundBigTurnDurationTick = Math.max(1, groundBigTurnDurationTick);
        airAttackPhaseTick = Math.max(1, airAttackPhaseTick);
        airDisengagePhaseTick = Math.max(1, airDisengagePhaseTick);
        airInitialDisengageTickMin = Math.max(0, airInitialDisengageTickMin);
        airInitialDisengageTickMax = Math.max(airInitialDisengageTickMin, airInitialDisengageTickMax);
    }

    public boolean matchesTarget(Entity entity) {
        for (String raw : targetTypes) {
            String type = raw.toLowerCase(Locale.ROOT);
            if ("vehicle".equals(type) && entity instanceof AbstractVehicle) {
                return true;
            }
            if ("player".equals(type) && entity instanceof Player) {
                return true;
            }
            if ("monster".equals(type) && (entity instanceof Monster || entity.getType().getCategory() == MobCategory.MONSTER)) {
                return true;
            }
            if ("living".equals(type) && entity instanceof LivingEntity) {
                return true;
            }
        }
        return false;
    }

    public RVP_EnumGunnerFaction getFaction() {
        return RVP_EnumGunnerFaction.parse(faction);
    }

    public String getName() {
        return name;
    }

    public List<String> getTargetTypes() {
        return targetTypes;
    }

    public double getSearchRadius() {
        return searchRadius;
    }

    public int getScanIntervalTick() {
        return scanIntervalTick;
    }

    public float getFireWindowDeg() {
        return fireWindowDeg;
    }

    public double getLeadScale() {
        return leadScale;
    }

    public int getBurstFireTick() {
        return burstFireTick;
    }

    public int getBurstRestTick() {
        return burstRestTick;
    }

    public double getCountermeasureRange() {
        return countermeasureRange;
    }

    public int getCountermeasureCooldownTick() {
        return countermeasureCooldownTick;
    }

    public boolean isAllowDrive() {
        return allowDrive;
    }

    public double getDrivePursuitDistance() {
        return drivePursuitDistance;
    }

    public double getDriveStopDistance() {
        return driveStopDistance;
    }

    public int getDriveStuckCheckTick() {
        return driveStuckCheckTick;
    }

    public double getDriveStuckDistance() {
        return driveStuckDistance;
    }

    public int getDriveRecoveryTick() {
        return driveRecoveryTick;
    }

    public double getRotaryCruiseAltitudeMin() {
        return rotaryCruiseAltitudeMin;
    }

    public double getRotaryCruiseAltitudeMax() {
        return rotaryCruiseAltitudeMax;
    }

    public double getFixedwingCruiseAltitudeMin() {
        return fixedwingCruiseAltitudeMin;
    }

    public double getFixedwingCruiseAltitudeMax() {
        return fixedwingCruiseAltitudeMax;
    }

    public double getFixedwingCombatRadiusMin() {
        return fixedwingCombatRadiusMin;
    }

    public double getFixedwingCombatRadiusMax() {
        return fixedwingCombatRadiusMax;
    }

    public boolean isGroundWanderEnabled() {
        return groundWanderEnabled;
    }

    public int getGroundBigTurnIntervalTickMin() {
        return groundBigTurnIntervalTickMin;
    }

    public int getGroundBigTurnIntervalTickMax() {
        return groundBigTurnIntervalTickMax;
    }

    public float getGroundBigTurnAngleDegMin() {
        return groundBigTurnAngleDegMin;
    }

    public float getGroundBigTurnAngleDegMax() {
        return groundBigTurnAngleDegMax;
    }

    public int getGroundBigTurnDurationTick() {
        return groundBigTurnDurationTick;
    }

    public int getAirAttackPhaseTick() {
        return airAttackPhaseTick;
    }

    public int getAirDisengagePhaseTick() {
        return airDisengagePhaseTick;
    }

    public int getAirInitialDisengageTickMin() {
        return airInitialDisengageTickMin;
    }

    public int getAirInitialDisengageTickMax() {
        return airInitialDisengageTickMax;
    }
}
