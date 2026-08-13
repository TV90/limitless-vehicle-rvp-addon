package org.ywzj.rvp.countermeasure;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 雷达箔条判定公共助手（双端安全，客户端/服务端复用同一逻辑）。
 *
 * <p>模型：雷达**当前锁定目标**周围 `radarJamRadius` 内箔条实体数 ≥ `radarJamCount` 时，
 * 雷达脱锁，目标进入 `radarJamCooldownTick` 禁锁期（可被扫描、不可被选中/锁定）。</p>
 */
public final class RVP_ChaffJamHelper {

    private RVP_ChaffJamHelper() {
    }

    /** 返回目标载具的箔条子系统配置（用于雷达判定）；非载具或未启用返回 null。 */
    @Nullable
    public static RVP_CountermeasureSystemData resolveChaffConfig(@Nullable Entity target) {
        if (target instanceof AbstractVehicle tv) {
            RVP_CountermeasureData config = RVP_CountermeasureConfigManager.INSTANCE.resolve(tv.getVehicleId());
            RVP_CountermeasureSystemData chaff = config == null ? null : config.getChaff();
            return chaff != null && chaff.isEnabled() ? chaff : null;
        }
        return null;
    }

    /** 统计目标周围 {@code radius} 内指定类型的干扰物实体数。 */
    public static int countDecoysNear(@Nullable Entity target, double radius, RVP_EnumCountermeasureType type) {
        if (target == null || radius <= 0) {
            return 0;
        }
        AABB box = target.getBoundingBox().inflate(radius);
        int count = 0;
        for (Entity entity : target.level().getEntities(target, box, e -> e instanceof RVP_Decoy && e.isAlive())) {
            if (((RVP_Decoy) entity).rvp$decoyType() == type && entity.distanceTo(target) <= radius) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统一箔条干扰处理：锁定目标被箔条遮蔽（超阈值）时脱锁并写目标禁锁期。
     *
     * @param radarOwnerVehicle 拥有该雷达的载具（用于清理其武器锁定）
     * @param radar             被干扰的雷达
     * @param locked            被锁定目标
     * @param gameTime          当前游戏 tick
     * @return 是否发生了干扰脱锁
     */
    public static boolean tryJamLock(Entity radarOwnerVehicle, RadarUnit radar, Entity locked, long gameTime) {
        RVP_CountermeasureSystemData chaff = resolveChaffConfig(locked);
        if (chaff == null) {
            return false;
        }
        int count = countDecoysNear(locked, chaff.getRadarJamRadius(), RVP_EnumCountermeasureType.CHAFF);
        if (count < chaff.getRadarJamCount()) {
            return false;
        }
        breakLock(radarOwnerVehicle, radar, locked);
        RVP_ChaffJamState.setCooldown(locked.getUUID(), gameTime, chaff.getRadarJamCooldownTick());
        return true;
    }

    /** 清除指向目标的所有雷达/武器锁定与 pending/外部锁定。 */
    private static void breakLock(Entity radarOwnerVehicle, RadarUnit radar, Entity target) {
        radar.setLockedEntity(null);
        if (radarOwnerVehicle instanceof AbstractVehicle vehicle) {
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit wu)) {
                    continue;
                }
                WeaponUnit root = wu.getRootParentWeaponUnit();
                if (root == null) {
                    continue;
                }
                if (root.getLockedEntity() == target) {
                    root.setLockedEntity(null);
                }
                if (RVP_WeaponLockStateTable.getPendingRadarLockEntityId(root) == target.getId()) {
                    RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(root);
                }
                if (RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root) == target.getId()) {
                    RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
                }
            }
        }
    }
}
