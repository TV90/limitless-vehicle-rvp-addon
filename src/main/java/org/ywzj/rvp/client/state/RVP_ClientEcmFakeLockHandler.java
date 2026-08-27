package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

import java.util.List;

/**
 * 客户端 RWR 伪造锁定处理器（主动ECM §8.3 无实体方案）。
 *
 * <p>服务端通过 {@code S2CEcmFakeLock} 定周期发伪造 radartype 列表，本处理器
 * 在客户端直接向本地驾驶载具的 {@code warningReceiver.targets} 写入
 * {@code RADAR_LOCK} 告警源（递增负数 id，不可能是真实实体 id）。
 * 本体 {@code WarningReceiver.tick} 会自动根据 targets 播放循环锁定告警音
 * 并在 RWR 上显示"被锁定"中心红字；由于源实体 id 取不到，方位线不画，
 * 符合"RWR 像坏了一样"的预期。</p>
 */
public final class RVP_ClientEcmFakeLockHandler {

    private RVP_ClientEcmFakeLockHandler() {
    }

    /**
     * 处理伪造锁定注入。
     *
     * @param vehicleId      被干扰载具实体 id
     * @param fakeRadarTypes 伪造 radartype 列表
     */
    public static void handle(int vehicleId, List<String> fakeRadarTypes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || fakeRadarTypes == null || fakeRadarTypes.isEmpty()) {
            return;
        }
        Entity target = mc.level.getEntity(vehicleId);
        if (!(target instanceof AbstractVehicle vehicle)) {
            org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.appendLog(
                    "[FakeLock] vehicleId=" + vehicleId + " 客户端找不到该实体, 丢弃");
            return;
        }
        // 仅对本地驾驶载具注入（与 RVP_ClientWarnRelay 相同语义）
        if (!isLocalDrivenVehicle(mc.player, vehicle)) {
            org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.appendLog(
                    "[FakeLock] vehicle=" + vehicle.getId() + " 不是本地驾驶载具, 丢弃");
            return;
        }
        WarningReceiver receiver = vehicle.warningReceiver;
        if (receiver == null) {
            org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.appendLog(
                    "[FakeLock] vehicle=" + vehicle.getId() + " 无 warningReceiver(RWR), 丢弃");
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < fakeRadarTypes.size(); i++) {
            String info = fakeRadarTypes.get(i);
            // 递增负数 id（不可能是真实实体 id），同受害者同索引保持稳定，避免闪烁
            int fakeId = -(1000000 + Math.abs(vehicle.getId()) * 100 + i);
            receiver.targets.put(fakeId, new WarningReceiver.WarnTarget(WarnType.RADAR_LOCK, info, now));
        }
        org.ywzj.rvp.client.state.RVP_ClientEcmDebugState.appendLog(
                "[FakeLock] vehicle=" + vehicle.getId() + " 注入 " + fakeRadarTypes.size()
                        + " 条伪造RADAR_LOCK: " + fakeRadarTypes);
    }

    /** 目标载具是否就是本地玩家驾驶的载具（与 RVP_ClientWarnRelay 相同判定）。 */
    private static boolean isLocalDrivenVehicle(Player player, Entity target) {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.vehicle == null) {
            return false;
        }
        return target == lvp.vehicle || target == player;
    }
}
