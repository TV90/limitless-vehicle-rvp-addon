package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端无钳制告警中继（由 {@code S2CRvpWarn} 经 DistExecutor 分发调用）。
 *
 * <p>本体 {@code WarningReceiver.handle} 会以目标相对本机的俯仰角是否超过 ±45° 为界
 * 丢弃告警（{@code WarningReceiver.java:45}），导致被导弹/雷达锁定但不告警。
 * RVP 服务端补发的本包在客户端确认目标就是本地驾驶载具后，直接写入本体
 * {@code warningReceiver.targets} 跳过角度钳制：RWR 图标与循环音效由本体
 * {@code warningReceiver.tick()} 依 targets 自动驱动。</p>
 *
 * <p><b>RADAR_SEARCH 响声节奏（2026-09-06 重构）</b>：本体每秒一响的搜索音已被
 * {@code WarningReceiverSearchWarnMixin} 截流，本类成为唯一播音点——仅当服务端
 * {@code audible=true}（首次接触 / 每轮扫描周期到点，由 {@code RVP_WarnRelayService}
 * 调度）时播一次性搜索音；{@code audible=false} 的轮询包只刷新 targets，
 * 保 RWR 图标与机型文字常亮不闪。另加 500ms/源防御间隔兜底防异常连发。</p>
 */
public final class RVP_ClientWarnRelay {

    /** 各来源上次播搜索告警音的时间戳（毫秒）：audible 包防御间隔，防异常连发刷屏。 */
    private static final Map<Integer, Long> LAST_SEARCH_SOUND_AT = new HashMap<>();

    /** 同一来源两次搜索告警音的最小间隔（毫秒）：小于服务端最小响声周期（10 tick）。 */
    private static final long SEARCH_SOUND_MIN_INTERVAL_MS = 500L;

    private RVP_ClientWarnRelay() {}

    public static void handle(int fromEntityId, int toEntityId, WarnType warnType, String info, boolean audible) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Entity target = mc.level.getEntity(toEntityId);
        if (!(target instanceof AbstractVehicle toVehicle) || !isLocalDrivenVehicle(mc.player, toVehicle)) {
            return;
        }
        WarningReceiver warningReceiver = toVehicle.warningReceiver;
        if (warningReceiver == null) {
            return;
        }
        // 对齐本体 WarningReceiver.handle 的 RADAR_SEARCH 去重：已有更高级告警时不覆盖为搜索
        if (warnType == WarnType.RADAR_SEARCH) {
            WarningReceiver.WarnTarget warnTarget = warningReceiver.targets.get(fromEntityId);
            if (warnTarget != null && warnTarget.warnType() != WarnType.RADAR_SEARCH) {
                return;
            }
        }
        // 无钳制直写 targets：RWR 图标、锁定/导弹循环音、overlay 红字、GunnerBrain 数据源
        warningReceiver.targets.put(fromEntityId,
                new WarningReceiver.WarnTarget(warnType, info, System.currentTimeMillis()));
        // RADAR_SEARCH 一次性告警音本体在 handle 分支中播放、tick() 不负责循环音；
        // 本体通道已被截流，这里仅按服务端 audible 调度播报（首次接触/每轮扫描响一声）
        if (warnType == WarnType.RADAR_SEARCH && audible && canPlaySearchSound(fromEntityId)) {
            LAST_SEARCH_SOUND_AT.put(fromEntityId, System.currentTimeMillis());
            new org.ywzj.vehicle.audio.VehicleSound(
                    org.ywzj.vehicle.all.AllSounds.RADAR_SEARCH_WARN.get(),
                    4f, 1f, 1f,
                    false, 50, false, false, toVehicle.getId()).play();
        }
    }

    /** 同一来源 500ms 内不重复播音（服务端 10 tick 最小响声周期之下的兜底防御）。 */
    private static boolean canPlaySearchSound(int fromEntityId) {
        long now = System.currentTimeMillis();
        Long last = LAST_SEARCH_SOUND_AT.get(fromEntityId);
        return last == null || now - last >= SEARCH_SOUND_MIN_INTERVAL_MS;
    }

    /** 目标载具是否就是本地玩家驾驶的载具（或本地玩家本身）。 */
    private static boolean isLocalDrivenVehicle(Player player, Entity target) {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null) {
            return false;
        }
        return target == lvp.vehicle || target == player;
    }
}
