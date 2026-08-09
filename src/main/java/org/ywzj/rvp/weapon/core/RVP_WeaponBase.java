package org.ywzj.rvp.weapon.core;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.LauncherDeployRuntimeManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared runtime base for the seven public RVP weapon types.
 */
public abstract class RVP_WeaponBase extends AbstractVehicleWeapon<RVP_WeaponData> {

    protected int chargeTick;
    private final RVP_WeaponHeatManager.HeatState localHeatState = new RVP_WeaponHeatManager.HeatState();
    private final RVP_WeaponFireController fireController = new RVP_WeaponFireController(this);

    protected RVP_WeaponBase(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, weaponUnit, index, data, serializeId);
    }

    public RVP_WeaponFireController getFireController() {
        return fireController;
    }

    /**
     * 服务端射击调试追踪（替代被删 {@code WeaponUnitShootDebugMixin}）。
     * 各具体武器在 {@code shoot()} 入口调用一次，记录本次射击请求的上下文。
     */
    protected void noteServerShootInvocation(List<AimContext> aimContexts, LivingEntity shooter) {
        RVP_WeaponOriginDebug.noteShootInvocation(getWeaponUnit(), getIndex(), this, aimContexts, shooter);
    }

    RVP_WeaponHeatManager.HeatState getLocalHeatState() {
        return localHeatState;
    }

    protected int getChargeTick() {
        return chargeTick;
    }

    protected void setChargeTick(int chargeTick) {
        this.chargeTick = Math.max(chargeTick, 0);
    }

    /**
     * 取消当前装填倒计时。
     * 供载具生成时“瞬间补满弹药”使用：直接清零 reloadTime，避免残留装填状态。
     */
    public void ywzj_rvp$clearReloadState() {
        setReloadTime(0);
    }

    /**
     * 设置装填倒计时（tick）。
     * 替代被删 {@code GunnerWeaponAccessorMixin} 的 setReloadTime invoker：炮手 AI
     * 对 RVP 武器直接调用本方法，本体武器仍由 {@code GunnerBrain} 反射兜底。
     */
    public void ywzj_rvp$setReloadTime(int reloadTime) {
        setReloadTime(Math.max(reloadTime, 0));
    }

    public int getChargeTickValue() {
        return chargeTick;
    }

    public SoundEvent getChargeSound() {
        Optional<BaseDisplay> displayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(getData().getWeaponId());
        return displayOptional.map(display -> display.getSoundEvents().get("charge")).orElse(null);
    }

    @OnlyIn(Dist.CLIENT)
    public void queueProgrammaticShot() {
        fireController.queueProgrammaticShot();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean doClientShoot() {
        if (getVehicle().level().isClientSide()) {
            fireController.syncClientInput();
            if (!fireController.shouldAttemptClientShot()) {
                return false;
            }
        }
        if (!passesFireModeChargeGate()) {
            return false;
        }
        if (!passesOffAxisShootGate()) {
            return false;
        }
        RVP_WeaponData data = getData();
        WeaponUnit unit = getWeaponUnit().getRootParentWeaponUnit();
        if (data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                && data.isAntiRadiationMissile()
                && data.isRequireLock()
                && !hasArmPreselectedTarget(unit)) {
            LocalVehiclePlayer.instance.sendMessage("ui.need_lock_entity");
            return false;
        }
        boolean isIrLaunchWeapon = RVP_IrLockHelper.isIrLaunchWeapon(data);
        if (requiresEntityLock(data)) {
            boolean isIrHmdManaged = data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()
                    && data.isEnableIrHmd();

            Entity externalLocked = null;
            int externalLockedId = Integer.MIN_VALUE;
            if (!isIrHmdManaged
                    && RVP_WeaponSensorHelper.effectiveSensorType(unit) == WeaponUnitData.FireControlSensorType.RF
                    && net.minecraft.client.Minecraft.getInstance().level != null) {
                externalLockedId = RVP_ExternalRadarLinkHelper.getClientLockedEntityId(
                        unit.getVehicle(),
                        net.minecraft.client.Minecraft.getInstance().level.dimension().location()
                );
                externalLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(
                        unit.getVehicle(),
                        net.minecraft.client.Minecraft.getInstance().level.dimension().location()
                );
            }

            Entity validatedIrLock = isIrHmdManaged
                    ? RVP_ClientHmdState.getInstance().getLockedEntity()
                    : (isIrLaunchWeapon
                    ? RVP_IrLockHelper.resolveUsableIrLaunchTarget(unit, unit.getLockedEntity(), externalLocked, data)
                    : null);

            boolean hasLock = isIrHmdManaged
                    ? RVP_ClientHmdState.getInstance().hasLock()
                    : (isIrLaunchWeapon
                    ? validatedIrLock != null
                    : unit.getLockedEntity() != null || externalLocked != null || externalLockedId != Integer.MIN_VALUE);

            if (!hasLock) {
                boolean eoExempt = !isIrHmdManaged
                        && !isIrLaunchWeapon
                        && RVP_WeaponSensorHelper.effectiveSensorType(unit) == WeaponUnitData.FireControlSensorType.EO;
                if (!eoExempt) {
                    LocalVehiclePlayer.instance.sendMessage("ui.need_lock_entity");
                    return false;
                }
            }

            if (hasLock) {
                Entity hmdEntity = RVP_ClientHmdState.getInstance().getLockedEntity();
                if (isIrHmdManaged && hmdEntity != null) {
                    unit.setLockedEntity(hmdEntity);
                } else if (isIrLaunchWeapon && validatedIrLock != null) {
                    unit.setLockedEntity(validatedIrLock);
                } else if (externalLocked != null && unit.getLockedEntity() == null) {
                    unit.setLockedEntity(externalLocked);
                }
            }
        }
        boolean fired = super.doClientShoot();
        return fired;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        // 客户端实体上 PartUnit.ownerId 不同步（仅服务端在乘客变更事件中设置），
        // 此处 owner 检查在客户端恒为 false，会拦截服务器回包（VehicleFireEvent.Post）
        // 的加热回调，导致热系统完全不工作。移除检查：热状态仅为本端模拟，无跨端副作用。
        // 单机（integrated server）时，服务端武器与本端武器是同一对象、共享同一热状态：
        // 服务端 shoot() 已计热一次，回包路径再计热会导致每发双倍（2x）。
        // 仅在连接独立服务器/局域网时由回包计热，驱动客户端 HUD 显示。
        boolean singlePlayer = net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer();
        if (!singlePlayer) {
            fireController.onShotFired();
        }
        super.onClientFire();
    }

    protected boolean passesFireModeChargeGate() {
        RVP_EnumFireMode mode = getData().getFireData().getFireMode();
        if (mode == RVP_EnumFireMode.FULL_AUTO || mode == RVP_EnumFireMode.SEMI_AUTO) {
            return true;
        }
        if (mode == RVP_EnumFireMode.BURST) {
            return fireController.canStartBurstRound(getData().getFireData());
        }
        return fireController.canShootNow();
    }

    protected boolean canShootOnServer() {
        return canShootOnServer(null);
    }

    /**
     * 服务端射击最终门控：发热 / 离轴 + 发射架部署门控。
     *
     * <p>发射架门控替代被删 {@code WeaponUnitLauncherDeployGateMixin}：按
     * {@link LauncherDeployRuntimeManager} 快照判定展开状态与车速，不满足时拒绝射击
     * 并向操作者提示（{@code operator} 为空时回退到载具乘客中的玩家）。</p>
     */
    protected boolean canShootOnServer(@Nullable LivingEntity operator) {
        if (!fireController.canShootNowAfterPrime() || !passesOffAxisShootGate()) {
            return false;
        }
        return passesLauncherDeployGate(operator);
    }

    private boolean passesLauncherDeployGate(@Nullable LivingEntity operator) {
        AbstractVehicle vehicle = getVehicle();
        if (vehicle == null || vehicle.level().isClientSide()) {
            return true;
        }
        RVP_LauncherDeployConfig config = findLauncherDeployConfig();
        if (config == null) {
            return true;
        }
        LauncherDeployRuntimeManager.Snapshot snapshot = LauncherDeployRuntimeManager.get(vehicle.getId(), config.id());
        LauncherDeployRuntimeManager.State state = snapshot == null
                ? LauncherDeployRuntimeManager.State.CLOSED
                : snapshot.state();
        double speedKph = snapshot == null
                ? vehicle.getDeltaMovement().length() * 20.0 * 3.6
                : snapshot.speedKph();

        if (state == LauncherDeployRuntimeManager.State.CLOSED && config.blockFireWhenClosed()) {
            denyLauncherDeployFire(operator, "发射架未展开");
            return false;
        }
        if (state == LauncherDeployRuntimeManager.State.DEPLOYING && config.blockFireWhenDeploying()) {
            denyLauncherDeployFire(operator, "发射架展开中");
            return false;
        }
        if (state == LauncherDeployRuntimeManager.State.RETRACTING && config.blockFireWhenRetracting()) {
            denyLauncherDeployFire(operator, "发射架收回中");
            return false;
        }
        if (config.blockFireWhenSpeeding() && speedKph >= config.retractSpeedMin()) {
            denyLauncherDeployFire(operator, "车速过高，无法发射");
            return false;
        }
        return true;
    }

    @Nullable
    private RVP_LauncherDeployConfig findLauncherDeployConfig() {
        AbstractVehicle vehicle = getVehicle();
        if (vehicle == null || vehicle.getVehicleId() == null) {
            return null;
        }
        Set<String> candidateUnitIds = new LinkedHashSet<>();
        collectWeaponUnitIds(candidateUnitIds, getWeaponUnit());
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            if (candidateUnitIds.stream().anyMatch(config::appliesToWeaponUnit)
                    || candidateUnitIds.contains(config.pitchPartUnitId())) {
                return config;
            }
        }
        return null;
    }

    private static void collectWeaponUnitIds(Set<String> out, @Nullable WeaponUnit weaponUnit) {
        WeaponUnit current = weaponUnit;
        while (current != null) {
            out.add(current.getId());
            current = current.getParentWeaponUnit();
        }
        if (weaponUnit != null) {
            for (WeaponUnit sub : weaponUnit.getSubWeaponUnits()) {
                out.add(sub.getId());
            }
        }
    }

    private void denyLauncherDeployFire(@Nullable LivingEntity operator, String message) {
        Player player = operator instanceof Player p ? p : null;
        if (player == null) {
            AbstractVehicle vehicle = getVehicle();
            if (vehicle != null) {
                for (Entity passenger : vehicle.getPassengers()) {
                    if (passenger instanceof Player p) {
                        player = p;
                        break;
                    }
                }
            }
        }
        if (player != null) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    @Override
    public boolean withSeeker() {
        RVP_EnumWeaponKind kind = getData().getWeaponKind();
        if (kind != RVP_EnumWeaponKind.MISSILE && kind != RVP_EnumWeaponKind.BOMB) {
            return false;
        }
        RVP_WeaponData data = getData();
        return data.usesGuidanceType(org.ywzj.rvp.guidance.RVP_EnumGuidanceType.IR)
                || data.isRadarHoming()
                || data.isAntiRadiationMissile();
    }

    @Override
    public void tick() {
        super.tick();
        // [B2] 替代被删 WeaponUnitSetWeaponMixin / WeaponUnitSwitchWeaponMixin /
        // WeaponUnitFollowParentRotationMixin：武器 tick 在本体 super.tick()（含 updateRot）之后执行
        WeaponUnit unit = getWeaponUnit();
        RVP_WeaponSwitchSyncHelper.tick(unit);
        RVP_FollowParentRotationHelper.tick(unit);
        boolean fireDown = !getVehicle().level().isClientSide() && isServerOperatorFiring();
        fireController.tick(fireDown);
    }

    protected boolean isServerOperatorFiring() {
        return false;
    }

    @Override
    public void onSwitchFrom() {
        fireController.reset();
    }

    protected boolean isCharged() {
        if (getData().getFireData().getChargeTick() <= 0) {
            return true;
        }
        return fireController.canShootNow();
    }

    protected float consumeChargeScale() {
        RVP_EnumFireMode mode = getData().getFireData().getFireMode();
        float scale = getData().getFireData().getChargePowerScale();
        if (getData().getFireData().getChargeTick() <= 0 || scale <= 1f) {
            return 1f;
        }
        float ratio = fireController.chargeRatio();
        if (!fireController.useContinuousChargeScale()) {
            setChargeTick(0);
        }
        return 1f + (scale - 1f) * ratio;
    }

    protected boolean requiresEntityLock(RVP_WeaponData data) {
        return data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                && data.isRequireLock()
                && !data.isAntiRadiationMissile()
                && !data.isGpsMissile();
    }

    protected boolean hasArmPreselectedTarget(WeaponUnit unit) {
        return unit != null && RVP_WeaponLockStateTable.getArmPreselectedVehicleId(unit) >= 0;
    }

    protected boolean passesOffAxisShootGate() {
        Integer maxOffAxisShootAngle = getData().getFireData().getMaxOffAxisShootAngle();
        if (maxOffAxisShootAngle == null) {
            return true;
        }
        Vec3 axis = resolveOffAxisReferenceDirection();
        Vec3 launch = resolveCurrentLaunchDirection();
        if (axis == null || launch == null || axis.lengthSqr() < 1.0E-6 || launch.lengthSqr() < 1.0E-6) {
            return true;
        }
        double angleDeg = Math.toDegrees(VectorUtil.angleBetween(axis.normalize(), launch.normalize()));
        return Double.isNaN(angleDeg) || angleDeg <= maxOffAxisShootAngle + 1.0E-4;
    }

    protected Vec3 resolveOffAxisReferenceDirection() {
        AbstractVehicle vehicle = getVehicle();
        if (vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle) {
            return vehicle.getLookAngle();
        }
        WeaponUnit root = getWeaponUnit().getRootParentWeaponUnit();
        if (root != null) {
            Vec3 rootVec = root.worldVec();
            if (rootVec.lengthSqr() >= 1.0E-6) {
                return rootVec;
            }
        }
        return vehicle.getLookAngle();
    }

    protected Vec3 resolveCurrentLaunchDirection() {
        WeaponUnit launchUnit = getWeaponUnit();
        Vec3 aimed = launchUnit.worldVec(launchUnit.getXAimRot(), launchUnit.getYAimRot());
        if (aimed.lengthSqr() >= 1.0E-6) {
            return aimed;
        }
        Vec3 current = launchUnit.worldVec();
        if (current.lengthSqr() >= 1.0E-6) {
            return current;
        }
        return getVehicle().getLookAngle();
    }
}
