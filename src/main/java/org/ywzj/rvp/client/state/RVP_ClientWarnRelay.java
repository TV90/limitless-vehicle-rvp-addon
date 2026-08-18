package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 客户端无钳制告警中继（由 {@code S2CRvpWarn} 经 DistExecutor 分发调用）。
 *
 * <p>本体 {@code WarningReceiver.handle} 会以目标相对本机的俯仰角是否超过 ±45° 为界
 * 丢弃告警（{@code WarningReceiver.java:45}），导致被导弹/雷达锁定但不告警。
 * RVP 服务端补发的本包在客户端确认目标就是本地驾驶载具后，直接写入本体
 * {@code warningReceiver.targets} 跳过角度钳制：RWR 图标与循环音效由本体
 * {@code warningReceiver.tick()} 依 targets 自动驱动，一次性搜索告警音效在此对齐
 * 本体 {@code WarningReceiver.handle} 的 RADAR_SEARCH 分支补播。</p>
 */
public final class RVP_ClientWarnRelay {

    private RVP_ClientWarnRelay() {}

    public static void handle(int fromEntityId, int toEntityId, WarnType warnType, String info) {
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
        boolean alreadyPresent = warningReceiver.targets.containsKey(fromEntityId);
        warningReceiver.targets.put(fromEntityId,
                new WarningReceiver.WarnTarget(warnType, info, System.currentTimeMillis()));
        // RADAR_SEARCH 的一次性告警音效本体在 handle 分支中播放，tick() 不负责循环音，
        // 这里补播以对齐本体 WarningReceiver.handle（新目标才播，避免每 tick 重复）
        if (warnType == WarnType.RADAR_SEARCH && !alreadyPresent) {
            new org.ywzj.vehicle.audio.VehicleSound(
                    org.ywzj.vehicle.all.AllSounds.RADAR_SEARCH_WARN.get(),
                    4f, 1f, 1f,
                    false, 50, false, false, toVehicle.getId()).play();
        }
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