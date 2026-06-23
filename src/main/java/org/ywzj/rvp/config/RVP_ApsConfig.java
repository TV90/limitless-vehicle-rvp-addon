package org.ywzj.rvp.config;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class RVP_ApsConfig {

    public static final RVP_ApsConfig DISABLED = new RVP_ApsConfig(
            false,
            0,
            600,
            20,
            1,
            32.0,
            8.0,
            1.0,
            80.0,
            List.of(),
            null,
            true
    );

    private final boolean enabled;
    private final int ammoMax;
    private final int reloadOneTick;
    private final int cooldownTick;
    private final int scanIntervalTick;
    private final double detectRadius;
    private final double interceptRadius;
    private final double projectileSpeedMin;
    private final double projectileSpeedMax;
    private final List<String> animationPartIds;
    private final String spawnPartId;
    private final boolean excludeOwnerProjectile;

    public RVP_ApsConfig(boolean enabled,
                         int ammoMax,
                         int reloadOneTick,
                         int cooldownTick,
                         int scanIntervalTick,
                         double detectRadius,
                         double interceptRadius,
                         double projectileSpeedMin,
                         double projectileSpeedMax,
                         List<String> animationPartIds,
                         @Nullable String spawnPartId,
                         boolean excludeOwnerProjectile) {
        this.enabled = enabled;
        this.ammoMax = Math.max(0, ammoMax);
        this.reloadOneTick = Math.max(1, reloadOneTick);
        this.cooldownTick = Math.max(1, cooldownTick);
        this.scanIntervalTick = Math.max(1, scanIntervalTick);
        this.detectRadius = Math.max(0.0, detectRadius);
        this.interceptRadius = Math.max(0.1, interceptRadius);
        this.projectileSpeedMin = Math.max(0.0, projectileSpeedMin);
        this.projectileSpeedMax = Math.max(this.projectileSpeedMin, projectileSpeedMax);
        this.animationPartIds = sanitizePartIds(animationPartIds);
        this.spawnPartId = normalizePartId(spawnPartId);
        this.excludeOwnerProjectile = excludeOwnerProjectile;
    }

    public boolean isEnabled() {
        return enabled && ammoMax > 0 && spawnPartId != null;
    }

    public int getAmmoMax() {
        return ammoMax;
    }

    public int getReloadOneTick() {
        return reloadOneTick;
    }

    public int getCooldownTick() {
        return cooldownTick;
    }

    public int getScanIntervalTick() {
        return scanIntervalTick;
    }

    public double getDetectRadius() {
        return detectRadius;
    }

    public double getInterceptRadius() {
        return interceptRadius;
    }

    public double getProjectileSpeedMin() {
        return projectileSpeedMin;
    }

    public double getProjectileSpeedMax() {
        return projectileSpeedMax;
    }

    public List<String> getAnimationPartIds() {
        return animationPartIds;
    }

    public @Nullable String getSpawnPartId() {
        return spawnPartId;
    }

    public boolean isExcludeOwnerProjectile() {
        return excludeOwnerProjectile;
    }

    public @Nullable String getAnimationPartId(int index) {
        if (animationPartIds.isEmpty()) {
            return spawnPartId;
        }
        return animationPartIds.get(Math.floorMod(index, animationPartIds.size()));
    }

    private static List<String> sanitizePartIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>(ids.size());
        for (String id : ids) {
            String normalized = normalizePartId(id);
            if (normalized != null) {
                sanitized.add(normalized);
            }
        }
        return List.copyOf(sanitized);
    }

    private static @Nullable String normalizePartId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
