package org.ywzj.rvp.countermeasure;

/**
 * 干扰物发射状态机（纯逻辑，无 MC 引用，可 JUnit 单测）。
 *
 * <p>一次按键 = 一次齐射：剩余充足时发射 {@code n} 轮 × 每轮 {@code m} 发 = {@code n*m}；
 * 剩余不足时发射 {@code ceil(R/m)} 轮耗尽全部剩余，末轮不足 {@code m} 只发剩余；耗尽后进入
 * 装填，{@code reloadTick} 后恢复至 {@code total}。齐射中 / 装填中 / 剩余为 0 时按键忽略。</p>
 */
public final class RVP_CountermeasureStateMachine {

    /** 弹舱容量。 */
    private final int total;
    /** 一轮发射数 m。 */
    private final int perRound;
    /** 总发射轮数 n。 */
    private final int burstRounds;
    /** 轮间发射间隔（tick）。 */
    private final int launchIntervalTick;
    /** 装填时间（tick）。 */
    private final int reloadTick;

    private int remaining;
    private boolean firing;
    private int roundsLeft;
    private int roundTimer;
    private boolean reloading;
    private int reloadProgress;

    public RVP_CountermeasureStateMachine(int total, int perRound, int burstRounds,
                                          int launchIntervalTick, int reloadTick) {
        this.total = Math.max(0, total);
        this.perRound = Math.max(1, perRound);
        this.burstRounds = Math.max(1, burstRounds);
        this.launchIntervalTick = Math.max(1, launchIntervalTick);
        this.reloadTick = Math.max(1, reloadTick);
        this.remaining = this.total;
    }

    /** 系统是否启用（弹舱容量 > 0）。 */
    public boolean isEnabled() {
        return total > 0;
    }

    /**
     * 玩家按下发射键：开始一次齐射。
     * 剩余充足 → {@code burstRounds} 轮；不足 → {@code ceil(remaining/perRound)} 轮耗尽全部剩余。
     */
    public void onKeyPress() {
        if (!isEnabled() || firing || reloading || remaining <= 0) {
            return;
        }
        firing = true;
        long fullBurst = (long) burstRounds * perRound;
        roundsLeft = remaining >= fullBurst
                ? burstRounds
                : Math.max(1, (int) Math.ceil((double) remaining / perRound));
        roundTimer = 0;
    }

    /**
     * 每 tick 推进一次。
     *
     * @return 本轮应发射的干扰物数量；0 表示本 tick 无发射
     */
    public int onTick() {
        if (reloading) {
            reloadProgress++;
            if (reloadProgress >= reloadTick) {
                remaining = total;
                reloading = false;
                reloadProgress = 0;
            }
            return 0;
        }
        if (!firing) {
            return 0;
        }
        if (roundTimer > 0) {
            roundTimer--;
            return 0;
        }
        int fireCount = Math.min(perRound, remaining);
        remaining -= fireCount;
        roundsLeft--;
        if (roundsLeft <= 0) {
            firing = false;
            if (remaining <= 0) {
                reloading = true;
                reloadProgress = 0;
            }
        } else {
            roundTimer = launchIntervalTick;
        }
        return fireCount;
    }

    public int getTotal() {
        return total;
    }

    public int getRemaining() {
        return remaining;
    }

    public boolean isFiring() {
        return firing;
    }

    public boolean isReloading() {
        return reloading;
    }

    public int getReloadProgress() {
        return reloadProgress;
    }

    public int getReloadTick() {
        return reloadTick;
    }
}
