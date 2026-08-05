package org.ywzj.rvp.weapon.effects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_HbmEffectData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class RVP_HbmEffectBridge {

    private static final String HBM_MOD_ID = "hbm_ntm_rebirth";
    private static final String PARTICLE_UTIL_CLASS = "com.hbm.ntm.particle.ParticleUtil";
    private static final String WEAPON_EXPLOSION_UTIL_CLASS = "com.hbm.ntm.explosion.vnt.WeaponExplosionUtil";
    private static final String EXPLOSION_LARGE_CLASS = "com.hbm.ntm.explosion.ExplosionLarge";
    private static final String EXPLOSION_CHAOS_CLASS = "com.hbm.ntm.explosion.ExplosionChaos";
    private static final String NUCLEAR_EXPLOSION_UTIL_CLASS = "com.hbm.ntm.explosion.NuclearExplosionUtil";
    private static final String NUKE_TOREX_ENTITY_CLASS = "com.hbm.ntm.entity.effect.NukeTorexEntity";
    private static final String HBM_LIVING_PROPS_CLASS = "com.hbm.ntm.player.HbmLivingProperties";
    private static final String HBM_FLUIDS_CLASS = "com.hbm.ntm.fluid.HbmFluids";
    private static final String FLUID_TYPE_CLASS = "com.hbm.ntm.fluid.FluidType";
    private static final String ENTITY_MIST_CLASS = "com.hbm.entity.effect.EntityMist";
    private static final String ENTITY_CHLORINE_FX_CLASS = "com.hbm.entity.particle.EntityChlorineFX";

    private static boolean resolved;
    private static boolean modLoaded;
    @Nullable
    private static Method spawnLegacyExplosionSmallMethod;
    @Nullable
    private static Method spawnLegacyExplosionLargeMethod;
    @Nullable
    private static Method spawnExplosionSmallMethod;
    @Nullable
    private static Method spawnExplosionLargeMethod;
    @Nullable
    private static Method explodeStandardMethod;
    @Nullable
    private static Method spawnNuclearMethod;
    @Nullable
    private static Method spawnNuclearCoreMethod;
    @Nullable
    private static Method createStandardTorexMethod;
    @Nullable
    private static Method setTorexCloudDensityMethod;
    @Nullable
    private static Method spawnShrapnelsMethod;
    @Nullable
    private static Method igniteAllBlocksMethod;
    @Nullable
    private static Method ensurePhosphorusMethod;
    @Nullable
    private static Method spawnHazeCloudMethod;
    @Nullable
    private static Method spawnRbmkMushMethod;
    @Nullable
    private static Method fluidFromNameMethod;
    @Nullable
    private static Method mistCreateMethod;
    @Nullable
    private static Constructor<?> chlorineFxConstructor;

    private RVP_HbmEffectBridge() {}

    public record Result(boolean anyApplied, boolean realExplosionApplied, boolean visualApplied) {}

    public static Result apply(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec, @Nullable Entity source) {
        if (level == null || pos == null || spec == null || !spec.hasAnyEffect()) {
            return new Result(false, false, false);
        }
        ensureResolved();

        boolean realApplied = modLoaded && applyRealExplosion(level, pos, spec, source);
        boolean visualApplied = false;
        if (!realApplied || !realExplosionAlreadyIncludesVisual(spec)) {
            visualApplied = applyVisualPresetWithBackend(level, pos, spec);
        }
        boolean fragApplied = modLoaded && applyShrapnel(level, pos, spec, source);
        boolean phosphorusApplied = modLoaded && applyWhitePhosphorus(level, pos, spec, source);
        boolean chlorineApplied = modLoaded && applyChlorine(level, pos, spec);
        return new Result(realApplied || visualApplied || fragApplied || phosphorusApplied || chlorineApplied,
                realApplied, visualApplied);
    }

    private static boolean applyVisualPresetWithBackend(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec) {
        return switch (spec.getVisualBackend()) {
            case "rvp" -> RVP_HbmVisualService.spawn(level, pos, spec);
            case "hbm" -> modLoaded && applyVisualPreset(level, pos, spec);
            default -> {
                boolean hbmApplied = modLoaded && applyVisualPreset(level, pos, spec);
                yield hbmApplied || RVP_HbmVisualService.spawn(level, pos, spec);
            }
        };
    }

    private static boolean realExplosionAlreadyIncludesVisual(RVP_HbmEffectData spec) {
        String real = spec.getRealExplosion();
        // VNT标准爆炸自带视觉效果，叠加视觉预设会导致双份特效错位
        if ("vnt".equalsIgnoreCase(real)) {
            return true;
        }
        if ("nuclear".equalsIgnoreCase(real)) {
            String preset = spec.getVisualPreset();
            return "nuclear".equalsIgnoreCase(preset) || "nuke".equalsIgnoreCase(preset);
        }
        return false;
    }

    private static boolean applyRealExplosion(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec,
            @Nullable Entity source) {
        if (!spec.hasRealExplosion() || explodeStandardMethod == null) {
            return false;
        }
        try {
            String realExplosion = spec.getRealExplosion();
            if ("vnt".equalsIgnoreCase(realExplosion)) {
                float size = Math.max(0.5f, spec.getEffectYield());
                explodeStandardMethod.invoke(null, level, pos.x, pos.y, pos.z, size, source, spec.isDestroyBlock(), false);
                return true;
            }
            if ("nuclear".equalsIgnoreCase(realExplosion)) {
                return spawnCustomNuclear(level, pos, spec);
            }
            return false;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean spawnCustomNuclear(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec)
            throws ReflectiveOperationException {
        if (spawnNuclearCoreMethod == null && spawnNuclearMethod == null) {
            return false;
        }
        int radius = nuclearRadius(spec);
        boolean spawned;
        if (spawnNuclearCoreMethod != null) {
            spawned = Boolean.TRUE.equals(
                    spawnNuclearCoreMethod.invoke(null, level, radius, pos.x, pos.y, pos.z));
        } else {
            spawned = Boolean.TRUE.equals(
                    spawnNuclearMethod.invoke(null, level, radius, pos.x, pos.y, pos.z));
        }
        if (!spawned || createStandardTorexMethod == null) {
            return spawned;
        }
        Object torex = createStandardTorexMethod.invoke(null, level,
                pos.x,
                pos.y,
                pos.z,
                (float) radius);
        applyTorexCloudDensity(torex, spec);
        if (torex instanceof Entity entity && !entity.isRemoved()) {
            level.addFreshEntity(entity);
        }
        return true;
    }

    private static boolean applyVisualPreset(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec) {
        if (!spec.hasVisualPreset()) {
            return false;
        }
        String preset = spec.getVisualPreset();
        float visualScale = spec.getVisualScale();
        try {
            if ("nuclear".equalsIgnoreCase(preset) || "nuke".equalsIgnoreCase(preset)) {
                return spawnVisualNuclear(level, pos, spec, visualScale);
            }
            if ("shell".equalsIgnoreCase(preset)) {
                if (spawnExplosionSmallMethod != null) {
                    spawnExplosionSmallMethod.invoke(null, level, pos.x, pos.y, pos.z,
                            shellCloudCount(visualScale), shellCloudScale(visualScale), shellCloudSpeed(visualScale),
                            shellDebrisCount(visualScale));
                    return true;
                }
                if (spawnLegacyExplosionSmallMethod != null) {
                    spawnLegacyExplosionSmallMethod.invoke(null, level, pos.x, pos.y, pos.z);
                    return true;
                }
            }
            if ("bomb".equalsIgnoreCase(preset)) {
                if (spawnExplosionLargeMethod != null) {
                    spawnExplosionLargeMethod.invoke(null, level, pos.x, pos.y, pos.z,
                            bombCloudCount(visualScale), bombCloudScale(visualScale), bombCloudSpeed(visualScale),
                            bombWaveScale(visualScale), bombDebrisCount(visualScale), bombDebrisSize(visualScale),
                            bombDebrisRetry(visualScale), bombDebrisVelocity(visualScale),
                            bombDebrisHorizontalDeviation(visualScale), -2.0f, bombSoundRange(visualScale));
                    return true;
                }
                if (spawnLegacyExplosionLargeMethod != null) {
                    spawnLegacyExplosionLargeMethod.invoke(null, level, pos.x, pos.y, pos.z);
                    return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
        return false;
    }

    private static boolean spawnVisualNuclear(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec, float visualScale)
            throws ReflectiveOperationException {
        if (createStandardTorexMethod == null) {
            return false;
        }
        float baseYield = Math.max(spec.getEffectYield(), 1.0f);
        float mushroomScale = (float) clampDouble(baseYield * Math.max(visualScale, 0.1f), 1.0D, 500.0D);
        Object torex = createStandardTorexMethod.invoke(null, level, pos.x, pos.y, pos.z, mushroomScale);
        applyTorexCloudDensity(torex, spec);
        if (torex instanceof Entity entity && !entity.isRemoved()) {
            level.addFreshEntity(entity);
            return true;
        }
        return false;
    }

    private static void applyTorexCloudDensity(Object torex, RVP_HbmEffectData spec) {
        if (torex == null || setTorexCloudDensityMethod == null) {
            return;
        }
        try {
            setTorexCloudDensityMethod.invoke(torex, spec.getVisualDensity());
        } catch (ReflectiveOperationException ignored) {
            // Older HBM builds do not expose cloud density; keep the visual bridge compatible.
        }
    }

    private static int shellCloudCount(float visualScale) {
        return clampInt(Math.round(10.0f * visualScale), 4, 80);
    }

    private static float shellCloudScale(float visualScale) {
        return (float) clampDouble(2.0D * visualScale, 0.4D, 16.0D);
    }

    private static float shellCloudSpeed(float visualScale) {
        return (float) clampDouble(0.5D * Math.sqrt(visualScale), 0.15D, 4.0D);
    }

    private static int shellDebrisCount(float visualScale) {
        return clampInt(Math.round(15.0f * visualScale), 0, 120);
    }

    private static int bombCloudCount(float visualScale) {
        return clampInt(Math.round(30.0f * visualScale), 8, 180);
    }

    private static float bombCloudScale(float visualScale) {
        return (float) clampDouble(6.5D * visualScale, 1.0D, 32.0D);
    }

    private static float bombCloudSpeed(float visualScale) {
        return (float) clampDouble(2.0D * Math.sqrt(visualScale), 0.35D, 6.0D);
    }

    private static float bombWaveScale(float visualScale) {
        return (float) clampDouble(65.0D * visualScale, 8.0D, 220.0D);
    }

    private static int bombDebrisCount(float visualScale) {
        return clampInt(Math.round(25.0f * visualScale), 2, 160);
    }

    private static int bombDebrisSize(float visualScale) {
        return clampInt(Math.round(16.0f * visualScale), 4, 64);
    }

    private static int bombDebrisRetry(float visualScale) {
        return clampInt(Math.round(50.0f * visualScale), 8, 160);
    }

    private static float bombDebrisVelocity(float visualScale) {
        return (float) clampDouble(1.25D * Math.sqrt(visualScale), 0.2D, 4.0D);
    }

    private static float bombDebrisHorizontalDeviation(float visualScale) {
        return (float) clampDouble(3.0D * visualScale, 0.5D, 12.0D);
    }

    private static float bombSoundRange(float visualScale) {
        return (float) clampDouble(350.0D * visualScale, 80.0D, 800.0D);
    }

    private static boolean applyShrapnel(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec,
            @Nullable Entity source) {
        if (!spec.hasFragEffect() || spawnShrapnelsMethod == null) {
            return false;
        }
        try {
            spawnShrapnelsMethod.invoke(null, level, pos.x, pos.y, pos.z, fragCount(spec), 1.0f, source);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean applyWhitePhosphorus(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec,
            @Nullable Entity source) {
        if (!spec.hasWhitePhosphorus()) {
            return false;
        }
        boolean applied = false;
        try {
            if (spawnShrapnelsMethod != null) {
                spawnShrapnelsMethod.invoke(null, level, pos.x, pos.y, pos.z, phosphorusFragCount(spec), 1.0f, source);
                applied = true;
            }
            if (igniteAllBlocksMethod != null) {
                igniteAllBlocksMethod.invoke(null, level, floor(pos.x), floor(pos.y), floor(pos.z),
                        phosphorusIgniteRadius(spec));
                applied = true;
            }
            int entityRadius = phosphorusEntityRadius(spec);
            AABB bounds = new AABB(
                    pos.x - entityRadius, pos.y - entityRadius, pos.z - entityRadius,
                    pos.x + entityRadius, pos.y + entityRadius, pos.z + entityRadius);
            for (Entity entity : level.getEntities(source, bounds, Entity::isAlive)) {
                entity.setSecondsOnFire(phosphorusFireSeconds(spec));
                if (entity instanceof LivingEntity living && ensurePhosphorusMethod != null) {
                    ensurePhosphorusMethod.invoke(null, living, phosphorusTicks(spec));
                }
                applied = true;
            }
            if (spawnHazeCloudMethod != null) {
                spawnHazeCloudMethod.invoke(null, level, pos, phosphorusHazeCount(spec), phosphorusHazeSpread(spec));
                applied = true;
            }
            if (spawnRbmkMushMethod != null) {
                spawnRbmkMushMethod.invoke(null, level, pos, phosphorusMushroomScale(spec));
                applied = true;
            }
        } catch (ReflectiveOperationException ignored) {
            return applied;
        }
        return applied;
    }

    private static boolean applyChlorine(ServerLevel level, Vec3 pos, RVP_HbmEffectData spec) {
        if (!spec.hasChlorineEffect()) {
            return false;
        }
        boolean applied = false;
        Object chlorineFluid = resolveChlorineFluid();
        int mistCount = chlorineMistCount(spec);
        if (chlorineFluid != null && mistCreateMethod != null) {
            try {
                for (int i = 0; i < mistCount; i++) {
                    double x = pos.x + level.random.nextGaussian() * chlorineScatter(spec);
                    double z = pos.z + level.random.nextGaussian() * chlorineScatter(spec);
                    Object mist = mistCreateMethod.invoke(null, level, x, pos.y + chlorineYOffset(spec), z,
                            chlorineFluid, chlorineWidth(spec), chlorineHeight(spec), chlorineDuration(spec));
                    if (mist instanceof Entity entity) {
                        level.addFreshEntity(entity);
                        applied = true;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to visual-only chlorine clouds if mist spawning fails.
            }
        }

        if (chlorineFxConstructor != null) {
            try {
                int cloudCount = chlorineVisualCount(spec);
                for (int i = 0; i < cloudCount; i++) {
                    double motionX = level.random.nextGaussian() * 0.04D;
                    double motionY = Math.max(0.01D, level.random.nextDouble() * 0.05D);
                    double motionZ = level.random.nextGaussian() * 0.04D;
                    Object cloud = chlorineFxConstructor.newInstance(level,
                            pos.x + level.random.nextGaussian() * chlorineScatter(spec) * 0.35D,
                            pos.y + 0.15D,
                            pos.z + level.random.nextGaussian() * chlorineScatter(spec) * 0.35D,
                            motionX, motionY, motionZ);
                    if (cloud instanceof Entity entity) {
                        level.addFreshEntity(entity);
                        applied = true;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                return applied;
            }
        }
        return applied;
    }

    @Nullable
    private static Object resolveChlorineFluid() {
        if (fluidFromNameMethod == null) {
            return null;
        }
        try {
            return fluidFromNameMethod.invoke(null, "chlorine");
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static int fragCount(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 6.0f), 6, 192);
    }

    private static int nuclearRadius(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f)), 1, 500);
    }

    private static int phosphorusFragCount(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 5.0f), 8, 160);
    }

    private static int phosphorusIgniteRadius(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 1.6f), 2, 24);
    }

    private static int phosphorusEntityRadius(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 2.2f), 3, 32);
    }

    private static int phosphorusFireSeconds(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 0.8f), 4, 20);
    }

    private static int phosphorusTicks(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 120.0f), 120, 20 * 60);
    }

    private static int phosphorusHazeCount(RVP_HbmEffectData spec) {
        return clampInt(Math.round(Math.max(spec.getEffectYield(), 1.0f) * 4.0f), 4, 48);
    }

    private static double phosphorusHazeSpread(RVP_HbmEffectData spec) {
        return clampDouble(Math.max(spec.getEffectYield(), 1.0f) * 1.5D, 2.0D, 24.0D);
    }

    private static float phosphorusMushroomScale(RVP_HbmEffectData spec) {
        return (float) clampDouble(Math.max(spec.getEffectYield(), 1.0f), 3.0D, 20.0D);
    }

    private static int chlorineMistCount(RVP_HbmEffectData spec) {
        return clampInt(Math.round(spec.getChlorineYield() * 0.75f), 1, 12);
    }

    private static int chlorineVisualCount(RVP_HbmEffectData spec) {
        return clampInt(Math.round(spec.getChlorineYield() * 0.5f), 1, 8);
    }

    private static float chlorineWidth(RVP_HbmEffectData spec) {
        return (float) clampDouble(spec.getChlorineYield() * 0.9D, 2.5D, 16.0D);
    }

    private static float chlorineHeight(RVP_HbmEffectData spec) {
        return (float) clampDouble(spec.getChlorineYield() * 0.45D, 1.5D, 8.0D);
    }

    private static int chlorineDuration(RVP_HbmEffectData spec) {
        return clampInt(Math.round(spec.getChlorineYield() * 18.0f), 80, 20 * 20);
    }

    private static double chlorineScatter(RVP_HbmEffectData spec) {
        return clampDouble(spec.getChlorineYield() * 0.65D, 0.75D, 10.0D);
    }

    private static double chlorineYOffset(RVP_HbmEffectData spec) {
        return clampDouble(spec.getChlorineYield() * 0.05D, 0.0D, 1.5D);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void ensureResolved() {
        if (resolved) {
            return;
        }
        resolved = true;
        modLoaded = ModList.get().isLoaded(HBM_MOD_ID);
        if (!modLoaded) {
            return;
        }

        Class<?> levelClass = loadClass("net.minecraft.world.level.Level");
        Class<?> entityClass = loadClass("net.minecraft.world.entity.Entity");
        Class<?> livingEntityClass = loadClass("net.minecraft.world.entity.LivingEntity");
        Class<?> vec3Class = loadClass("net.minecraft.world.phys.Vec3");
        Class<?> fluidTypeClass = loadClass(FLUID_TYPE_CLASS);

        Class<?> particleUtilClass = loadClass(PARTICLE_UTIL_CLASS);
        Class<?> weaponExplosionUtilClass = loadClass(WEAPON_EXPLOSION_UTIL_CLASS);
        Class<?> explosionLargeClass = loadClass(EXPLOSION_LARGE_CLASS);
        Class<?> explosionChaosClass = loadClass(EXPLOSION_CHAOS_CLASS);
        Class<?> nuclearExplosionUtilClass = loadClass(NUCLEAR_EXPLOSION_UTIL_CLASS);
        Class<?> nukeTorexEntityClass = loadClass(NUKE_TOREX_ENTITY_CLASS);
        Class<?> hbmLivingPropsClass = loadClass(HBM_LIVING_PROPS_CLASS);
        Class<?> hbmFluidsClass = loadClass(HBM_FLUIDS_CLASS);
        Class<?> entityMistClass = loadClass(ENTITY_MIST_CLASS);
        Class<?> chlorineFxClass = loadClass(ENTITY_CHLORINE_FX_CLASS);

        spawnLegacyExplosionSmallMethod = getMethod(particleUtilClass,
                "spawnLegacyExplosionSmall", levelClass, double.class, double.class, double.class);
        spawnLegacyExplosionLargeMethod = getMethod(particleUtilClass,
                "spawnLegacyExplosionLarge", levelClass, double.class, double.class, double.class);
        spawnExplosionSmallMethod = getMethod(particleUtilClass,
                "spawnExplosionSmall", levelClass, double.class, double.class, double.class,
                int.class, float.class, float.class, int.class);
        spawnExplosionLargeMethod = getMethod(particleUtilClass,
                "spawnExplosionLarge", levelClass, double.class, double.class, double.class,
                int.class, float.class, float.class, float.class, int.class, int.class, int.class,
                float.class, float.class, float.class, float.class);
        explodeStandardMethod = getMethod(weaponExplosionUtilClass,
                "explodeStandard", levelClass, double.class, double.class, double.class, float.class,
                entityClass, boolean.class, boolean.class);
        spawnNuclearMethod = getMethod(nuclearExplosionUtilClass,
                "spawnNuclear", levelClass, int.class, double.class, double.class, double.class);
        spawnNuclearCoreMethod = getMethod(nuclearExplosionUtilClass,
                "spawnNuclearCore", levelClass, int.class, double.class, double.class, double.class);
        createStandardTorexMethod = getMethod(nukeTorexEntityClass,
                "createStandard", levelClass, double.class, double.class, double.class, float.class);
        setTorexCloudDensityMethod = getMethod(nukeTorexEntityClass,
                "setCloudDensity", float.class);
        spawnShrapnelsMethod = getMethod(explosionLargeClass,
                "spawnShrapnels", levelClass, double.class, double.class, double.class, int.class, float.class,
                entityClass);
        igniteAllBlocksMethod = getMethod(explosionChaosClass,
                "igniteAllBlocks", levelClass, int.class, int.class, int.class, int.class);
        ensurePhosphorusMethod = getMethod(hbmLivingPropsClass,
                "ensurePhosphorus", livingEntityClass, int.class);
        spawnHazeCloudMethod = getMethod(particleUtilClass,
                "spawnHazeCloud", levelClass, vec3Class, int.class, double.class);
        spawnRbmkMushMethod = getMethod(particleUtilClass,
                "spawnRbmkMush", levelClass, vec3Class, float.class);
        fluidFromNameMethod = getMethod(hbmFluidsClass,
                "fromName", String.class);
        mistCreateMethod = getMethod(entityMistClass,
                "create", levelClass, double.class, double.class, double.class, fluidTypeClass,
                float.class, float.class, int.class);
        chlorineFxConstructor = getConstructor(chlorineFxClass,
                Level.class, double.class, double.class, double.class, double.class, double.class, double.class);
    }

    @Nullable
    private static Class<?> loadClass(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        try {
            return Class.forName(className);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method getMethod(@Nullable Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Constructor<?> getConstructor(@Nullable Class<?> owner, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getConstructor(parameterTypes);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
