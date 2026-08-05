package org.ywzj.rvp.weapon.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.vehicle.all.AllKeys;

/**
 * 按 {@link RVP_EnumFireMode} 驱动客户端是否发送开火包，以及服务端 spin / 轨道炮蓄力状态。
 */
public final class RVP_WeaponFireController {

    private final RVP_WeaponBase weapon;

    private boolean burstLatched;
    private long burstPauseUntilMs;
    /** 服务端：点射轮内剩余待发射弹数（不含已立即打出的第一发）。 */
    private int burstVolleyShotsRemaining;
    private int burstVolleyCooldownTicks;
    private int burstVolleyIntervalTicks;

    private boolean railgunCharging;
    private int railgunChargeTick;
    private int spinTick;
    private boolean clientFireDownPrev;
    private boolean lastFireDown;
    private boolean lastPressed;
    private boolean lastReleased;
    private boolean programmaticShotQueued;

    public RVP_WeaponFireController(RVP_WeaponBase weapon) {
        this.weapon = weapon;
    }

    public void reset() {
        burstLatched = false;
        burstPauseUntilMs = 0L;
        burstVolleyShotsRemaining = 0;
        burstVolleyCooldownTicks = 0;
        burstVolleyIntervalTicks = 0;
        railgunCharging = false;
        railgunChargeTick = 0;
        spinTick = 0;
        clientFireDownPrev = false;
        lastFireDown = false;
        lastPressed = false;
        lastReleased = false;
        programmaticShotQueued = false;
        weapon.setChargeTick(0);
    }

    public RVP_EnumFireMode mode() {
        return weapon.getData().getFireData().getFireMode();
    }

    @OnlyIn(Dist.CLIENT)
    public void syncClientInput() {
        boolean fireDown = isFireKeyDown();
        if (weapon.isReloading() || !weapon.hasAmmo()) {
            lastPressed = false;
            lastReleased = false;
            lastFireDown = false;
            // Treat blocked time like a released button so holding through reload can re-trigger charging when ready.
            clientFireDownPrev = false;
            clearChargeState();
            return;
        }
        lastPressed = fireDown && !clientFireDownPrev;
        lastReleased = !fireDown && clientFireDownPrev;
        clientFireDownPrev = fireDown;
        lastFireDown = fireDown;

        RVP_FireData fire = weapon.getData().getFireData();
        if (mode() == RVP_EnumFireMode.RAILGUN && lastPressed && !railgunCharging) {
            railgunCharging = true;
            railgunChargeTick = 0;
            weapon.setChargeTick(0);
            playChargeSound();
        }
        if (mode() == RVP_EnumFireMode.RAILGUN && lastReleased) {
            railgunCharging = false;
        }
        if (mode() == RVP_EnumFireMode.CHARGE && lastPressed) {
            playChargeSound();
        }

        tickSpinClient(fireDown);
        int chargeCap = fire.getChargeTick();
        if (chargeCap <= 0) {
            return;
        }
        if (mode() == RVP_EnumFireMode.CHARGE && fireDown) {
            weapon.setChargeTick(Math.min(weapon.getChargeTick() + 1, chargeCap));
        }
        if (mode() == RVP_EnumFireMode.RAILGUN && railgunCharging && fireDown) {
            railgunChargeTick = Math.min(railgunChargeTick + 1, chargeCap);
            weapon.setChargeTick(railgunChargeTick);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void playChargeSound() {
        SoundEvent sound = weapon.getChargeSound();
        if (sound == null) {
            return;
        }
        var vehicle = weapon.getVehicle();
        vehicle.level().playLocalSound(
                vehicle.getX(),
                vehicle.getY(),
                vehicle.getZ(),
                sound,
                SoundSource.PLAYERS,
                1f,
                1f,
                false
        );
    }

    @OnlyIn(Dist.CLIENT)
    public boolean shouldAttemptClientShot() {
        if (weapon.isReloading() || !weapon.hasAmmo()) {
            return false;
        }
        if (programmaticShotQueued) {
            programmaticShotQueued = false;
            return !weapon.isCoolingDown() && canShootHeat();
        }
        RVP_FireData fire = weapon.getData().getFireData();
        return switch (mode()) {
            case FULL_AUTO -> lastFireDown && !weapon.isCoolingDown() && canShootHeat();
            case SEMI_AUTO -> lastPressed && !weapon.isCoolingDown() && canShootHeat();
            case BURST -> shouldAttemptBurstClientShot(fire);
            case CHARGE -> lastFireDown && weapon.getChargeTick() >= fire.getChargeTick() && !weapon.isCoolingDown() && canShootHeat();
            case MINIGUN -> lastFireDown && spinTick >= fire.getChargeTick() && !weapon.isCoolingDown() && canShootHeat();
            case RAILGUN -> railgunCharging && railgunChargeTick >= fire.getChargeTick() && !weapon.isCoolingDown() && canShootHeat();
        };
    }

    @OnlyIn(Dist.CLIENT)
    public void queueProgrammaticShot() {
        programmaticShotQueued = true;
    }

    /**
     * 客户端：挂挡且按住时，每轮点射只发一个包；服务端在一轮内连发 {@code burst_count} 颗。
     */
    @OnlyIn(Dist.CLIENT)
    private boolean shouldAttemptBurstClientShot(RVP_FireData fire) {
        if (lastPressed) {
            burstLatched = true;
        }
        if (lastReleased) {
            burstLatched = false;
        }
        if (!burstLatched || !lastFireDown) {
            return false;
        }
        if (!canStartBurstRound(fire)) {
            return false;
        }
        return !weapon.isCoolingDown() && canShootHeat();
    }

    /** 是否可开始新一轮点射（轮间 {@code burst_delay} 已结束）。 */
    public boolean canStartBurstRound(RVP_FireData fire) {
        long now = System.currentTimeMillis();
        if (burstPauseUntilMs > 0L && now < burstPauseUntilMs) {
            return false;
        }
        if (burstPauseUntilMs > 0L) {
            burstPauseUntilMs = 0L;
        }
        return burstVolleyShotsRemaining <= 0 && canShootHeat();
    }

    public void completeBurstRound(RVP_FireData fire) {
        burstVolleyShotsRemaining = 0;
        burstVolleyCooldownTicks = 0;
        long delay = fire.getBurstDelay();
        burstPauseUntilMs = delay > 0L ? System.currentTimeMillis() + delay : 0L;
    }

    /**
     * 服务端：第一发已在 {@code shoot()} 内打出，此处排队剩余弹丸。
     */
    public void startBurstVolleyAfterFirstShot(RVP_FireData fire, long shootIntervalMs) {
        int remaining = Math.max(fire.getBurstCount() - 1, 0);
        burstVolleyShotsRemaining = remaining;
        burstVolleyIntervalTicks = Math.max(1, (int) (shootIntervalMs / 50L));
        burstVolleyCooldownTicks = burstVolleyIntervalTicks;
    }

    public boolean isBurstVolleyActive() {
        return burstVolleyShotsRemaining > 0 || burstVolleyCooldownTicks > 0;
    }

    /**
     * 服务端 tick：按 {@code shoot_interval} 间隔打出点射轮内后续弹丸。
     *
     * @return 本 tick 是否打出了一发
     */
    public boolean tickBurstVolley(Runnable fireShot) {
        if (burstVolleyShotsRemaining <= 0) {
            return false;
        }
        if (burstVolleyCooldownTicks > 0) {
            burstVolleyCooldownTicks--;
            return false;
        }
        if (weapon.isReloading() || !weapon.hasAmmo() || !canShootHeat()) {
            completeBurstRound(weapon.getData().getFireData());
            return false;
        }
        fireShot.run();
        recordHeatForShot();
        burstVolleyShotsRemaining--;
        burstVolleyCooldownTicks = burstVolleyIntervalTicks;
        if (burstVolleyShotsRemaining <= 0) {
            completeBurstRound(weapon.getData().getFireData());
        }
        return true;
    }

    public void tick(boolean fireDown) {
        RVP_FireData fire = weapon.getData().getFireData();
        RVP_WeaponHeatManager.tick(weapon, weapon.getLocalHeatState());
        tickSpinServer(fireDown);
        if (weapon.isReloading() || !weapon.hasAmmo()) {
            clearChargeState();
            return;
        }

        if (mode() == RVP_EnumFireMode.RAILGUN && railgunCharging && fireDown) {
            railgunChargeTick = Math.min(railgunChargeTick + 1, fire.getChargeTick());
            weapon.setChargeTick(railgunChargeTick);
        } else if (mode() == RVP_EnumFireMode.RAILGUN && !fireDown) {
            railgunCharging = false;
        }

        int chargeCap = fire.getChargeTick();
        if (chargeCap <= 0) {
            return;
        }
        if (mode() == RVP_EnumFireMode.CHARGE && fireDown) {
            weapon.setChargeTick(Math.min(weapon.getChargeTick() + 1, chargeCap));
        } else if (mode() == RVP_EnumFireMode.CHARGE && !fireDown) {
            weapon.setChargeTick(decayCharge(weapon.getChargeTick(), fire));
        }
        if (mode() == RVP_EnumFireMode.MINIGUN && fireDown) {
            spinTick = Math.min(spinTick + 1, chargeCap);
            weapon.setChargeTick(spinTick);
        } else if (mode() == RVP_EnumFireMode.MINIGUN && !fireDown) {
            spinTick = decayCharge(spinTick, fire);
            weapon.setChargeTick(spinTick);
        }
        if (mode() == RVP_EnumFireMode.RAILGUN && !fireDown) {
            railgunChargeTick = decayCharge(railgunChargeTick, fire);
            weapon.setChargeTick(railgunChargeTick);
        }
    }

    private void tickSpinClient(boolean fireDown) {
        if (mode() != RVP_EnumFireMode.MINIGUN) {
            return;
        }
        RVP_FireData fire = weapon.getData().getFireData();
        int cap = Math.max(fire.getChargeTick(), 1);
        int decay = resolveChargeDecayStep(fire);
        if (fireDown) {
            spinTick = Math.min(spinTick + 1, cap);
        } else {
            spinTick = Math.max(spinTick - decay, 0);
        }
        weapon.setChargeTick(spinTick);
    }

    private void tickSpinServer(boolean fireDown) {
        if (mode() != RVP_EnumFireMode.MINIGUN) {
            return;
        }
        if (fireDown) {
            RVP_FireData fire = weapon.getData().getFireData();
            int cap = Math.max(fire.getChargeTick(), 1);
            spinTick = Math.min(spinTick + 1, cap);
            weapon.setChargeTick(spinTick);
        } else {
            RVP_FireData fire = weapon.getData().getFireData();
            spinTick = decayCharge(spinTick, fire);
            weapon.setChargeTick(spinTick);
        }
    }

    public boolean canShootNow() {
        return canShootNow(false);
    }

    public boolean canShootNowAfterPrime() {
        return canShootNow(true);
    }

    private boolean canShootNow(boolean afterPrime) {
        RVP_FireData fire = weapon.getData().getFireData();
        int chargeCap = fire.getChargeTick();
        return canShootHeat() && switch (mode()) {
            case FULL_AUTO, SEMI_AUTO -> true;
            case BURST -> canStartBurstRound(fire);
            case CHARGE -> chargeCap <= 0 || afterPrime || weapon.getChargeTick() >= chargeCap;
            case MINIGUN -> chargeCap <= 0 || afterPrime || spinTick >= chargeCap;
            case RAILGUN -> chargeCap <= 0 || afterPrime
                    || railgunCharging && railgunChargeTick >= chargeCap;
        };
    }

    public void onShotFired() {
        recordHeatForShot();
        switch (mode()) {
            case CHARGE -> weapon.setChargeTick(0);
            case RAILGUN -> {
                railgunCharging = false;
                railgunChargeTick = 0;
                weapon.setChargeTick(0);
            }
            case BURST -> completeBurstRound(weapon.getData().getFireData());
            case MINIGUN -> { }
            default -> { }
        }
    }

    public void recordHeatForShot() {
        RVP_WeaponHeatManager.onShotFired(weapon, weapon.getLocalHeatState());
    }

    public boolean canShootHeat() {
        return RVP_WeaponHeatManager.canShoot(weapon, weapon.getLocalHeatState());
    }

    public boolean hasHeat() {
        return RVP_WeaponHeatManager.hasHeat(weapon, weapon.getLocalHeatState());
    }

    public boolean isOverheated() {
        return RVP_WeaponHeatManager.isOverheated(weapon, weapon.getLocalHeatState());
    }

    public int getCurrentHeat() {
        return RVP_WeaponHeatManager.currentHeat(weapon, weapon.getLocalHeatState());
    }

    public int getMaxHeatCount() {
        return RVP_WeaponHeatManager.maxHeat(weapon, weapon.getLocalHeatState());
    }

    public float heatRatio() {
        return RVP_WeaponHeatManager.heatRatio(weapon, weapon.getLocalHeatState());
    }

    private void clearChargeState() {
        switch (mode()) {
            case CHARGE -> weapon.setChargeTick(0);
            case RAILGUN -> {
                railgunCharging = false;
                railgunChargeTick = 0;
                weapon.setChargeTick(0);
            }
            case MINIGUN -> {
                spinTick = 0;
                weapon.setChargeTick(0);
            }
            default -> { }
        }
    }

    public float chargeRatio() {
        RVP_FireData fire = weapon.getData().getFireData();
        int cap = fire.getChargeTick();
        if (cap <= 0) {
            return 1f;
        }
        if (mode() == RVP_EnumFireMode.MINIGUN) {
            return Math.min(spinTick / (float) cap, 1f);
        }
        if (mode() == RVP_EnumFireMode.RAILGUN) {
            return Math.min(railgunChargeTick / (float) cap, 1f);
        }
        return Math.min(weapon.getChargeTick() / (float) cap, 1f);
    }

    public boolean useContinuousChargeScale() {
        return mode() == RVP_EnumFireMode.MINIGUN;
    }

    public void primeServerShot() {
        RVP_FireData fire = weapon.getData().getFireData();
        int cap = fire.getChargeTick();
        if (cap <= 0) {
            return;
        }
        switch (mode()) {
            case CHARGE -> weapon.setChargeTick(cap);
            case RAILGUN -> {
                railgunCharging = true;
                railgunChargeTick = cap;
                weapon.setChargeTick(cap);
            }
            case MINIGUN -> {
                spinTick = Math.max(spinTick, cap);
                weapon.setChargeTick(spinTick);
            }
            default -> { }
        }
    }

    private int decayCharge(int current, RVP_FireData fire) {
        if (current <= 0) {
            return 0;
        }
        return Math.max(current - resolveChargeDecayStep(fire), 0);
    }

    private int resolveChargeDecayStep(RVP_FireData fire) {
        int cap = Math.max(fire.getChargeTick(), 1);
        int decayDuration = Math.max(fire.getChargeDecayTick(), 1);
        return Math.max((cap + decayDuration - 1) / decayDuration, 1);
    }

    @OnlyIn(Dist.CLIENT)
    private boolean isFireKeyDown() {
        var unit = weapon.getWeaponUnit();
        if (unit.getCurrentWeapon().orElse(null) == weapon) {
            return AllKeys.MAIN_WEAPON_SHOOT.isDown();
        }
        if (unit.getCurrentSecondaryWeapon().orElse(null) == weapon) {
            return AllKeys.SECONDARY_WEAPON_SHOOT.isDown();
        }
        return AllKeys.MAIN_WEAPON_SHOOT.isDown() || AllKeys.SECONDARY_WEAPON_SHOOT.isDown();
    }
}
