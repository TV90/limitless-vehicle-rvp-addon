package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [RVP] 同 faction gunner 组网交战网络侧表（"组网智能拦截"）。
 *
 * <p>记录每个维度内、各 faction 最近被本 faction 任一 gunner 交战（选为跟踪目标或射击）
 * 的目标截止 tick，供 {@code GunnerTargeting.findBestTarget} 的可拦截导弹层做<b>降权</b>：
 * 某目标在窗口内已被同 faction 任何 gunner 交战，则该 gunner 优先选择其它未交战目标——
 * 拦截作业时多台防空车不再全体重复锁同一枚最近的导弹，弹幕自动分配到不同来袭目标上。</p>
 *
 * <p>语义（2026-09-15 与用户定版）：<b>降权而非禁选</b>——当所有候选都已被交战时，
 * 忽略降权照常选择（仍有弹的 gunner 继续打击）；窗口过后目标恢复可选。窗口随交战时
 * 射手与目标的距离滑动（近距离 100t、远距离 200t，由调用方计算后传入），记账点包括
 * "开始跟踪新目标"（覆盖选中但延迟开火的窗口）与"实际发射"（刷新窗口）。</p>
 *
 * <p>结构仿 {@code RVP_ChaffJamState}：静态 Map + 每服务端 tick 懒清理；服务端专用
 * （gunner AI 与射击记账均在服务端），无客户端读取路径。记账频率 = 发射/换目标事件级，
 * 查询频率 = 每 gunner 扫描周期 O(候选数) 哈希查表，无可测量 TPS 开销。</p>
 */
public final class RVP_GunnerEngagementNet {

    private record Key(ResourceLocation dimension, RVP_EnumGunnerFaction faction, UUID targetUuid) {}

    /** 键 → 降权截止 gameTime（超过即恢复可选并清理）。 */
    private static final Map<Key, Long> ENGAGED_UNTIL = new HashMap<>();

    private RVP_GunnerEngagementNet() {
    }

    /**
     * 记录一次交战：faction 网络内该目标在 {@code windowTick} 内进入其它 gunner 的降权窗口。
     * 后续记账（如期发射）会刷新截止 tick。
     */
    public static void markEngaged(Level level, RVP_EnumGunnerFaction faction, Entity target, long windowTick) {
        if (level == null || faction == null || target == null || !target.isAlive() || windowTick <= 0) {
            return;
        }
        ENGAGED_UNTIL.put(new Key(level.dimension().location(), faction, target.getUUID()),
                level.getGameTime() + windowTick);
    }

    /** 目标是否处于本 faction 网络的降权窗口内（任一同 faction gunner 交战过且未到期）。 */
    public static boolean isRecentlyEngaged(Level level, RVP_EnumGunnerFaction faction, Entity target) {
        if (level == null || faction == null || target == null) {
            return false;
        }
        Long until = ENGAGED_UNTIL.get(new Key(level.dimension().location(), faction, target.getUUID()));
        if (until == null) {
            return false;
        }
        if (level.getGameTime() >= until) {
            ENGAGED_UNTIL.remove(new Key(level.dimension().location(), faction, target.getUUID()));
            return false;
        }
        return true;
    }

    /** 服务端每 tick 清理过期条目，避免内存膨胀。 */
    public static void onServerTick(long gameTime) {
        ENGAGED_UNTIL.entrySet().removeIf(entry -> gameTime >= entry.getValue());
    }
}
