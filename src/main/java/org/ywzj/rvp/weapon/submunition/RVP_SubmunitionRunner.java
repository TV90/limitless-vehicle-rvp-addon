package org.ywzj.rvp.weapon.submunition;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionParentAction;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Per-projectile scheduler for {@link RVP_SubmunitionData} release waves.
 */
public final class RVP_SubmunitionRunner {

    private final List<WaveState> waves = new ArrayList<>();
    private boolean globallyDisabled;

    private RVP_SubmunitionRunner(List<WaveState> waves) {
        this.waves.addAll(waves);
    }

    public static RVP_SubmunitionRunner create(RVP_SubmunitionData data, int submunitionDepth,
                                             RVP_EnumWeaponKind parentKind) {
        if (data == null || !data.isEnabled()) {
            return new RVP_SubmunitionRunner(List.of());
        }
        List<WaveState> states = new ArrayList<>();
        for (RVP_SubmunitionReleaseData release : data.getReleases()) {
            RVP_SubmunitionReleaseData effective = release;
            if (data.usesLegacySchema() && parentKind == RVP_EnumWeaponKind.MACHINEGUN) {
                effective = RVP_SubmunitionReleaseData.legacyInFlightMachinegun(
                        data.getCount(), data.getDelayTick(), data.getIntervalTick(), data.getSpread());
            }
            int legacy = data.usesLegacySchema() ? data.getCount() : 0;
            states.add(new WaveState(effective, legacy));
        }
        return new RVP_SubmunitionRunner(states);
    }

    public void disableAll() {
        globallyDisabled = true;
        for (WaveState wave : waves) {
            wave.eventsRemaining = 0;
        }
    }

    public boolean isActive() {
        if (globallyDisabled) {
            return false;
        }
        for (WaveState wave : waves) {
            if (wave.eventsRemaining > 0 && !wave.oneShotFired) {
                return true;
            }
            if (wave.eventsRemaining > 0 && wave.config.getTriggers().contains(RVP_EnumSubmunitionTrigger.IN_FLIGHT)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if parent should be discarded this tick */
    public boolean tickInFlight(RVP_BaseBullet parent) {
        if (globallyDisabled || parent.level().isClientSide()) {
            return false;
        }
        boolean discardParent = false;
        for (WaveState wave : waves) {
            if (!wave.config.getTriggers().contains(RVP_EnumSubmunitionTrigger.IN_FLIGHT)) {
                continue;
            }
            if (wave.eventsRemaining <= 0) {
                continue;
            }
            if (wave.sprinkleTimer > 0) {
                wave.sprinkleTimer--;
                continue;
            }
            int perTick = wave.config.getPerTick();
            int interval = wave.config.getIntervalTick();
            int toFire = interval > 0 ? perTick : wave.eventsRemaining;
            if (interval <= 0) {
                toFire = wave.eventsRemaining;
            }
            RVP_SubmunitionSpawner.spawnReleaseWave(parent, wave.config, toFire);
            wave.eventsRemaining -= toFire;
            if (wave.config.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_ON_FIRST_SPAWN) {
                discardParent = true;
            }
            if (wave.eventsRemaining > 0) {
                wave.sprinkleTimer = Math.max(interval, 1);
            } else if (wave.config.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_AFTER_RELEASE) {
                discardParent = true;
            }
        }
        return discardParent;
    }

    /**
     * @return true if parent should be discarded immediately after this trigger
     */
    public boolean fireTrigger(RVP_BaseBullet parent, RVP_EnumSubmunitionTrigger trigger) {
        if (globallyDisabled || parent.level().isClientSide()) {
            return false;
        }
        boolean discardParent = false;
        for (WaveState wave : waves) {
            if (!matches(wave.config.getTriggers(), trigger)) {
                continue;
            }
            if (wave.oneShotFired && isOneShotTrigger(trigger)) {
                continue;
            }
            if (wave.eventsRemaining <= 0) {
                continue;
            }
            int toFire = wave.config.getIntervalTick() > 0 ? wave.config.getPerTick() : wave.eventsRemaining;
            RVP_SubmunitionSpawner.spawnReleaseWave(parent, wave.config, toFire);
            wave.eventsRemaining -= toFire;
            if (isOneShotTrigger(trigger)) {
                wave.oneShotFired = true;
            }
            if (wave.config.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_ON_FIRST_SPAWN
                    || wave.config.getParentAction() == RVP_EnumSubmunitionParentAction.DISCARD_AFTER_RELEASE) {
                discardParent = true;
            }
        }
        return discardParent;
    }

    private static boolean matches(Set<RVP_EnumSubmunitionTrigger> configured, RVP_EnumSubmunitionTrigger fired) {
        if (configured.contains(fired)) {
            return true;
        }
        return fired != RVP_EnumSubmunitionTrigger.ON_IMPACT
                && configured.contains(RVP_EnumSubmunitionTrigger.ON_IMPACT)
                && (fired == RVP_EnumSubmunitionTrigger.ON_BLOCK_HIT
                || fired == RVP_EnumSubmunitionTrigger.ON_ENTITY_HIT);
    }

    private static boolean isOneShotTrigger(RVP_EnumSubmunitionTrigger trigger) {
        return trigger != RVP_EnumSubmunitionTrigger.IN_FLIGHT;
    }

    private static final class WaveState {
        final RVP_SubmunitionReleaseData config;
        int eventsRemaining;
        int sprinkleTimer;
        boolean oneShotFired;

        WaveState(RVP_SubmunitionReleaseData config, int legacyCount) {
            this.config = config;
            this.eventsRemaining = config.resolveReleaseEvents(legacyCount);
            this.sprinkleTimer = config.getDelayTick();
        }
    }
}
