package org.ywzj.rvp.weapon.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
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

        // 目的（2026-09-17）：同轴机枪等共用武器站开火键的输入不得驱动蓄力/转管积累——
        // isFireKeyDown 的回退分支（未选中武器时任意开火键都算开火）会让打同轴机枪时
        // railgun 蓄力条一起涨。蓄力类模式只认本武器被选中（主/副操作位）时的开火输入。
        // 判定必须经 RVP_WeaponResolveHelper 解包 Agent/Multi 代理后再比对——
        // getCurrentWeapon() 返回的可能是 VehicleMultiWeapons 包装而非本武器实例。
        boolean chargeOwnInput = RVP_WeaponResolveHelper.currentPrimary(weapon.getWeaponUnit()) == weapon
                || RVP_WeaponResolveHelper.unwrap(weapon.getWeaponUnit().getCurrentSecondaryWeapon().orElse(null)) == weapon;

        RVP_FireData fire = weapon.getData().getFireData();
        if (mode() == RVP_EnumFireMode.RAILGUN && lastPressed && !railgunCharging && chargeOwnInput) {
            railgunCharging = true;
            railgunChargeTick = 0;
            weapon.setChargeTick(0);
            playChargeSound();
        }
        if (mode() == RVP_EnumFireMode.RAILGUN && lastReleased) {
            railgunCharging = false;
        }
        if (mode() == RVP_EnumFireMode.CHARGE && lastPressed && chargeOwnInput) {
            playChargeSound();
        }

        tickSpinClient(fireDown && chargeOwnInput);
        int chargeCap = fire.getChargeTick();
        if (chargeCap <= 0) {
            return;
        }
        if (mode() == RVP_EnumFireMode.CHARGE && fireDown && chargeOwnInput) {
            weapon.setChargeTick(Math.min(weapon.getChargeTick() + 1, chargeCap));
        } else if (mode() == RVP_EnumFireMode.CHARGE && weapon.getChargeTick() > 0) {
            // 2026-09-17：松开蓄力后客户端按 charge_decay_tick 快速衰减清零（镜像服务端 tick 的
            // 衰减语义），蓄力条跟随真实值平滑跌落，不再冻结在松开瞬间
            weapon.setChargeTick(decayCharge(weapon.getChargeTick(), fire));
        }
        if (mode() == RVP_EnumFireMode.RAILGUN && railgunCharging && fireDown && chargeOwnInput) {
            railgunChargeTick = Math.min(railgunChargeTick + 1, chargeCap);
            weapon.setChargeTick(railgunChargeTick);
        } else if (mode() == RVP_EnumFireMode.RAILGUN && !railgunCharging && railgunChargeTick > 0) {
            // 2026-09-17：松开蓄力后客户端同样快速衰减清零，蓄力条不再冻结（镜像服务端语义）
            railgunChargeTick = decayCharge(railgunChargeTick, fire);
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
        RVP_WeaponHeatManager.tick(weapon);
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
        resetStateAfterShot();
    }

    /**
     * 仅重置模式射击后状态，不记录热量。
     *
     * <p>客户端多弹种槽（{@code VehicleMultiWeapons}）发包成功后调用本方法：热量统一由
     * 服务器回包（{@code ServerVehicleFire} → {@code VehicleFireEvent.Post} → {@code onClientFire}）
     * 记录，避免单发在客户端被计热两次（发包即时计热 + 回包计热）导致过热过快。</p>
     */
    public void resetStateAfterShot() {
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
        RVP_WeaponHeatManager.onShotFired(weapon);
    }

    public boolean canShootHeat() {
        return RVP_WeaponHeatManager.canShoot(weapon);
    }

    public boolean hasHeat() {
        return RVP_WeaponHeatManager.hasHeat(weapon);
    }

    public boolean isOverheated() {
        return RVP_WeaponHeatManager.isOverheated(weapon);
    }

    public int getCurrentHeat() {
        return RVP_WeaponHeatManager.currentHeat(weapon);
    }

    public int getMaxHeatCount() {
        return RVP_WeaponHeatManager.maxHeat(weapon);
    }

    public float heatRatio() {
        return RVP_WeaponHeatManager.heatRatio(weapon);
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
