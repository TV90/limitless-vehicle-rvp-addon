package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 客户端 HMD 头盔瞄准具状态管理。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>RADAR</b> — 雷达 HMD（格斗模式），按 5 键切换。扫描使用雷达 5° 锥体 + 雷达检测列表。</li>
 *   <li><b>IR</b> — 红外弹 HMD，切换到红外弹并开启导引头时自动启用。扫描使用导引头 FOV + 导引头范围。</li>
 * </ul>
 */
public class RVP_ClientHmdState {

    public enum HmdType {
        NONE, RADAR, IR
    }

    private static final RVP_ClientHmdState INSTANCE = new RVP_ClientHmdState();

    /** 雷达 HMD 扫描锥体半角（度），全角 5°。 */
    private static final float RADAR_HMD_HALF_FOV = 2.5f;

    /** 雷达 HMD 扫描距离倍率（相对雷达最大距离）。 */
    private static final float HMD_RANGE_MULTIPLIER = 0.5f;

    /** HMD 扫描间隔（tick）。 */
    private static final int HMD_SCAN_INTERVAL = 5;

    /** 超出雷达离轴限制后的退出延迟（tick）。 */
    private static final int OUT_OF_BOUNDS_TIMEOUT = 20;

    /** HMD 延时平滑系数（~0.03s 追上）。 */
    private static final float SMOOTH_FACTOR = 0.4f;

    private HmdType hmdType = HmdType.NONE;
    private int scanCounter;
    private int outOfBoundsTicks;
    private int lockedEntityId = -1;
    /** 离轴警告剩余 tick（0=无警告，>0=红色闪烁中）。 */
    private int warningTicks;

    // tick 计数器供 overlay 动画使用
    private int tickCount;

    // HMD 扫描方向平滑角度
    private float smoothPitch;
    private float smoothYaw;
    private boolean smoothInitialized;

    // IR HMD 的 seeker 参数（由 checkIrHmd 更新）
    private float irSeekerFov = 0f;
    private float irSeekerRange = 0f;
    /** 导引头离轴角（度），大圈限制。 */
    private float irGuideHeadMaxAngle = 0f;
    /** 离地高度锁定过滤值。 */
    private float irLockMinHeight = 4f;
    /** 对地红外弹（lock_min_height < 0）。 */
    private boolean groundIr = false;

    private RVP_ClientHmdState() {
    }

    public static RVP_ClientHmdState getInstance() {
        return INSTANCE;
    }

    public boolean isHmdMode() {
        return hmdType != HmdType.NONE;
    }

    public HmdType getHmdType() {
        return hmdType;
    }

    public boolean isRadarHmd() {
        return hmdType == HmdType.RADAR;
    }

    public boolean isIrHmd() {
        return hmdType == HmdType.IR;
    }

    public int getTickCount() {
        return tickCount;
    }

    public boolean isWarning() {
        return warningTicks > 0;
    }

    public int getLockedEntityId() {
        return lockedEntityId;
    }

    /** RVP HMD 是否有锁（HMD 类型且锁定实体 ID 有效）。 */
    public boolean hasLock() {
        return hmdType == HmdType.IR && lockedEntityId != -1;
    }

    /** 从 HMD 锁 ID 获取锁定实体（客户端）。 */
    public Entity getLockedEntity() {
        if (lockedEntityId == -1) return null;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getEntity(lockedEntityId);
    }

    public float getSmoothPitch() {
        return smoothPitch;
    }

    public float getSmoothYaw() {
        return smoothYaw;
    }

    public float getIrSeekerFov() {
        return irSeekerFov;
    }

    public float getIrSeekerRange() {
        return irSeekerRange;
    }

    public float getIrGuideHeadMaxAngle() {
        return irGuideHeadMaxAngle;
    }

    public float getIrLockMinHeight() {
        return irLockMinHeight;
    }

    public boolean isGroundIr() {
        return groundIr;
    }

    /**
     * 切换雷达 HMD（格斗模式）。不用于 IR HMD。
     */
    public boolean toggle() {
        if (hmdType == HmdType.RADAR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            return false;
        }
        ywzj_rvp$ensureRadarOn();
        hmdType = HmdType.RADAR;
        scanCounter = 0;
        outOfBoundsTicks = 0;
        warningTicks = 0;
        smoothInitialized = false;
        return true;
    }

    private void ywzj_rvp$ensureRadarOn() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
    }

    /**
     * 强制退出任何 HMD 模式。
     */
    public void disable() {
        if (hmdType != HmdType.NONE) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
        }
    }

    /**
     * 检查当前武器是否为红外弹，并据此自动启用/禁用 IR HMD。
     * 在客户端 tick 中调用。
     */
    public void checkIrHmd() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            if (hmdType == HmdType.IR) {
                hmdType = HmdType.NONE;
            }
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            if (hmdType == HmdType.IR) {
                hmdType = HmdType.NONE;
            }
            return;
        }

        boolean shouldBeActive = false;
        float seekerFov = 0f;
        float seekerRange = 0f;
        float guideHeadMaxAngle = 0f;
        float lockMinHeight = 4f;

        java.util.Optional<AbstractVehicleWeapon<?>> weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isPresent() && weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon) {
            RVP_WeaponData data = rvpWeapon.getData();
            if (data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()) {
                if (weaponUnit.isSeekerOn()) {
                    shouldBeActive = true;
                    seekerFov = data.getMaxLockOnAngle();
                    seekerRange = data.getMaxLockOnRange();
                    guideHeadMaxAngle = data.getMaxGuideHeadAngle();
                    lockMinHeight = data.getLockMinHeight();
                }
            }
        }

        if (shouldBeActive && hmdType != HmdType.IR) {
            hmdType = HmdType.IR;
            scanCounter = 0;
            smoothInitialized = false;
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irLockMinHeight = lockMinHeight;
            groundIr = lockMinHeight < 0;
        } else if (!shouldBeActive && hmdType == HmdType.IR) {
            hmdType = HmdType.NONE;
            lockedEntityId = -1;
            warningTicks = 0;
            groundIr = false;
        } else if (hmdType == HmdType.IR) {
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irLockMinHeight = lockMinHeight;
            groundIr = lockMinHeight < 0;
        }
    }

    /**
     * 每客户端 tick 调用一次。处理 HMD 扫描和自动锁定。
     */
    public void tick() {
        tickCount++;
        if (hmdType == HmdType.NONE) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
            disable();
            return;
        }

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            disable();
            return;
        }

        // === 平滑瞄准角度（每帧更新，与框同步） ===
        float aimPitch = LocalVehiclePlayer.instance.cameraAimRotX - LocalVehiclePlayer.CAMERA_UPWARD_ANGLE;
        float aimYaw = LocalVehiclePlayer.instance.cameraAimRotY;
        if (!smoothInitialized) {
            smoothPitch = aimPitch;
            smoothYaw = aimYaw;
            smoothInitialized = true;
        }
        smoothPitch += (aimPitch - smoothPitch) * SMOOTH_FACTOR;
        smoothYaw += (aimYaw - smoothYaw) * SMOOTH_FACTOR;

        // IR HMD 模式：将 HMD 方向钳制在导引头离轴角内
        if (hmdType == HmdType.IR && irGuideHeadMaxAngle > 0f) {
            Vec3 weaponDir = weaponUnit.worldVec();
            Vec3 hmdDir = VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
            double currentAngle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, weaponDir.dot(hmdDir)))));
            if (currentAngle > irGuideHeadMaxAngle) {
                // 朝武器方向回拉
                double excess = currentAngle - irGuideHeadMaxAngle;
                float pull = (float) (1.0 - excess / currentAngle);
                smoothPitch = aimPitch + (smoothPitch - aimPitch) * pull;
                smoothYaw = aimYaw + (smoothYaw - aimYaw) * pull;
            }
        }

        // 扫描方向使用平滑角度
        Vec3 headLook = VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();

        if (hmdType == HmdType.RADAR) {
            tickRadarHmd(mc, vehicle, weaponUnit, headLook);
        } else if (hmdType == HmdType.IR) {
            tickIrHmd(mc, vehicle, weaponUnit, headLook);
        }
    }

    /**
     * 查找第一个 HMS 启用的雷达。没有则返回主雷达（向后兼容）。
     */
    private static RadarUnit findHmdRadar(WeaponUnit weaponUnit) {
        RadarUnit main = weaponUnit.getMainRadarUnit();
        for (RadarUnit r : weaponUnit.getRadarUnits()) {
            if (r == main) continue;
            if (isRadarHmsEnabled(r)) return r;
        }
        if (main != null && isRadarHmsEnabled(main)) return main;
        return main;
    }

    private static boolean isRadarHmsEnabled(RadarUnit radar) {
        Object data = ((PartUnitAccessorMixin) (Object) radar).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$isEnableHms();
        }
        return true;
    }

    /**
     * 雷达 HMD 扫描逻辑（原格斗模式）。
     */
    private void tickRadarHmd(Minecraft mc, AbstractVehicle vehicle,
                              WeaponUnit weaponUnit, Vec3 headLook) {
        RadarUnit radar = findHmdRadar(weaponUnit);
        if (radar == null) {
            disable();
            return;
        }

        // 观瞄模式下沿武器指向（屏幕中心）扫描，非观瞄沿头盔方向
        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        Vec3 scanDir = isScope ? weaponUnit.worldVec().normalize() : headLook;

        Vec3 radarPos = radar.worldRadarPosition();
        float maxRange = radar.getMaxScanDistance() * HMD_RANGE_MULTIPLIER;

        // 每 HMD_SCAN_INTERVAL tick 扫描一次
        if (++scanCounter < HMD_SCAN_INTERVAL) {
            return;
        }
        scanCounter = 0;

        // 离轴限制检查（观瞄模式用武器方向）
        if (!isWithinRadarLimits(radar, scanDir)) {
            outOfBoundsTicks++;
            warningTicks = Math.min(warningTicks + 1, OUT_OF_BOUNDS_TIMEOUT + 5);
            if (outOfBoundsTicks >= OUT_OF_BOUNDS_TIMEOUT) {
                mc.player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.out_of_bounds"), true);
                disable();
            }
            return;
        }
        outOfBoundsTicks = 0;
        warningTicks = 0;

        // 从雷达检测列表中按 5° + 距离过滤
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        for (RadarUnit.DetectedObject obj : radar.getDetectedEntities().values()) {
            Entity entity = obj.entity;
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(radarPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                continue;
            }
            Vec3 dir = toTarget.normalize();
            double angle = Math.toDegrees(Math.acos(scanDir.dot(dir)));
            if (angle > RADAR_HMD_HALF_FOV) {
                continue;
            }
            double score = angle * 0.7 + dist * 0.0003;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            radar.setLockedEntity(bestTarget);
            weaponUnit.setLockedEntity(bestTarget);
            lockedEntityId = bestTarget.getId();
            hmdType = HmdType.NONE;
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.locked"), true);
        }
    }

    /**
     * 红外弹 HMD 扫描逻辑。
     * 扫描只在 seeker FOV（小圈）内进行；锁定维持则在离轴角（大圈）内有效。
     */
    private void tickIrHmd(Minecraft mc, AbstractVehicle vehicle,
                           WeaponUnit weaponUnit, Vec3 headLook) {
        Vec3 seekerPos = weaponUnit.worldPivotPosition();
        float maxRange = irSeekerRange;
        float halfFov = irSeekerFov / 2f;

        // IR HMD 无离轴限制警告
        warningTicks = 0;
        outOfBoundsTicks = 0;

        // === 锁定维持：已锁定则持续刷锁，超出离轴角才丢锁 ===
        Entity alreadyLocked = weaponUnit.getLockedEntity();
        if (alreadyLocked != null && alreadyLocked.isAlive()) {
            Vec3 toTarget = alreadyLocked.getBoundingBox().getCenter().subtract(seekerPos);
            Vec3 dir = toTarget.normalize();
            Vec3 refDir = headLook; // 锁维持与扫描使用同一参考方向（HMD）
            double offBoresightAngle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, refDir.dot(dir)))));
            if (offBoresightAngle <= irGuideHeadMaxAngle) {
                // 在离轴角内 → 检查离地高度过滤
                if (!RVP_GuidanceMath.isTargetPassAltFilter(alreadyLocked, irLockMinHeight)) {
                    weaponUnit.setLockedEntity(null);
                    lockedEntityId = -1;
                    return;
                }
                // 维持锁定
                lockedEntityId = alreadyLocked.getId();
                weaponUnit.setLockedEntity(alreadyLocked);
                return;
            } else {
                // 超出离轴角 → 丢锁
                weaponUnit.setLockedEntity(null);
                lockedEntityId = -1;
            }
        }

        // 每 HMD_SCAN_INTERVAL tick 扫描一次
        if (++scanCounter < HMD_SCAN_INTERVAL) {
            return;
        }
        scanCounter = 0;

        // 扫描方向：观瞄用武器指向（屏幕中心），非观瞄用 HMD 方向
        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        Vec3 scanDir = isScope ? weaponUnit.worldVec() : headLook;
        // 扫描角用小圈 FOV
        float scanHalfAngle = halfFov;

        // 扫描：只在 FOV（小圈/屏幕中心）内搜索目标
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        var entities = mc.level.getEntities(
                vehicle,
                vehicle.getBoundingBox().inflate(maxRange),
                e -> e.isAlive() && e != mc.player && !(e instanceof AbstractVehicle v && v.isDestroyed())
        );

        for (Entity entity : entities) {
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(seekerPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) continue;
            Vec3 dir = toTarget.normalize();
            double angle = Math.toDegrees(Math.acos(
                    Math.max(-1.0, Math.min(1.0, scanDir.dot(dir)))));
            if (angle > scanHalfAngle) continue; // 不在扫描范围内
            if (!RVP_GuidanceMath.isTargetPassAltFilter(entity, irLockMinHeight)) continue; // 离地过滤
            double score = angle * 0.7 + dist * 0.0003;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            weaponUnit.setLockedEntity(bestTarget);
            lockedEntityId = bestTarget.getId();
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.ir_locked"), true);
        }
    }

    private static boolean isWithinRadarLimits(RadarUnit radar, Vec3 headLook) {
        Vec3 radarPos = radar.worldRadarPosition();
        Vec3 aimPoint = radarPos.add(headLook.scale(100));
        Vec2 localRot = radar.worldVecToLocalRot(aimPoint.subtract(radarPos));

        float yRot = (float) localRot.y;
        float yMin = radar.getYRotMin();
        float yMax = radar.getYRotMax();
        if (yMax - yMin < 360f && yMin >= 0f && yMax > 180f && yRot < 0f) {
            yRot += 360f;
        }
        if (yMax - yMin < 360f && (yRot < yMin || yRot > yMax)) {
            return false;
        }
        float xRot = (float) localRot.x;
        float radarXRot = radar.getXRot();
        float halfSector = radar.getScanSectorAngle() / 2f;
        if (Math.abs(xRot - radarXRot) > halfSector) {
            return false;
        }
        return true;
    }
}
