package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class RVP_ProjectileData {

    @SerializedName("velocity")
    private Float velocity;

    @SerializedName("gravity")
    private float gravity = 0f;

    @SerializedName("gravity_in_water")
    private float gravityInWater = 0f;

    @SerializedName("drag")
    private float drag = 0f;

    @SerializedName("drag_in_water")
    private float dragInWater = 0f;

    @SerializedName("inherit_vehicle_velocity")
    private boolean inheritVehicleVelocity = false;

    @SerializedName("constant_speed")
    private boolean constantSpeed = false;

    @SerializedName("rotate_to_motion")
    private boolean rotateToMotion = true;

    @SerializedName("max_speed")
    private float maxSpeed = 0f;

    @SerializedName("min_speed")
    private float minSpeed = 0f;

    @SerializedName("turning_factor")
    private Map<RVP_Range<Integer>, Float> turningFactor;

    @SerializedName("has_rocket_engine")
    private boolean hasRocketEngine = false;

    @SerializedName("mass")
    private float mass = 0f;

    @SerializedName("thrust")
    private float thrust = 0f;

    @SerializedName("motor_burn_time")
    private float motorBurnTime = 0f;

    @SerializedName("second_pulse")
    private boolean secondPulse = false;

    @SerializedName("second_pulse_trigger_speed")
    private float secondPulseTriggerSpeed = 0f;

    @SerializedName("second_pulse_trigger_distance")
    private float secondPulseTriggerDistance = 0f;

    @SerializedName("second_pulse_thrust")
    private float secondPulseThrust = 0f;

    @SerializedName("second_pulse_burn_time")
    private float secondPulseBurnTime = 0f;

    @SerializedName("ignition_delay_tick")
    private int ignitionDelayTick = 0;

    @SerializedName("drag_coefficient")
    private float dragCoefficient = 0f;

    @SerializedName("altitude_drag_factor")
    private Map<RVP_Range<Float>, Float> altitudeDragFactor;

    public void resolvePropulsionFallback(JsonObject weaponRoot, JsonObject projectileJson) {
        if (!hasRocketEngine || weaponRoot == null) {
            return;
        }
        JsonObject proj = projectileJson != null ? projectileJson : new JsonObject();
        if (!proj.has("mass") && weaponRoot.has("mass") && weaponRoot.get("mass").isJsonPrimitive()) {
            mass = weaponRoot.get("mass").getAsFloat();
        }
        if (!proj.has("thrust") && weaponRoot.has("thrust") && weaponRoot.get("thrust").isJsonPrimitive()) {
            thrust = weaponRoot.get("thrust").getAsFloat();
        }
        if (!proj.has("motor_burn_time") && weaponRoot.has("motor_burn_time")
                && weaponRoot.get("motor_burn_time").isJsonPrimitive()) {
            motorBurnTime = weaponRoot.get("motor_burn_time").getAsFloat();
        }
        if (!proj.has("ignition_delay_tick") && weaponRoot.has("ignition_delay_tick")
                && weaponRoot.get("ignition_delay_tick").isJsonPrimitive()) {
            ignitionDelayTick = weaponRoot.get("ignition_delay_tick").getAsInt();
        }
        if (!proj.has("drag_coefficient") && weaponRoot.has("drag_coefficient")
                && weaponRoot.get("drag_coefficient").isJsonPrimitive()) {
            dragCoefficient = weaponRoot.get("drag_coefficient").getAsFloat();
        }
        if (!proj.has("second_pulse") && weaponRoot.has("second_pulse")
                && weaponRoot.get("second_pulse").isJsonPrimitive()) {
            secondPulse = weaponRoot.get("second_pulse").getAsBoolean();
        }
        if (!proj.has("second_pulse_trigger_speed") && weaponRoot.has("second_pulse_trigger_speed")
                && weaponRoot.get("second_pulse_trigger_speed").isJsonPrimitive()) {
            secondPulseTriggerSpeed = weaponRoot.get("second_pulse_trigger_speed").getAsFloat();
        }
        if (!proj.has("second_pulse_trigger_distance") && weaponRoot.has("second_pulse_trigger_distance")
                && weaponRoot.get("second_pulse_trigger_distance").isJsonPrimitive()) {
            secondPulseTriggerDistance = weaponRoot.get("second_pulse_trigger_distance").getAsFloat();
        }
        if (!proj.has("second_pulse_thrust") && weaponRoot.has("second_pulse_thrust")
                && weaponRoot.get("second_pulse_thrust").isJsonPrimitive()) {
            secondPulseThrust = weaponRoot.get("second_pulse_thrust").getAsFloat();
        }
        if (!proj.has("second_pulse_burn_time") && weaponRoot.has("second_pulse_burn_time")
                && weaponRoot.get("second_pulse_burn_time").isJsonPrimitive()) {
            secondPulseBurnTime = weaponRoot.get("second_pulse_burn_time").getAsFloat();
        }
    }

    public Float getVelocityOverride() {
        return velocity;
    }

    public boolean hasVelocityOverride() {
        return velocity != null;
    }

    public float getGravity() {
        return gravity;
    }

    public float getGravityInWater() {
        return gravityInWater;
    }

    public float getDrag() {
        return drag;
    }

    public float getDragInWater() {
        return dragInWater;
    }

    public boolean isInheritVehicleVelocity() {
        return inheritVehicleVelocity;
    }

    public boolean isConstantSpeed() {
        return constantSpeed;
    }

    public boolean isRotateToMotion() {
        return rotateToMotion;
    }

    public float getMaxSpeed() {
        return Math.max(maxSpeed, 0f);
    }

    public float getMinSpeed() {
        return Math.max(minSpeed, 0f);
    }

    public boolean hasTurningFactor() {
        return turningFactor != null && !turningFactor.isEmpty();
    }

    public Float resolveTurningFactor(int flightTick) {
        if (!hasTurningFactor()) {
            return null;
        }
        int tick = Math.max(flightTick, 0);
        for (Map.Entry<RVP_Range<Integer>, Float> entry : turningFactor.entrySet()) {
            RVP_Range<Integer> range = entry.getKey();
            Float value = entry.getValue();
            if (range == null || value == null || !range.contains(tick) || !Float.isFinite(value)) {
                continue;
            }
            return Math.max(0f, Math.min(1f, value));
        }
        return null;
    }

    public boolean hasRocketEngine() {
        return hasRocketEngine;
    }

    public float getResolvedMass() {
        return hasRocketEngine ? Math.max(mass, 0f) : 0f;
    }

    public float getResolvedThrust() {
        return hasRocketEngine ? Math.max(thrust, 0f) : 0f;
    }

    public float getResolvedMotorBurnTime() {
        return hasRocketEngine ? Math.max(motorBurnTime, 0f) : 0f;
    }

    public boolean isSecondPulse() {
        return secondPulse;
    }

    public float getResolvedSecondPulseTriggerSpeed() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseTriggerSpeed, 0f) : 0f;
    }

    public float getResolvedSecondPulseTriggerDistance() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseTriggerDistance, 0f) : 0f;
    }

    public float getResolvedSecondPulseThrust() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseThrust, 0f) : 0f;
    }

    public float getResolvedSecondPulseBurnTime() {
        return hasRocketEngine && secondPulse ? Math.max(secondPulseBurnTime, 0f) : 0f;
    }

    public boolean usesSecondPulse() {
        if (!hasRocketEngine || !secondPulse) {
            return false;
        }
        if (getResolvedSecondPulseThrust() <= 0f || getResolvedSecondPulseBurnTime() <= 0f) {
            return false;
        }
        return getResolvedSecondPulseTriggerSpeed() > 0f || getResolvedSecondPulseTriggerDistance() > 0f;
    }

    public int getResolvedIgnitionDelayTick() {
        return hasRocketEngine ? Math.max(ignitionDelayTick, 0) : 0;
    }

    public float getResolvedDragCoefficient() {
        return hasRocketEngine ? Math.max(dragCoefficient, 0f) : 0f;
    }

    public Map<RVP_Range<Float>, Float> getAltitudeDragFactor() {
        return altitudeDragFactor;
    }

    public float resolveAltitudeDragFactor(double y) {
        if (altitudeDragFactor == null || altitudeDragFactor.isEmpty()) {
            return 1.0f;
        }
        float sample = normalizeAltitudeSample(y);
        for (Map.Entry<RVP_Range<Float>, Float> entry : altitudeDragFactor.entrySet()) {
            RVP_Range<Float> range = entry.getKey();
            if (range == null || !range.contains(sample)) {
                continue;
            }
            Float factor = entry.getValue();
            if (factor == null || !Float.isFinite(factor) || factor <= 0f) {
                return 1.0f;
            }
            return factor;
        }
        return 1.0f;
    }

    private static float normalizeAltitudeSample(double y) {
        if (Double.isNaN(y)) {
            return 0f;
        }
        if (Double.isInfinite(y)) {
            return y > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
        }
        if (y > Float.MAX_VALUE) {
            return Float.MAX_VALUE;
        }
        if (y < -Float.MAX_VALUE) {
            return -Float.MAX_VALUE;
        }
        return (float) y;
    }

    public boolean usesPropulsion() {
        if (!hasRocketEngine) {
            return false;
        }
        return getResolvedMass() > 1.0E-6f
                && getResolvedThrust() > 0f
                && getResolvedMotorBurnTime() > 0f;
    }

    public boolean isRocketEngineMisconfigured() {
        return hasRocketEngine && !usesPropulsion();
    }
}
