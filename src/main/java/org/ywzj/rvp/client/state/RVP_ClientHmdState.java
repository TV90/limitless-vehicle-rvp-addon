package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.debug.RVP_DebugStateLogs;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_IrHudProfile;
import org.ywzj.rvp.guidance.RVP_HmdTargetingMath;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.mixin.PartUnitAccessorMixin;
import org.ywzj.rvp.radar.RVP_RadarHmsMode;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.seeker.ElectroOptical;

public class RVP_ClientHmdState {

    /** 客户端唯一 HMD 状态实例。 */
    private static final RVP_ClientHmdState INSTANCE = new RVP_ClientHmdState();

    /** 雷达 HMD 捕获框、雷达扇区与捕获算法共用的单侧半视场角（度）。 */
    public static final float RADAR_HMD_HALF_FOV_DEG = 2.5f;
    /** 雷达 HMD 相对雷达最大探测距离的捕获距离倍率。 */
    private static final float HMD_RANGE_MULTIPLIER = 0.5f;
    /** 雷达 HMD 捕获扫描周期：2 Tick 可降低快速扫过已发现航迹时的漏检概率。目前 5 Tick 降低开销*/
    private static final int RADAR_HMD_SCAN_INTERVAL = 5;
    /** 雷达目标可见轮廓之外允许的轻微捕获容差（度），不扩大雷达发现范围。 */
    private static final double RADAR_HMD_OUTLINE_TOLERANCE_DEG = 0.75;
    /** IR HMD 捕获扫描周期：2 Tick 可避免快速掠过目标时被旧 5 Tick 周期漏检。 */
    private static final int IR_HMD_SCAN_INTERVAL = 2;
    /** 目标可见轮廓之外允许的轻微捕获容差（度），只影响头瞄捕获，不放宽发射离轴终检。 */
    private static final double IR_HMD_OUTLINE_TOLERANCE_DEG = 0.0;
    /** IR 目标短暂越出离轴范围后的保锁宽限，单位 Tick。 */
    private static final int IR_LOCK_GRACE_TICKS = 20;
    /** 雷达 HMD 连续越界扫描达到该次数后自动关闭。 */
    private static final int OUT_OF_BOUNDS_TIMEOUT = 20;
    /** HUD 头瞄方向每 Tick 的插值比例。 */
    private static final float SMOOTH_FACTOR = 0.4f;
    /** EO 头瞄最大捕获/保锁距离（格）：光电无探测表，视距内轮廓直接捕获，超过即脱锁。 */
    public static final float EO_HMD_MAX_RANGE = 512f;
    /** EO HMD 捕获扫描周期（Tick），对齐雷达 HMD。 */
    private static final int EO_HMD_SCAN_INTERVAL = 5;

    /** 雷达、IR 与 EO 三条互不排斥的 HMD 活动通道。 */
    private final RVP_HmdChannelState channels = new RVP_HmdChannelState();
    /** 雷达 HMD 独立扫描计数器。 */
    private int radarScanCounter;
    /** IR HMD 独立扫描计数器。 */
    private int irScanCounter;
    /** EO HMD 独立扫描计数器。 */
    private int eoScanCounter;
    /** 雷达 HMD 连续超出机械边界的扫描次数。 */
    private int radarOutOfBoundsTicks;
    /** IR HMD 当前确认锁定的实体 ID。 */
    private int irLockedEntityId = -1;
    /** EO 头瞄当前建立的锁定实体 ID（-1 无）：捕获即关通道，锁的维持由本状态独立检查。 */
    private int eoLockedEntityId = -1;
    /** 雷达 HMD 越界警告闪烁计数。 */
    private int radarWarningTicks;
    /** HMD 客户端累计 Tick，用于 IR 保锁宽限计时。 */
    private int tickCount;

    /** 未受 IR 离轴钳制的平滑头瞄俯仰，供雷达 HMD 使用。 */
    private float smoothPitch;
    /** 未受 IR 离轴钳制的平滑头瞄偏航，供雷达 HMD 使用。 */
    private float smoothYaw;
    /** IR 离轴钳制后的平滑头瞄俯仰，供 IR HUD 使用。 */
    private float irSmoothPitch;
    /** IR 离轴钳制后的平滑头瞄偏航，供 IR HUD 使用。 */
    private float irSmoothYaw;
    /** 共享头瞄平滑值是否已初始化。 */
    private boolean smoothInitialized;

    /** 当前 IR 弹发射前捕获完整视场角，单位度。 */
    private float irSeekerFov = 0f;
    /** 当前 IR 弹发射前最大锁定距离，单位格。 */
    private float irSeekerRange = 0f;
    /** 当前 IR 弹单侧最大离轴锁定角，单位度。 */
    private float irGuideHeadMaxAngle = 0f;
    /** IR 离轴基准是否叠加武器站当前旋转。 */
    private boolean irOffAxisStacksWithStationRotation = false;
    /** 旧数据链 IR 目标最低离地高度，单位格。 */
    private float irLockMinHeight = 4f;
    /** 当前 IR HUD 是否使用对地样式。 */
    private boolean groundIr = false;
    /** 当前 IR 武器是否使用 RVP 新版发射锁定数据。 */
    private boolean irUsesNewLaunchData;
    /** 当前 IR 武器数据，供锁定包线与 HUD 类型解析使用。 */
    private RVP_WeaponData irLaunchWeapon;
    /** 当前 IR 高度包线对应的 HUD 样式。 */
    private RVP_IrHudProfile irHudProfile = RVP_IrHudProfile.AIR;

    /** 最近一次 IR 确认锁定的实体 ID。 */
    private int irCachedLockedEntityId = -1;
    /** 最近一次 IR 目标通过离轴检查的客户端 Tick。 */
    private int irLastConfirmedLockTick = Integer.MIN_VALUE;
    /** 当前 IR 离轴宽限开始的客户端 Tick。 */
    private int irGraceStartTick = Integer.MIN_VALUE;
    /** 当前处于 IR 离轴宽限的目标实体 ID。 */
    private int irGraceTargetId = -1;

    private RVP_ClientHmdState() {}

    public static RVP_ClientHmdState getInstance() {
        return INSTANCE;
    }

    public boolean isHmdMode() {
        return channels.isAnyActive();
    }

    public boolean isRadarHmd() {
        return channels.isRadarActive();
    }

    public boolean isRadarOnlyAcm(RadarUnit radarUnit) {
        if (radarUnit == null) {
            return false;
        }
        Object data = ((PartUnitAccessorMixin) (Object) radarUnit).ywzj_rvp$getData();
        return data instanceof RadarUnitDataExt ext && ext.ywzj_rvp$isOnlyAcmHms();
    }

    public boolean isIrHmd() {
        return channels.isIrActive();
    }

    /** @return EO 头瞄捕获通道是否开启（捕获成功建立 EO 锁后通道自动关闭，锁独立维持）。 */
    public boolean isEoHmd() {
        return channels.isEoActive();
    }

    public int getTickCount() {
        return tickCount;
    }

    public boolean isWarning() {
        return radarWarningTicks > 0;
    }

    public int getLockedEntityId() {
        return irLockedEntityId;
    }

    public boolean hasLock() {
        return channels.isIrActive() && irLockedEntityId != -1;
    }

    public Entity getLockedEntity() {
        if (irLockedEntityId == -1) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return mc.level.getEntity(irLockedEntityId);
    }

    public boolean shouldKeepIrLockGrace(Entity target) {
        return channels.isIrActive()
                && target != null
                && target.isAlive()
                && target.getId() == irCachedLockedEntityId
                && tickCount - irLastConfirmedLockTick <= IR_LOCK_GRACE_TICKS;
    }

    public float getSmoothPitch() {
        return smoothPitch;
    }

    public float getSmoothYaw() {
        return smoothYaw;
    }

    /** @return 经过 IR 离轴限制后的平滑头瞄俯仰。 */
    public float getIrSmoothPitch() {
        return irSmoothPitch;
    }

    /** @return 经过 IR 离轴限制后的平滑头瞄偏航。 */
    public float getIrSmoothYaw() {
        return irSmoothYaw;
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

    /** @return 头瞄离轴角是否与武器站旋转叠加（离轴锥跟随武器站当前朝向）。 */
    public boolean isIrOffAxisStacksWithStationRotation() {
        return irOffAxisStacksWithStationRotation;
    }

    public float getIrLockMinHeight() {
        return irLockMinHeight;
    }

    public boolean isGroundIr() {
        return resolveIrHudProfile() == RVP_IrHudProfile.GROUND;
    }

    public boolean isMixedIr() {
        return resolveIrHudProfile() == RVP_IrHudProfile.MIXED;
    }

    public RadarUnit getRadarHmdUnit(WeaponUnit weaponUnit) {
        return findHmdRadar(weaponUnit);
    }

    public Vec3 resolveRadarAimDir(WeaponUnit weaponUnit, RadarUnit radarUnit) {
        if (weaponUnit == null) {
            return VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
        }
        boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        if (scope || isRadarOnlyAcm(radarUnit)) {
            Vec3 boresight = weaponUnit.worldVec();
            return boresight.lengthSqr() > 1.0E-6 ? boresight.normalize() : VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
        }
        return VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
    }

    public boolean toggleRadarHmd() {
        if (channels.isRadarActive()) {
            disableRadarHmd();
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || findHmdRadar(weaponUnit) == null) {
            return false;
        }
        ensureRadarOn();
        channels.enableRadar();
        radarScanCounter = 0;
        radarOutOfBoundsTicks = 0;
        radarWarningTicks = 0;
        // IR HMD 已经在工作时沿用同一头瞄平滑值，避免按 5 后两套框发生一次跳变。
        if (!channels.isIrActive()) {
            smoothInitialized = false;
        }
        return true;
    }

    private void ensureRadarOn() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (isRadarHmsEnabled(radarUnit) && !radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
    }

    /** 只关闭雷达 HMD 通道，IR HMD 与其锁定状态保持不变。 */
    public void disableRadarHmd() {
        channels.disableRadar();
        radarScanCounter = 0;
        radarOutOfBoundsTicks = 0;
        radarWarningTicks = 0;
    }

    /**
     * 切换光电（EO）头瞄通道（与雷达头瞄共用 5 键，雷达优先）：仅当前武器站为 EO 传感器
     * 且载具无任何雷达部件时可用。进入时清空当前武器站锁，避免 R 键旧锁（视距级距离）
     * 与 EO 头瞄 512 米捕获/脱锁语义不一致。
     */
    public boolean toggleEoHmd() {
        if (channels.isEoActive()) {
            disableEoHmd();
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null
                || weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.EO
                || !vehicleHasNoRadar(weaponUnit)) {
            return false;
        }
        channels.enableEo();
        eoScanCounter = 0;
        weaponUnit.setLockedEntity(null);
        eoLockedEntityId = -1;
        // IR HMD 已经在工作时沿用同一头瞄平滑值，避免按 5 后两套框发生一次跳变。
        if (!channels.isIrActive()) {
            smoothInitialized = false;
        }
        return true;
    }

    /** 只关闭 EO 头瞄捕获通道；已建立的 EO 锁由维持逻辑独立管理，不在此清除。 */
    public void disableEoHmd() {
        channels.disableEo();
        eoScanCounter = 0;
    }

    /**
     * 判断载具是否完全没有雷达部件（含武器站 sub_part 挂载的雷达）：
     * EO 头瞄仅限"EO 武器站且无雷达"的载具，有雷达载具按 5 仍走雷达头瞄。
     */
    private static boolean vehicleHasNoRadar(WeaponUnit weaponUnit) {
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null) {
            return false;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit) {
                return false;
            }
            if (partUnit instanceof WeaponUnit unit && !unit.getRadarUnits().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 离开有效载具上下文时关闭所有 HMD 通道。 */
    public void disableAll() {
        channels.disableAll();
        radarScanCounter = 0;
        irScanCounter = 0;
        eoScanCounter = 0;
        radarOutOfBoundsTicks = 0;
        radarWarningTicks = 0;
        irLockedEntityId = -1;
        eoLockedEntityId = -1;
        clearIrLockState();
    }

    public void checkIrHmd() {
        if (!LocalVehiclePlayer.instance.onVehicle()) {
            disableIrHmd();
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            disableIrHmd();
            return;
        }

        boolean shouldBeActive = false;
        float seekerFov = 0f;
        float seekerRange = 0f;
        float guideHeadMaxAngle = 0f;
        boolean stackWithStationRotation = false;
        float lockMinHeight = 4f;
        boolean usesNewLaunchData = false;
        RVP_IrHudProfile nextHudProfile = RVP_IrHudProfile.AIR;
        RVP_WeaponData nextLaunchWeapon = null;

        java.util.Optional<AbstractVehicleWeapon<?>> weaponOpt = weaponUnit.getCurrentWeapon();
        AbstractVehicleWeapon<?> currentWeapon = RVP_LaserWeapons.unwrap(weaponOpt.orElse(null));
        if (currentWeapon instanceof RVP_WeaponBase rvpWeapon) {
            RVP_WeaponData data = rvpWeapon.getData();
            if (data.isHomingProjectile()
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()
                    && data.isEnableIrHmd()
                    && weaponUnit.isSeekerOn()) {
                shouldBeActive = true;
                seekerFov = data.resolveLaunchSeekerFullFov();
                seekerRange = data.resolveLaunchLockRange();
                guideHeadMaxAngle = data.resolveLaunchOffAxisLockAngle();
                stackWithStationRotation = data.resolveLaunchOffAxisStacksWithStationRotation();
                usesNewLaunchData = true;
                nextLaunchWeapon = data;
                nextHudProfile = RVP_IrHudProfile.resolve(RVP_IrLockHelper.getLaunchAltitudeRange(data));
            }
        }

        if (shouldBeActive && !channels.isIrActive()) {
            channels.setIrActive(true);
            irScanCounter = 0;
            // 雷达 HMD 已开启时沿用当前头瞄平滑值，使两套框从同一方向开始。
            if (!channels.isRadarActive()) {
                smoothInitialized = false;
            }
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irOffAxisStacksWithStationRotation = stackWithStationRotation;
            irLockMinHeight = lockMinHeight;
            groundIr = nextHudProfile == RVP_IrHudProfile.GROUND;
            irUsesNewLaunchData = usesNewLaunchData;
            irLaunchWeapon = nextLaunchWeapon;
            irHudProfile = nextHudProfile;
        } else if (!shouldBeActive && channels.isIrActive()) {
            disableIrHmd();
        } else if (channels.isIrActive()) {
            irSeekerFov = seekerFov;
            irSeekerRange = seekerRange;
            irGuideHeadMaxAngle = guideHeadMaxAngle;
            irOffAxisStacksWithStationRotation = stackWithStationRotation;
            irLockMinHeight = lockMinHeight;
            groundIr = nextHudProfile == RVP_IrHudProfile.GROUND;
            irUsesNewLaunchData = usesNewLaunchData;
            irLaunchWeapon = nextLaunchWeapon;
            irHudProfile = nextHudProfile;
        }
    }

    /** 只关闭 IR HMD 通道，不影响按键控制的雷达 HMD。 */
    private void disableIrHmd() {
        channels.setIrActive(false);
        irScanCounter = 0;
        irLockedEntityId = -1;
        groundIr = false;
        irOffAxisStacksWithStationRotation = false;
        irUsesNewLaunchData = false;
        irLaunchWeapon = null;
        irHudProfile = RVP_IrHudProfile.AIR;
        clearIrLockState();
    }

    public void tick() {
        tickCount++;
        // EO 锁维持独立于 HMD 通道：捕获即关通道后，脱锁检查（距离/视场/遮挡）仍需每 Tick 执行。
        tickEoLockMaintenance();
        if (!channels.isAnyActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
            disableAll();
            return;
        }

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            disableAll();
            return;
        }

        float aimPitch = LocalVehiclePlayer.instance.cameraAimRotX - LocalVehiclePlayer.CAMERA_UPWARD_ANGLE;
        float aimYaw = LocalVehiclePlayer.instance.cameraAimRotY;
        if (!smoothInitialized) {
            smoothPitch = aimPitch;
            smoothYaw = aimYaw;
            smoothInitialized = true;
        }
        smoothPitch += (aimPitch - smoothPitch) * SMOOTH_FACTOR;
        smoothYaw += (aimYaw - smoothYaw) * SMOOTH_FACTOR;

        irSmoothPitch = smoothPitch;
        irSmoothYaw = smoothYaw;

        Vec3 rawHeadLook = VectorUtil.rotToVec(aimPitch, aimYaw).normalize();
        if (channels.isIrActive() && irGuideHeadMaxAngle > 0f) {
            Vec3 weaponDir = RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit, irOffAxisStacksWithStationRotation);
            Vec3 hmdDir = VectorUtil.rotToVec(irSmoothPitch, irSmoothYaw).normalize();
            if (weaponDir.lengthSqr() > 1.0E-6) {
                double currentAngle = angleBetweenDeg(weaponDir, hmdDir);
                if (currentAngle > irGuideHeadMaxAngle) {
                    Vec3 limitedDir = clampDirectionToCone(weaponDir, hmdDir, irGuideHeadMaxAngle);
                    Vec2 limitedRot = VectorUtil.vecToRot(limitedDir);
                    irSmoothPitch = limitedRot.x;
                    irSmoothYaw = limitedRot.y;
                }
            }
        }

        if (channels.isRadarActive()) {
            // 雷达捕获与 IR HMD 一样使用本 Tick 原始头瞄方向；HUD 和雷达扫描线仍读取平滑方向。
            tickRadarHmd(mc, weaponUnit, rawHeadLook);
        }
        if (channels.isIrActive()) {
            // 捕获使用本 Tick 原始头瞄方向，避免 HUD 平滑方向滞后造成快速扫过目标时漏锁；
            // HUD 仍读取 smoothPitch/smoothYaw，故显示动画和平滑手感保持不变。
            tickIrHmd(mc, vehicle, weaponUnit, clampIrScanDirection(weaponUnit, rawHeadLook));
        }
        if (channels.isEoActive()) {
            // EO 捕获同雷达 HMD 语义：使用本 Tick 原始头瞄方向（观瞄为武器轴线）。
            tickEoHmd(mc, vehicle, weaponUnit, rawHeadLook);
        }
    }

    /**
     * EO 头瞄捕获（无雷达探测表）：遍历客户端可见实体做轮廓捕获，复用雷达头瞄的视场、
     * 容差与评分；候选额外过 EO 视线检查（{@link ElectroOptical#checkTarget}，不透光方块/
     * 烟幕类视觉遮挡不可捕获）。命中即写武器站锁（内建客户端→服务端同步，服务端发射授权与
     * HOMING 制导直接可用）并关闭通道——捕获即关，同雷达头瞄语义。
     */
    private void tickEoHmd(Minecraft mc, AbstractVehicle vehicle, WeaponUnit weaponUnit, Vec3 rawHeadLook) {
        if (++eoScanCounter < EO_HMD_SCAN_INTERVAL) {
            return;
        }
        eoScanCounter = 0;

        Vec3 pivot = weaponUnit.worldPivotPosition();
        Vec3 scanDir = resolveEoScanDir(weaponUnit, rawHeadLook);

        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        boolean bestIsVehicle = false;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == vehicle || !entity.isAlive() || entity == mc.player
                    || (entity instanceof AbstractVehicle v && v.isDestroyed())
                    // 排除乘员/炮手等坐在载具内的实体：应锁定载具本体而非车内小人（同 IR HMD 口径）。
                    || entity.getVehicle() != null) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(pivot);
            double dist = toTarget.length();
            if (dist > EO_HMD_MAX_RANGE || dist < 1.0) {
                continue;
            }
            // 与雷达头瞄同款轮廓捕获：目标包围盒进入捕获框即可捕获，框外仅固定小容差。
            double effectiveAngle = RVP_HmdTargetingMath.effectiveAngularMissDeg(
                    pivot, scanDir, entity.getBoundingBox());
            if (effectiveAngle > RADAR_HMD_HALF_FOV_DEG + RADAR_HMD_OUTLINE_TOLERANCE_DEG) {
                continue;
            }
            // 光电物理：视线被不透光方块或视觉遮挡物挡住时不可捕获（角度过滤后再查，控制射线开销）。
            if (ElectroOptical.checkTarget(weaponUnit, entity) == null) {
                continue;
            }
            double score = effectiveAngle * 0.7 + dist * 0.0003;
            boolean candidateIsVehicle = entity instanceof AbstractVehicle;
            // 同一捕获区域内优先载具（同 IR HMD 口径），避免生物/弹体抢走载具目标。
            if (bestTarget == null
                    || candidateIsVehicle && !bestIsVehicle
                    || candidateIsVehicle == bestIsVehicle && score < bestScore) {
                bestScore = score;
                bestIsVehicle = candidateIsVehicle;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            weaponUnit.setLockedEntity(bestTarget);
            eoLockedEntityId = bestTarget.getId();
            disableEoHmd();
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.eo_locked"), true);
        }
    }

    /**
     * EO 锁维持（每 Tick、独立于 HMD 通道）：当前武器站锁不再是 EO 头瞄锁（玩家 R 键另锁
     * 其它目标）时让位不干预；目标死亡 / 超出 512 米 / 离开头瞄视场 / 视线被遮挡时清锁并提示
     * 脱锁。脱锁后不自动重开捕获通道，需再按 5（同雷达头瞄捕获即关语义）。
     */
    private void tickEoLockMaintenance() {
        if (eoLockedEntityId == -1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle)) {
            eoLockedEntityId = -1;
            return;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            eoLockedEntityId = -1;
            return;
        }
        Entity unitLocked = weaponUnit.getLockedEntity();
        if (unitLocked == null || unitLocked.getId() != eoLockedEntityId) {
            eoLockedEntityId = -1;
            return;
        }
        Entity target = mc.level == null ? null : mc.level.getEntity(eoLockedEntityId);
        if (target == null || !target.isAlive()) {
            clearEoLock(weaponUnit);
            return;
        }
        Vec3 pivot = weaponUnit.worldPivotPosition();
        double dist = target.getBoundingBox().getCenter().subtract(pivot).length();
        // 脱锁条件只看 距离 + 视线遮挡，不做视场角门：火控（如 rvp_ballistic_lead）带动炮塔
        // 指向提前点时天然偏离目标中心（离轴角内最多 10°），视场门会把有效 EO 锁误清，
        // 造成"提前量圈消失、炮塔回摆"的死循环（2026-09-24 实测修复）。
        if (dist > EO_HMD_MAX_RANGE || dist < 1.0
                || ElectroOptical.checkTarget(weaponUnit, target) == null) {
            clearEoLock(weaponUnit);
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.eo_unlocked"), true);
        }
    }

    /** 清除 EO 头瞄建立的武器站锁（仅在锁仍为 EO 目标时写入，避免覆盖其它来源的新锁）。 */
    private void clearEoLock(WeaponUnit weaponUnit) {
        Entity unitLocked = weaponUnit.getLockedEntity();
        if (unitLocked != null && unitLocked.getId() == eoLockedEntityId) {
            weaponUnit.setLockedEntity(null);
        }
        eoLockedEntityId = -1;
    }

    /**
     * 解析 EO 头瞄的实际捕获/维持方向：观瞄模式使用武器轴线（屏幕中心语义），
     * 非观瞄使用调用方给定的方向（捕获=原始头瞄，维持=相机视线）。
     */
    private Vec3 resolveEoScanDir(WeaponUnit weaponUnit, Vec3 fallbackDir) {
        boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        if (scope) {
            Vec3 boresight = weaponUnit.worldVec();
            if (boresight.lengthSqr() > 1.0E-6) {
                return boresight.normalize();
            }
        }
        return fallbackDir.lengthSqr() > 1.0E-6
                ? fallbackDir.normalize()
                : VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
    }

    private static RadarUnit findHmdRadar(WeaponUnit weaponUnit) {
        RadarUnit main = weaponUnit.getMainRadarUnit();
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit != main && isRadarHmsEnabled(radarUnit)) {
                return radarUnit;
            }
        }
        if (main != null && isRadarHmsEnabled(main)) {
            return main;
        }
        return null;
    }

    private static boolean isRadarHmsEnabled(RadarUnit radar) {
        Object data = ((PartUnitAccessorMixin) (Object) radar).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            return ext.ywzj_rvp$isEnableHms();
        }
        return true;
    }

    private void tickRadarHmd(Minecraft mc, WeaponUnit weaponUnit, Vec3 rawHeadLook) {
        RadarUnit radar = findHmdRadar(weaponUnit);
        if (radar == null) {
            disableRadarHmd();
            return;
        }

        Vec3 scanDir = resolveRadarScanDir(weaponUnit, radar, rawHeadLook);
        Vec3 radarPos = radar.worldRadarPosition();
        float maxRange = radar.getMaxScanDistance() * HMD_RANGE_MULTIPLIER;

        if (++radarScanCounter < RADAR_HMD_SCAN_INTERVAL) {
            return;
        }
        radarScanCounter = 0;

        if (!isWithinRadarLimits(radar, scanDir)) {
            radarOutOfBoundsTicks++;
            radarWarningTicks = Math.min(radarWarningTicks + 1, OUT_OF_BOUNDS_TIMEOUT + 5);
            if (radarOutOfBoundsTicks >= OUT_OF_BOUNDS_TIMEOUT) {
                mc.player.displayClientMessage(
                        Component.translatable("message.ywzj_rvp.hmd.out_of_bounds"), true);
                disableRadarHmd();
            }
            return;
        }
        radarOutOfBoundsTicks = 0;
        radarWarningTicks = 0;

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
            // 目标中心仍须位于雷达机械扫描范围内，防止轮廓容差越过方位/俯仰边界误锁。
            if (!isWithinRadarLimits(radar, dir)) {
                continue;
            }
            // 当前选择 IR HMD 弹时，雷达头瞄只宣布可由红外导引头接收的组合锁定；
            // 目的：保证雷达落锁后 IR HMD 接收同一目标，同时不绕过 IR 距离、高度、LOS 与离轴门槛。
            if (channels.isIrActive() && !canAcceptRadarCueForIr(weaponUnit, entity)) {
                continue;
            }
            // 调用共用头瞄轮廓算法：目标包围盒进入捕获框即可捕获，框外仅增加固定小容差。
            double effectiveAngle = RVP_HmdTargetingMath.effectiveAngularMissDeg(
                    radarPos, scanDir, entity.getBoundingBox());
            if (effectiveAngle > RADAR_HMD_HALF_FOV_DEG + RADAR_HMD_OUTLINE_TOLERANCE_DEG) {
                continue;
            }
            double score = effectiveAngle * 0.7 + dist * 0.0003;
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            radar.setLockedEntity(bestTarget);
            weaponUnit.setLockedEntity(bestTarget);
            if (channels.isIrActive()) {
                Vec3 irBoresight = RVP_IrLockHelper.resolveIrBoresightDir(
                        weaponUnit, irOffAxisStacksWithStationRotation);
                Vec3 targetDir = bestTarget.getBoundingBox().getCenter()
                        .subtract(weaponUnit.worldPivotPosition());
                // 调用本项目 IR 锁定确认入口，把雷达 HMD 捕获到的同一实体显式交给 IR 通道。
                confirmIrLock(weaponUnit, bestTarget, angleBetweenDeg(irBoresight, targetDir), "radar-cue");
            }
            disableRadarHmd();
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.locked"), true);
        }
    }

    /**
     * 检查雷达 HMD 候选能否作为当前 IR HMD 的同目标指示。
     * 雷达只负责提供航迹，IR 仍执行自己的距离、高度、视线与离轴物理门槛。
     */
    private boolean canAcceptRadarCueForIr(WeaponUnit weaponUnit, Entity target) {
        if (!channels.isIrActive() || target == null || !target.isAlive()) {
            return false;
        }
        if (irUsesNewLaunchData && irLaunchWeapon != null) {
            // 调用本项目 IR 持续包线与发射离轴检查，避免雷达指示绕过红外弹现有授权边界。
            return RVP_IrLockHelper.isTargetWithinHoldEnvelope(weaponUnit, target, irLaunchWeapon)
                    && RVP_IrLockHelper.isLaunchTargetWithinOffAxis(
                    weaponUnit, target, irLaunchWeapon, 0f);
        }
        return RVP_IrLockHelper.isTargetWithinLimits(
                weaponUnit,
                target,
                RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit, irOffAxisStacksWithStationRotation),
                Math.max(1f, irGuideHeadMaxAngle),
                Math.max(0f, irSeekerRange),
                irLockMinHeight
        );
    }

    private void tickIrHmd(Minecraft mc, AbstractVehicle vehicle, WeaponUnit weaponUnit, Vec3 headLook) {
        Vec3 seekerPos = weaponUnit.worldPivotPosition();
        float maxRange = irSeekerRange;
        float halfFov = irSeekerFov / 2f;

        Entity tracked = resolveTrackedIrTarget(mc, weaponUnit);
        if (tracked != null) {
            if (irUsesNewLaunchData && irLaunchWeapon != null
                    && !RVP_IrLockHelper.isTargetWithinHoldEnvelope(weaponUnit, tracked, irLaunchWeapon)) {
                RVP_DebugStateLogs.logIrHms("drop new-launch-envelope target=" + tracked.getId());
                clearIrLockState(weaponUnit);
                return;
            }
            Vec3 toTarget = tracked.getBoundingBox().getCenter().subtract(seekerPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                RVP_DebugStateLogs.logIrHms("drop range target=" + tracked.getId() + " dist=" + formatAngle(dist));
                clearIrLockState(weaponUnit);
                return;
            }
            Vec3 dir = toTarget.normalize();
            Vec3 refDir = RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit, irOffAxisStacksWithStationRotation);
            double offBoresightAngle = angleBetweenDeg(refDir, dir);
            if (!irUsesNewLaunchData && !RVP_IrLockHelper.isTargetWithinLimits(
                    weaponUnit,
                    tracked,
                    refDir,
                    180f,
                    maxRange,
                    irLockMinHeight
            )) {
                RVP_DebugStateLogs.logIrHms("drop alt-filter target=" + tracked.getId());
                clearIrLockState(weaponUnit);
                return;
            }
            if (offBoresightAngle <= irGuideHeadMaxAngle) {
                confirmIrLock(weaponUnit, tracked, offBoresightAngle, "hold");
                return;
            }
            if (shouldKeepIrLockGrace(tracked)) {
                if (weaponUnit.getLockedEntity() == null || weaponUnit.getLockedEntity().getId() != tracked.getId()) {
                    weaponUnit.setLockedEntity(tracked);
                }
                irLockedEntityId = tracked.getId();
                if (irGraceTargetId != tracked.getId()) {
                    irGraceTargetId = tracked.getId();
                    irGraceStartTick = tickCount;
                    RVP_DebugStateLogs.logIrHms("grace-start target=" + tracked.getId()
                            + " angle=" + formatAngle(offBoresightAngle)
                            + " expireIn=" + (IR_LOCK_GRACE_TICKS - (tickCount - irLastConfirmedLockTick)));
                }
                return;
            }
            RVP_DebugStateLogs.logIrHms("grace-expire target=" + tracked.getId()
                    + " angle=" + formatAngle(offBoresightAngle)
                    + " lostFor=" + (tickCount - irLastConfirmedLockTick));
            clearIrLockState(weaponUnit);
        }

        if (++irScanCounter < IR_HMD_SCAN_INTERVAL) {
            return;
        }
        irScanCounter = 0;

        boolean isScope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        Vec3 scanDir = isScope ? weaponUnit.worldVec() : headLook;
        float scanHalfAngle = halfFov;

        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        double bestEffectiveAngle = Double.MAX_VALUE;
        boolean bestIsVehicle = false;
        // O(实体) 遍历已加载实体，替代 ±maxRange（雷达扫描距离可达数千格）立方体 getEntities
        // （客户端 HMD IR 扫描掉帧）；maxRange 距离闸门保留在下方循环内
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == vehicle || !entity.isAlive() || entity == mc.player
                    || (entity instanceof AbstractVehicle v && v.isDestroyed())
                    // 排除乘员/炮手等坐在载具内的实体：导引头应锁定载具本体而非车内小人，
                    // 否则导弹 targetEntity 指向乘员（如 GunnerEntity），gunner 反制判定
                    // （要求 targetEntity==载具本体）永不成立，导致不抛烟/不规避。
                    || entity.getVehicle() != null) {
                continue;
            }
            // 调用本项目 IR 发射终检几何，保持距离、高度、LOS 与离轴中心点门槛不变；
            // 捕获锥角单独按下方目标可见轮廓计算，避免中心点门槛抵消轮廓辅助。
            if (irUsesNewLaunchData && irLaunchWeapon != null
                    && !RVP_IrLockHelper.isLaunchTargetWithinOffAxis(
                    weaponUnit, entity, irLaunchWeapon, 0f)) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(seekerPos);
            double dist = toTarget.length();
            if (dist > maxRange || dist < 1.0) {
                continue;
            }
            // 调用本项目头瞄轮廓角算法：准线命中目标包围盒外接轮廓时按 0° 计，
            // 轮廓外仅再给予固定 IR_HMD_OUTLINE_TOLERANCE_DEG° 容差，避免简单扩大整片空域吸附范围。
            double effectiveAngle = RVP_HmdTargetingMath.effectiveAngularMissDeg(
                    seekerPos, scanDir, entity.getBoundingBox());
            if (effectiveAngle > scanHalfAngle + IR_HMD_OUTLINE_TOLERANCE_DEG) {
                continue;
            }
            double score = effectiveAngle * 0.7 + dist * 0.0003;
            boolean candidateIsVehicle = entity instanceof AbstractVehicle;
            // 同一捕获区域内优先载具，避免提高灵敏度后普通生物或弹体抢走载具目标；
            // 同类别仍按轮廓角距离优先、距离次优的原权重排序。
            if (bestTarget == null
                    || candidateIsVehicle && !bestIsVehicle
                    || candidateIsVehicle == bestIsVehicle && score < bestScore) {
                bestScore = score;
                bestEffectiveAngle = effectiveAngle;
                bestIsVehicle = candidateIsVehicle;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            confirmIrLock(weaponUnit, bestTarget, bestEffectiveAngle, "acquire");
            mc.player.displayClientMessage(
                    Component.translatable("message.ywzj_rvp.hmd.ir_locked"), true);
        } else if (irGraceTargetId != -1 && tickCount - irLastConfirmedLockTick > IR_LOCK_GRACE_TICKS) {
            irGraceTargetId = -1;
            irGraceStartTick = Integer.MIN_VALUE;
        }
    }

    /**
     * 把原始头瞄扫描方向钳制在 IR 机械离轴锥内；只供捕获扫描使用，HUD 仍使用平滑方向。
     */
    private Vec3 clampIrScanDirection(WeaponUnit weaponUnit, Vec3 rawHeadLook) {
        Vec3 weaponDir = RVP_IrLockHelper.resolveIrBoresightDir(
                weaponUnit, irOffAxisStacksWithStationRotation);
        if (weaponDir.lengthSqr() <= 1.0E-6 || rawHeadLook.lengthSqr() <= 1.0E-6
                || irGuideHeadMaxAngle <= 0f) {
            return rawHeadLook;
        }
        return angleBetweenDeg(weaponDir, rawHeadLook) > irGuideHeadMaxAngle
                ? clampDirectionToCone(weaponDir, rawHeadLook, irGuideHeadMaxAngle)
                : rawHeadLook;
    }

    /**
     * 解析雷达 HMD 的实际捕获方向：普通视角使用原始头瞄，观瞄/仅 ACM 模式保持武器轴线语义。
     */
    private Vec3 resolveRadarScanDir(WeaponUnit weaponUnit, RadarUnit radarUnit, Vec3 rawHeadLook) {
        boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        if (scope || isRadarOnlyAcm(radarUnit)) {
            Vec3 boresight = weaponUnit.worldVec();
            return boresight.lengthSqr() > 1.0E-6 ? boresight.normalize() : rawHeadLook;
        }
        return rawHeadLook.lengthSqr() > 1.0E-6
                ? rawHeadLook.normalize()
                : VectorUtil.rotToVec(smoothPitch, smoothYaw).normalize();
    }

    private Entity resolveTrackedIrTarget(Minecraft mc, WeaponUnit weaponUnit) {
        Entity locked = weaponUnit.getLockedEntity();
        if (locked != null && locked.isAlive()) {
            return locked;
        }
        if (irCachedLockedEntityId == -1 || mc.level == null) {
            return null;
        }
        Entity cached = mc.level.getEntity(irCachedLockedEntityId);
        return cached != null && cached.isAlive() ? cached : null;
    }

    private void confirmIrLock(WeaponUnit weaponUnit, Entity target, double angle, String reason) {
        if (weaponUnit.getLockedEntity() == null || weaponUnit.getLockedEntity().getId() != target.getId()) {
            weaponUnit.setLockedEntity(target);
        }
        irLockedEntityId = target.getId();
        irCachedLockedEntityId = target.getId();
        irLastConfirmedLockTick = tickCount;
        if (irGraceTargetId == target.getId()) {
            RVP_DebugStateLogs.logIrHms("grace-recapture target=" + target.getId()
                    + " angle=" + formatAngle(angle)
                    + " heldFor=" + (tickCount - irGraceStartTick));
        } else {
            RVP_DebugStateLogs.logIrHms(reason + " target=" + target.getId()
                    + " angle=" + formatAngle(angle));
        }
        irGraceTargetId = -1;
        irGraceStartTick = Integer.MIN_VALUE;
    }

    private void clearIrLockState() {
        irCachedLockedEntityId = -1;
        irLastConfirmedLockTick = Integer.MIN_VALUE;
        irGraceTargetId = -1;
        irGraceStartTick = Integer.MIN_VALUE;
    }

    private void clearIrLockState(WeaponUnit weaponUnit) {
        Entity unitLocked = weaponUnit.getLockedEntity();
        if (unitLocked != null
                && unitLocked.getId() == irLockedEntityId
                && !isHeldByRadar(weaponUnit, unitLocked)) {
            weaponUnit.setLockedEntity(null);
        }
        irLockedEntityId = -1;
        clearIrLockState();
    }

    /**
     * 判断共享武器站目标是否仍由任一雷达硬锁持有。
     * IR 失锁时保留这种目标，避免一个 HMD 通道反向清除另一个通道的有效锁定。
     */
    private static boolean isHeldByRadar(WeaponUnit weaponUnit, Entity target) {
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            Entity radarLocked = radarUnit.getLockedEntity();
            if (radarLocked != null && radarLocked.getId() == target.getId()) {
                return true;
            }
        }
        return false;
    }

    private RVP_IrHudProfile resolveIrHudProfile() {
        if (irHudProfile != RVP_IrHudProfile.MIXED || irLaunchWeapon == null) {
            return irHudProfile;
        }
        Entity locked = getLockedEntity();
        return locked != null && locked.isAlive()
                ? RVP_IrHudProfile.resolveForTarget(
                RVP_IrLockHelper.getLaunchAltitudeRange(irLaunchWeapon), locked)
                : RVP_IrHudProfile.MIXED;
    }

    private static double angleBetweenDeg(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-6 || b.lengthSqr() <= 1.0E-6) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(Mth.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0)));
    }

    private static Vec3 clampDirectionToCone(Vec3 baseDir, Vec3 targetDir, float maxAngleDeg) {
        if (baseDir.lengthSqr() <= 1.0E-6) {
            return targetDir.lengthSqr() <= 1.0E-6 ? Vec3.ZERO : targetDir.normalize();
        }
        Vec3 base = baseDir.normalize();
        if (targetDir.lengthSqr() <= 1.0E-6) {
            return base;
        }
        Vec3 target = targetDir.normalize();
        double currentAngle = angleBetweenDeg(base, target);
        if (currentAngle <= maxAngleDeg) {
            return target;
        }
        Vec3 lateral = target.subtract(base.scale(base.dot(target)));
        if (lateral.lengthSqr() <= 1.0E-6) {
            return base;
        }
        Vec3 tangent = lateral.normalize();
        double maxAngleRad = Math.toRadians(maxAngleDeg);
        return base.scale(Math.cos(maxAngleRad)).add(tangent.scale(Math.sin(maxAngleRad))).normalize();
    }

    private static String formatAngle(double angle) {
        return String.format(java.util.Locale.ROOT, "%.2f", angle);
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
        return Math.abs(xRot - radarXRot) <= halfSector;
    }
}
