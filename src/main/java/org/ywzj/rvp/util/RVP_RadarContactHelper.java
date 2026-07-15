package org.ywzj.rvp.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CHbmMissileSnapshot;
import org.ywzj.rvp.radar.RVP_HbmRadarContact;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RVP_RadarContactHelper {
    private static final String HBM_MOD_ID = "hbm_ntm_rebirth";
    private static final String HBM_MISSILE_CLASS = "com.hbm.ntm.entity.missile.MissileEntity";
    private static final String HBM_CUSTOM_MISSILE_CLASS = "com.hbm.ntm.entity.missile.CustomMissileEntity";
    private static final String HBM_ABM_CLASS = "com.hbm.ntm.entity.missile.AntiBallisticMissileEntity";

    private static boolean hbmResolved;
    private static boolean hbmAvailable;
    private static Class<?> hbmMissileClass;
    private static Class<?> hbmCustomMissileClass;
    private static Class<?> hbmAbmClass;
    private static Field hbmTargetXField;
    private static Field hbmTargetZField;
    private static Field hbmTrackingField;
    private static Method hbmClearChunkLoaderMethod;
    private static Method hbmOnMissileImpactMethod;

    private RVP_RadarContactHelper() {}

    @Nullable
    public static Entity resolveRadarIdentity(@Nullable Entity entity) {
        Entity current = entity;
        int guard = 0;
        while (current instanceof PartEntity<?> partEntity && guard++ < 8) {
            current = partEntity.getParent();
        }
        return current;
    }

    public static boolean isHbmMissile(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null) {
            return false;
        }
        if (resolved instanceof RVP_HbmRadarContact) {
            return true;
        }
        ensureHbmResolved();
        if (!hbmAvailable) {
            return false;
        }
        return isInstance(hbmMissileClass, resolved)
                || isInstance(hbmCustomMissileClass, resolved)
                || isInstance(hbmAbmClass, resolved);
    }

    public static boolean isBossThreat(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (!(resolved instanceof LivingEntity living)) {
            return false;
        }
        if (resolved instanceof EnderDragon
                || resolved instanceof WitherBoss
                || resolved instanceof Warden
                || resolved instanceof ElderGuardian) {
            return true;
        }
        return resolved instanceof Enemy
                && resolved.getType().getCategory() == MobCategory.MONSTER
                && living.getMaxHealth() >= 80.0f;
    }

    public static boolean usesMonsterIcon(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null) {
            return false;
        }
        return isBossThreat(resolved) || resolved.getType().getCategory() == MobCategory.MONSTER;
    }

    public static boolean forceHostileIff(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved instanceof RVP_HbmRadarContact contact) {
            S2CHbmMissileSnapshot.Affiliation affiliation = contact.ywzj_rvp$getRadarAffiliation();
            return affiliation == null || affiliation == S2CHbmMissileSnapshot.Affiliation.HOSTILE;
        }
        return isBossThreat(entity) || isHbmMissile(entity);
    }

    @Nullable
    public static String resolveShortNctr(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null) {
            return null;
        }
        if (resolved instanceof RVP_HbmRadarContact) {
            return "MSL";
        }
        if (isHbmMissile(resolved)) {
            return "MSL";
        }
        if (resolved instanceof EnderDragon) {
            return "DRG";
        }
        if (resolved instanceof WitherBoss) {
            return "WTH";
        }
        if (resolved instanceof Warden) {
            return "WRD";
        }
        if (resolved instanceof ElderGuardian) {
            return "EGD";
        }
        if (isBossThreat(resolved)) {
            return "BOS";
        }
        return null;
    }

    @Nullable
    public static Vec3 resolveSpecialTargetPos(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null) {
            return null;
        }
        if (resolved instanceof RVP_HbmRadarContact contact) {
            return contact.ywzj_rvp$getRadarTargetPos();
        }
        if (!isHbmMissile(resolved)) {
            return null;
        }
        ensureHbmResolved();
        if (hbmAbmClass != null && hbmAbmClass.isInstance(resolved) && hbmTrackingField != null) {
            try {
                Object tracking = hbmTrackingField.get(resolved);
                if (tracking instanceof Entity trackingEntity && trackingEntity.isAlive()) {
                    return trackingEntity.position();
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        if ((isInstance(hbmMissileClass, resolved) || isInstance(hbmCustomMissileClass, resolved))
                && hbmTargetXField != null && hbmTargetZField != null) {
            try {
                double targetX = hbmTargetXField.getDouble(resolved);
                double targetZ = hbmTargetZField.getDouble(resolved);
                return new Vec3(targetX, resolved.getY(), targetZ);
            } catch (IllegalAccessException ignored) {
            }
        }
        return null;
    }

    public static boolean neutralizeHbmMissile(@Nullable Entity entity) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null || !isHbmMissile(resolved)) {
            return false;
        }
        ensureHbmResolved();
        if (hbmClearChunkLoaderMethod != null && isInstance(hbmMissileClass, resolved)) {
            try {
                hbmClearChunkLoaderMethod.invoke(resolved);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        resolved.discard();
        return true;
    }

    public static boolean triggerHbmMissileFuze(@Nullable Entity entity, @Nullable Vec3 hitPos) {
        Entity resolved = resolveRadarIdentity(entity);
        if (resolved == null || !isHbmMissile(resolved)) {
            return false;
        }
        ensureHbmResolved();
        if (isInstance(hbmMissileClass, resolved) && hbmOnMissileImpactMethod != null) {
            Vec3 impactPos = hitPos != null ? hitPos : resolved.position();
            HitResult hit = new BlockHitResult(impactPos, Direction.UP, BlockPos.containing(impactPos), false);
            try {
                hbmOnMissileImpactMethod.invoke(resolved, hit);
            } catch (ReflectiveOperationException ignored) {
                return neutralizeHbmMissile(resolved);
            }
            if (hbmClearChunkLoaderMethod != null) {
                try {
                    hbmClearChunkLoaderMethod.invoke(resolved);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            resolved.discard();
            return true;
        }
        return neutralizeHbmMissile(resolved);
    }

    private static void ensureHbmResolved() {
        if (hbmResolved) {
            return;
        }
        hbmResolved = true;
        if (!ModList.get().isLoaded(HBM_MOD_ID)) {
            return;
        }
        try {
            hbmMissileClass = Class.forName(HBM_MISSILE_CLASS);
            hbmCustomMissileClass = Class.forName(HBM_CUSTOM_MISSILE_CLASS);
            hbmAbmClass = Class.forName(HBM_ABM_CLASS);
            hbmTargetXField = findField(hbmMissileClass, "targetX");
            hbmTargetZField = findField(hbmMissileClass, "targetZ");
            hbmTrackingField = findField(hbmAbmClass, "tracking");
            hbmClearChunkLoaderMethod = findMethod(hbmMissileClass, "clearChunkLoader");
            hbmOnMissileImpactMethod = findMethod(hbmMissileClass, "onMissileImpact", HitResult.class);
            hbmAvailable = true;
        } catch (Throwable ignored) {
            hbmAvailable = false;
        }
    }

    @Nullable
    private static Field findField(@Nullable Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethod(@Nullable Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isInstance(@Nullable Class<?> type, Entity entity) {
        return type != null && type.isInstance(entity);
    }
}
