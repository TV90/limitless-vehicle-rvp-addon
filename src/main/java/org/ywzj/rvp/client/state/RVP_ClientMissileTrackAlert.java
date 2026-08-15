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
            RVP_ClientLockWarningState.markIrTrack();
            // 驾驶舱 RWR 提示音：本地播放（对齐本体 WarningReceiver 的 VehicleSound 一次性播放）
            new org.ywzj.vehicle.audio.VehicleSound(
                    RVP_Sounds.IR_ALERT.get(), 4f, 1f, 1f, false, 0, false, false, mc.player.getId()).play();
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
