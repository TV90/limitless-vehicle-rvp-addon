package org.ywzj.rvp.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientRadarAction;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RVP_RadarRoleHelper {
    public static final String ROLE_ALL = "ALL";
    public static final String ROLE_SEARCH = "SEARCH";
    public static final String ROLE_FIRE_CONTROL = "FIRE_CONTROL";
    public static final float MANUAL_LOCK_REQUEST_FOV = 18.0f;

    /**
     * 自弹手动锁评分罚分（2026-09-15）：高于任意方位+距离的原始评分理论上限
     * （{@code 180×4 + 远距×0.01}），保证自己载具发射的弹体沉到手动锁候选末位——
     * 降权而非禁锁，循环选择到末位仍可选中（如需回收自弹测试等场景）。
     */
    private static final double OWN_MISSILE_LOCK_PENALTY = 1000.0;

    private RVP_RadarRoleHelper() {}

    public record ManualLockCandidate(Entity entity, Vec3 position, double score) {}

    public static String getRadarRole(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return ROLE_ALL;
        }
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$getRadarRole();
        }
        return ROLE_ALL;
    }

    public static boolean canSearch(RadarUnit radarUnit) {
        return radarUnit != null;
    }

    public static boolean canLock(RadarUnit radarUnit) {
        return radarUnit != null && !ROLE_SEARCH.equalsIgnoreCase(getRadarRole(radarUnit));
    }

    /** 雷达对箔条目标的锁定抗性（0~1）：越大，箔条作为锁定候选的优先级越低（非完全不可锁）。 */
    public static float getRadarChaffResistance(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return 0f;
        }
        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$getChaffResistance();
        }
        return 0.5f;
    }

    public static RadarUnit getPreferredLockRadar(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        RadarUnit firstLockCapable = null;
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!canLock(radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            if (ROLE_FIRE_CONTROL.equalsIgnoreCase(getRadarRole(radarUnit))) {
                return radarUnit;
            }
            if (firstLockCapable == null) {
                firstLockCapable = radarUnit;
            }
        }
        return firstLockCapable;
    }

    /**
     * 解析实际承担锁定的雷达：优先返回能探测到目标的火控雷达；若火控雷达探测不到
     * （目标在火控扇区/高度外），退回任意探测到目标的其它可锁雷达。
     *
     * <p>锁定必须由可锁雷达（火控雷达）完成：目标在火控扇区外时保持 pending 跟踪，
     * 绝不回退到搜索雷达落锁——搜索雷达无锁定能力，回退会绕过火控雷达的物理限制，
     * 也会让 NCTR（搜索雷达 nctr_mode 可能为 NONE）与告警链路读到错误的雷达。</p>
     *
     * <p>返回 {@code preferred}（可能探测不到目标）作为兜底，供 pending 跟踪使用；
     * 雷达锁定只有落在"自身探测表包含目标"的雷达上才会被本体 {@code tickLock} 保留。</p>
     */
    public static RadarUnit resolveLockRadarForTarget(@Nullable WeaponUnit weaponUnit, @Nullable Entity target) {
        if (weaponUnit == null || target == null) {
            return null;
        }
        RadarUnit preferred = getPreferredLockRadar(weaponUnit);
        if (preferred != null && radarCurrentlyDetects(preferred, target)) {
            return preferred;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit == preferred || !radarUnit.isOn() || !radarCurrentlyDetects(radarUnit, target)) {
                continue;
            }
            if (canLock(radarUnit)) {
                return radarUnit;
            }
        }
        return preferred;
    }

    public static RadarUnit getLockedRadar(WeaponUnit weaponUnit) {
        RadarUnit preferred = getPreferredLockRadar(weaponUnit);
        if (preferred != null && preferred.getLockedEntity() != null) {
            return preferred;
        }
        if (weaponUnit == null) {
            return null;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit.getLockedEntity() != null) {
                return radarUnit;
            }
        }
        return preferred;
    }

    public static Entity getLockedRadarEntity(WeaponUnit weaponUnit) {
        RadarUnit radarUnit = getLockedRadar(weaponUnit);
        return radarUnit != null ? radarUnit.getLockedEntity() : null;
    }

    @Nullable
    public static Entity getEffectiveRfLockedEntity(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        RadarUnit radarUnit = getLockedRadar(root);
        if (radarUnit != null) {
            Entity radarLocked = radarUnit.getLockedEntity();
            if (radarLocked != null && radarLocked.isAlive()) {
                return radarLocked;
            }
        }
        int externalLockedId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
        if (externalLockedId != Integer.MIN_VALUE) {
            Entity externalLocked = root.getVehicle().level().getEntity(externalLockedId);
            if (externalLocked != null && externalLocked.isAlive()) {
                return externalLocked;
            }
        }
        Entity localLocked = root.getLockedEntity();
        return localLocked != null && localLocked.isAlive() ? localLocked : null;
    }

    public static void clearAllRadarLocks(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
        clearPendingRadarLock(weaponUnit);
        syncRadarLockClearToServer(weaponUnit);
    }

    public static boolean applyRequestedLock(WeaponUnit weaponUnit, Entity target) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        // 箔条禁锁期：目标被箔条干扰脱锁后的短时间内不可被选中 / 锁定（仍可被扫描）
        if (weaponUnit.getVehicle() != null
                && RVP_ChaffJamState.isInCooldown(target.getUUID(), weaponUnit.getVehicle().level().getGameTime())) {
            return false;
        }
        RadarUnit lockRadar = resolveLockRadarForTarget(weaponUnit, target);
        if (lockRadar == null) {
            return false;
        }
        weaponUnit.setFocusLockPos(null);
        if (!entityMatches(weaponUnit.getLockedEntity(), target.getId())) {
            weaponUnit.setLockedEntity(target);
        }
        if (radarCurrentlyDetects(lockRadar, target)) {
            if (!entityMatches(lockRadar.getLockedEntity(), target.getId())) {
                clearOtherRadarLocks(weaponUnit, lockRadar);
                lockRadar.setLockedEntity(target);
                syncRadarLockToServer(weaponUnit, target);
            }
            clearPendingRadarLock(weaponUnit);
        } else {
            setPendingRadarLock(weaponUnit, target.getId());
        }
        return true;
    }

    public static void tickPendingRadarLock(WeaponUnit weaponUnit) {
        int pendingId = RVP_WeaponLockStateTable.getPendingRadarLockEntityId(weaponUnit);
        if (pendingId == Integer.MIN_VALUE || weaponUnit == null) {
            return;
        }
        Entity target = weaponUnit.getVehicle().level().getEntity(pendingId);
        if (target == null || !target.isAlive()) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            if (entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
                weaponUnit.setLockedEntity(null);
            }
            return;
        }
        // 箔条禁锁期：清 pending，不落锁（目标仍可被扫描）
        if (RVP_ChaffJamState.isInCooldown(target.getUUID(), weaponUnit.getVehicle().level().getGameTime())) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            if (entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
                weaponUnit.setLockedEntity(null);
            }
            return;
        }
        RadarUnit lockRadar = resolveLockRadarForTarget(weaponUnit, target);
        if (lockRadar == null) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            return;
        }
        if (radarCurrentlyDetects(lockRadar, target)) {
            if (!entityMatches(lockRadar.getLockedEntity(), pendingId)) {
                clearOtherRadarLocks(weaponUnit, lockRadar);
                lockRadar.setLockedEntity(target);
                syncRadarLockToServer(weaponUnit, target);
            }
            if (!entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
                weaponUnit.setLockedEntity(target);
            }
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
            return;
        }
        if (!entityMatches(weaponUnit.getLockedEntity(), pendingId)) {
            weaponUnit.setLockedEntity(target);
        }
    }

    public static Vec3 resolveManualLockAimVec(@Nullable WeaponUnit weaponUnit) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            Vec3 look = player.getLookAngle();
            if (look.lengthSqr() > 1.0E-6) {
                return look.normalize();
            }
        }
        if (weaponUnit != null) {
            Vec3 aimVec = weaponUnit.worldVec();
            if (aimVec.lengthSqr() > 1.0E-6) {
                return aimVec.normalize();
            }
        }
        return new Vec3(0.0, 0.0, 1.0);
    }

    public static double scoreManualLockCandidate(Vec3 origin, Vec3 aimVec, Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(origin);
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return Double.MAX_VALUE;
        }
        double angle = Math.toDegrees(VectorUtil.angleBetween(aimVec, toTarget));
        if (Double.isNaN(angle)) {
            return Double.MAX_VALUE;
        }
        double distance = Math.sqrt(origin.distanceToSqr(targetPos));
        return angle * 4.0 + distance * 0.01;
    }

    public static List<ManualLockCandidate> collectManualLockCandidates(@Nullable WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return List.of();
        }
        Set<Integer> seen = new HashSet<>();
        List<ManualLockCandidate> candidates = new ArrayList<>();
        Vec3 aimVec = resolveManualLockAimVec(weaponUnit);
        Vec3 origin = weaponUnit.worldPivotPosition();
        long gameTime = weaponUnit.getVehicle() == null ? 0 : weaponUnit.getVehicle().level().getGameTime();
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!canSearch(radarUnit) || !radarUnit.isOn()) {
                continue;
            }
            // 雷达箔条抗性：对本雷达检测到的箔条候选施加评分罚分（越大优先级越低，非完全不可锁）
            float chaffResistance = getRadarChaffResistance(radarUnit);
            for (RadarUnit.DetectedObject detectedObject : radarUnit.getDetectedEntities().values()) {
                Entity entity = detectedObject.entity;
                if (entity == null || !entity.isAlive() || !seen.add(entity.getId())) {
                    continue;
                }
                // 箔条禁锁期：不可被手动选中 / 锁定
                if (RVP_ChaffJamState.isInCooldown(entity.getUUID(), gameTime)) {
                    continue;
                }
                Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(origin);
                if (toTarget.lengthSqr() <= 1.0E-6) {
                    continue;
                }
                Vec3 targetPos = entity.getBoundingBox().getCenter();
                double score = scoreManualLockCandidate(origin, aimVec, targetPos);
                if (entity instanceof org.ywzj.rvp.countermeasure.RVP_Decoy decoy
                        && decoy.rvp$decoyType() == org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType.CHAFF) {
                    score += chaffResistance * 30.0;
                }
                // 目的：自弹最低优先级（2026-09-15）——拦截导弹作业时，自己刚发射的拦截弹常是
                // 距雷达最近、方位最正的空中物体，按原始评分（方位×4+距离×0.01）总排在候选最前，
                // 想锁来袭目标时反复锁到自弹。对自己载具发射的弹体施加固定大额评分罚分——
                // 罚分高于任意方位+距离的原始评分上限（180×4+远距×0.01），保证自弹沉到候选末位；
                // 参照上方箔条抗性罚分先例，是降权而非禁锁（循环选择到末位仍可选中自弹）。
                if (entity instanceof RVP_BaseBullet ownProjectile
                        && ownProjectile.getShooterVehicle() == weaponUnit.getVehicle()) {
                    score += OWN_MISSILE_LOCK_PENALTY;
                }
                if (!Double.isFinite(score)) {
                    continue;
                }
                candidates.add(new ManualLockCandidate(entity, targetPos, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(ManualLockCandidate::score));
        return candidates;
    }

    public static Entity findManualLockCandidate(WeaponUnit weaponUnit) {
        List<ManualLockCandidate> candidates = collectManualLockCandidates(weaponUnit);
        return candidates.isEmpty() ? null : candidates.get(0).entity();
    }

    public static boolean radarCurrentlyDetects(RadarUnit radarUnit, Entity target) {
        return radarUnit != null && target != null && radarUnit.getDetectedEntities().containsKey(target.getId());
    }

    public static boolean entityMatches(Entity entity, int entityId) {
        return entity != null && entityId != Integer.MIN_VALUE && entity.getId() == entityId;
    }

    public static void setPendingRadarLock(WeaponUnit weaponUnit, int entityId) {
        if (weaponUnit != null) {
            RVP_WeaponLockStateTable.setPendingRadarLockEntityId(weaponUnit, entityId);
        }
    }

    public static void clearPendingRadarLock(WeaponUnit weaponUnit) {
        if (weaponUnit != null) {
            RVP_WeaponLockStateTable.clearPendingRadarLockEntityId(weaponUnit);
        }
    }

    private static void clearOtherRadarLocks(WeaponUnit weaponUnit, RadarUnit keepRadar) {
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit != keepRadar && radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
    }

    /**
     * 客户端雷达锁定落位后同步给服务端：复用本体 {@link ClientRadarAction#Action.LOCK} 报文，
     * 服务端 {@code ClientRadarActionMixin} 会按角色把锁定路由到火控雷达，
     * 使本体 {@code WeaponUnit.tick()} 能读到锁定并发送 RADAR_LOCK 告警。
     * 仅在客户端（物理侧）发送；服务端调用时直接返回。
     */
    private static void syncRadarLockToServer(WeaponUnit weaponUnit, Entity target) {
        if (weaponUnit == null || target == null || !target.isAlive()
                || weaponUnit.getVehicle() == null
                || !weaponUnit.getVehicle().level().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        ClientRadarAction message = new ClientRadarAction();
        message.action = ClientRadarAction.Action.LOCK;
        message.toEntityId = target.getId();
        Channel.CHANNEL.sendToServer(message);
    }

    /** 客户端解锁时同步清除服务端雷达锁定（toEntityId=-1 表示清锁）。 */
    private static void syncRadarLockClearToServer(WeaponUnit weaponUnit) {
        if (weaponUnit == null || weaponUnit.getVehicle() == null
                || !weaponUnit.getVehicle().level().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        ClientRadarAction message = new ClientRadarAction();
        message.action = ClientRadarAction.Action.LOCK;
        message.toEntityId = -1;
        Channel.CHANNEL.sendToServer(message);
    }
}
