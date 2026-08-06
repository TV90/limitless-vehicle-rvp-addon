package org.ywzj.rvp.weapon.core;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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

import java.util.List;
import java.util.Optional;

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
        if (LocalVehiclePlayer.instance.getPlayer() == getWeaponUnit().getOwner()) {
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
        return fireController.canShootNowAfterPrime() && passesOffAxisShootGate();
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
