package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.weapon.BulletEntity;

/** AIR 红外搜索、雷达更新和无目标计时的独立决策，不改变 ARH 的雷达语义。 */
public final class RVP_AirSeekerPolicy {
    private RVP_AirSeekerPolicy() {}

    /** searchOn 为红外搜索开启；lossTimerArmed 为已进入配置开机距离的计时锁存。 */
    public record Decision(boolean searchOn, boolean lossTimerArmed) {}

    /** 距离单位为格；负距离表示没有记忆点，开机距离不大于0表示无距离限制。 */
    public static Decision decide(boolean searching, boolean timerArmed, boolean radarSupport,
                                  double referenceDistance, int activationRange) {
        boolean reachedRange = activationRange <= 0
                || referenceDistance >= 0 && referenceDistance <= activationRange;
        boolean armed = timerArmed || reachedRange;
        return new Decision(searching || !radarSupport || armed, armed);
    }

    /** 计时单位为 tick；提前搜索不累计失锁时间，有目标或 DIRCM 抑制时清零。 */
    public static int nextLostTicks(int previous, boolean timerArmed, boolean hasTarget, boolean suppressed) {
        return !timerArmed || hasTarget || suppressed ? 0 : Math.min(Math.max(previous, 0), Integer.MAX_VALUE - 1) + 1;
    }

    /** AIR 中继必须通过实时 RCS 包络；其它主动雷达类型保留既有探测表/硬锁旁路。 */
    public static boolean radarSupports(boolean infrared, boolean withinEnvelope, boolean tracked) {
        return withinEnvelope || !infrared && tracked;
    }

    /** 箔条数据链要求当前硬锁与实时探测包络同时有效，脱锁立即冻结最后坐标。 */
    public static boolean chaffRelayAvailable(boolean withinEnvelope, boolean hardLocked) {
        return withinEnvelope && hardLocked;
    }

    /** AIR 红外和雷达中继共用的弹种过滤，覆盖 RVP 与本体的机炮弹。 */
    public static boolean allowsTarget(Entity target) {
        return target != null && !(target instanceof BulletEntity)
                && !(target instanceof RVP_BaseBullet bullet && bullet.getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN);
    }

    public static boolean isAirInfraredCandidate(Entity target) {
        return allowsTarget(target) && isInfraredCandidate(target);
    }

    /** 红外捕获排除箔条，允许热焰弹参与反制。 */
    public static boolean isInfraredCandidate(Entity target) {
        return target != null && (!(target instanceof RVP_Decoy decoy)
                || decoy.rvp$decoyType() == RVP_EnumCountermeasureType.FLARE);
    }
}
