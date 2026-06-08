package org.ywzj.rvp.weapon.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import org.ywzj.rvp.ext.WeaponUnitSeekerExt;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

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
        if (requiresEntityLock(data)) {
            WeaponUnit unit = getWeaponUnit().getRootParentWeaponUnit();
            if (unit.getLockedEntity() == null
                    && unit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.EO) {
                LocalVehiclePlayer.instance.sendMessage("ui.need_lock_entity");
                return false;
            }
        }
        boolean fired = super.doClientShoot();
        if (fired) {
            fireController.onShotFired();
        }
        return fired;
    }

    /**
     * 非全自动/连发模式下的蓄力门槛；CHARGE/MINIGUN/RAILGUN 在 {@link RVP_WeaponFireController} 中判定。
     */
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
        return RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(getData());
    }

    @Override
    public void onSwitchTo() {
        super.onSwitchTo();
        WeaponUnit root = getWeaponUnit().getRootParentWeaponUnit();
        if (RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(getData())) {
            if (getVehicle().level().isClientSide()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        org.ywzj.rvp.client.seeker.RVP_ClientSeekerBridge.onWeaponSelected(root, getData()));
            }
        } else if (root != null) {
            if (getVehicle().level().isClientSide()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        org.ywzj.rvp.client.seeker.RVP_ClientSeekerBridge.onWeaponDeselected(root));
            } else if (root.isSeekerOn() && root instanceof WeaponUnitSeekerExt seekerExt) {
                seekerExt.ywzj_rvp$forceSeekerOff();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        boolean fireDown = !getVehicle().level().isClientSide() && isServerOperatorFiring();
        fireController.tick(fireDown);
    }

    /**
     * 服务端：近似认为操作员仍在开火（用于 CHARGE/MINIGUN 蓄力 tick）。
     * 玩家真实开火由客户端包驱动；此仅辅助同 tick 蓄力累加。
     */
    protected boolean isServerOperatorFiring() {
        return false;
    }

    @Override
    public void onSwitchFrom() {
        if (getVehicle().level().isClientSide()) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    org.ywzj.rvp.client.seeker.RVP_ClientSeekerBridge.onWeaponDeselected(
                            getWeaponUnit().getRootParentWeaponUnit()));
        }
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
        if (data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE || !data.isRequireLock()) {
            return false;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)) {
            return true;
        }
        return !data.isActiveRadar()
                && !data.isAntiRadiationMissile()
                && !data.isGpsMissile();
    }
}
