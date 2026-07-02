package org.ywzj.rvp.weapon.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
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
        if (requiresEntityLock(data)) {
            WeaponUnit unit = getWeaponUnit().getRootParentWeaponUnit();
            // RVP HMD 管理的 IR 导弹：只认 HMD 锁状态
            boolean isIrHmdManaged = data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                    && !data.isRadarHoming()
                    && !data.isAntiRadiationMissile()
                    && !data.isGpsMissile();
            boolean hasLock = isIrHmdManaged
                    ? RVP_ClientHmdState.getInstance().hasLock()
                    : unit.getLockedEntity() != null;
            if (!hasLock) {
                // HMD 管理的导弹不适用 EO 豁免（武器站 EO ≠ 导引头已锁定）
                boolean eoExempt = !isIrHmdManaged
                        && unit.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.EO;
                if (!eoExempt) {
                    LocalVehiclePlayer.instance.sendMessage("ui.need_lock_entity");
                    return false;
                }
            }
            // 发射前强制同步 HMD 锁 → 服务器，防止 tickFireControl 的锁清除包比发射包先到
            if (hasLock) {
                Entity hmdEntity = RVP_ClientHmdState.getInstance().getLockedEntity();
                if (hmdEntity != null) {
                    unit.setLockedEntity(hmdEntity);
                }
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
        // 只有寻的弹（IR/SARH/ARH/ARM）才允许开启导引头，SACLOS/MCLOS/IOG/GPS 没有寻的头
        if (getData().getWeaponKind() != org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MISSILE) return false;
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

    /**
     * 服务端：近似认为操作员仍在开火（用于 CHARGE/MINIGUN 蓄力 tick）。
     * 玩家真实开火由客户端包驱动；此仅辅助同 tick 蓄力累加。
     */
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
