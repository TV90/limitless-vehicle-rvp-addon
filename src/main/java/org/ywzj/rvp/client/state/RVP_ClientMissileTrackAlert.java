package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.network.S2CMissileTrackAlert;

/**
 * 客户端导弹跟踪告警处理（由 {@code S2CMissileTrackAlert} 经 DistExecutor 分发调用）。
 *
 * <p>仅当本地驾驶载具就是导弹锁定目标时才写入提示状态（避免附近玩家收到 TRACKING_ENTITY
 * 广播而误报）：ARH 写箔条规避提示，IR/AIR 写热焰弹规避提示并本地播放 ir_alert.ogg。</p>
 */
public final class RVP_ClientMissileTrackAlert {

    private RVP_ClientMissileTrackAlert() {}

    /** 激光告警音效最小间隔（毫秒）：服务端每 5 tick 发一包，原逻辑每包都响 → 过于频繁。
     *  此处对音效做节流，文字提示仍随每包刷新（保持常亮）。 */
    private static final long LASER_SOUND_INTERVAL_MS = 500L;
    private static long lastLaserSoundAt = 0L;

    public static void handle(int missileEntityId, int targetEntityId, byte guidanceType) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // 只有被锁定的目标才触发告警：解析目标实体，确认其载具就是本地驾驶载具
        Entity target = mc.level.getEntity(targetEntityId);
        if (!isLocalDrivenVehicle(mc.player, target)) {
            return;
        }
        if (guidanceType == S2CMissileTrackAlert.TYPE_ARH) {
            RVP_ClientLockWarningState.markArhTrack();
            return;
        }
        if (guidanceType == S2CMissileTrackAlert.TYPE_AIR || guidanceType == S2CMissileTrackAlert.TYPE_IR) {
            // 驾驶舱 RWR 提示音：本地播放（对齐本体 WarningReceiver 的 VehicleSound 一次性播放）
            RVP_ClientLockWarningState.markIrTrack();
            new org.ywzj.vehicle.audio.VehicleSound(
                    RVP_Sounds.IR_ALERT.get(), 4f, 1f, 1f, false, 0, false, false, mc.player.getId()).play();
            return;
        }
        if (guidanceType == S2CMissileTrackAlert.TYPE_HITL_TV) {
            // 人在回路电视制导：音效同红外告警
            RVP_ClientLockWarningState.markHitlTvTrack();
            new org.ywzj.vehicle.audio.VehicleSound(
                    RVP_Sounds.IR_ALERT.get(), 4f, 1f, 1f, false, 0, false, false, mc.player.getId()).play();
            return;
        }
        if (guidanceType == S2CMissileTrackAlert.TYPE_LASER) {
            // 激光照射：文字提示每次刷新，音效节流（避免每 0.25s 一响）
            RVP_ClientLockWarningState.markLaserTrack();
            long now = System.currentTimeMillis();
            if (now - lastLaserSoundAt >= LASER_SOUND_INTERVAL_MS) {
                lastLaserSoundAt = now;
                new org.ywzj.vehicle.audio.VehicleSound(
                        RVP_Sounds.LASER_ALERT.get(), 4f, 1f, 1f, false, 0, false, false, mc.player.getId()).play();
            }
            return;
        }
    }

    /** 目标实体是否就是本地玩家驾驶的载具（或本地玩家本身）。 */
    private static boolean isLocalDrivenVehicle(Player player, Entity target) {
        if (target == null) {
            return false;
        }
        org.ywzj.vehicle.vehicle.LocalVehiclePlayer lvp = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null) {
            return false;
        }
        return target == lvp.vehicle || target == player;
    }
}
