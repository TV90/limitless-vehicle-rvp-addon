package org.ywzj.rvp.weapon.core;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * Shared runtime base for the seven public RVP weapon types.
 */
public abstract class RVP_WeaponBase extends AbstractVehicleWeapon<RVP_WeaponData> {

    protected int chargeTick;
    private final RVP_WeaponFireController fireController = new RVP_WeaponFireController(this);

    protected RVP_WeaponBase(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, weaponUnit, index, data, serializeId);
    }

    public RVP_WeaponFireController getFireController() {
        return fireController;
    }

    protected int getChargeTick() {
        return chargeTick;
    }

    protected void setChargeTick(int chargeTick) {
        this.chargeTick = Math.max(chargeTick, 0);
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
        RVP_WeaponData data = getData();
        boolean isIrLaunchWeapon = RVP_IrLockHelper.isIrLaunchWeapon(data);
        if (requiresEntityLock(data)) {
            WeaponUnit unit = getWeaponUnit().getRootParentWeaponUnit();
            boolean isIrHmdManaged = data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile()
                    && data.isEnableIrHmd();

            Entity externalLocked = null;
            int externalLockedId = Integer.MIN_VALUE;
            if (!isIrHmdManaged
                    && unit.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF
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
                        && unit.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.EO;
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
        if (fired) {
            fireController.onShotFired();
        }
        return fired;
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
        return fireController.canShootNowAfterPrime();
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
        if (getData().getFireData().getChargeTime() <= 0) {
            return true;
        }
        return fireController.canShootNow();
    }

    protected float consumeChargeScale() {
        RVP_EnumFireMode mode = getData().getFireData().getFireMode();
        float scale = getData().getFireData().getChargePowerScale();
        if (getData().getFireData().getChargeTime() <= 0 || scale <= 1f) {
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
}
