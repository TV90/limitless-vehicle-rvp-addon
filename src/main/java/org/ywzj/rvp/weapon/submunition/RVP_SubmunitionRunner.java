package org.ywzj.rvp.weapon.submunition;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.debug.RVP_AheadDebug;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionParentAction;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionData;
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

    public static RVP_SubmunitionRunner create(RVP_SubmunitionData data, int submunitionDepth) {
        if (data == null || !data.isEnabled()) {
            return new RVP_SubmunitionRunner(List.of());
        }
        List<WaveState> states = new ArrayList<>();
        for (RVP_SubmunitionReleaseData release : data.getReleases()) {
            states.add(new WaveState(release));
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

    /** 不生成子体的到期预检，允许虚拟管理器在布撒前恢复原实体。 */
    public boolean willReleaseWithin(int ticks) {
        return !globallyDisabled && waves.stream().anyMatch(wave -> wave.eventsRemaining > 0
                && wave.config.getTriggers().contains(RVP_EnumSubmunitionTrigger.IN_FLIGHT)
                && wave.sprinkleTimer <= Math.max(1, ticks));
    }

    /** 只递减尚未到期的布撒计时，绝不在未加载区块生成子体或消耗释放次数。 */
    public void advanceVirtualDelay() {
        if (globallyDisabled) return;
        for (WaveState wave : waves) if (wave.eventsRemaining > 0 && wave.sprinkleTimer > 0
                && wave.config.getTriggers().contains(RVP_EnumSubmunitionTrigger.IN_FLIGHT)) wave.sprinkleTimer--;
    }

    /** 保存真实/虚拟交接中的剩余波次，避免恢复或读档后重新从第一波开始。 */
    public net.minecraft.nbt.CompoundTag saveVirtualState() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean("disabled", globallyDisabled);
        var entries = new net.minecraft.nbt.ListTag();
        for (WaveState wave : waves) {
            var entry = new net.minecraft.nbt.CompoundTag();
            entry.putInt("remaining", wave.eventsRemaining); entry.putInt("timer", wave.sprinkleTimer);
            entry.putBoolean("fired", wave.oneShotFired); entries.add(entry);
        }
        tag.put("waves", entries);
        return tag;
    }

    /** 只恢复与当前配置波次数匹配的状态，配置变化不擅自追加旧载荷。 */
    public void restoreVirtualState(net.minecraft.nbt.CompoundTag tag) {
        var entries = tag.getList("waves", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (entries.size() != waves.size()) return;
        globallyDisabled = tag.getBoolean("disabled");
        for (int index = 0; index < waves.size(); index++) {
            var entry = entries.getCompound(index); WaveState wave = waves.get(index);
            wave.eventsRemaining = Math.max(0, entry.getInt("remaining"));
            wave.sprinkleTimer = Math.max(0, entry.getInt("timer")); wave.oneShotFired = entry.getBoolean("fired");
        }
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
            int spawned = RVP_SubmunitionSpawner.spawnReleaseWave(parent, wave.config, toFire, null);
            RVP_AheadDebug.logFuseRelease(parent, wave.config, RVP_EnumSubmunitionTrigger.IN_FLIGHT, toFire, spawned);
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
    public boolean fireTrigger(RVP_BaseBullet parent, RVP_EnumSubmunitionTrigger trigger, @Nullable Vec3 anchor) {
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
            int spawned = RVP_SubmunitionSpawner.spawnReleaseWave(parent, wave.config, toFire, anchor);
            RVP_AheadDebug.logFuseRelease(parent, wave.config, trigger, toFire, spawned);
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

        WaveState(RVP_SubmunitionReleaseData config) {
            this.config = config;
            this.eventsRemaining = config.resolveReleaseEvents();
            this.sprinkleTimer = config.getDelayTick();
        }
    }
}
