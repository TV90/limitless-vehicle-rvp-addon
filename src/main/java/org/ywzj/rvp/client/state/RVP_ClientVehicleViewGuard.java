package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.cockpit.RVP_CockpitDiag;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 客户端观瞄视角残留守卫。
 *
 * <p>载具被移除（/kill、被击毁、区块卸载）或玩家异常离车后，若迟迟收不到母车的
 * {@code ServerVehicleSeatsChange} 座位同步（车辆已不存在的正常路径不会补发），
 * {@link LocalVehiclePlayer#vehicle} 会一直指向已不存在的车辆，导致 {@code onVehicle()==true}，
 * 出现"人不在载具上却仍有 zbl08a 观瞄视角"的残留。</p>
 *
 * <p>这里在客户端每 tick 核对：本地缓存的车辆与玩家实际坐骑不一致且持续超过宽限期时，
 * 强制 {@link LocalVehiclePlayer#toSeat} 清空观瞄状态（本体 {@code checkState} 下一 tick
 * 会把 viewType 复位为第三人称）。宽限期覆盖 M 键无人机↔母车切换期间服务端 startRiding
 * 先于座位同步到达的正常窗口（约 1-2 tick）。</p>
 *
 * <p><b>判定逻辑自 2026-08-07 引入以来未改动</b>；2026-10-15 只加了诊断日志（开始不一致 /
 * 执行清空 / 恢复），且日志受 {@code /cockpitdiag} 开关控制（默认不记录）。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVehicleViewGuard {

    /** 缓存车辆与玩家实际坐骑不一致的宽限 tick 数（M 键切换正常窗口约 1-2 tick，留足余量）。 */
    private static final int MISMATCH_GRACE_TICKS = 40;

    private static int mismatchTick;

    /** 本次不一致已持续的最长 tick（仅用于诊断日志，不参与判定）。 */
    private static int diagMaxMismatchTick;

    private RVP_ClientVehicleViewGuard() {
    }

    /** 诊断日志：默认不记录（需 {@code /cockpitdiag on} 或 {@code watch}）。 */
    private static void diagLog(String message) {
        if (RVP_CockpitDiag.isEnabled() || RVP_CockpitDiag.isWatch()) {
            RVP_CockpitDiag.log(message);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (instance == null) {
            return;
        }
        AbstractVehicle cached = instance.vehicle;
        if (cached == null) {
            mismatchTick = 0;
            diagMaxMismatchTick = 0;
            return;
        }
        Entity actualRide = player.getVehicle();
        if (actualRide == cached) {
            if (diagMaxMismatchTick > 0) {
                diagLog("观瞄守卫：不一致恢复（最长 " + diagMaxMismatchTick + " tick），未动手");
                diagMaxMismatchTick = 0;
            }
            mismatchTick = 0;
            return;
        }
        diagMaxMismatchTick = Math.max(diagMaxMismatchTick, mismatchTick);
        if (mismatchTick == 0) {
            diagLog("观瞄守卫：开始不一致（本体缓存=" + cached.getVehicleId()
                    + " 原版坐骑=" + actualRide + " 玩家坐标=" + player.position()
                    + "）——超过 " + MISMATCH_GRACE_TICKS + " tick 将执行 toSeat(null,null)");
        }
        if (++mismatchTick > MISMATCH_GRACE_TICKS) {
            mismatchTick = 0;
            diagLog("观瞄守卫：★ 执行 toSeat(null,null)（本体缓存=" + cached.getVehicleId()
                    + " removed=" + cached.isRemoved() + " alive=" + cached.isAlive()
                    + " 距玩家=" + String.format(java.util.Locale.ROOT, "%.1f",
                            Math.sqrt(cached.getBoundingBox().distanceToSqr(player.position()))) + "格"
                    + " 原版坐骑=" + actualRide + "）");
            instance.toSeat(null, null);
        }
    }
}
